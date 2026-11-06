package app.pochitalk.rtc

import app.pochitalk.core.NostrClient
import app.pochitalk.core.TrysteroCrypto
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Trystero 0.20.0 nostr 戦略の「リレー接続」部分。ブラウザ版の strategy.js / nostr.js に対応。
 *  - リレーは Trystero の defaultRelayUrls の先頭5件 (relayRedundancy = 5) を使う。
 *    ブラウザ版も同じ5件に購読と投稿をするので、1つでも生きていれば相互に見つけ合える。
 *  - selfId はプロセス内で全部屋共通 (Trystero の utils.selfId と同じ)。
 *  - 1つのリレー接続を全部屋 (ロビー + 通話部屋) で共有する。
 */
class TrysteroNetwork(
    val factory: PeerConnectionFactory,
    val iceServers: List<PeerConnection.IceServer>,
    relayHosts: List<String> = DEFAULT_RELAYS,
) {
    val selfId: String = TrysteroCrypto.genId(20)

    internal class SubInfo(val room: TrysteroRoom, val clientIndex: Int, val topic: String)

    private val subs = ConcurrentHashMap<String, SubInfo>()
    private val rooms = HashMap<String, TrysteroRoom>() // "appId/roomId"

    private val http = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    internal val relays: List<NostrClient> = relayHosts.mapIndexed { index, host ->
        NostrClient(
            index = index,
            url = "wss://$host",
            http = http,
            schedule = { ms, task -> Runtime.scope.launch { delay(ms); task() } },
            onEvent = { client, subId, content ->
                // 別スレッドから来るので、エンジンスレッドに戻してから処理する
                Runtime.scope.launch {
                    val info = subs[subId]
                    if (info != null && info.clientIndex == client.index) {
                        info.room.handleRelayMessage(info.clientIndex, info.topic, content)
                    }
                }
            },
            log = Runtime::log,
        )
    }

    fun start() {
        relays.forEach { it.connect() }
    }

    fun joinRoom(appId: String, roomId: String): TrysteroRoom {
        val key = "$appId/$roomId"
        rooms[key]?.let { return it }
        val room = TrysteroRoom(appId, roomId, this)
        rooms[key] = room
        return room
    }

    internal fun roomClosed(appId: String, roomId: String) {
        rooms.remove("$appId/$roomId")
    }

    internal fun subscribe(room: TrysteroRoom, clientIndex: Int, topic: String): String {
        val subId = TrysteroCrypto.genId(64)
        subs[subId] = SubInfo(room, clientIndex, topic)
        relays[clientIndex].subscribe(subId, topic)
        return subId
    }

    internal fun unsubscribe(clientIndex: Int, subId: String) {
        subs.remove(subId)
        relays[clientIndex].unsubscribe(subId)
    }

    companion object {
        /** Trystero 0.20.0 の defaultRelayUrls の先頭5件 (ブラウザ版が実際に使うもの)。 */
        val DEFAULT_RELAYS = listOf(
            "relay.nostr.net",
            "relay.snort.social",
            "relay.piazza.today",
            "relay.exit.pub",
            "nostr.lu.ke",
        )
    }
}
