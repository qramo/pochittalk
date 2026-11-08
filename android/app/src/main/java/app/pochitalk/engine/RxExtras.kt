package app.pochitalk.engine

import app.pochitalk.core.Fax
import app.pochitalk.core.FaxPreDetector
import app.pochitalk.core.MorseDecoder
import app.pochitalk.core.ToneAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.AudioTrack
import org.webrtc.AudioTrackSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 受け取ったモールスと FAX を、Web版のように表示するための部品。
 * ・相手の声のトラックを聞いて、700Hz の音をモールスとして読む / 1500Hz の「ピー」を FAX の開始の合図にする
 * ・FAX の中身は `faxPage` のデータで確実に届くので、それを音に合わせて 1 行ずつ出す (Web版 startFaxReveal と同じ進行表)
 * 状態の更新は [onMorse] / [onFax] で engine に返す (どのスレッドからでも呼ばれる)。
 */
class RxExtras(
    private val scope: CoroutineScope,
    private val nameOf: (String) -> String,
    private val onMorse: (from: String, text: String) -> Unit,
    private val onFax: (FaxPaper?) -> Unit,
) {
    private class Listener(val track: AudioTrack, val sink: AudioTrackSink)

    private class Decoders(val morse: MorseDecoder, val pre: FaxPreDetector) {
        var analyzer: ToneAnalyzer? = null
        var sampleRate = 0
        var mono = FloatArray(0)
    }

    private class Pending(val grid: Array<BooleanArray>, val from: String) {
        var started = false
        var timeout: Job? = null
        var reveal: Job? = null
    }

    private val lock = Any()
    private val listeners = HashMap<String, Listener>()
    private val decoders = HashMap<String, Decoders>()
    private val pending = HashMap<String, Pending>()
    private var morseClear: Job? = null

    private fun decodersOf(peerId: String): Decoders = synchronized(lock) {
        decoders.getOrPut(peerId) {
            Decoders(
                MorseDecoder(
                    onChange = { text -> morseChanged(peerId, text) },
                    onEsc = { onFax(null) }, // 「ESC」を受けたら紙を片づける (Web版と同じ)
                ),
                FaxPreDetector(),
            )
        }
    }

    private fun morseChanged(peerId: String, text: String) {
        onMorse(if (text.isEmpty()) "" else nameOf(peerId), text)
        morseClear?.cancel()
        if (text.isNotEmpty()) {
            morseClear = scope.launch {
                delay(MORSE_SHOW_MS)
                onMorse("", "")
            }
        }
    }

    /** 相手の声のトラックを聞き始める (同じ相手の古いトラックは外す)。 */
    fun attach(peerId: String, track: AudioTrack) {
        val d = decodersOf(peerId)
        val sink = AudioTrackSink { data, bits, sampleRate, channels, frames, _ ->
            try {
                onAudio(d, data, bits, sampleRate, channels, frames)
            } catch (_: Exception) {
            }
        }
        synchronized(lock) {
            listeners.remove(peerId)?.let { old -> runCatching { old.track.removeSink(old.sink) } }
            listeners[peerId] = Listener(track, sink)
        }
        runCatching { track.addSink(sink) }
    }

    private fun onAudio(d: Decoders, data: ByteBuffer, bits: Int, sampleRate: Int, channels: Int, frames: Int) {
        if (bits != 16 || channels < 1 || frames <= 0) return
        val buf = data.duplicate().order(ByteOrder.nativeOrder())
        synchronized(d) {
            if (d.analyzer == null || d.sampleRate != sampleRate) {
                d.sampleRate = sampleRate
                d.analyzer = ToneAnalyzer(sampleRate) { f ->
                    d.morse.onFrame(f)
                    if (d.pre.onFrame(f)) onFaxTone(d)
                }
            }
            if (d.mono.size < frames) d.mono = FloatArray(frames)
            var idx = 0
            for (i in 0 until frames) {
                var sum = 0
                for (c in 0 until channels) sum += buf.getShort(idx++ * 2).toInt()
                d.mono[i] = sum / channels / 32768f
            }
            d.analyzer!!.feed(d.mono, frames)
        }
    }

    /** 「ピー」が聞こえた: データが届いている相手の FAX を、音に合わせて出し始める。 */
    private fun onFaxTone(d: Decoders) {
        val peerId = synchronized(lock) { decoders.entries.firstOrNull { it.value === d }?.key } ?: return
        scope.launch { startReveal(peerId, 500) }
    }

    /** 相手が話し始めた: 前の文字は消す。 */
    fun onTalkStart(peerId: String) {
        val d = synchronized(lock) { decoders[peerId] } ?: return
        synchronized(d) { d.morse.reset() }
        d.pre.reset()
    }

    /** `faxPage` のデータが届いた。 */
    fun onFaxPage(peerId: String, c: Int, base64: String) {
        val grid = Fax.payloadToGrid(c, base64) ?: return
        cancelFax(peerId)
        val p = Pending(grid, nameOf(peerId))
        p.timeout = scope.launch {
            delay(1500) // 音の合図が聞こえればそちらで開始。聞こえなくても少し待って開始する
            startReveal(peerId, 0)
        }
        synchronized(lock) { pending[peerId] = p }
    }

    private fun startReveal(peerId: String, delayMs: Long) {
        val p = synchronized(lock) { pending[peerId] } ?: return
        if (p.started) return
        p.started = true
        p.timeout?.cancel()
        val cols = p.grid[0].size
        val black = BooleanArray(Fax.ROWS * cols)
        onFax(FaxPaper(cols, black, 0, p.from))
        p.reveal = scope.launch {
            delay(delayMs)
            var elapsed = 0L
            for ((row, at) in Fax.revealSchedule(p.grid)) {
                delay((at - elapsed).coerceAtLeast(0))
                elapsed = maxOf(elapsed, at)
                if (row >= 1) for (x in 0 until cols) if (p.grid[row - 1][x]) black[row * cols + x] = true
                onFax(FaxPaper(cols, black.copyOf(), row + 1, p.from))
            }
            delay(4000)
            synchronized(lock) { if (pending[peerId] === p) pending.remove(peerId) }
        }
    }

    private fun cancelFax(peerId: String) {
        val p = synchronized(lock) { pending.remove(peerId) } ?: return
        p.timeout?.cancel()
        p.reveal?.cancel()
    }

    fun detach(peerId: String) {
        synchronized(lock) {
            listeners.remove(peerId)?.let { l -> runCatching { l.track.removeSink(l.sink) } }
            decoders.remove(peerId)
        }
        cancelFax(peerId)
    }

    /** 部屋を出た: 全部やめる。 */
    fun clear() {
        val ids = synchronized(lock) { listeners.keys + decoders.keys + pending.keys }
        ids.toSet().forEach { detach(it) }
        morseClear?.cancel()
        onMorse("", "")
        onFax(null)
    }

    companion object {
        /** 読んだ文字を出しておく長さ。 */
        private const val MORSE_SHOW_MS = 20_000L
    }
}
