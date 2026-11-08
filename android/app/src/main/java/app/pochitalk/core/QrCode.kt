package app.pochitalk.core

/**
 * QRコードの作成 (バイトモード・誤り訂正レベル M・バージョン 1〜10 = 最大 213 バイト)。招待URL用。
 * 外部ライブラリを使わず、QR の規格 (ISO/IEC 18004) の手順どおりに作る。
 * 結果は [modules] (true = 黒) の正方形。[size] は一辺のモジュール数。
 */
class QrCode private constructor(val version: Int, private val mask: Int, codewords: ByteArray) {
    val size = version * 4 + 17
    private val m = Array(size) { BooleanArray(size) }
    private val fn = Array(size) { BooleanArray(size) } // 機能パターン (データを置かない場所)

    init {
        drawFunctionPatterns()
        drawCodewords(codewords)
        applyMask(mask)
        drawFormatBits(mask)
    }

    fun get(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && m[y][x]

    private fun set(x: Int, y: Int, dark: Boolean) {
        m[y][x] = dark
        fn[y][x] = true
    }

    private fun drawFunctionPatterns() {
        for (i in 0 until size) {
            set(6, i, i % 2 == 0)
            set(i, 6, i % 2 == 0)
        }
        drawFinder(3, 3)
        drawFinder(size - 4, 3)
        drawFinder(3, size - 4)
        val pos = alignmentPositions(version)
        for (i in pos.indices) for (j in pos.indices) {
            if ((i == 0 && j == 0) || (i == 0 && j == pos.size - 1) || (i == pos.size - 1 && j == 0)) continue
            drawAlignment(pos[i], pos[j])
        }
        drawFormatBits(0) // 場所の確保 (あとで本番の値を上書き)
        drawVersion()
    }

    private fun drawFinder(cx: Int, cy: Int) {
        for (dy in -4..4) for (dx in -4..4) {
            val dist = maxOf(Math.abs(dx), Math.abs(dy))
            val x = cx + dx
            val y = cy + dy
            if (x in 0 until size && y in 0 until size) set(x, y, dist != 2 && dist != 4)
        }
    }

    private fun drawAlignment(cx: Int, cy: Int) {
        for (dy in -2..2) for (dx in -2..2) set(cx + dx, cy + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
    }

    private fun drawFormatBits(mask: Int) {
        val data = (0 shl 3) or mask // 誤り訂正レベル M のビットは 00
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        val bits = ((data shl 10) or rem) xor 0x5412
        fun bit(i: Int) = (bits ushr i) and 1 != 0
        for (i in 0..5) set(8, i, bit(i))
        set(8, 7, bit(6))
        set(8, 8, bit(7))
        set(7, 8, bit(8))
        for (i in 9..14) set(14 - i, 8, bit(i))
        for (i in 0..7) set(size - 1 - i, 8, bit(i))
        for (i in 8..14) set(8, size - 15 + i, bit(i))
        set(8, size - 8, true) // 常に黒のモジュール
    }

    private fun drawVersion() {
        if (version < 7) return
        var rem = version
        repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
        val bits = (version shl 12) or rem
        for (i in 0..17) {
            val b = (bits ushr i) and 1 != 0
            val a = size - 11 + i % 3
            val c = i / 3
            set(a, c, b)
            set(c, a, b)
        }
    }

    private fun drawCodewords(data: ByteArray) {
        var i = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) {
                for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!fn[y][x] && i < data.size * 8) {
                        m[y][x] = ((data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
                        i++
                    }
                }
            }
            right -= 2
        }
    }

    private fun applyMask(mask: Int) {
        for (y in 0 until size) for (x in 0 until size) {
            val invert = when (mask) {
                0 -> (x + y) % 2 == 0
                1 -> y % 2 == 0
                2 -> x % 3 == 0
                3 -> (x + y) % 3 == 0
                4 -> (x / 3 + y / 2) % 2 == 0
                5 -> x * y % 2 + x * y % 3 == 0
                6 -> (x * y % 2 + x * y % 3) % 2 == 0
                else -> ((x + y) % 2 + x * y % 3) % 2 == 0
            }
            if (!fn[y][x] && invert) m[y][x] = !m[y][x]
        }
    }

