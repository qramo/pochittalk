package app.pochitalk.audio

import org.webrtc.ExternalAudioProcessingFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * 送信するマイク音声の増幅 (入力レベル)。libwebrtc のキャプチャ後処理 (エコー除去・ノイズ抑制の後、
 * エンコードの前) に差し込んで、サンプルに係数を掛ける。機種によってマイクの入力が小さい
 * (実機: Galaxy は Pixel の 1/4〜1/10) ための調整用。
 *
 * 実機で確認したこと: process() に渡されるバッファは float (48kHz なら 10ms = 480サンプル、
 * 4バイト×480 = 1920バイト)。WebRTC 内部の float は 16bit 整数と同じ振幅 (±32768) なので、
 * 掛けたあとは ±32767 に収める。
 */
class MicGain : ExternalAudioProcessingFactory.AudioProcessing {
    /** 線形の係数 (1.0 = そのまま)。 */
    @Volatile var gain: Float = 1f

    /** 実機での検証用: 無音の部屋でも増幅の効果が見えるように、小さなテスト音を足す。通常は false。 */
    @Volatile var testTone: Boolean = false
    private var phase = 0.0

    override fun initialize(sampleRateHz: Int, numChannels: Int) {}

    override fun reset(newRate: Int) {}

    override fun process(numBands: Int, numFrames: Int, buffer: ByteBuffer) {
        val g = gain
        val tone = testTone
        if (g == 1f && !tone) return
        val fb = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val n = fb.limit()
        for (i in 0 until n) {
            var v = fb.get(i)
            if (tone) {
                v += (100.0 * sin(phase)).toFloat() // -50dBFS 程度の 440Hz
                phase += 2 * PI * 440.0 / 48000.0
            }
            v *= g
            if (v > 32767f) v = 32767f else if (v < -32767f) v = -32767f
            fb.put(i, v)
        }
    }
}
