import app.pochitalk.core.*
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

fun main() {
    val hosts = listOf("relay.nostr.net", "relay.snort.social", "relay.piazza.today", "relay.exit.pub", "nostr.lu.ke")
    val http = OkHttpClient()
    val topic = "pochi-test-" + TrysteroCrypto.genId(12)
    val results = ConcurrentHashMap<String, String>()
    val latch = CountDownLatch(hosts.size)
    val clients = hosts.mapIndexed { i, h ->
        NostrClient(
            index = i, url = "wss://$h", http = http,
            schedule = { ms, task -> Thread { Thread.sleep(ms); task() }.start() },
            onEvent = { c, subId, content ->
                if (results.putIfAbsent(c.url, "echo:" + content) == null) latch.countDown()
            },
            log = { println("  log: $it") },
        )
    }
    clients.forEach { it.connect() }
    Thread.sleep(3000)
    clients.forEach { c ->
        println("${c.url} open=${c.isOpen}")
        c.subscribe(TrysteroCrypto.genId(64), topic)
    }
    Thread.sleep(500)
    clients.forEach { it.publish(topic, "{\"peerId\":\"kotlin-test\"}") }
    latch.await(10, TimeUnit.SECONDS)
    clients.forEach { c -> println("RESULT ${c.url}: ${results[c.url] ?: "NO ECHO"}") }
    val ok = results.size
    println("relays echoing our signed event: $ok / ${hosts.size}")
    clients.forEach { it.close() }
    System.exit(if (ok > 0) 0 else 1)
}
