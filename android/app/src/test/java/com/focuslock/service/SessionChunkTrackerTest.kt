package com.focuslock.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionChunkTrackerTest {

    private fun tracker(maxMin: Int = 15, breakMin: Int = 2) =
        SessionChunkTracker().apply { configure(maxMin, breakMin) }

    private val MIN = 60_000L

    @Test
    fun `disabled tracker always allows and has no cap`() {
        val t = SessionChunkTracker().apply { configure(0, 2) }
        assertTrue(t.onTargetOpened(0L) is SessionChunkTracker.Gate.Allow)
        assertNull(t.msUntilCap(0L))
    }

    @Test
    fun `full chunk budget available on first open`() {
        val t = tracker(15, 2)
        t.onTargetOpened(0L)
        assertEquals(15 * MIN, t.msUntilCap(0L))
    }

    @Test
    fun `budget shrinks with elapsed presence`() {
        val t = tracker(15, 2)
        t.onTargetOpened(0L)
        assertEquals(5 * MIN, t.msUntilCap(10 * MIN))
    }

    @Test
    fun `quick close and reopen keeps accumulating`() {
        val t = tracker(15, 2)
        t.onTargetOpened(0L)
        t.onTargetClosed(10 * MIN)          // 10 min used
        t.onTargetOpened(10 * MIN + 30_000) // back after 30s — less than the 2-min break
        assertEquals(5 * MIN, t.msUntilCap(10 * MIN + 30_000))
    }

    @Test
    fun `voluntary absence of at least break length resets the chunk`() {
        val t = tracker(15, 2)
        t.onTargetOpened(0L)
        t.onTargetClosed(10 * MIN)
        t.onTargetOpened(12 * MIN) // away 2 min == break length
        assertEquals(15 * MIN, t.msUntilCap(12 * MIN))
    }

    @Test
    fun `startBreak locks all opens until it expires`() {
        val t = tracker(15, 2)
        t.onTargetOpened(0L)
        val until = t.startBreak(15 * MIN)
        assertEquals(17 * MIN, until)
        val gate = t.onTargetOpened(16 * MIN)
        assertTrue(gate is SessionChunkTracker.Gate.InBreak)
        assertEquals(until, (gate as SessionChunkTracker.Gate.InBreak).untilMs)
        // After the break ends, a fresh chunk begins.
        assertTrue(t.onTargetOpened(17 * MIN + 1) is SessionChunkTracker.Gate.Allow)
        assertEquals(15 * MIN, t.msUntilCap(17 * MIN + 1))
    }

    @Test
    fun `app hopping between targets cannot dodge the cap`() {
        val t = tracker(15, 2)
        t.onTargetOpened(0L)          // app A
        t.onTargetClosed(8 * MIN)
        t.onTargetOpened(8 * MIN)     // switch straight to app B
        assertEquals(7 * MIN, t.msUntilCap(8 * MIN))
    }

    @Test
    fun `reconfigure resets state forgivingly`() {
        val t = tracker(15, 2)
        t.onTargetOpened(0L)
        t.startBreak(15 * MIN)
        t.configure(10, 3)
        // New config: no lingering break, fresh budget.
        assertTrue(t.onTargetOpened(16 * MIN) is SessionChunkTracker.Gate.Allow)
        assertEquals(10 * MIN, t.msUntilCap(16 * MIN))
    }

    @Test
    fun `same-config reconfigure keeps running state`() {
        val t = tracker(15, 2)
        t.onTargetOpened(0L)
        t.configure(15, 2) // policy refresh with unchanged values
        assertEquals(10 * MIN, t.msUntilCap(5 * MIN))
    }
}
