package app.pochitalk.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

private const val SAMPLE_RATE = 48000

/**
 * Web版 (Web Audio API) の効果音をPCMで合成して鳴らす。
 *  - start    : 送信開始の「ピッ」
 *  - end      : 発話終了のザラついた「ガッ」(歪ませた粗いノイズを22Hzで途切れさせる)
 *  - busy     : 話中ブザー
 *  - crosstalk: 混線ノイズ
 *  - tune     : 部屋を切り替えたときの「ザーッ」(カットオフを掃引)
 * 音色は Web 版に近づけているだけで、耳で聞いての微調整は実機で行う前提。
 */
class RadioSounds {
    private val handler = Handler(Looper.getMainLooper())
    private val cache = HashMap<String, ShortArray>()

    @Volatile var enabled: Boolean = true

    fun playStart() = play("start") { synthStart() }
    fun playEnd() = play("end") { synthEnd() }
    fun playBusy() = play("busy") { synthBusy() }
    fun playCrosstalk() = play("crosstalk") { synthCrosstalk() }
    fun playTune() = play("tune") { synthTune() }
    fun playEnter() = play("enter") { synthEnter() }

    private fun play(name: String, synth: () -> ShortArray) {
        if (!enabled) return
        try {
            // 確率的に作るノイズは毎回作り直す。トーン系だけキャッシュ。
            val samples = if (name == "start" || name == "busy" || name == "enter") cache.getOrPut(name, synth) else synth()
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(samples, 0, samples.size)
            track.play()
            val ms = samples.size * 1000L / SAMPLE_RATE + 150
            handler.postDelayed({ runCatching { track.release() } }, ms)
        } catch (_: Exception) {
            // 効果音が鳴らなくても通話には影響させない
        }
    }

    // ---------------------------------------------------------------- 合成

    private fun toShorts(f: FloatArray) = ShortArray(f.size) { i ->
        (f[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
    }

    /** 1次ローパス。 */
    private class LowPass(private val cutoff: Float) {
        private var y = 0f
        fun process(x: Float, cutoffOverride: Float = cutoff): Float {
            val a = 1f - exp(-2f * PI.toFloat() * cutoffOverride / SAMPLE_RATE)
            y += a * (x - y)
            return y
        }
    }

    /** 1次ハイパス。 */
    private class HighPass(cutoff: Float) {
        private val a = run {
            val rc = 1f / (2f * PI.toFloat() * cutoff)
            val dt = 1f / SAMPLE_RATE
            rc / (rc + dt)
        }
        private var y = 0f
        private var prevX = 0f
        fun process(x: Float): Float {
            y = a * (y + x - prevX)
            prevX = x
            return y
        }
    }

    private fun synthStart(): ShortArray {
        val n = (SAMPLE_RATE * 0.08).toInt()
        val f = FloatArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val freq = if (t < 0.04) 880.0 else 1320.0
            phase += 2 * PI * freq / SAMPLE_RATE
            val env = 0.12 * (0.001 / 0.12).pow(t / 0.08)
            f[i] = (sin(phase) * env).toFloat()
        }
        return toShorts(f)
    }

    /** 発話終了: ザラザラに歪んだノイズを細かく途切れさせた「ガッ」 (長さ0.16秒)。 */
    private fun synthEnd(): ShortArray {
        val dur = 0.16
        val n = (SAMPLE_RATE * dur).toInt()
        val hp = HighPass(350f)
        val lp = LowPass(4200f)
        val f = FloatArray(n)
        var held = 0f
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            if (i % 5 == 0) held = Random.nextFloat() * 2f - 1f // 粗いサンプルホールドでザラつかせる
            val gate = if (floor(t * 22).toInt() % 2 == 0) 1f else 0.15f
            val decay = if (t < 0.09) 1f else max(0.0, 1.0 - (t - 0.09) / 0.07).toFloat()
            val x = lp.process(hp.process(held * gate * decay))
            f[i] = tanh(x * 5f) * 0.07f
        }
        return toShorts(f)
    }

    private fun synthBusy(): ShortArray {
        val total = (SAMPLE_RATE * 0.21).toInt()
        val f = FloatArray(total)
        for (offset in listOf(0.0, 0.12)) {
            val start = (offset * SAMPLE_RATE).toInt()
            val len = (0.08 * SAMPLE_RATE).toInt()
            for (i in 0 until len) {
                if (start + i >= total) break
                val t = i.toDouble() / SAMPLE_RATE
                f[start + i] = (if (sin(2 * PI * 330 * t) >= 0) 0.06f else -0.06f)
            }
        }
        return toShorts(f)
    }

    private fun synthCrosstalk(): ShortArray {
        val dur = 0.7
        val n = (SAMPLE_RATE * dur).toInt()
        val hp = HighPass(400f)
        val lp = LowPass(3500f)
        val f = FloatArray(n)
        var held = 0f
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            if (i % 3 == 0) held = Random.nextFloat() * 2f - 1f
            val wobble = (0.55 + 0.45 * sin(2 * PI * 9 * t)).toFloat()
            val env = max(0.0, 1.0 - t / dur).toFloat()
            f[i] = lp.process(hp.process(held * wobble * env)) * 0.2f * 1.6f
        }
        return toShorts(f)
    }

    /** 入室音「ピコーン」(Web版と同じ音程: 短い高音のあとに、伸びる高音)。音量はWeb版の半分に下げてある。 */
    private fun synthEnter(): ShortArray {
        val total = (SAMPLE_RATE * 0.72).toInt()
        val f = FloatArray(total)
        fun note(freq: Double, start: Double, dur: Double, vol: Double) {
            val s0 = (start * SAMPLE_RATE).toInt()
            val len = (dur * SAMPLE_RATE).toInt()
            for (i in 0 until len) {
                if (s0 + i >= total) break
                val t = i.toDouble() / SAMPLE_RATE
                // 10msで立ち上がり、指数的に減衰する
                val env = if (t < 0.01) 0.001 * (vol / 0.001).pow(t / 0.01) else vol * (0.001 / vol).pow((t - 0.01) / (dur - 0.01))
                f[s0 + i] += (sin(2 * PI * freq * t) * env).toFloat()
            }
        }
        note(1046.5, 0.00, 0.09, 0.07)
        note(1568.0, 0.08, 0.60, 0.07)
        note(3136.0, 0.08, 0.30, 0.015)
        return toShorts(f)
    }

    private fun synthTune(): ShortArray {
        val dur = 0.32
        val n = (SAMPLE_RATE * dur).toInt()
        val hp = HighPass(300f)
        val lp = LowPass(500f)
        val f = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val cutoff = (500.0 * (3200.0 / 500.0).pow(t / dur)).toFloat() // 500Hz → 3200Hz
            val env = (0.12 * (0.001 / 0.12).pow(t / dur)).toFloat()
            val x = Random.nextFloat() * 2f - 1f
            f[i] = lp.process(hp.process(x), cutoff) * env * 2.2f
        }
        return toShorts(f)
    }

}
