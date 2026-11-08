import Foundation
import os

/// 受信した音 (再生側に流れる、相手の声) を 15ms ごとのブロックに区切り、モールスの 700Hz と FAX のトーン
/// (1500/900/1300/2100Hz) の強さを測る。音声スレッドから `feed` を呼ぶ。メモリの確保はしない (ゴルツェル法で、サンプルごとに足すだけ)。
/// 結果はリングに積み、エンジン側が `drain` で取り出して復号する (Web 版の decodeMorseRx / decodeFaxRx に相当)。
final class ToneMeter {
    static let blockMs = 15.0

    struct Block {
        /// ブロックの先頭時刻 (ms)。サンプル数から数えるので、スレッドのゆらぎに影響されない。
        var t: Double = 0
        /// ブロック全体の実効値 (0〜1)。
        var rms: Float = 0
        /// 700Hz 帯 (640〜760Hz) に集まっているエネルギーの割合。肉声は帯域全体に散るので小さく、純音は大きい。
        var pure: Float = 0
        /// FAX のトーン: 0 なし / 1 ピー(1500) / 2 同期(900) / 3 白(1300) / 4 黒(2100)
        var fax: UInt8 = 0
    }

    // 測る周波数: 0..2 = モールス帯、3 = pre、4 = sync、5 = white、6 = black
    private static let freqs: [Double] = [640, 700, 760, 1500, 900, 1300, 2100]
    private static let bins = 7

    private var rate: Double = 0
    private var blockLen = 0
    private var count = 0
    private var total = 0
    private var sumsq: Float = 0
    private let coeff = UnsafeMutablePointer<Float>.allocate(capacity: bins)
    private let s1 = UnsafeMutablePointer<Float>.allocate(capacity: bins)
    private let s2 = UnsafeMutablePointer<Float>.allocate(capacity: bins)

    private var lock = os_unfair_lock_s()
    private let ringSize = 512
    private var ring: UnsafeMutablePointer<Block>
    private var head = 0, tail = 0

    init() {
        ring = .allocate(capacity: ringSize)
        ring.initialize(repeating: Block(), count: ringSize)
        coeff.initialize(repeating: 0, count: Self.bins)
        s1.initialize(repeating: 0, count: Self.bins)
        s2.initialize(repeating: 0, count: Self.bins)
    }

    deinit { coeff.deallocate(); s1.deallocate(); s2.deallocate(); ring.deallocate() }

    private func setRate(_ r: Double) {
        rate = r
        blockLen = max(1, Int(r * Self.blockMs / 1000))
        for k in 0..<Self.bins { coeff[k] = Float(2 * cos(2 * Double.pi * Self.freqs[k] / r)) }
        resetBlock()
    }

    private func resetBlock() {
        count = 0; sumsq = 0
        for k in 0..<Self.bins { s1[k] = 0; s2[k] = 0 }
    }

    /// 音声スレッドから呼ぶ。`p` は 16bit のモノラル。
    func feed(_ p: UnsafePointer<Int16>, _ n: Int, rate r: Double) {
        if r != rate { setRate(r) }
        for i in 0..<n {
            let x = Float(p[i]) * (1.0 / 32768.0)
            sumsq += x * x
            for k in 0..<Self.bins {
                let s = x + coeff[k] * s1[k] - s2[k]
                s2[k] = s1[k]; s1[k] = s
            }
            count += 1
            if count >= blockLen { finishBlock() }
        }
    }

    private func finishBlock() {
        let n = Float(count)
        var b = Block()
        b.t = Double(total) / rate * 1000
        total += count
        b.rms = (sumsq / n).squareRoot()
        if sumsq > 1e-9 {
            func f(_ k: Int) -> Float { 2 * (s1[k] * s1[k] + s2[k] * s2[k] - coeff[k] * s1[k] * s2[k]) / (n * sumsq) }
            b.pure = min(1, max(0, f(0) + f(1) + f(2)))
            let ePre = max(0, f(3)), eSync = max(0, f(4)), eW = max(0, f(5)), eB = max(0, f(6))
            let sum = ePre + eSync + eW + eB
            if sum >= 0.2 && b.rms >= 0.002 {
                let m = max(max(ePre, eSync), max(eW, eB))
                if m / sum >= 0.5 { b.fax = m == ePre ? 1 : m == eSync ? 2 : m == eW ? 3 : 4 }
            }
        }
        resetBlock()
        os_unfair_lock_lock(&lock)
        ring[head] = b
        head = (head + 1) % ringSize
        if head == tail { tail = (tail + 1) % ringSize } // 取りこぼしたら古いほうを捨てる
        os_unfair_lock_unlock(&lock)
    }

    /// たまったブロックを、古い順に取り出す。
    func drain() -> [Block] {
        os_unfair_lock_lock(&lock)
        var out: [Block] = []
        while tail != head { out.append(ring[tail]); tail = (tail + 1) % ringSize }
        os_unfair_lock_unlock(&lock)
        return out
    }
}
