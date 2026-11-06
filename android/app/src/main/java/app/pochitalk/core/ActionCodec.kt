package app.pochitalk.core

import org.json.JSONObject

/**
 * Trystero 0.20.0 room.js のデータチャネル上のフレーミング。
 *
 *  byte 0..11 : アクション名 (UTF-8, 0 埋めで12バイト固定)
 *  byte 12    : nonce (送信ごとに +1, 0..255 で循環)
 *  byte 13    : tag   bit0=最後のチャンク bit1=メタ bit2=バイナリ bit3=JSON
 *  byte 14    : progress (0..255)
 *  byte 15..  : ペイロード (1チャンクの最大長は 16KiB - 15)
 */
object ActionCodec {
    const val TYPE_BYTE_LIMIT = 12
    private const val NONCE_INDEX = TYPE_BYTE_LIMIT
    private const val TAG_INDEX = NONCE_INDEX + 1
    private const val PROGRESS_INDEX = TAG_INDEX + 1
    const val PAYLOAD_INDEX = PROGRESS_INDEX + 1
    const val CHUNK_SIZE = 16 * 1024 - PAYLOAD_INDEX

    private const val TAG_LAST = 1
    private const val TAG_META = 2
    private const val TAG_BINARY = 4
    private const val TAG_JSON = 8

    /** 文字列/JSON を送るチャンク列を作る (メタ付きバイナリは本アプリでは使わない)。 */
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
            chunk[NONCE_INDEX] = nonce.toByte()
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

    /** ピアごとに1つ持ち、受信チャンクを組み立てる (room.js の pendingTransmissions 相当)。 */
    class Reassembler {
        private class Pending {
            val chunks = ArrayList<ByteArray>()
        }

        private val pending = HashMap<String, Pending>()

        /** 完成したら Message を返し、まだなら null。メタ付き送信のメタ部分は読み捨てる。 */
        fun feed(data: ByteArray): Message? {
            if (data.size < PAYLOAD_INDEX) return null
            var typeEnd = 0
            while (typeEnd < TYPE_BYTE_LIMIT && data[typeEnd].toInt() != 0) typeEnd++
            val type = String(data, 0, typeEnd, Charsets.UTF_8)
            val nonce = data[NONCE_INDEX].toInt() and 0xff
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
}
