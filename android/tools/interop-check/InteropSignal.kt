import app.pochitalk.core.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 本物の Trystero(JS) が announce/offer を出す部屋に、Kotlin の信号層だけで入って、JSのオファーを復号できるか試す */
fun main() {
    val appId = "pochitalk-walkie-talkie-cf-worker"
    val roomId = "h-deadbeef"
    val hosts = listOf("relay.nostr.net", "relay.snort.social", "relay.piazza.today")
    val selfId = TrysteroCrypto.genId(20)
    val rootPlain = TrysteroCrypto.topicPath("Trystero", appId, roomId)
    val rootTopic = TrysteroCrypto.sha1Topic(rootPlain)
    val selfTopic = TrysteroCrypto.sha1Topic(TrysteroCrypto.topicPath(rootPlain, selfId))
    val key = TrysteroCrypto.genKey("", appId, roomId)
    val http = OkHttpClient()
    val gotAnnounce = CountDownLatch(1)
    val gotOffer = CountDownLatch(1)
    var jsPeerId = ""
    val topicNames = mapOf<String, String>()
    val subTopics = HashMap<String, String>()
    val clients = hosts.mapIndexed { i, h ->
        NostrClient(i, "wss://$h", http, { ms, t -> Thread { Thread.sleep(ms); t() }.start() },
            onEvent = { c, subId, content ->
                val topic = subTopics[subId]
                val msg = try { JSONObject(content) } catch (e: Exception) { null }
                if (msg != null) {
                    val pid = msg.optString("peerId", "")
                    if (pid.isNotEmpty() && pid != selfId) {
                        if (topic == rootTopic && !msg.has("offer") && !msg.has("answer")) {
                            if (gotAnnounce.count > 0) { println("RX announce from JS peer $pid via ${c.url} (root topic matched)"); jsPeerId = pid; gotAnnounce.countDown() }
                        }
                        val offer = msg.optJSONObject("offer")
                        if (topic == selfTopic && offer != null) {
                            val sdp = TrysteroCrypto.decrypt(key, offer.getString("sdp"))
                            println("RX OFFER via ${c.url} type=${offer.getString("type")} decrypted sdp: " + sdp.replace("\r\n", " | ").take(120))
                            gotOffer.countDown()
                        }
                    }
                }
            })
    }
    clients.forEach { it.connect() }
    Thread.sleep(2500)
    clients.forEach { c ->
        val a = TrysteroCrypto.genId(64); val b = TrysteroCrypto.genId(64)
        subTopics[a] = rootTopic; subTopics[b] = selfTopic
        c.subscribe(a, rootTopic); c.subscribe(b, selfTopic)
    }
    // 自分の announce を5秒おきに出す (Trystero と同じ形式)
    val t = Thread { while (true) { clients.forEach { it.publish(rootTopic, JSONObject().put("peerId", selfId).toString()) }; Thread.sleep(5333) } }
    t.isDaemon = true; t.start()
    println("Kotlin selfId=$selfId waiting for JS peer...")
    val a = gotAnnounce.await(40, TimeUnit.SECONDS)
    val o = gotOffer.await(40, TimeUnit.SECONDS)
    println("announce from JS seen: $a / offer from JS decrypted: $o")
    System.exit(if (a && o) 0 else 1)
}
