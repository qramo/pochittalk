package app.pochitalk.ui

import kotlin.math.sqrt

/**
 * 話すボタンの押し方の判定 (画面の描画や Compose から切り離した状態遷移)。Web 版の touchmove と同じ考え方。
 *
 * 入室後は、左から右へのスワイプで部屋一覧が開く。そのスワイプの指が話すボタンの上に乗っても、
 * 誤って送信しないようにする。ただし、話している最中に指が少し滑っただけで止まらないように、
 * 「どれだけ動いたか」ではなく「指がボタン (円) の外に出たか」で判定する。
 *
 *   IDLE ──押す──▶ WAITING ──[holdMs たつ]──▶ TALKING ──離す──▶ 送信を終える
 *                    │                            │
 *                    ├─ 離す (短いタップ)           └─ 指が円の外に出る ─▶ SWIPE: すぐ止める
 *                    │   ─▶ 送信しない
 *                    └─ 指が円の外に出る ─▶ SWIPE: 送信しない
 *
 * 円の上にいる間は、どれだけ動いてもスワイプとみなさない。円の縁から limitMarginPx
 * (約14dp) 以上離れたときだけ、スワイプとみなす。いったんスワイプになったら、円に戻っても再開しない。
 */
class PttGesture(
    private val centerX: Float,
    private val centerY: Float,
    /** 円の中心からこの距離 (px) を超えたら、「ボタンの外」。円の半径 + 縁の余裕 (約14dp)。 */
    private val outsideRadiusPx: Float,
    private val holdMs: Long = DEFAULT_HOLD_MS,
) {
    enum class State { IDLE, WAITING, TALKING, SWIPE }

    enum class Action {
        /** 何もしない */
        NONE,

        /** 送信を始める */
        START,

        /** 指が離れた (または中断した): 送信を終える */
        RELEASE,

        /** スワイプと判定した (まだ送信していなかった): 送信は始めない。部屋一覧のスワイプとして扱う */
        SWIPE,

        /** スワイプと判定した (送信中だった): 終了音を鳴らさずにすぐ止める。部屋一覧のスワイプとしても扱う */
        ABORT,
    }

    var state: State = State.IDLE
        private set

    private var downTime = 0L

    /** 指がボタン (円) の外にあるか。 */
    fun isOutside(x: Float, y: Float): Boolean {
        val dx = x - centerX
        val dy = y - centerY
        return sqrt(dx * dx + dy * dy) > outsideRadiusPx
    }

    fun onDown(timeMs: Long, x: Float, y: Float) {
        downTime = timeMs
        state = if (isOutside(x, y)) State.SWIPE else State.WAITING
    }

    /** 指が動いた。 */
    fun onMove(timeMs: Long, x: Float, y: Float): Action {
        val outside = isOutside(x, y)
        return when (state) {
            State.WAITING -> when {
                outside -> {
                    state = State.SWIPE
                    Action.SWIPE // まだ送信していないので、止めるものは無い
                }
                timeMs - downTime >= holdMs -> start()
                else -> Action.NONE
            }
            State.TALKING -> if (outside) {
                state = State.SWIPE
                Action.ABORT
            } else {
                Action.NONE // 円の上なら、どれだけ滑っても話し続ける
            }
            else -> Action.NONE
        }
    }

    /** 時間だけが進んだ (指は動いていない)。待ち時間を過ぎたら送信を始める。 */
    fun onTimer(timeMs: Long): Action =
        if (state == State.WAITING && timeMs - downTime >= holdMs) start() else Action.NONE

    /** 指を離した。 */
    fun onUp(): Action {
        val was = state
        state = State.IDLE
        return if (was == State.TALKING) Action.RELEASE else Action.NONE // 短いタップ・スワイプ後は何もしない
    }

    /** ジェスチャーが取り消された (画面が閉じた、など)。 */
    fun onCancel(): Action = onUp()

    /** WAITING のとき、あと何ミリ秒で送信を始めるか。 */
    fun remainingMs(nowMs: Long): Long = (holdMs - (nowMs - downTime)).coerceAtLeast(0L)

    private fun start(): Action {
        state = State.TALKING
        return Action.START
    }

    companion object {
        /** 押してから、この時間 (ミリ秒) たつと送信を始める。最初の一言が切れすぎない短さにしてある。 */
        const val DEFAULT_HOLD_MS = 130L
    }
}
