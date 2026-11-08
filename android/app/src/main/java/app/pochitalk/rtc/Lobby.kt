package app.pochitalk.rtc

import app.pochitalk.core.NostrClient
import app.pochitalk.core.NostrProtocol
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.PeerConnection
import kotlin.coroutines.resume

/**
 * 部屋一覧 (在室通知)。通話の接続 (WebRTC) とは別に、Nostr リレーに「いま自分はこの部屋にいる」を流して、
 * 他の人の通知を集める。Web版 (worker.js の presence*) と同じ形:
 *   イベント kind 29334 / タグ ["x","pochitalk-presence-v1"] / content = {ch, talking, name, tag, by, at}
 *   購読は since = いまの5秒前。同じイベントが複数のリレーから届くので id で重複を捨てる。
 */
class PresenceClient(
    http: OkHttpClient,
    urls: List<String>,
    private val onPresence: (pubkey: String, data: JSONObject) -> Unit,
) {
    private val seen = HashMap<String, Long>()
    private val subId = "pochi-presence"

    private val clients: List<NostrClient> = urls.mapIndexed { i, url ->
        NostrClient(
            index = i,
            url = url,
            http = http,
            schedule = { ms, task -> Runtime.scope.launch { delay(ms); task() } },
            onEvent = { _, _, _, content, ev -> Runtime.scope.launch { handle(content, ev) } },
            log = Runtime::log,
        )
    }

    fun start() {
        clients.forEach { c ->
            c.connect()
            c.subscribe(subId) { NostrProtocol.subscribePresence(subId) }
        }
    }

    fun reconnect() {
        clients.forEach { it.reconnect() }
    }

    /** 一覧の取り直し: 開いているリレーには購読をやり直し、死んだ接続はつなぎ直す。 */
    fun refresh() {
        clients.forEach { it.refresh() }
    }

    fun close() {
        clients.forEach { it.close() }
    }

    /** 自分の在室を全リレーに流す。 */
    fun publish(data: JSONObject) {
        val ev = NostrProtocol.createEvent(NostrProtocol.PRESENCE_KIND, NostrProtocol.PRESENCE_TOPIC, data.toString())
        clients.forEach { it.publishRaw(ev) }
    }

    private fun handle(content: String, ev: JSONObject) {
        if (ev.optInt("kind") != NostrProtocol.PRESENCE_KIND) return
        val pubkey = ev.optString("pubkey", "")
        if (pubkey.isEmpty() || pubkey == NostrProtocol.publicKeyHex) return
        val id = ev.optString("id", "")
        val now = System.currentTimeMillis()
        if (id.isNotEmpty()) {
            if (seen.containsKey(id)) return
            seen[id] = now
            if (seen.size > 500) seen.entries.removeAll { now - it.value > 120_000L }
        }
        val createdAt = ev.optLong("created_at", 0L)
        if (createdAt != 0L && createdAt < now / 1000 - 120) return
        val data = try {
            JSONObject(content)
        } catch (e: Exception) {
            return
        }
        if (data.opt("ch") !is String) return
        onPresence(pubkey, data)
    }
}

/** Web版と同じ顔ぶれのリレー。 */
object RelayList {
    val NOSTR_RELAY_URLS = listOf(
        "wss://relay.primal.net",
        "wss://purplerelay.com",
        "wss://bucket.coracle.social",
        "wss://yabu.me/v2",
        "wss://x.kojira.io",
        "wss://relay.notoshi.win",
        "wss://relay.ditto.pub",
    )

    /** 6秒以内に開いたら「生きている」。 */
    private suspend fun probe(http: OkHttpClient, url: String): Boolean = withTimeoutOrNull(6_000L) {
        suspendCancellableCoroutine<Boolean> { cont ->
            val ws = http.newWebSocket(
                Request.Builder().url(url).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (cont.isActive) cont.resume(true)
                        webSocket.close(1000, null)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (cont.isActive) cont.resume(false)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
            )
            cont.invokeOnCancellation { ws.cancel() }
        }
    } ?: false

