package app.pochitalk.core

import java.security.MessageDigest

/** Trystero nostr 戦略が使うイベントの作り方 (src/nostr.js)。 */
object NostrProtocol {
    const val KIND = 29333
    const val TAG = "x"

    private val privateKey = Schnorr.randomPrivateKey()
    val publicKeyHex: String by lazy { Schnorr.publicKey(privateKey).toHex() }

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

    fun createEvent(topic: String, content: String, nowSec: Long = System.currentTimeMillis() / 1000): String {
        val pubkey = publicKeyHex
        val tags = "[[" + jsonString(TAG) + "," + jsonString(topic) + "]]"
        val serialized = "[0," + jsonString(pubkey) + "," + nowSec + "," + KIND + "," + tags + "," + jsonString(content) + "]"
        val idBytes = MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray(Charsets.UTF_8))
        val sig = Schnorr.sign(idBytes, privateKey).toHex()
        return "[\"EVENT\",{\"kind\":" + KIND +
            ",\"content\":" + jsonString(content) +
            ",\"pubkey\":" + jsonString(pubkey) +
            ",\"created_at\":" + nowSec +
            ",\"tags\":" + tags +
            ",\"id\":\"" + idBytes.toHex() + "\"" +
            ",\"sig\":\"" + sig + "\"}]"
    }

    fun subscribe(subId: String, topic: String, nowSec: Long = System.currentTimeMillis() / 1000): String =
        "[\"REQ\"," + jsonString(subId) + ",{\"kinds\":[" + KIND + "],\"since\":" + nowSec +
            ",\"#" + TAG + "\":[" + jsonString(topic) + "]}]"

    fun unsubscribe(subId: String): String = "[\"CLOSE\"," + jsonString(subId) + "]"
}
