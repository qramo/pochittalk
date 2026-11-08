package app.pochitalk.core

import java.security.MessageDigest

/**
 * Trystero 0.25.4 の nostr 戦略 (@trystero-p2p/nostr) が使うイベントの作り方。
 *  - イベントの kind は topic ごとに変わる: strToNum(topic, 10000) + 20000
 *  - タグは ["x", topic]。購読は kinds と #x の配列を1本のREQにまとめる
 * 在室通知 (部屋一覧) は Web版の独自方式: kind 29334, タグ x = "pochitalk-presence-v1"。
 */
object NostrProtocol {
    const val TAG = "x"
    const val PRESENCE_KIND = 29334
    const val PRESENCE_TOPIC = "pochitalk-presence-v1"

    private val privateKey = Schnorr.randomPrivateKey()
    val publicKeyHex: String by lazy { Schnorr.publicKey(privateKey).toHex() }

    /** JS の strToNum: 文字コード(UTF-16)の合計を limit で割った余り。 */
    fun strToNum(str: String, limit: Int): Int {
        var sum = 0L
        for (c in str) sum += c.code
        return (sum % limit).toInt()
    }

    fun topicToKind(topic: String): Int = strToNum(topic, 10_000) + 20_000

    /** JSON.stringify と同じ文字列エスケープ。イベントidのハッシュ対象なのでリレー側の再計算と一致させる。 */
    fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\u000c' -> sb.append("\\f")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** topic 宛てのイベント (["EVENT", {...}]) を作る。kind は呼び出し側が決める。 */
    fun createEvent(kind: Int, topic: String, content: String, nowSec: Long = System.currentTimeMillis() / 1000): String {
        val pubkey = publicKeyHex
        val tags = "[[" + jsonString(TAG) + "," + jsonString(topic) + "]]"
        val serialized = "[0," + jsonString(pubkey) + "," + nowSec + "," + kind + "," + tags + "," + jsonString(content) + "]"
        val idBytes = MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray(Charsets.UTF_8))
        val sig = Schnorr.sign(idBytes, privateKey).toHex()
        return "[\"EVENT\",{\"kind\":" + kind +
            ",\"content\":" + jsonString(content) +
            ",\"pubkey\":" + jsonString(pubkey) +
            ",\"created_at\":" + nowSec +
            ",\"tags\":" + tags +
            ",\"id\":\"" + idBytes.toHex() + "\"" +
            ",\"sig\":\"" + sig + "\"}]"
    }

    /** Trystero の通信イベント: kind は topic から決まる。 */
    fun createTopicEvent(topic: String, content: String, nowSec: Long = System.currentTimeMillis() / 1000): String =
        createEvent(topicToKind(topic), topic, content, nowSec)

    /** 複数の topic をまとめて購読する (Trystero の batch 購読と同じ形)。 */
    fun subscribe(subId: String, topics: List<String>, sinceSec: Long = System.currentTimeMillis() / 1000): String {
        val kinds = topics.map { topicToKind(it) }.distinct().joinToString(",")
        val xs = topics.joinToString(",") { jsonString(it) }
        return "[\"REQ\"," + jsonString(subId) + ",{\"kinds\":[" + kinds + "],\"since\":" + sinceSec +
            ",\"#" + TAG + "\":[" + xs + "]}]"
    }

    /** 在室通知の購読 (Web版と同じ: 5秒前から)。 */
    fun subscribePresence(subId: String, nowSec: Long = System.currentTimeMillis() / 1000): String =
        "[\"REQ\"," + jsonString(subId) + ",{\"kinds\":[" + PRESENCE_KIND + "],\"#" + TAG + "\":[" +
            jsonString(PRESENCE_TOPIC) + "],\"since\":" + (nowSec - 5) + "}]"

    fun unsubscribe(subId: String): String = "[\"CLOSE\"," + jsonString(subId) + "]"
}