    /**
     * 動いているリレーを選ぶ (Web版の pickRelaysMain と同じ): 全部を同時に調べ、ばらばらの順にして、
     * 通話用も在室通知用も、動いている全部 (Web版は在室通知を全部に流す)。1本も開かなければ全部を使う。
     */
    suspend fun pick(http: OkHttpClient): Pair<List<String>, List<String>> {
        val results = coroutineScope {
            NOSTR_RELAY_URLS.map { u -> async { u to probe(http, u) } }.map { it.await() }
        }
        val alive = results.filter { it.second }.map { it.first }.shuffled()
        // 通話用は、動いている全部を使う。Web版は7本から3本をばらばらに選ぶので、こちらも3本だと、
        // 約11%の組み合わせで共通のリレーが1本も無く、お互いが見えない (Android同士も同じ)。全部使えば必ず重なる。
        return if (alive.isEmpty()) NOSTR_RELAY_URLS to NOSTR_RELAY_URLS
        else alive to alive
    }
}

/**
 * TURN の資格情報。鍵は端末に持たず、Web版の /api/turn から短命の資格情報を受け取る。
 * 取れなければ STUN だけ (直接つなぐ)。
 */
class TurnClient(private val http: OkHttpClient, private val endpoint: String) {
    private val stunOnly: List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
        PeerConnection.IceServer.builder(GOOGLE_STUN).createIceServer(),
    )

    @Volatile
    var iceServers: List<PeerConnection.IceServer> = stunOnly
        private set

    @Volatile
    var hasTurn = false
        private set
    private var fetchedAt = 0L

    /** 取り直す。10分以内に取れていれば (force でなければ) 何もしない。失敗しても前回の分を使い続ける。 */
    suspend fun refresh(force: Boolean = false) {
        if (!force && fetchedAt != 0L && System.currentTimeMillis() - fetchedAt < CACHE_MS) return
        val text = withTimeoutOrNull(8_000L) {
            withContext(Dispatchers.IO) {
                try {
                    http.newCall(Request.Builder().url(endpoint).header("Cache-Control", "no-store").build()).execute().use { r ->
                        if (r.isSuccessful) r.body?.string() else null
                    }
                } catch (e: Exception) {
                    null
                }
            }
        } ?: return
        val list = parse(text) ?: return
        iceServers = list + PeerConnection.IceServer.builder(GOOGLE_STUN).createIceServer()
        hasTurn = list.any { s -> s.urls.any { it.startsWith("turn:") || it.startsWith("turns:") } }
        fetchedAt = System.currentTimeMillis()
    }

    companion object {
        const val GOOGLE_STUN = "stun:stun.l.google.com:19302"
        private const val CACHE_MS = 10 * 60 * 1000L

        /** {"iceServers":[{"urls":[...]|"...", "username":"...", "credential":"..."}]} を読む。空なら null。 */
        fun parse(text: String?): List<PeerConnection.IceServer>? {
            if (text == null) return null
            return try {
                val arr = JSONObject(text).optJSONArray("iceServers") ?: return null
                val out = ArrayList<PeerConnection.IceServer>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val urls = ArrayList<String>()
                    val u = o.opt("urls")
                    if (u is String) urls.add(u)
                    else if (u is org.json.JSONArray) for (k in 0 until u.length()) urls.add(u.optString(k))
                    if (urls.isEmpty()) continue
                    val b = PeerConnection.IceServer.builder(urls)
                    val user = o.optString("username", "")
                    val cred = o.optString("credential", "")
                    if (user.isNotEmpty()) b.setUsername(user)
                    if (cred.isNotEmpty()) b.setPassword(cred)
                    out.add(b.createIceServer())
                }
                out.ifEmpty { null }
            } catch (e: Exception) {
                null
            }
        }
    }
}
