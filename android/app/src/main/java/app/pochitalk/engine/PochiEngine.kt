package app.pochitalk.engine

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.core.content.ContextCompat
import app.pochitalk.audio.MicGain
import app.pochitalk.audio.MicMonitor
import app.pochitalk.audio.RadioSounds
import app.pochitalk.rtc.Runtime
import app.pochitalk.rtc.TrysteroNetwork
import app.pochitalk.rtc.TrysteroRoom
import app.pochitalk.service.CallService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.ExternalAudioProcessingFactory
import org.webrtc.AudioTrack
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import java.security.SecureRandom
import kotlin.math.min
import kotlin.math.sqrt

/**
 * アプリ全体の状態とふるまい。Web版 (worker.js) のクライアントコードに対応する:
 * ロビー(部屋一覧)、部屋への入退室、ボタンを押して話す、話中ロック、混線、VOX、効果音。
 *
 * 内部状態はすべて Runtime.scope (単一スレッド) 上で触る。UI からの呼び出しは各 public 関数が
 * scope.launch でエンジンスレッドに移す。UI へは [state] (StateFlow) で伝える。
 */
object PochiEngine {
    // ---- Web版と共通のプロトコル定数。変えるとWeb版と通話できなくなる
    const val APP_ID = "pochitalk-walkie-talkie-cf-worker"
    const val LOBBY_ROOM_ID = "pochitalk-lobby"
    private const val ACTION_PTT_STATE = "pttState"
    private const val ACTION_PTT_NAME = "pttName"
    private const val ACTION_PRESENCE = "presence"
    private const val ACTION_JOIN_AGE = "joinAge" // Web版と共通: 入室してからの経過時間を交換し、新しく来た側を判定する

    /** 招待URL。Web版の公開URLに合わせて変更する。 */
    const val INVITE_BASE_URL = "https://pochittalk.ryota-softwares.workers.dev/"

    private const val PREFS = "pochitalk"
    private const val PREF_NAME = "pochitalk_ptt_name"
    // Web版とは入力レベルが違うので、Android専用のキー (Web版の "pochitalk_vox_thr" とは別の値の意味)
    private const val PREF_VOX_THR = "pochitalk_vox_level"
    private const val PREF_MIC_GAIN = "pochitalk_input_db"      // Web版と同じキー名 (0〜30dB)
    private const val PREF_WELCOME = "pochitalk_welcome"       // Web版と同じ: オフにしたときだけ "off" を保存
    private const val DEFAULT_VOX_STEP = 4
    private const val STREAM_ID = "pochitalk-audio"
    private const val VOX_HOLD_MS = 1500L
    /** 送信を続けるかの判定は、開始のしきい値より低くして (ヒステリシス)、語尾や言葉の間で切れないようにする。 */
    private const val VOX_HOLD_RATIO = 0.6f
    private const val VOX_COOLDOWN_MS = 700L
    private const val LOBBY_STALE_MS = 15_000L
    private const val METER_INTERVAL_MS = 50L
    private const val RX_POLL_INTERVAL_MS = 150L

    private lateinit var app: Application
    private lateinit var prefs: SharedPreferences
    private var initialized = false

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val _meter = MutableStateFlow(MeterState())
    val meter: StateFlow<MeterState> = _meter.asStateFlow()

    val sounds = RadioSounds()
    private val micGain = MicGain()

    /** 待機中のマイク音量の監視 (VOX・レベルメーター用)。送信中は止めて libwebrtc の録音の音量を使う。 */
    private val micMonitor = MicMonitor { rms -> pushMonRms(rms) }

    // ---------------------------------------------------------------- エンジンスレッド専用の状態

    private var factory: PeerConnectionFactory? = null
    private var net: TrysteroNetwork? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    private var lobby: TrysteroRoom? = null
    private var presenceAction: TrysteroRoom.Action? = null
    private class LobbyEntry(val key: String, val talking: Boolean, val name: String, val ts: Long)
    private val lobbyEntries = HashMap<String, LobbyEntry>()