    /** マスクの良し悪しの点数 (規格の4つの規則)。低いほど読み取りやすい。 */
    fun penalty(): Int {
        var result = 0
        for (horizontal in listOf(true, false)) {
            for (a in 0 until size) {
                var run = 1
                for (b in 1 until size) {
                    val cur = if (horizontal) m[a][b] else m[b][a]
                    val prev = if (horizontal) m[a][b - 1] else m[b - 1][a]
                    if (cur == prev) {
                        run++
                        if (run == 5) result += 3 else if (run > 5) result++
                    } else run = 1
                }
                val line = BooleanArray(size) { if (horizontal) m[a][it] else m[it][a] }
                for (s in 0..size - 11) {
                    fun pat(p: String) = p.indices.all { line[s + it] == (p[it] == '1') }
                    if (pat("10111010000") || pat("00001011101")) result += 40
                }
            }
        }
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val c = m[y][x]
            if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) result += 3
        }
        var dark = 0
        for (row in m) for (c in row) if (c) dark++
        val total = size * size
        val k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1
        result += k * 10
        return result
    }

    companion object {
        private val ECC_PER_BLOCK_M = intArrayOf(0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
        private val NUM_BLOCKS_M = intArrayOf(0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)

        fun alignmentPositions(version: Int): IntArray {
            if (version == 1) return IntArray(0)
            val num = version / 7 + 2
            val size = version * 4 + 17
            val step = (version * 4 + num * 2 + 1) / (num * 2 - 2) * 2
            val out = IntArray(num)
            out[0] = 6
            var p = size - 7
            for (i in num - 1 downTo 1) {
                out[i] = p
                p -= step
            }
            return out
        }

        private fun rawModules(ver: Int): Int {
            var r = (16 * ver + 128) * ver + 64
            if (ver >= 2) {
                val n = ver / 7 + 2
                r -= (25 * n - 10) * n - 55
                if (ver >= 7) r -= 36
            }
            return r
        }

        private fun dataCodewords(ver: Int) = rawModules(ver) / 8 - ECC_PER_BLOCK_M[ver] * NUM_BLOCKS_M[ver]

        /** 文字列 (UTF-8) から作る。長すぎる (213 バイト超) ときは IllegalArgumentException。 */
        fun encode(text: String): QrCode {
            val data = text.toByteArray(Charsets.UTF_8)
            var ver = 1
            while (ver <= 10) {
                val countBits = if (ver < 10) 8 else 16
                if (4 + countBits + data.size * 8 <= dataCodewords(ver) * 8) break
                ver++
            }
            require(ver <= 10) { "text too long for QR (max 213 bytes)" }
            val capacity = dataCodewords(ver) * 8
            val bits = ArrayList<Int>()
            fun put(v: Int, n: Int) {
                for (i in n - 1 downTo 0) bits.add((v ushr i) and 1)
            }
            put(0b0100, 4)
            put(data.size, if (ver < 10) 8 else 16)
            for (b in data) put(b.toInt() and 0xff, 8)
            put(0, minOf(4, capacity - bits.size))
            while (bits.size % 8 != 0) bits.add(0)
            var pad = 0xEC
            while (bits.size < capacity) {
                put(pad, 8)
                pad = pad xor 0xEC xor 0x11
            }
            val bytes = ByteArray(bits.size / 8)
            for (i in bits.indices) bytes[i ushr 3] = (bytes[i ushr 3].toInt() or (bits[i] shl (7 - (i and 7)))).toByte()

            val all = addEccAndInterleave(bytes, ver)
            var best: QrCode? = null
            var bestScore = Int.MAX_VALUE
            for (mask in 0..7) {
                val q = QrCode(ver, mask, all)
                val s = q.penalty()
                if (s < bestScore) {
                    bestScore = s
                    best = q
                }
            }
            return best!!
        }

        private fun addEccAndInterleave(data: ByteArray, ver: Int): ByteArray {
            val numBlocks = NUM_BLOCKS_M[ver]
            val eccLen = ECC_PER_BLOCK_M[ver]
            val raw = rawModules(ver) / 8
            val numShort = numBlocks - raw % numBlocks
            val shortLen = raw / numBlocks
            val blocks = ArrayList<ByteArray>()
            val divisor = rsDivisor(eccLen)
            var k = 0
            for (i in 0 until numBlocks) {
                val len = shortLen - eccLen + if (i < numShort) 0 else 1
                val dat = data.copyOfRange(k, k + len)
                k += len
                val ecc = rsRemainder(dat, divisor)
                val block = ByteArray(shortLen + 1)
                System.arraycopy(dat, 0, block, 0, dat.size)
                System.arraycopy(ecc, 0, block, block.size - eccLen, eccLen)
                blocks.add(block)
            }
            val out = ByteArray(raw)
            var o = 0
            for (i in 0..shortLen) for (j in blocks.indices) {
                if (i != shortLen - eccLen || j >= numShort) out[o++] = blocks[j][i]
            }
            return out
        }

        private fun rsMul(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        private fun rsDivisor(degree: Int): IntArray {
            val result = IntArray(degree)
            result[degree - 1] = 1
            var root = 1
            for (i in 0 until degree) {
                for (j in 0 until degree) {
                    result[j] = rsMul(result[j], root)
                    if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
                }
                root = rsMul(root, 0x02)
            }
            return result
        }

        private fun rsRemainder(data: ByteArray, divisor: IntArray): ByteArray {
            val result = IntArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() and 0xff) xor result[0]
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = result[i] xor rsMul(divisor[i], factor)
            }
            return ByteArray(result.size) { result[it].toByte() }
        }
    }
}
