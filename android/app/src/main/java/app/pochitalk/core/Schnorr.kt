package app.pochitalk.core

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * BIP340 (Nostr) Schnorr 署名。外部ライブラリ無しの純Kotlin実装。
 * Trystero の nostr 戦略は @noble/curves の schnorr.sign / getPublicKey を使っており、
 * リレーはイベントの id と sig を検証するので、ここが間違うと一切つながらない。
 */
object Schnorr {
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val GX = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val GY = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val TWO = BigInteger.valueOf(2)
    private val THREE = BigInteger.valueOf(3)
    private val random = SecureRandom()

    /** ヤコビアン座標の点。z == 0 は無限遠点。 */
    private class Jac(val x: BigInteger, val y: BigInteger, val z: BigInteger) {
        val isInfinity get() = z.signum() == 0
    }

    private val INF = Jac(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
    private val G = Jac(GX, GY, BigInteger.ONE)

    private fun mod(a: BigInteger) = a.mod(P)

    private fun double(p: Jac): Jac {
        if (p.isInfinity || p.y.signum() == 0) return INF
        val ySq = mod(p.y.multiply(p.y))
        val s = mod(p.x.multiply(ySq).shiftLeft(2))
        val m = mod(THREE.multiply(p.x).multiply(p.x))
        val x3 = mod(m.multiply(m).subtract(TWO.multiply(s)))
        val y3 = mod(m.multiply(s.subtract(x3)).subtract(ySq.multiply(ySq).shiftLeft(3)))
        val z3 = mod(TWO.multiply(p.y).multiply(p.z))
        return Jac(x3, y3, z3)
    }

    private fun add(a: Jac, b: Jac): Jac {
        if (a.isInfinity) return b
        if (b.isInfinity) return a
        val z1Sq = mod(a.z.multiply(a.z))
        val z2Sq = mod(b.z.multiply(b.z))
        val u1 = mod(a.x.multiply(z2Sq))
        val u2 = mod(b.x.multiply(z1Sq))
        val s1 = mod(a.y.multiply(z2Sq).multiply(b.z))
        val s2 = mod(b.y.multiply(z1Sq).multiply(a.z))
        if (u1 == u2) {
            return if (s1 == s2) double(a) else INF
        }
        val h = mod(u2.subtract(u1))
        val r = mod(s2.subtract(s1))
        val h2 = mod(h.multiply(h))
        val h3 = mod(h2.multiply(h))
        val u1h2 = mod(u1.multiply(h2))
        val x3 = mod(r.multiply(r).subtract(h3).subtract(TWO.multiply(u1h2)))
        val y3 = mod(r.multiply(u1h2.subtract(x3)).subtract(s1.multiply(h3)))
        val z3 = mod(h.multiply(a.z).multiply(b.z))
        return Jac(x3, y3, z3)
    }

    private fun mul(p: Jac, k: BigInteger): Jac {
        var result = INF
        var addend = p
        var n = k
        while (n.signum() > 0) {
            if (n.testBit(0)) result = add(result, addend)
            addend = double(addend)
            n = n.shiftRight(1)
        }
        return result
    }

    /** 戻り値は (x, y) のアフィン座標。無限遠点は null。 */
    private fun toAffine(p: Jac): Pair<BigInteger, BigInteger>? {
        if (p.isInfinity) return null
        val zi = p.z.modInverse(P)
        val zi2 = mod(zi.multiply(zi))
        return Pair(mod(p.x.multiply(zi2)), mod(p.y.multiply(zi2).multiply(zi)))
    }

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    private fun taggedHash(tag: String, vararg parts: ByteArray): ByteArray {
        val t = sha256(tag.toByteArray(Charsets.UTF_8))
        return sha256(t, t, *parts)
    }

    private fun bytesOf(n: BigInteger): ByteArray {
        val raw = n.toByteArray()
        val out = ByteArray(32)
        // BigInteger.toByteArray は符号バイトを含む場合があるので末尾32バイトを使う
        val len = minOf(raw.size, 32)
        System.arraycopy(raw, raw.size - len, out, 32 - len, len)
        return out
    }

    private fun intOf(b: ByteArray) = BigInteger(1, b)

    private fun hasEvenY(p: Pair<BigInteger, BigInteger>) = !p.second.testBit(0)

    fun randomPrivateKey(): ByteArray {
        while (true) {
            val b = ByteArray(32)
            random.nextBytes(b)
            val d = intOf(b)
            if (d.signum() > 0 && d < N) return b
        }
    }

    /** x-only 公開鍵 (32バイト) */
    fun publicKey(privateKey: ByteArray): ByteArray {
        val d = intOf(privateKey)
        require(d.signum() > 0 && d < N) { "invalid private key" }
        val pt = toAffine(mul(G, d))!!
        return bytesOf(pt.first)
    }

    /** BIP340 署名 (64バイト)。message は32バイトのハッシュ (Nostrではイベントid)。 */
    fun sign(message: ByteArray, privateKey: ByteArray, aux: ByteArray = ByteArray(32).also { random.nextBytes(it) }): ByteArray {
        require(message.size == 32) { "message must be 32 bytes" }
        val d0 = intOf(privateKey)
        require(d0.signum() > 0 && d0 < N) { "invalid private key" }
        val pt = toAffine(mul(G, d0))!!
        val d = if (hasEvenY(pt)) d0 else N.subtract(d0)
        val px = bytesOf(pt.first)
        val t = bytesOf(d.xor(intOf(taggedHash("BIP0340/aux", aux))))
        val rand = taggedHash("BIP0340/nonce", t, px, message)
        val k0 = intOf(rand).mod(N)
        require(k0.signum() != 0) { "bad nonce" }
        val rPt = toAffine(mul(G, k0))!!
        val k = if (hasEvenY(rPt)) k0 else N.subtract(k0)
        val rx = bytesOf(rPt.first)
        val e = intOf(taggedHash("BIP0340/challenge", rx, px, message)).mod(N)
        val s = k.add(e.multiply(d)).mod(N)
        return rx + bytesOf(s)
    }

    /** 自己テスト用の検証 */
    fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean {
        if (signature.size != 64 || message.size != 32 || publicKey.size != 32) return false
        val px = intOf(publicKey)
        if (px >= P) return false
        // lift_x
        val c = mod(px.pow(3).add(BigInteger.valueOf(7)))
        val y0 = c.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        if (mod(y0.multiply(y0)) != c) return false
        val y = if (y0.testBit(0)) P.subtract(y0) else y0
        val pPoint = Jac(px, y, BigInteger.ONE)
        val r = intOf(signature.copyOfRange(0, 32))
        val s = intOf(signature.copyOfRange(32, 64))
        if (r >= P || s >= N) return false
        val e = intOf(taggedHash("BIP0340/challenge", bytesOf(r), publicKey, message)).mod(N)
        val negE = N.subtract(e).mod(N)
        val rPt = toAffine(add(mul(G, s), mul(pPoint, negE))) ?: return false
        return hasEvenY(rPt) && rPt.first == r
    }
}

fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 15])
    }
    return sb.toString()
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { i -> ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte() }
}
