package app.pochitalk.ui

import app.pochitalk.ui.PttGesture.Action
import app.pochitalk.ui.PttGesture.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PttGestureTest {
    // 中心 (100,100)、「ボタンの外」は中心から 120px より遠いところ (例: 半径 100 + 余裕 20)
    private fun gesture() = PttGesture(centerX = 100f, centerY = 100f, outsideRadiusPx = 120f, holdMs = 130)

    @Test
    fun insideOrOutsideIsMeasuredFromTheCenter() {
        val g = gesture()
        assertFalse(g.isOutside(100f, 100f))
        assertFalse(g.isOutside(100f + 119f, 100f))
        assertTrue(g.isOutside(100f + 121f, 100f))
        assertTrue(g.isOutside(100f, 100f - 121f))
    }

    @Test
    fun holdingStillStartsTalkingAfterTheWaitAndReleasesOnUp() {
        val g = gesture()
        g.onDown(0, 100f, 100f)
        assertEquals(State.WAITING, g.state)
        assertEquals(Action.NONE, g.onTimer(100))
        assertEquals(Action.START, g.onTimer(130))
        assertEquals(State.TALKING, g.state)
        assertEquals(Action.RELEASE, g.onUp())
        assertEquals(State.IDLE, g.state)
    }

    @Test
    fun slidingALongWayInsideTheButtonNeverCountsAsSwipe() {
        val g = gesture()
        g.onDown(0, 100f, 100f)
        // 待っている間に、円の中を大きく動いても、スワイプとみなさない (以前の「16dp動いたら」とは違う)
        assertEquals(Action.NONE, g.onMove(50, 190f, 100f))
        assertEquals(State.WAITING, g.state)
        assertEquals(Action.START, g.onMove(140, 20f, 60f))
        // 話している間も、円の上なら、どれだけ滑っても話し続ける
        assertEquals(Action.NONE, g.onMove(300, 215f, 100f))
        assertEquals(Action.NONE, g.onMove(400, 100f, 205f))
        assertEquals(State.TALKING, g.state)
        assertEquals(Action.RELEASE, g.onUp())
    }

    @Test
    fun leavingTheButtonBeforeTheWaitEndsNeverStartsTalking() {
        val g = gesture()
        g.onDown(0, 100f, 100f)
        assertEquals(Action.SWIPE, g.onMove(60, 230f, 100f)) // 円の外: スワイプ (送信はしていない)
        assertEquals(State.SWIPE, g.state)
        assertEquals(Action.NONE, g.onTimer(500)) // 時間がたっても、送信しない
        assertEquals(Action.NONE, g.onMove(600, 100f, 100f)) // 円に戻っても、再開しない
        assertEquals(State.SWIPE, g.state)
        assertEquals(Action.NONE, g.onUp())
    }

    @Test
    fun leavingTheButtonWhileTalkingAbortsImmediatelyAndUpDoesNotReleaseAgain() {
        val g = gesture()
        g.onDown(0, 100f, 100f)
        assertEquals(Action.START, g.onTimer(130))
        assertEquals(Action.ABORT, g.onMove(200, 100f, -30f)) // 円の外 (上)
        assertEquals(State.SWIPE, g.state)
        assertEquals(Action.NONE, g.onMove(250, 100f, 100f)) // 戻っても再開しない
        assertEquals(Action.NONE, g.onMove(260, 400f, 100f)) // 二重に止めない
        assertEquals(Action.NONE, g.onUp())
    }

    @Test
    fun pressingOutsideTheCircleIsASwipeFromTheStart() {
        val g = gesture()
        g.onDown(0, 100f + 200f, 100f)
        assertEquals(State.SWIPE, g.state)
        assertEquals(Action.NONE, g.onTimer(500))
        assertEquals(Action.NONE, g.onUp())
    }

    @Test
    fun shortTapDoesNotTalk() {
        val g = gesture()
        g.onDown(0, 100f, 100f)
        assertEquals(Action.NONE, g.onUp()) // 130ms より前に離した
        assertEquals(State.IDLE, g.state)
    }

    @Test
    fun cancelWhileTalkingReleases() {
        val g = gesture()
        g.onDown(0, 100f, 100f)
        g.onTimer(130)
        assertEquals(Action.RELEASE, g.onCancel())
    }

    @Test
    fun remainingTimeCountsDown() {
        val g = gesture()
        g.onDown(1000, 100f, 100f)
        assertEquals(130L, g.remainingMs(1000))
        assertEquals(30L, g.remainingMs(1100))
        assertEquals(0L, g.remainingMs(1500))
    }
}
