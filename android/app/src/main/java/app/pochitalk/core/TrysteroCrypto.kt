package app.pochitalk.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Trystero 0.20.0 (src/crypto.js, src/utils.js) と同じ表現を作る。
 *  - topicPath = parts.join('@')
 *  - sha1(str)  = 各バイトを toString(36) して連結 (ゼロ埋め無し。16進ではない!)
 *  - 鍵        = SHA-256("${password}:${appId}:${roomId}") をそのまま AES-GCM の鍵に使う
 *  - 暗号文    = iv(16バイト)を10進で "," 連結 + "$" + base64(暗号文||GCMタグ)
 */
object TrysteroCrypto {
    private val random = SecureRandom()

    fun topicPath(vararg parts: String) = parts.joinToString("@")

    fun sha1Topic(str: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(str.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in digest) sb.append((b.toInt() and 0xff).toString(36))
        return sb.toString()
    }

    fun genKey(secret: String, appId: String, roomId: String): SecretKeySpec {
        val raw = MessageDigest.getInstance("SHA-256")
            .digest("$secret:$appId:$roomId".toByteArray(Charsets.UTF_8))
        return SecretKeySpec(raw, "AES")
    }

    fun encrypt(key: SecretKeySpec, plaintext: String): String {
        val iv = ByteArray(16).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ivText = iv.joinToString(",") { (it.toInt() and 0xff).toString() }
        return ivText + "$" + Base64.getEncoder().encodeToString(ct)
    }

    fun decrypt(key: SecretKeySpec, raw: String): String {
        val sep = raw.indexOf('$')
        require(sep > 0) { "bad ciphertext" }
        val iv = raw.substring(0, sep).split(",").map { it.toInt().toByte() }.toByteArray()
        val ct = Base64.getDecoder().decode(raw.substring(sep + 1))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private const val ID_CHARS = "0123456789AaBbCcDdEeFfGgHhIiJjKkLlMmNnOoPpQqRrSsTtUuVvWwXxYyZz"

    /** Trystero の genId。selfId は20文字、REQの subId は64文字。 */
    fun genId(n: Int): String {
        val sb = StringBuilder(n)
        repeat(n) { sb.append(ID_CHARS[random.nextInt(ID_CHARS.length)]) }
        return sb.toString()
    }
}
