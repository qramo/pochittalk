package app.pochitalk.rtc

import app.pochitalk.core.NostrClient
import app.pochitalk.core.TrysteroCrypto
import app.pochitalk.core.NostrProtocol
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Trystero 0.25.4 nostr 戦略の「リレー接続」部分。
 *  - リレーは呼び出し側が選ぶ (Web版と同じ: 動いているものから最大3本)。
 *  - selfId はプロセスで1つ (Trystero の utils.selfId と同じ。全部屋共通)。
 *  - 1つのリレー接続を全部屋で共有する。
 */
class TrysteroNetwork(
    val factory: PeerConnectionFactory,
    /** 接続の準備を作るたびに呼ばれる。TURN の資格情報が更新されても、次の接続から反映される。 */
    val iceServers: () -> List<PeerConnection.IceServer>,
    relayUrls: List<String>,
    private val http: OkHttpClient,
) {
    val selfId: String get() = SELF_ID

    internal class SubInfo(val room: TrysteroRoom, val clientIndex: Int)

    private val subs = ConcurrentHashMap<String, SubInfo>()
    private val rooms = HashMap<String, TrysteroRoom>() // "appId/roomId"

    internal val relays: List<NostrClient> = relayUrls.mapIndexed { index, url ->
        NostrClient(
            index = index,
            url = url,
            http = http,
            schedule = { ms, task -> Runtime.scope.launch { delay(ms); task() } },
            onEvent = { client, subId, topic, content, _ ->
                // 別スレッドから来るので、エンジンスレッドに戻してから処理する
                Runtime.scope.launch {
                    val info = subs[subId]
                    if (info != null && info.clientIndex == client.index) {
                        info.room.handleRelayMessage(info.clientIndex, topic, content)
                    }
                }
            },
            log = Runtime::log,
        )
    }

    fun start() {
        relays.forEach { it.connect() }
    }

    /** 全リレーとのWebSocketを張り直す (購読は保持)。 */
    fun reconnectRelays() {
        relays.forEach { it.reconnect() }
    }

    fun close() {
        rooms.values.toList().forEach { it.leave() }
        relays.forEach { it.close() }
        subs.clear()
    }

    fun joinRoom(appId: String, roomId: String): TrysteroRoom {
        val key = "$appId/$roomId"
        rooms[key]?.let { return it }
        val room = TrysteroRoom(appId, roomId, this)
        rooms[key] = room
        return room
    }

    internal fun roomClosed(appId: String, roomId: String, room: TrysteroRoom) {
        val key = "$appId/$roomId"
        if (rooms[key] === room) rooms.remove(key)
    }

    internal fun subscribe(room: TrysteroRoom, clientIndex: Int, topics: List<String>): String {
        val subId = TrysteroCrypto.genId(64)
        subs[subId] = SubInfo(room, clientIndex)
        relays[clientIndex].subscribe(subId) { NostrProtocol.subscribe(subId, topics) }
        return subId
    }

    internal fun unsubscribe(clientIndex: Int, subId: String) {
        subs.remove(subId)
        relays[clientIndex].unsubscribe(subId)
    }

    companion object {
        /**
         * Trystero の selfId (20文字)。プロセスで1つ。
         * 先頭を "00" にして、相手より「小さい id」になるようにしている。Trystero は id の小さい側がオファーを作るので、
         * Android が必ずオファー側になる。Web版 (0.25.4) は、57秒以上前に作り置きしたオファーを出すとき、
         * ブラウザの挙動 (ロールバック後の ICE 再起動) で中身の無い SDP になり、ブラウザ側がオファーを出す役のときは
         * つながらないことがあるため (Android が答える側だと、これを受け取ってしまう)。
         */
        val SELF_ID: String by lazy { "00" + TrysteroCrypto.genId(18) }

        fun newHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
