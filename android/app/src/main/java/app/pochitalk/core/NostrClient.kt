package app.pochitalk.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 1本のNostrリレーとのWebSocket。切れたら指数バックオフで再接続し、購読(REQ)を再送する
 * (Trystero本体も再接続時に購読を再送する。since は再送のたびに現在時刻へ更新する)。
 */
class NostrClient(
    val index: Int,
    val url: String,
    private val http: OkHttpClient,
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    /** topic は、イベントのタグ ["x", topic] の値 (無ければ空文字)。 */
    private val onEvent: (client: NostrClient, subId: String, topic: String, content: String, event: JSONObject) -> Unit,
    private val log: (String) -> Unit = {},
) {
    @Volatile private var socket: WebSocket? = null
    @Volatile var isOpen: Boolean = false
        private set
    @Volatile private var closed = false
    @Volatile private var generation = 0 // 作り直したあとに、古いソケットのコールバックを無視するための世代番号
    private var retryMs = DEFAULT_RETRY_MS
    private val subs = ConcurrentHashMap<String, () -> String>() // subId -> REQ メッセージを作る関数

    fun connect() {
        if (closed) return
        val request = Request.Builder().url(url).build()
        val gen = ++generation
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation) return
                isOpen = true
                retryMs = DEFAULT_RETRY_MS
                log("relay open: $url")
                subs.values.forEach { webSocket.send(it()) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen == generation) handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen == generation) handleClosed()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != generation) return
                log("relay failure: $url ${t.message}")
                handleClosed()
            }
        })
    }

    private fun handleClosed() {
        isOpen = false
        socket = null
        if (closed) return
        val wait = retryMs
        retryMs = minOf(retryMs * 2, MAX_RETRY_MS)
        schedule(wait) { connect() }
    }

    private fun handleMessage(text: String) {
        try {
            val arr = JSONArray(text)
            when (arr.getString(0)) {
                "EVENT" -> {
                    val subId = arr.getString(1)
                    val ev = arr.getJSONObject(2)
                    val content = ev.getString("content")
                    var topic = ""
                    val tags = ev.optJSONArray("tags")
                    if (tags != null) {
                        for (i in 0 until tags.length()) {
                            val t = tags.optJSONArray(i) ?: continue
                            if (t.optString(0) == NostrProtocol.TAG) {
                                topic = t.optString(1)
                                break
                            }
                        }
                    }
                    onEvent(this, subId, topic, content, ev)
                }
                "NOTICE" -> log("relay notice $url: ${arr.optString(1)}")
                "OK" -> if (!arr.optBoolean(2, true)) log("relay rejected $url: ${arr.optString(3)}")
            }
        } catch (e: Exception) {
            log("relay parse error $url: ${e.message}")
        }
    }

    fun send(text: String): Boolean {
        val s = socket
        return isOpen && s != null && s.send(text)
    }

    /** 購読する。builder は接続のたびに呼ばれる (since を最新にするため)。 */
    fun subscribe(subId: String, builder: () -> String) {
        subs[subId] = builder
        send(builder())
    }

    fun unsubscribe(subId: String) {
        subs.remove(subId)
        send(NostrProtocol.unsubscribe(subId))
    }

    /** topic 宛てに、Trystero の通信イベントとして投稿する。送れたら true。 */
    fun publish(topic: String, content: String): Boolean = send(NostrProtocol.createTopicEvent(topic, content))

    /** 作ったイベント (["EVENT", ...]) をそのまま投稿する。 */
    fun publishRaw(event: String): Boolean = send(event)

    /**
     * いまの接続を捨てて、つなぎ直す (購読は保持して、開いたら再送する)。
     * 回線が切り替わったあとなど、見かけ上は開いていても実際には死んでいる接続を張り直すために使う。
     */
    fun reconnect() {
        if (closed) return
        val old = socket
        generation++ // 古いソケットのコールバックを無効にする
        socket = null
        isOpen = false
        retryMs = DEFAULT_RETRY_MS
        try {
            old?.cancel()
        } catch (_: Exception) {
        }
        connect()
    }

    /**
     * 在室通知などの取り直し用 (Web版の presenceRefresh と同じ):
     * 開いている接続には購読 (REQ) をやり直し、閉じている/閉じかけの接続は捨ててつなぎ直す。
     * 古い接続の「閉じた」通知は世代番号で無視するので、新しい接続の登録を消さない。
     */
    fun refresh() {
        if (closed) return
        val s = socket
        if (isOpen && s != null) {
            retryMs = DEFAULT_RETRY_MS
            subs.values.forEach { s.send(it()) }
        } else {
            reconnect()
        }
    }

    fun close() {
        closed = true
        isOpen = false
        generation++
        socket?.close(1000, null)
        socket = null
    }

    companion object {
        private const val DEFAULT_RETRY_MS = 3333L
        private const val MAX_RETRY_MS = 60_000L
    }
}
