package app.pochitalk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class RxDecodeTest {
    private val sr = 48000

    /** Web版の送信と同じ時間配分でモールスの音を作る (打ち始めの前 700ms、符号の間 100ms、文字の間 +200ms、単語の間 600ms)。 */
    private fun morseAudio(msg: String, amp: Double = 0.35): FloatArray {
        val out = ArrayList<Float>()
        var phase = 0.0
        fun tone(ms: Int, on: Boolean) {
            val n = sr * ms / 1000
            for (i in 0 until n) {
                // 立ち上がり 4ms のなだらかな切り替え
                val env = if (!on) 0.0 else minOf(1.0, minOf(i, n - i) / (sr * 0.004))
                out.add((amp * env * sin(phase)).toFloat())
                phase += 2 * PI * 700 / sr
            }
        }
        tone(700, false)
        for (ch in msg) {
            if (ch == ' ') { tone(600, false); continue }
            val pat = Morse.CODE.getValue(ch)
            for ((i, c) in pat.withIndex()) {
                tone(if (c == '.') 100 else 300, true)
                tone(100, false)
                if (i == pat.length - 1) tone(200, false)
            }
        }
        tone(1500, false)
        return out.toFloatArray()
    }

    private fun decode(audio: FloatArray): String {
        var last = ""
        val dec = MorseDecoder({ last = it }, {})
        val an = ToneAnalyzer(sr) { dec.onFrame(it) }
        var i = 0
        while (i < audio.size) {
            val n = minOf(480, audio.size - i)
            an.feed(audio.copyOfRange(i, i + n), n)
            i += n
        }
        return last
    }

    @Test fun decodesSimpleWords() {
        assertEquals("SOS", decode(morseAudio("SOS")).trim())
        assertEquals("HELLO 123", decode(morseAudio("HELLO 123")).trim())
    }

    @Test fun decodesQuietTone() {
        assertEquals("CQ", decode(morseAudio("CQ", amp = 0.02)).trim())
    }

    @Test fun ignoresSpeechLikeNoise() {
        val rnd = java.util.Random(1)
        val noise = FloatArray(sr * 3) { (rnd.nextGaussian() * 0.1).toFloat() }
        assertEquals("", decode(noise))
    }

    @Test fun escIsReported() {
        var esc = false
        val dec = MorseDecoder({}, { esc = true })
        val an = ToneAnalyzer(sr) { dec.onFrame(it) }
        val a = morseAudio("ESC")
        an.feed(a, a.size)
        assertTrue(esc)
    }

    @Test fun detectsFaxPreTone() {
        val det = FaxPreDetector()
        var found = false
        val an = ToneAnalyzer(sr) { if (det.onFrame(it)) found = true }
        val a = FloatArray(sr) { (0.2 * sin(2 * PI * 1500 * it / sr)).toFloat() }
        an.feed(a, a.size)
        assertTrue(found)
        // 700Hz のモールス音は FAX の合図ではない
        val det2 = FaxPreDetector()
        var found2 = false
        val an2 = ToneAnalyzer(sr) { if (det2.onFrame(it)) found2 = true }
        val b = FloatArray(sr) { (0.2 * sin(2 * PI * 700 * it / sr)).toFloat() }
        an2.feed(b, b.size)
        assertTrue(!found2)
    }

    @Test fun faxPayloadRoundTrip() {
        // Web版の faxGridToPayload と同じ並び: 1行 = ceil(cols/8) バイト、左の画素が上位ビット
        val cols = 10
        val rows = Fax.TEXT_ROWS
        val bytes = ByteArray(2 * rows)
        bytes[0] = 0b10100000.toByte() // 0行目: x=0, x=2 が黒
        bytes[1] = 0b01000000.toByte() //          x=9 が黒
        val d = java.util.Base64.getEncoder().encodeToString(bytes)
        val grid = Fax.payloadToGrid(cols, d)
        assertNotNull(grid)
        assertEquals(listOf(0, 2, 9), grid!![0].indices.filter { grid[0][it] })
        assertTrue(grid[1].none { it })
        assertEquals(null, Fax.payloadToGrid(cols, "!!!"))
        assertEquals(null, Fax.payloadToGrid(cols, java.util.Base64.getEncoder().encodeToString(ByteArray(3))))
        val sched = Fax.revealSchedule(grid)
        assertEquals(15, sched.size)
        assertEquals(0L to 0L, sched[0].let { it.first.toLong() to it.second })
        assertEquals(230L, sched[1].second)
        assertEquals(230L + 150 + 10 * 20, sched[2].second)
    }
}
