package app.pochitalk.rtc

import app.pochitalk.core.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * @thaunknown/simple-peer 10.x (index.js + lite.js) の移植。Trystero のブラウザ版が相手なので、
 * 次の挙動を忘れずに合わせている:
 *  - trickle:false。ICE候補を集め終えてから (iceGatheringState COMPLETE、または最初の候補から5秒後に)
 *    SDP を1回だけ送る。
 *  - イニシエータが DataChannel を作り、ノンイニシエータは ondatachannel で受ける。
 *  - 接続後の再ネゴシエーション (トラック追加など) は、データチャネル上の "@_signal" で
 *    {type:'renegotiate'} / {type:'transceiverRequest'} / {type:'offer'|'answer', sdp} をやり取りする。
 *    ノンイニシエータは自分でオファーを作れないので、イニシエータに依頼する。
 *
 * すべてのメソッドは Runtime.scope (単一スレッド) 上から呼ぶこと。
 */
class RtcPeer(
    factory: PeerConnectionFactory,
    iceServers: List<PeerConnection.IceServer>,
    val initiator: Boolean,
    private val scope: CoroutineScope = Runtime.scope,
    private val log: (String) -> Unit = Runtime::log,
) {
    val id: String = ByteArray(4).also { SecureRandom().nextBytes(it) }.toHex().take(7)
    val created: Long = System.currentTimeMillis()

    /** 最初に発生した signal (イニシエータならオファー、ノンイニシエータならアンサー)。 */
    val firstSignal = CompletableDeferred<JSONObject>()

    var isDead = false
        private set
    private var destroying = false
    private var connected = false

    private var pc: PeerConnection? = null
    private var channel: DataChannel? = null

    private var pcReady = false
    private var channelReady = false
    private var iceComplete = false
    private var iceCompleteTimer: Job? = null
    private val iceCompleteWaiters = ArrayList<() -> Unit>()

    private var isNegotiating = false
    private var firstNegotiation = true
    private var batchedNegotiation = false
    private var queuedNegotiation = false

    private var remoteDescriptionSet = false
    private val pendingCandidates = ArrayList<IceCandidate>()
    private val requestedTransceivers = HashSet<RtpTransceiver>()
    private val senders = HashMap<MediaStreamTrack, RtpSender>()

    private val earlyData = ArrayList<ByteArray>()
    private var dataHandler: ((ByteArray) -> Unit)? = null
    private val signalListeners = ArrayList<(JSONObject) -> Unit>()
    private val connectListeners = ArrayList<() -> Unit>()
    private val closeListeners = ArrayList<() -> Unit>()

    private fun post(block: () -> Unit) {
        scope.launch { block() }
    }

    private abstract inner class SdpObs : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) {}
        override fun onSetFailure(error: String) {}
    }

    private val pcObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) {
            post { onSignalingStateChange(state) }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            post { onIceStateChange(state) }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) post { markIceComplete() }
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            // trickle:false なので候補は送らない。最初の候補からタイムアウトを数え始めるだけ。
            post { startIceCompleteTimeout() }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onRenegotiationNeeded() {}

        override fun onDataChannel(dc: DataChannel) {
            post { setupData(dc) }
        }

        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
            // 受信した音声トラックは libwebrtc が自動で再生する。ここで特別な処理は要らない。
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            if (state == PeerConnection.PeerConnectionState.FAILED) post { destroy("connection failed") }
        }
    }

    init {
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        pc = factory.createPeerConnection(config, pcObserver)
        if (pc == null) {
            post { destroy("createPeerConnection failed") }
        } else {
            if (initiator) {
                val label = ByteArray(20).also { SecureRandom().nextBytes(it) }.toHex()
                val dc = pc!!.createDataChannel(label, DataChannel.Init())
                if (dc != null) setupData(dc) else post { destroy("createDataChannel failed") }
            }
            needsNegotiation()
        }
    }

    // ------------------------------------------------------------------ 公開API

    fun onSignal(listener: (JSONObject) -> Unit) { signalListeners.add(listener) }
    fun onConnect(listener: () -> Unit) { connectListeners.add(listener) }
    fun onClose(listener: () -> Unit) { closeListeners.add(listener) }

    /** データ受信ハンドラを登録し、それまでに届いていたデータを流し込む (Trystero の drainEarlyData)。 */
    fun setDataHandler(handler: (ByteArray) -> Unit) {
        dataHandler = handler
        val buffered = ArrayList(earlyData)
        earlyData.clear()
        buffered.forEach(handler)
    }

    fun send(bytes: ByteArray) {
        val dc = channel ?: return
        if (isDead || destroying) return
        dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))
    }

    /** 相手から届いた signal (SDP / renegotiate / transceiverRequest / candidate) を処理する。 */
    fun signal(data: JSONObject) {
        if (destroying || isDead) return
        val conn = pc ?: return
        val renegotiate = data.optBoolean("renegotiate", false)
        val transceiverRequest = data.optJSONObject("transceiverRequest")
        val candidate = data.optJSONObject("candidate")
        val sdp = data.optString("sdp", "")

        if (renegotiate && initiator) {
            log("[$id] got request to renegotiate")
            needsNegotiation()
        }
        if (transceiverRequest != null && initiator) {
            log("[$id] got request for transceiver")
            addTransceiver(transceiverRequest.optString("kind", "audio"))
        }
        if (candidate != null) {
            val c = IceCandidate(
                candidate.optString("sdpMid", ""),
                candidate.optInt("sdpMLineIndex", 0),
                candidate.optString("candidate", ""),
            )
            if (remoteDescriptionSet) conn.addIceCandidate(c) else pendingCandidates.add(c)
        }
        if (sdp.isNotEmpty()) {
            val type = SessionDescription.Type.fromCanonicalForm(data.optString("type", ""))
            conn.setRemoteDescription(object : SdpObs() {
                override fun onSetSuccess() {
                    post {
                        if (isDead) return@post
                        remoteDescriptionSet = true
                        pendingCandidates.forEach { pc?.addIceCandidate(it) }
                        pendingCandidates.clear()
                        if (type == SessionDescription.Type.OFFER) createAnswer()
                    }
                }

                override fun onSetFailure(error: String) {
                    post { destroy("setRemoteDescription: $error") }
                }
            }, SessionDescription(type, sdp))
        }
        if (sdp.isEmpty() && candidate == null && !renegotiate && transceiverRequest == null) {
            destroy("signal() called with invalid signal data")
        }
    }

    /** 自分の音声トラックを相手に流す (Trystero の room.addStream 相当)。 */
    fun addTrack(track: MediaStreamTrack, streamId: String) {
        if (destroying || isDead) return
        val conn = pc ?: return
        if (senders.containsKey(track)) return
        val sender = conn.addTrack(track, listOf(streamId))
        if (sender == null) {
            log("[$id] addTrack failed")
            return
        }
        senders[track] = sender
        log("[$id] audio track added")
        needsNegotiation()
    }

    /**
     * 受信中の音声の大きさ (0..1 の線形値) を libwebrtc の統計から取る。
     * Web版の AnalyserNode による RX レベルに相当。取れなければ null。
     */
    fun fetchInboundAudioLevel(callback: (Double?) -> Unit) {
        val conn = pc
        if (conn == null || isDead) {
            callback(null)
            return
        }
        conn.getStats { report ->
            var level: Double? = null
            for (stat in report.statsMap.values) {
                if (stat.type == "inbound-rtp") {
                    val v = stat.members["audioLevel"]
                    if (v is Double) level = maxOf(level ?: 0.0, v)
                }
            }
            scope.launch { callback(level) }
        }
    }

    fun destroy(reason: String? = null) {
        if (isDead || destroying) return
        destroying = true
        isDead = true
        log("[$id] destroying${if (reason != null) " ($reason)" else ""}")
        post {
            iceCompleteTimer?.cancel()
            try {
                channel?.unregisterObserver()
                channel?.close()
                channel?.dispose()
            } catch (_: Exception) {
            }
            try {
                pc?.close()
                pc?.dispose()
            } catch (_: Exception) {
            }
            pc = null
            channel = null
            closeListeners.toList().forEach { it() }
        }
    }

    // ------------------------------------------------------------------ ネゴシエーション

    private fun needsNegotiation() {
        if (batchedNegotiation) return // 同じ tick 内の複数要求をまとめる
        batchedNegotiation = true
        post {
            batchedNegotiation = false
            if (initiator || !firstNegotiation) {
                negotiate()
            } // ノンイニシエータの最初のネゴシエーション要求は破棄 (simple-peer と同じ)
            firstNegotiation = false
        }
    }

    private fun negotiate() {
        if (destroying || isDead) return
        if (initiator) {
            if (isNegotiating) {
                queuedNegotiation = true
            } else {
                post { createOffer() }
            }
        } else {
            if (isNegotiating) {
                queuedNegotiation = true
            } else {
                // イニシエータにオファーを作ってもらう
                emitSignal(JSONObject().put("type", "renegotiate").put("renegotiate", true))
            }
        }
        isNegotiating = true
    }

    private fun addTransceiver(kind: String) {
        if (destroying || isDead) return
        val conn = pc ?: return
        if (initiator) {
            try {
                val mediaType = if (kind == "video") MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
                else MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
                conn.addTransceiver(mediaType, RtpTransceiver.RtpTransceiverInit())
                needsNegotiation()
            } catch (e: Exception) {
                destroy("addTransceiver: ${e.message}")
            }
        } else {
            emitSignal(
                JSONObject().put("type", "transceiverRequest")
                    .put("transceiverRequest", JSONObject().put("kind", kind)),
            )
        }
    }

    private fun requestMissingTransceivers() {
        val conn = pc ?: return
        for (t in conn.transceivers) {
            val track = t.sender?.track()
            if (t.mid == null && track != null && !requestedTransceivers.contains(t)) {
                requestedTransceivers.add(t)
                addTransceiver(track.kind())
            }
        }
    }

    private fun filterTrickle(sdp: String) = sdp.replace(Regex("a=ice-options:trickle\\s\\n"), "")

    private fun createOffer() {
        if (isDead) return
        val conn = pc ?: return
        conn.createOffer(object : SdpObs() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                post {
                    if (isDead) return@post
                    val offer = SessionDescription(sdp.type, filterTrickle(sdp.description))
                    setLocalThenSend(offer, afterSend = {})
                }
            }

            override fun onCreateFailure(error: String) {
                post { destroy("createOffer: $error") }
            }
        }, MediaConstraints())
    }

    private fun createAnswer() {
        if (isDead) return
        val conn = pc ?: return
        conn.createAnswer(object : SdpObs() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                post {
                    if (isDead) return@post
                    val answer = SessionDescription(sdp.type, filterTrickle(sdp.description))
                    setLocalThenSend(answer, afterSend = { if (!initiator) requestMissingTransceivers() })
                }
            }

            override fun onCreateFailure(error: String) {
                post { destroy("createAnswer: $error") }
            }
        }, MediaConstraints())
    }

    private fun setLocalThenSend(desc: SessionDescription, afterSend: () -> Unit) {
        val conn = pc ?: return
        conn.setLocalDescription(object : SdpObs() {
            override fun onSetSuccess() {
                post {
                    if (isDead) return@post
                    val send = {
                        if (!isDead) {
                            val local = pc?.localDescription ?: desc
                            emitSignal(
                                JSONObject().put("type", local.type.canonicalForm()).put("sdp", local.description),
                            )
                            afterSend()
                        }
                    }
                    // trickle:false: 候補を集め終えるまで待つ
                    if (iceComplete) send() else iceCompleteWaiters.add(send)
                }
            }

            override fun onSetFailure(error: String) {
                post { destroy("setLocalDescription: $error") }
            }
        }, desc)
    }

    private fun markIceComplete() {
        if (iceComplete) return
        iceComplete = true
        iceCompleteTimer?.cancel()
        val waiters = iceCompleteWaiters.toList()
        iceCompleteWaiters.clear()
        waiters.forEach { it() }
    }

    private fun startIceCompleteTimeout() {
        if (isDead || iceCompleteTimer != null) return
        iceCompleteTimer = scope.launch {
            delay(ICE_COMPLETE_TIMEOUT_MS)
            if (!iceComplete) {
                log("[$id] ice gathering timeout; sending what we have")
                markIceComplete()
            }
        }
    }

    private fun emitSignal(json: JSONObject) {
        if (!firstSignal.isCompleted) firstSignal.complete(json)
        signalListeners.toList().forEach { it(json) }
    }

    // ------------------------------------------------------------------ 状態変化

    private fun onSignalingStateChange(state: PeerConnection.SignalingState) {
        if (isDead) return
        if (state == PeerConnection.SignalingState.STABLE) {
            isNegotiating = false
            if (queuedNegotiation) {
                queuedNegotiation = false
                needsNegotiation()
            }
        }
    }

    private fun onIceStateChange(state: PeerConnection.IceConnectionState) {
        if (isDead) return
        log("[$id] ice $state")
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                pcReady = true
                maybeReady()
            }
            PeerConnection.IceConnectionState.FAILED -> destroy("ice failed")
            PeerConnection.IceConnectionState.CLOSED -> destroy("ice closed")
            else -> {}
        }
    }

    private fun setupData(dc: DataChannel) {
        channel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                val state = dc.state()
                post {
                    when (state) {
                        DataChannel.State.OPEN -> onChannelOpen()
                        DataChannel.State.CLOSED -> destroy("data channel closed")
                        else -> {}
                    }
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                // buffer.data はこの関数を抜けると解放されるので、ここでコピーする
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                post { handleData(bytes) }
            }
        })
        if (dc.state() == DataChannel.State.OPEN) post { onChannelOpen() }
    }

    private fun onChannelOpen() {
        if (connected || isDead) return
        channelReady = true
        maybeReady()
    }

    private fun maybeReady() {
        if (connected || !pcReady || !channelReady) return
        connected = true
        log("[$id] connect")
        connectListeners.toList().forEach { it() }
    }

    private fun handleData(bytes: ByteArray) {
        if (isDead) return
        val handler = dataHandler
        if (handler == null) earlyData.add(bytes) else handler(bytes)
    }

    companion object {
        private const val ICE_COMPLETE_TIMEOUT_MS = 5_000L
    }
}
