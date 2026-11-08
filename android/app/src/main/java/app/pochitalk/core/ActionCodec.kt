package app.pochitalk.core

import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Trystero 0.25.4 (action-wire.ts) のデータチャネル上のフレーミング。
 *
 *  byte 0..31 : アクション名 (UTF-8, 0 埋めで32バイト固定)
 *  byte 32..33: nonce (16ビット, 送信ごとに +1, ビッグエンディアン)
 *  byte 34    : tag   bit0=最後のチャンク bit1=メタ bit2=バイナリ bit3=JSON
 *  byte 35    : progress (0..255)
 *  byte 36..  : ペイロード (1チャンクの最大長は 16KiB - 36)
 *
 * さらに共有ピア (shared-peer.ts) が、部屋ごとのトークンで包む:
 *  部屋フレーム   : [1][トークン長 2バイト][トークン UTF-8][上のアクションのフレーム]
 *  在室フレーム   : [2][在室=1/退室=0][トークン長 2バイト][トークン UTF-8]
 */
object ActionCodec {
    const val TYPE_BYTE_LIMIT = 32
    private const val NONCE_INDEX = 32
    private const val TAG_INDEX = 34
    private const val PROGRESS_INDEX = 35
    const val PAYLOAD_INDEX = 36
    const val CHUNK_SIZE = 16 * 1024 - PAYLOAD_INDEX

    private const val TAG_LAST = 1
    private const val TAG_META = 2
    private const val TAG_BINARY = 4
    private const val TAG_JSON = 8

    /** 文字列/JSON を送るチャンク列を作る (メタ付き送信は本アプリでは使わない)。nonce は 0..65535。 */
    fun encode(type: String, data: ByteArray, nonce: Int, isBinary: Boolean, isJson: Boolean): List<ByteArray> {
        val typeBytes = type.toByteArray(Charsets.UTF_8)
        require(typeBytes.size <= TYPE_BYTE_LIMIT) { "action type too long: $type" }
        val total = maxOf(1, (data.size + CHUNK_SIZE - 1) / CHUNK_SIZE)
        return List(total) { i ->
            val isLast = i == total - 1
            val from = i * CHUNK_SIZE
            val to = minOf(data.size, from + CHUNK_SIZE)
            val chunk = ByteArray(PAYLOAD_INDEX + (to - from))
            System.arraycopy(typeBytes, 0, chunk, 0, typeBytes.size)
            chunk[NONCE_INDEX] = (nonce shr 8).toByte()
            chunk[NONCE_INDEX + 1] = (nonce and 0xff).toByte()
            var tag = 0
            if (isLast) tag = tag or TAG_LAST
            if (isBinary) tag = tag or TAG_BINARY
            if (isJson) tag = tag or TAG_JSON
            chunk[TAG_INDEX] = tag.toByte()
            chunk[PROGRESS_INDEX] = Math.round((i + 1).toDouble() / total * 255).toInt().toByte()
            System.arraycopy(data, from, chunk, PAYLOAD_INDEX, to - from)
            chunk
        }
    }

    fun encodeString(type: String, text: String, nonce: Int) =
        encode(type, text.toByteArray(Charsets.UTF_8), nonce, isBinary = false, isJson = false)

    fun encodeJson(type: String, json: JSONObject, nonce: Int) =
        encode(type, json.toString().toByteArray(Charsets.UTF_8), nonce, isBinary = false, isJson = true)

    /** フレームの先頭32バイトからアクション名を読む (組み立てる前に、未登録の名前を捨てるため)。 */
    fun peekType(data: ByteArray): String? {
        if (data.size < PAYLOAD_INDEX) return null
        val sb = StringBuilder()
        // JS は 0 バイトを全部取り除く。名前は ASCII の想定なので、0 以外をそのまま並べる
        val raw = data.copyOfRange(0, TYPE_BYTE_LIMIT).filter { it.toInt() != 0 }.toByteArray()
        sb.append(String(raw, StandardCharsets.UTF_8))
        return sb.toString()
    }

