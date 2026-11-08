package app.pochitalk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QrCodeTest {
    private val url = "https://pochittalk.ryota-softwares.workers.dev/?r=7a0b07c1"

    @Test
    fun sizeAndFinderPatterns() {
        val q = QrCode.encode(url)
        assertEquals(q.version * 4 + 17, q.size)
        assertEquals(4, q.version) // 57 バイトは、バージョン4 (最大62バイト) に収まる
        // 3つの角に、7x7 の位置検出パターン
        for ((ox, oy) in listOf(0 to 0, q.size - 7 to 0, 0 to q.size - 7)) {
            for (i in 0..6) {
                assertTrue(q.get(ox + i, oy)); assertTrue(q.get(ox + i, oy + 6))
                assertTrue(q.get(ox, oy + i)); assertTrue(q.get(ox + 6, oy + i))
            }
            assertTrue(q.get(ox + 3, oy + 3))
            assertTrue(!q.get(ox + 1, oy + 1))
        }
    }

    @Test
    fun longerTextsUseBiggerVersions() {
        assertTrue(QrCode.encode("x".repeat(100)).version > 4)
        assertTrue(QrCode.encode("日本語のテキスト".repeat(8)).version in 5..10)
    }

    /** 検証用: 環境変数 QR_DUMP が指定されているときだけ、読み取り確認用の行列をファイルに書き出す。 */
    @Test
    fun dumpForDecoderCheck() {
        val path = System.getenv("QR_DUMP") ?: return
        val texts = listOf(url, "A", "x".repeat(100), "y".repeat(130), "z".repeat(150), "w".repeat(180), "q".repeat(20), "q".repeat(40), "q".repeat(60), "q".repeat(80), "q".repeat(120), "q".repeat(160), "q".repeat(200), "日本語のテキスト".repeat(8), "https://example.com/" + "a".repeat(170))
        File(path).printWriter().use { w ->
            for (t in texts) {
                val q = QrCode.encode(t)
                w.println(t)
                for (y in 0 until q.size) w.println((0 until q.size).joinToString("") { if (q.get(it, y)) "1" else "0" })
                w.println("--")
            }
        }
    }
}
