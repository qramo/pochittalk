package app.pochitalk.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/**
 * 1本のNostrリレーとのWebSocket。切れたら指数バックオフで再接続し、購読(REQ)を再送する
 * (Trystero本体は再接続時に購読を再送しないが、モバイルは回線が切れやすいのでここで補う)。
 */
class NostrClient(
    val index: Int,
    val url: String,
    private val http: OkHttpClient,
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    private val onEvent: (client: NostrClient, subId: String, content: String) -> Unit,
    private val log: (String) -> Unit = {},
) {
    @Volatile private var socket: WebSocket? = null
    @Volatile var isOpen: Boolean = false
        private set
    @Volatile private var closed = false
    private var retryMs = DEFAULT_RETRY_MS
    private val subs = ConcurrentHashMap<String, String>() // subId -> REQ メッセージ

    fun connect() {
        if (closed) return
        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isOpen = true
                retryMs = DEFAULT_RETRY_MS
                log("relay open: $url")
                subs.values.forEach { webSocket.send(it) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleClosed()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
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
                    val content = arr.getJSONObject(2).getString("content")
                    onEvent(this, subId, content)
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

    fun subscribe(subId: String, topic: String) {
        val req = NostrProtocol.subscribe(subId, topic)
        subs[subId] = req
        send(req)
    }

    fun unsubscribe(subId: String) {
        subs.remove(subId)
        send(NostrProtocol.unsubscribe(subId))
    }

    fun publish(topic: String, content: String) {
        send(NostrProtocol.createEvent(topic, content))
    }

    fun close() {
        closed = true
        isOpen = false
        socket?.close(1000, null)
        socket = null
    }

    companion object {
        private const val DEFAULT_RETRY_MS = 3333L
        private const val MAX_RETRY_MS = 60_000L
    }
}