    private var room: TrysteroRoom? = null
    private var pttStateAction: TrysteroRoom.Action? = null
    private var pttNameAction: TrysteroRoom.Action? = null
    private class PeerState(var name: String, var talking: Boolean, var joinJudged: Boolean = false)
    private val peers = LinkedHashMap<String, PeerState>()

    private var myNickname = ""
    private var currentKey: String? = null
    private var pendingKey: String? = null
    private var micTest = false
    private var roomEnterTime = 0L
    private var currentTalkerId: String? = null
    private var isTransmitting = false
    private var pressed = false

    private var flashJob: Job? = null
    private var crosstalkJob: Job? = null

    // VOX
    private var voxJob: Job? = null
    private var voxHits = 0
    private var voxLastVoice = 0L
    private var voxCooldownUntil = 0L
    private var voxInitiated = false

    @Volatile private var monLevel = 0f   // 待機用の録音 (MicMonitor) の音量
    @Volatile private var lastMonAt = 0L
    @Volatile private var rtcLevel = 0f   // libwebrtc の録音の音量 (送信中だけ来る)
    @Volatile private var lastRtcAt = 0L

    /** 新しい方の録音から、直近300ms以内の大きい方の音量を返す。取れていなければ null。 */
    private fun currentMicLevel(now: Long): Float? {
        val mon = if (now - lastMonAt <= 300) monLevel else null
        val rtc = if (now - lastRtcAt <= 300) rtcLevel else null
        return when {
            mon != null && rtc != null -> maxOf(mon, rtc)
            else -> mon ?: rtc
        }
    }

    // レベルメーター (Web版の draw() と同じ: 平滑化 + ピークホールド)
    private var meterLevel = 0f
    private var meterPeak = 0f
    private var meterPeakHoldUntil = 0L
    private var rxLevel = 0.0
    private var rxPolledAt = 0L
    private var rxPolling = false

    // ================================================================ 起動

    fun init(application: Application) {
        if (initialized) return
        initialized = true
        app = application
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        myNickname = prefs.getString(PREF_NAME, null)
            ?: ("無線機_" + (100 + SecureRandom().nextInt(899)))
        val savedThr = prefs.getInt(PREF_VOX_THR, DEFAULT_VOX_STEP).coerceIn(1, 10)
        _state.update { it.copy(nickname = myNickname, voxSens = savedThr) }
        _state.update { it.copy(voxThreshold = voxMeterFraction(voxThreshold())) }
        val savedGain = prefs.getInt(PREF_MIC_GAIN, 0).coerceIn(0, 30)
        micGain.gain = Math.pow(10.0, savedGain / 20.0).toFloat()
        _state.update { it.copy(micGainDb = savedGain, welcomeOn = prefs.getString(PREF_WELCOME, null) != "off") }
        refreshMicPermission()
        Runtime.scope.launch { startNetwork() }
    }