    /** 受信完了したメッセージ。 */
    class Message(
        val type: String,
        val isBinary: Boolean,
        val isJson: Boolean,
        val payload: ByteArray,
    ) {
        val text: String get() = String(payload, Charsets.UTF_8)
        fun json(): JSONObject = JSONObject(text)
    }

    /** ピアごとに1つ持ち、受信チャンクを組み立てる (action-wire.ts の pendingTransmissions 相当)。 */
    class Reassembler {
        private class Pending {
            val chunks = ArrayList<ByteArray>()
        }

        private val pending = HashMap<String, Pending>()

        /** 完成したら Message を返し、まだなら null。メタ付き送信のメタ部分は読み捨てる。 */
        fun feed(data: ByteArray): Message? {
            val type = peekType(data) ?: return null
            val nonce = ((data[NONCE_INDEX].toInt() and 0xff) shl 8) or (data[NONCE_INDEX + 1].toInt() and 0xff)
            val tag = data[TAG_INDEX].toInt() and 0xff
            val isLast = tag and TAG_LAST != 0
            val isMeta = tag and TAG_META != 0
            val isBinary = tag and TAG_BINARY != 0
            val isJson = tag and TAG_JSON != 0
            val payload = data.copyOfRange(PAYLOAD_INDEX, data.size)
            val key = "$type/$nonce"
            val p = pending.getOrPut(key) { Pending() }
            if (!isMeta) p.chunks.add(payload)
            if (!isLast) return null
            pending.remove(key)
            val full = ByteArray(p.chunks.sumOf { it.size })
            var off = 0
            for (c in p.chunks) {
                System.arraycopy(c, 0, full, off, c.size)
                off += c.size
            }
            return Message(type, isBinary, isJson, full)
        }
    }

    // ---------------------------------------------------------------- 共有ピアの部屋フレーム

    private const val ROOM_FRAME_VERSION = 1
    private const val PRESENCE_FRAME_VERSION = 2

    fun wrapRoomFrame(roomToken: String, data: ByteArray): ByteArray {
        val token = roomToken.toByteArray(Charsets.UTF_8)
        val frame = ByteArray(3 + token.size + data.size)
        frame[0] = ROOM_FRAME_VERSION.toByte()
        frame[1] = (token.size shr 8).toByte()
        frame[2] = (token.size and 0xff).toByte()
        System.arraycopy(token, 0, frame, 3, token.size)
        System.arraycopy(data, 0, frame, 3 + token.size, data.size)
        return frame
    }

    fun wrapPresenceFrame(roomToken: String, isPresent: Boolean): ByteArray {
        val token = roomToken.toByteArray(Charsets.UTF_8)
        val frame = ByteArray(4 + token.size)
        frame[0] = PRESENCE_FRAME_VERSION.toByte()
        frame[1] = if (isPresent) 1 else 0
        frame[2] = (token.size shr 8).toByte()
        frame[3] = (token.size and 0xff).toByte()
        System.arraycopy(token, 0, frame, 4, token.size)
        return frame
    }

    sealed class Frame {
        class Room(val roomToken: String, val payload: ByteArray) : Frame()
        class Presence(val roomToken: String, val isPresent: Boolean) : Frame()
    }

    fun unwrapFrame(data: ByteArray): Frame? {
        if (data.size < 3) return null
        val v = data[0].toInt() and 0xff
        if (v == ROOM_FRAME_VERSION) {
            val size = ((data[1].toInt() and 0xff) shl 8) or (data[2].toInt() and 0xff)
            val header = 3 + size
            if (size <= 0 || data.size < header) return null
            return Frame.Room(String(data, 3, size, Charsets.UTF_8), data.copyOfRange(header, data.size))
        }
        if (v != PRESENCE_FRAME_VERSION || data.size < 4) return null
        val size = ((data[2].toInt() and 0xff) shl 8) or (data[3].toInt() and 0xff)
        val header = 4 + size
        if (size <= 0 || data.size < header) return null
        return Frame.Presence(String(data, 4, size, Charsets.UTF_8), (data[1].toInt() and 0xff) == 1)
    }
}
