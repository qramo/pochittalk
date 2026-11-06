package app.pochitalk.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 待機中にマイクの音量だけを測る (VOX とレベルメーター用)。
 *
 * libwebrtc は、送信トラックが無効 (= ボタンを押していない) の間は録音そのものを止めてしまい、
 * その間は音量が取れない。そこで待機中だけ別の AudioRecord で音量を測り、送信が始まったら止める
 * (送信中の音量は libwebrtc の録音から取る)。ここで録った音は送信も保存もしない。
 *
 * 呼び出し側で RECORD_AUDIO の許可を確認してから start() すること。
 */
class MicMonitor(private val onRms: (Float) -> Unit) {
    @Volatile private var running = false
    val isRunning: Boolean get() = running
    private var thread: Thread? = null

    fun start() {
        if (running) return
        // 前のスレッドが AudioRecord を解放し終わるまで待つ (同時に2つ録音すると、どちらも音が来なくなる)
        thread?.join(200)
        running = true
        val t = Thread({ loop() }, "pochi-mic-monitor")
        thread = t
        t.start()
    }

    /** 止める。待たない版 (部屋を出るときなど、直後に別の録音を始めない場合用)。 */
    fun stop() {
        running = false
    }

    /**
     * 止めて、AudioRecord を解放し終わるまで待つ。libwebrtc の録音を始める直前に使う。
     * Android は同じアプリの2つの録音を同時に走らせると音を止めてしまうので、必ず1つずつにする。
     */
    fun stopAndWait(timeoutMs: Long = 400) {
        running = false
        thread?.join(timeoutMs)
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val sampleRate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            running = false
            return
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // libwebrtc の録音と同じ入力。エコー除去などが効く
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, sampleRate / 5 * 2),
            )
        } catch (e: Exception) {
            running = false
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            running = false
            return
        }
        val buf = ShortArray(sampleRate / 50) // 20ms
        try {
            record.startRecording()
            while (running) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) break
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i].toDouble()
                if (running) onRms((sqrt(sum / n) / 32768.0).toFloat())
            }
        } catch (_: Exception) {
        } finally {
            try {
                record.stop()
            } catch (_: Exception) {
            }
            record.release()
            running = false
        }
    }
}