    private fun startNetwork() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions(),
        )
        val adm = JavaAudioDeviceModule.builder(app)
            .setSamplesReadyCallback { samples -> onMicSamples(samples) }
            .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                override fun onWebRtcAudioRecordStart() { Runtime.scope.launch { onRtcRecording(true) } }
                override fun onWebRtcAudioRecordStop() { Runtime.scope.launch { onRtcRecording(false) } }
            })
            .createAudioDeviceModule()
        val processing = ExternalAudioProcessingFactory()
        processing.setCapturePostProcessing(micGain)
        val f = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setAudioProcessingFactory(processing)
            .createPeerConnectionFactory()
        factory = f
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        )
        val n = TrysteroNetwork(f, iceServers)
        net = n
        n.start()
        joinLobby(n)
        _state.update { it.copy(ready = true) }
        renderScan()
        Runtime.scope.launch {
            while (isActive) {
                meterTick()
                delay(METER_INTERVAL_MS)
            }
        }
        Runtime.scope.launch {
            while (isActive) {
                delay(4_000)
                val now = System.currentTimeMillis()
                lobbyEntries.values.removeAll { now - it.ts > LOBBY_STALE_MS }
                announce(null)
                renderScan()
            }
        }
    }

    // ================================================================ 権限

    private fun micGranted() =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Activity が権限ダイアログの後や onResume で呼ぶ。 */
    fun refreshMicPermission() {
        val granted = micGranted()
        _state.update { it.copy(micGranted = granted) }
        if (granted) Runtime.scope.launch {
            if (room != null) {
                ensureMic()
                updateMicMonitor()
                if (!CallService.isRunning) startForegroundService()
            }
        }
    }

    // ================================================================ ロビー (部屋一覧)

    private fun joinLobby(n: TrysteroNetwork) {
        val l = n.joinRoom(APP_ID, LOBBY_ROOM_ID)
        lobby = l
        val act = l.action(ACTION_PRESENCE)
        presenceAction = act
        act.onMessage = { msg, peerId ->
            if (msg.isJson) {
                val d = msg.json()
                val ch = d.optString("ch", "")
                if (isRoomKey(ch)) {
                    lobbyEntries[peerId] = LobbyEntry(
                        key = ch,
                        talking = d.optBoolean("talking", false),
                        name = d.optString("name", "?").take(12),
                        ts = System.currentTimeMillis(),
                    )
                    renderScan()
                }
            }
        }
        l.onPeerJoin = { peerId -> announce(peerId) }
        l.onPeerLeave = { peerId ->
            lobbyEntries.remove(peerId)
            renderScan()
        }
    }

    /** いまどの部屋にいるかをロビーに知らせる (Web版の announce)。入室していなければ何も送らない。 */
    private fun announce(to: String?) {
        val key = currentKey ?: return
        val msg = JSONObject().put("ch", key).put("talking", isTransmitting).put("name", myNickname)
        presenceAction?.sendJson(msg, to)
    }

    private class Stat(var count: Int = 0, var air: Boolean = false, val names: MutableList<String> = ArrayList())

    private fun renderScan() {
        val stats = HashMap<String, Stat>()
        for (e in lobbyEntries.values) {
            val s = stats.getOrPut(e.key) { Stat() }
            s.count++
            s.names.add(e.name)
            if (e.talking) s.air = true
        }
        val cur = currentKey
        if (cur != null) {
            val s = stats.getOrPut(cur) { Stat() }
            s.count++
            s.names.add(0, myNickname + "(自分)")
            if (isTransmitting) s.air = true
        }
        val pend = pendingKey
        if (pend != null && !stats.containsKey(pend)) stats[pend] = Stat()
        var total = 0
        stats.values.forEach { total += it.count }
        val rooms = stats.keys.sorted().map { k ->
            val s = stats.getValue(k)
            RoomInfo(
                key = k,
                count = s.count,
                air = s.air,
                names = s.names.toList(),
                invited = k == pend && s.count == 0,
                isCurrent = k == cur,
            )
        }
        _state.update { it.copy(rooms = rooms, totalPeople = total, pendingKey = pend) }
    }

    // ================================================================ 入退室

    fun createRoom() {
        val bytes = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val key = bytes.joinToString("") { String.format("%02x", it.toInt() and 0xff) }
        join(key)
        Runtime.scope.launch { flash("新しい部屋を作りました。招待で友達を呼べます") }
    }

    fun join(key: String) {
        Runtime.scope.launch { joinInternal(key) }
    }

    private fun joinInternal(key: String) {
        val n = net ?: return flash("接続の準備中です。少し待ってください")
        if (!isRoomKey(key)) return
        if (isTransmitting) return flash("送信中は部屋を変えられません")
        if (key == currentKey) return flash("すでにこの部屋です")
        leaveInternal(keepService = true)
        persistNickname()
        currentKey = key
        pendingKey = null
        micTest = false
        _state.update { it.copy(micTest = false) }
        stopVoxInternal() // Web版と同じく、入室するとVOXはオフに戻る
        roomEnterTime = System.currentTimeMillis()

        val r = n.joinRoom(APP_ID, "h-$key")
        room = r
        val stateAction = r.action(ACTION_PTT_STATE)
        val nameAction = r.action(ACTION_PTT_NAME)
        val joinAgeAction = r.action(ACTION_JOIN_AGE)
        pttStateAction = stateAction
        pttNameAction = nameAction

        nameAction.onMessage = { msg, peerId ->
            peers[peerId]?.let {
                it.name = msg.text.take(24)
                renderPeers()
                renderScan()
            }
        }
        stateAction.onMessage = { msg, peerId ->
            if (msg.isJson) onPttState(peerId, msg.json().optBoolean("isTalking", false))
        }
        // 新しく来た人を判定して、すでに部屋にいる側だけ入室音を鳴らす (Web版と同じ仕組み)
        joinAgeAction.onMessage = { msg, peerId ->
            val peer = peers[peerId]
            if (peer != null && !peer.joinJudged && msg.isJson) {
                val age = msg.json().optLong("age", -1)
                if (age >= 0) {
                    peer.joinJudged = true
                    val myAge = System.currentTimeMillis() - roomEnterTime
                    val newer = age < myAge - 1000
                    Runtime.log("joinAge from $peerId: age=${age}ms myAge=${myAge}ms -> " + if (newer) "peer is new (enter tone)" else "no tone")
                    if (newer) sounds.playEnter() // 相手のほうが新しい = 相手が今入ってきた
                }
            }
        }
        r.onPeerJoin = { peerId ->
            peers[peerId] = PeerState("接続中...", false)
            renderPeers()
            nameAction.sendString(myNickname, peerId)
            val ageAtJoin = System.currentTimeMillis() - roomEnterTime
            joinAgeAction.sendJson(JSONObject().put("age", ageAtJoin), peerId)
            // 相手が経過時間を送ってこない場合 (別の実装など): 自分が20秒以上いるなら、相手は新規とみなす
            Runtime.scope.launch {
                delay(2_500)
                val p = peers[peerId]
                if (p != null && !p.joinJudged) {
                    p.joinJudged = true
                    if (ageAtJoin > 20_000) sounds.playEnter()
                }
            }
        }
        r.onPeerLeave = { peerId ->
            peers.remove(peerId)
            if (currentTalkerId == peerId) onStopReceiving()
            renderPeers()
        }

        enterAudioSession()
        ensureMic()
        startForegroundService()
        updateMicMonitor()
        resetStatus()
        _state.update { it.copy(currentKey = key, pendingKey = null) }
        renderPeers()
        announce(null)
        renderScan()
    }

    fun leave() {
        Runtime.scope.launch { leaveInternal(keepService = false) }
    }

    private fun leaveInternal(keepService: Boolean) {
        if (room == null && currentKey == null) return
        if (isTransmitting) {
            pressed = false
            stopTransmission()
        }
        room?.leave()
        room = null
        pttStateAction = null
        pttNameAction = null
        peers.clear()
        currentTalkerId = null
        currentKey = null
        stopVoxInternal()
        updateMicMonitor()
        crosstalkJob?.cancel()
        if (!keepService) {
            exitAudioSession()
            CallService.stop(app)
        }
        resetStatus()
        _state.update { it.copy(currentKey = null, peers = emptyList()) }
        renderScan()
    }

    /** 招待リンクで開かれた。自動では入室せず、一覧に「招待」として出す。 */
    fun setPendingKey(key: String?) {
        Runtime.scope.launch {
            pendingKey = if (isRoomKey(key)) key else null
            renderScan()
        }
    }

    fun inviteUrl(): String? = currentKey?.let { INVITE_BASE_URL + "?r=" + it }

    // ================================================================ ニックネーム

    fun setNickname(text: String) {
        _state.update { it.copy(nickname = text) }
        Runtime.scope.launch { myNickname = text.trim().ifEmpty { "NoName" } }
    }

    /** 入力が終わったとき (フォーカスが外れたとき) に保存して、部屋の仲間に知らせる。 */
    fun commitNickname() {
        Runtime.scope.launch {
            persistNickname()
            pttNameAction?.sendString(myNickname)
            announce(null)
            renderScan()
        }
    }

    private fun persistNickname() {
        myNickname = _state.value.nickname.trim().ifEmpty { "NoName" }
        prefs.edit().putString(PREF_NAME, myNickname).apply()
    }

    // ================================================================ マイクと音声セッション

    private fun ensureMic() {
        if (audioTrack != null) {
            room?.let { r -> audioTrack?.let { r.addLocalTrack(it, STREAM_ID) } }
            return
        }
        if (!micGranted()) return
        val f = factory ?: return
        val constraints = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            optional.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        val source = f.createAudioSource(constraints)
        val track = f.createAudioTrack("pochitalk-mic", source)
        track.setEnabled(false) // ボタンを押している間だけ有効にする (Web版と同じ)
        audioSource = source
        audioTrack = track
        room?.addLocalTrack(track, STREAM_ID)
    }

    private fun enterAudioSession() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = true
    }

    private fun exitAudioSession() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = false
        am.mode = AudioManager.MODE_NORMAL
    }

    private fun startForegroundService() {
        val key = currentKey ?: return
        // マイク種別のフォアグラウンドサービスは RECORD_AUDIO が許可されていないと開始できない
        if (micGranted()) CallService.start(app, key)
    }

    /** JavaAudioDeviceModule が録音中に呼ぶ (音声スレッド)。送信中の音量を測る。 */
    private fun onMicSamples(samples: JavaAudioDeviceModule.AudioSamples) {
        val d = samples.data
        val n = d.size / 2
        if (n == 0) return
        var sum = 0.0
        var i = 0
        while (i + 1 < d.size) {
            val v = ((d[i].toInt() and 0xff) or (d[i + 1].toInt() shl 8)).toShort().toInt()
            sum += (v * v).toDouble()
            i += 2
        }
        pushRtcRms((sqrt(sum / n) / 32768.0).toFloat())
    }

    // Web版と同じく、メーターとVOXは入力レベル (増幅) を掛けたあとの音量で見る
    private fun pushMonRms(rawRms: Float) {
        val rms = rawRms * micGain.gain
        val prev = monLevel
        monLevel = if (rms > prev) rms else prev * 0.85f + rms * 0.15f
        lastMonAt = System.currentTimeMillis()
    }

    private fun pushRtcRms(rawRms: Float) {
        val rms = rawRms * micGain.gain
        val prev = rtcLevel
        rtcLevel = if (rms > prev) rms else prev * 0.85f + rms * 0.15f
        lastRtcAt = System.currentTimeMillis()
    }

    /**
     * 部屋に入っていて、マイクが許可されていて、送信していないときだけ、待機中の音量監視を動かす。
     * 送信が始まると止める (送信中は libwebrtc が録音するため)。
     */
    private var lastMonitorCheck = 0L
    private fun updateMicMonitor() {
        // libwebrtc が録音中 (送信中、または接続直後の短い間) は、待機用の録音を止めておく。
        // 録音の停止通知が来ない場合に備えて、4秒たったら止まったものとみなす。
        val rtcBusy = rtcRecording && (isTransmitting || System.currentTimeMillis() - rtcRecordingSince < 4_000)
        val want = (room != null || micTest) && micGranted() && !isTransmitting && !rtcBusy
        if (want) micMonitor.start() else micMonitor.stop()
    }

    private var rtcRecording = false
    private var rtcRecordingSince = 0L

    /** libwebrtc の録音が始まった/止まった。止まったら待機用の録音を再開する。 */
    private fun onRtcRecording(active: Boolean) {
        Runtime.log("webrtc recording " + if (active) "start" else "stop")
        rtcRecording = active
        if (active) rtcRecordingSince = System.currentTimeMillis()
        if (active) micMonitor.stopAndWait() // 先に待機用が走っていたら、競合する前に解放する
        updateMicMonitor()
    }

    // ================================================================ 話す (PTT)

    fun pttPress() {
        Runtime.scope.launch {
            pressed = true
            startTransmission(quiet = false)
        }
    }

    fun pttRelease() {
        Runtime.scope.launch {
            pressed = false
            stopTransmission()
        }
    }

    private fun othersTalking() = peers.values.count { it.talking }

    private fun startTransmission(quiet: Boolean) {
        if (isTransmitting) return
        if (room == null) return flash("先に部屋に入ってください")
        if (othersTalking() > 0) {
            // 他の人が話している間は割り込めない
            if (!quiet) {
                sounds.playBusy()
                flash("話中です")
            }
            return
        }
        if (!micGranted()) {
            flash("マイクの許可が必要です")
            _events.tryEmit(UiEvent.RequestMic)
            return
        }
        ensureMic()
        val track = audioTrack ?: return flash("マイクを準備できませんでした")
        if (!pressed) return
        isTransmitting = true
        micMonitor.stopAndWait() // 録音を1つずつにするため、送信を始める前に待機用を解放する
        track.setEnabled(true)
        setStatus(StatusKind.TX, "TX (送信中)", ledTx = true, ledRx = _state.value.ledRx)
        sounds.playStart()
        pttStateAction?.sendJson(JSONObject().put("isTalking", true))
        announce(null)
        renderScan()
    }

    private fun stopTransmissionSilently() {
        if (!isTransmitting) return
        isTransmitting = false
        audioTrack?.setEnabled(false)
        updateMicMonitor()
        _state.update { it.copy(ledTx = false) }
        pttStateAction?.sendJson(JSONObject().put("isTalking", false))
        announce(null)
        renderScan()
    }

    private fun stopTransmission() {
        if (!isTransmitting) return
        isTransmitting = false
        audioTrack?.setEnabled(false)
        updateMicMonitor()
        val talker = currentTalkerId?.let { peers[it] }
        if (talker != null) {
            onStartReceiving(talker.name)
        } else {
            setStatus(StatusKind.STANDBY, "STANDBY", ledTx = false, ledRx = false)
        }
        _state.update { it.copy(ledTx = false) }
        sounds.playEnd()
        pttStateAction?.sendJson(JSONObject().put("isTalking", false))
        announce(null)
        renderScan()
    }

    private fun onPttState(peerId: String, talking: Boolean) {
        val peer = peers[peerId]
        if (peer != null) peer.talking = talking
        if (talking) {
            currentTalkerId = peerId
            if (isTransmitting) {
                // ほぼ同時に話し始めた: お互いの送信が切れて混線
                pressed = false
                stopTransmissionSilently()
                showCrosstalk()
                flash("混線！ もう一度押してください")
            } else if (othersTalking() >= 2) {
                showCrosstalk()
            } else {
                onStartReceiving(peer?.name ?: "相手")
            }
        } else if (currentTalkerId == peerId) {
            onStopReceiving()
        }
        renderPeers()
    }

    private fun onStartReceiving(name: String) {
        setStatus(StatusKind.RX, "RX: $name", ledTx = _state.value.ledTx, ledRx = true)
    }

    private fun onStopReceiving() {
        currentTalkerId = null
        _state.update { it.copy(ledRx = false) }
        if (!isTransmitting) {
            setStatus(StatusKind.STANDBY, "STANDBY", ledTx = false, ledRx = false)
            sounds.playEnd()
        }
    }

    private fun showCrosstalk() {
        sounds.playCrosstalk()
        setStatus(StatusKind.CROSSTALK, "混線！", ledTx = false, ledRx = _state.value.ledRx)
        crosstalkJob?.cancel()
        crosstalkJob = Runtime.scope.launch {
            delay(1300)
            if (!isTransmitting && currentTalkerId == null) {
                setStatus(StatusKind.STANDBY, "STANDBY", ledTx = false, ledRx = false)
            }
        }
    }

    private fun resetStatus() {
        setStatus(StatusKind.STANDBY, "STANDBY", ledTx = false, ledRx = false)
    }

    private fun setStatus(kind: StatusKind, text: String, ledTx: Boolean, ledRx: Boolean) {
        _state.update { it.copy(status = kind, statusText = text, ledTx = ledTx, ledRx = ledRx) }
    }

    private fun renderPeers() {
        val list = peers.map { (id, p) -> PeerInfo(id, p.name, p.talking) }
        _state.update { it.copy(peers = list) }
    }

    private fun flash(text: String) {
        _state.update { it.copy(flash = text) }
        flashJob?.cancel()
        flashJob = Runtime.scope.launch {
            delay(2_500)
            _state.update { it.copy(flash = null) }
        }
    }

    // ================================================================ 設定 / VOX

    /** 入力レベル (マイクの増幅)。声が小さく伝わる端末で上げる。0〜30 dB。 */
    fun setMicGainDb(db: Int) {
        val v = db.coerceIn(0, 30)
        micGain.gain = Math.pow(10.0, v / 20.0).toFloat()
        _state.update { it.copy(micGainDb = v) }
        prefs.edit().putInt(PREF_MIC_GAIN, v).apply()
    }

    /** 初回案内を表示するか。Web版と同じく、オフにしたときだけ "off" を保存する。 */
    fun setWelcomeOn(on: Boolean) {
        _state.update { it.copy(welcomeOn = on) }
        if (on) prefs.edit().remove(PREF_WELCOME).apply() else prefs.edit().putString(PREF_WELCOME, "off").apply()
    }

    /**
     * 設定のマイクテスト。入室しなくても、入力レベルとメーターを確認できる。
     * 音は送信も保存もしない。設定を閉じたら止める。
     */
    fun setMicTest(on: Boolean) {
        Runtime.scope.launch {
            if (on) {
                if (room != null) return@launch
                if (!micGranted()) {
                    flash("マイクの許可が必要です")
                    _events.tryEmit(UiEvent.RequestMic)
                    return@launch
                }
            }
            micTest = on
            _state.update { it.copy(micTest = on) }
            updateMicMonitor()
        }
    }

    fun setSound(on: Boolean) {
        sounds.enabled = on
        _state.update { it.copy(soundOn = on) }
    }

    /**
     * 1〜10の値が大きいほど、大きな声が必要になる (Web版と同じ向き)。
     * 機種でマイクの入力レベルが4〜10倍違う (実機: Pixel の話し声は 0.02〜0.09、Galaxy は 0.005〜0.014) ので、
     * 0.0015〜0.04 を対数目盛り (1段あたり約1.44倍) にして、どちらの端末でも合わせられるようにしている。
     */
    private fun voxThreshold(): Float =
        (0.0015 * Math.pow(1.44, (_state.value.voxSens - 1).toDouble())).toFloat()

    /** VOXメーター用: 音量を dB (-60〜-20dB) で 0..1 にする。小さい音も見えるように対数にしている。 */
    private fun voxMeterFraction(level: Float): Float {
        if (level <= 1e-5f) return 0f
        val db = 20.0 * Math.log10(level.toDouble())
        return ((db + 60.0) / 40.0).coerceIn(0.0, 1.0).toFloat()
    }

    fun setVoxSensitivity(sens: Int) {
        val v = sens.coerceIn(1, 10)
        _state.update { it.copy(voxSens = v) }
        _state.update { it.copy(voxThreshold = voxMeterFraction(voxThreshold())) }
        prefs.edit().putInt(PREF_VOX_THR, v).apply()
    }

    fun setVox(on: Boolean) {
        Runtime.scope.launch {
            if (on) {
                if (!micGranted()) {
                    flash("マイクの許可が必要です")
                    _events.tryEmit(UiEvent.RequestMic)
                    return@launch
                }
                ensureMic()
                _state.update { it.copy(voxOn = true) }
                voxHits = 0
                voxJob?.cancel()
                voxJob = launch {
                    while (isActive) {
                        voxTick()
                        delay(50)
                    }
                }
            } else {
                stopVoxInternal()
            }
        }
    }

    private fun stopVoxInternal() {
        voxJob?.cancel()
        voxJob = null
        if (voxInitiated && isTransmitting) {
            pressed = false
            stopTransmission()
        }
        voxInitiated = false
        _state.update { it.copy(voxOn = false, voxLevel = 0f) }
    }

    private fun voxTick() {
        val now = System.currentTimeMillis()
        val lvl = currentMicLevel(now) ?: 0f
        val thr = voxThreshold()
        _state.update { it.copy(voxLevel = voxMeterFraction(lvl), voxThreshold = voxMeterFraction(thr)) }
        if (room == null) {
            voxHits = 0
            return
        }
        if (!isTransmitting) {
            voxInitiated = false
            // 他の人が話している間は割り込まない
            if (lvl > thr && othersTalking() == 0 && now > voxCooldownUntil) {
                if (++voxHits >= 3) {
                    voxHits = 0
                    voxInitiated = true
                    voxLastVoice = now
                    pressed = true
                    startTransmission(quiet = true)
                }
            } else if (lvl <= thr) {
                voxHits = 0
            }
        } else if (voxInitiated) {
            if (lvl > thr * VOX_HOLD_RATIO) {
                voxLastVoice = now
            } else if (now - voxLastVoice > VOX_HOLD_MS) {
                pressed = false
                voxInitiated = false
                stopTransmission()
                voxCooldownUntil = System.currentTimeMillis() + VOX_COOLDOWN_MS
            }
        }
    }

    // ================================================================ 音量レベルメーター

    /**
     * 送信中は自分のマイク (TX)、誰かが話していればその音声 (RX)、それ以外はマイク待機 (MIC)。
     * マイクの音量は録音中だけ取れる (誰かと接続して音声トラックが付いているとき)。
     */
    private fun meterTick() {
        if (room == null && !micTest) {
            if (_meter.value != MeterState()) {
                meterLevel = 0f
                meterPeak = 0f
                _meter.value = MeterState()
            }
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastMonitorCheck >= 500) {
            lastMonitorCheck = now
            updateMicMonitor()
        }
        val micNow = currentMicLevel(now)
        var label = "LEVEL"
        var rms: Double? = null
        val talker = currentTalkerId
        if (isTransmitting) {
            label = "TX"
            if (micNow != null) rms = micNow.toDouble()
        } else if (talker != null) {
            label = "RX"
            pollRxLevel(talker, now)
            rms = rxLevel
        } else if (micNow != null) {
            label = "MIC"
            rms = micNow.toDouble()
        }
        val db = if (rms != null && rms > 1e-5) 20.0 * Math.log10(rms) else Double.NEGATIVE_INFINITY
        val target = if (db.isFinite()) ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat() else 0f
        // Web版は60fps前提の係数 (上がり0.5 / 下がり0.12 / ピーク減衰0.02) を、50ms刻みに換算している
        meterLevel += (target - meterLevel) * (if (target > meterLevel) 0.875f else 0.32f)
        if (meterLevel >= meterPeak) {
            meterPeak = meterLevel
            meterPeakHoldUntil = now + 800
        } else if (now > meterPeakHoldUntil) {
            meterPeak = maxOf(meterLevel, meterPeak - 0.06f)
        }
        _meter.value = MeterState(
            label = label,
            level = meterLevel,
            peak = meterPeak,
            dbText = if (db.isFinite()) Math.round(db).toString() + " dB" else "-∞ dB",
        )
    }

    private fun pollRxLevel(peerId: String, now: Long) {
        if (rxPolling || now - rxPolledAt < RX_POLL_INTERVAL_MS) return
        rxPolling = true
        rxPolledAt = now
        room?.fetchInboundAudioLevel(peerId) { level ->
            rxPolling = false
            rxLevel = level ?: 0.0
        } ?: run { rxPolling = false }
    }
}
