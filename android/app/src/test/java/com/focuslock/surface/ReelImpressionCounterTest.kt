package com.focuslock.surface

import org.junit.Assert.assertEquals
import org.junit.Test

class ReelImpressionCounterTest {

    private val reels = BlockableSurface.INSTAGRAM_REELS
    private val shorts = BlockableSurface.YOUTUBE_SHORTS

    private fun counter(
        advanceDeltaPx: Int = 900,
        sessionGapMs: Long = 3000L,
        maxAdvancePerEvent: Int = 10,
        minAdvanceIntervalMs: Long = 250L
    ) = ReelImpressionCounter(advanceDeltaPx, sessionGapMs, maxAdvancePerEvent, minAdvanceIntervalMs)

    @Test
    fun `entering the viewer counts one`() {
        val c = counter()
        assertEquals(1, c.onViewerVisible(reels, 0L))
    }

    @Test
    fun `staying in the viewer without scrolling counts nothing more`() {
        val c = counter()
        assertEquals(1, c.onViewerVisible(reels, 0L))
        assertEquals(0, c.onViewerVisible(reels, 500L))
        assertEquals(0, c.onViewerVisible(reels, 1000L))
    }

    @Test
    fun `each settled pager advance counts one`() {
        val c = counter()
        c.onViewerVisible(reels, 0L)
        // First settled index establishes the baseline.
        assertEquals(0, c.onPagerScrolled(reels, fromIndex = 0, toIndex = 0, scrollDeltaY = 0, nowMs = 10L))
        assertEquals(1, c.onPagerScrolled(reels, fromIndex = 1, toIndex = 1, scrollDeltaY = 0, nowMs = 300L))
    }

    @Test
    fun `mid swipe events with unequal from and to indices count nothing`() {
        val c = counter()
        c.onViewerVisible(reels, 0L)
        assertEquals(0, c.onPagerScrolled(reels, fromIndex = 0, toIndex = 0, scrollDeltaY = 0, nowMs = 10L))
        // Mid-swipe: fromIndex != toIndex, not settled.
        assertEquals(0, c.onPagerScrolled(reels, fromIndex = 0, toIndex = 1, scrollDeltaY = 100, nowMs = 20L))
    }

    @Test
    fun `the first settled index only establishes the baseline`() {
        val c = counter()
        c.onViewerVisible(reels, 0L)
        assertEquals(0, c.onPagerScrolled(reels, fromIndex = 3, toIndex = 3, scrollDeltaY = 0, nowMs = 10L))
    }

    @Test
    fun `a fling across five indices counts five`() {
        val c = counter()
        c.onViewerVisible(reels, 0L)
        assertEquals(0, c.onPagerScrolled(reels, fromIndex = 0, toIndex = 0, scrollDeltaY = 0, nowMs = 10L))
        assertEquals(5, c.onPagerScrolled(reels, fromIndex = 5, toIndex = 5, scrollDeltaY = 0, nowMs = 300L))
    }

    @Test
    fun `an index jump beyond the clamp is capped`() {
        val c = counter(maxAdvancePerEvent = 10)
        c.onViewerVisible(reels, 0L)
        assertEquals(0, c.onPagerScrolled(reels, fromIndex = 50, toIndex = 50, scrollDeltaY = 0, nowMs = 10L))
        // Recycler rebind snaps back to 0 — a huge jump, clamped.
        assertEquals(10, c.onPagerScrolled(reels, fromIndex = 0, toIndex = 0, scrollDeltaY = 0, nowMs = 300L))
    }

    @Test
    fun `oscillating settled index inside the anti thrash window counts once`() {
        val c = counter(minAdvanceIntervalMs = 250L)
        c.onViewerVisible(reels, 0L)
        assertEquals(0, c.onPagerScrolled(reels, fromIndex = 0, toIndex = 0, scrollDeltaY = 0, nowMs = 10L))
        // A -> B, well clear of the session-start reference, counts.
        assertEquals(1, c.onPagerScrolled(reels, fromIndex = 1, toIndex = 1, scrollDeltaY = 0, nowMs = 500L))
        // B -> A back within 250ms of the last counted advance (nowMs=500) is thrash.
        assertEquals(0, c.onPagerScrolled(reels, fromIndex = 0, toIndex = 0, scrollDeltaY = 0, nowMs = 650L))
    }

    @Test
    fun `oscillating settled index outside the anti thrash window counts each move`() {
        val c = counter(minAdvanceIntervalMs = 250L)
        c.onViewerVisible(reels, 0L)
        assertEquals(0, c.onPagerScrolled(reels, fromIndex = 0, toIndex = 0, scrollDeltaY = 0, nowMs = 10L))
        assertEquals(1, c.onPagerScrolled(reels, fromIndex = 1, toIndex = 1, scrollDeltaY = 0, nowMs = 500L))
        // Well outside the anti-thrash window — a real re-watch of the earlier reel.
        assertEquals(1, c.onPagerScrolled(reels, fromIndex = 0, toIndex = 0, scrollDeltaY = 0, nowMs = 1500L))
    }

    @Test
    fun `index less scrolls accumulate to one count per screen height`() {
        val c = counter(advanceDeltaPx = 900)
        c.onViewerVisible(reels, 0L)
        assertEquals(
            1,
            c.onPagerScrolled(reels, fromIndex = -1, toIndex = -1, scrollDeltaY = 900, nowMs = 10L)
        )
    }

    @Test
    fun `index less scrolls carry the remainder`() {
        val c = counter(advanceDeltaPx = 900)
        c.onViewerVisible(reels, 0L)
        assertEquals(
            0,
            c.onPagerScrolled(reels, fromIndex = -1, toIndex = -1, scrollDeltaY = 500, nowMs = 10L)
        )
        assertEquals(
            1,
            c.onPagerScrolled(reels, fromIndex = -1, toIndex = -1, scrollDeltaY = 500, nowMs = 20L)
        )
        // 500 + 500 = 1000 -> 1 advance (900), 100px remainder carried forward.
        // Adding 700 more (100 + 700 = 800) stays under the 900 threshold.
        assertEquals(
            0,
            c.onPagerScrolled(reels, fromIndex = -1, toIndex = -1, scrollDeltaY = 700, nowMs = 30L)
        )
    }

    @Test
    fun `a large index less scroll burst counts multiple advances`() {
        val c = counter(advanceDeltaPx = 900)
        c.onViewerVisible(reels, 0L)
        assertEquals(
            3,
            c.onPagerScrolled(reels, fromIndex = -1, toIndex = -1, scrollDeltaY = 2700, nowMs = 10L)
        )
    }

    @Test
    fun `the delta fallback is disabled once a settled index has been seen`() {
        val c = counter(advanceDeltaPx = 900)
        c.onViewerVisible(reels, 0L)
        // A settled index event latches sawIndexEvents.
        assertEquals(0, c.onPagerScrolled(reels, fromIndex = 0, toIndex = 0, scrollDeltaY = 0, nowMs = 10L))
        // Now an unsettled/index-less event must not fall back to delta counting.
        assertEquals(
            0,
            c.onPagerScrolled(reels, fromIndex = -1, toIndex = -1, scrollDeltaY = 5000, nowMs = 20L)
        )
    }

    @Test
    fun `re entry immediately after leaving counts a new reel`() {
        val c = counter()
        assertEquals(1, c.onViewerVisible(reels, 0L))
        c.onSurfaceLeft(reels)
        // Immediate re-entry, well within the session gap and within the old
        // tracker's 1500ms cooldown — must still count as a new reel.
        assertEquals(1, c.onViewerVisible(reels, 100L))
    }

    @Test
    fun `returning within the session gap without leaving does not recount`() {
        val c = counter(sessionGapMs = 3000L)
        assertEquals(1, c.onViewerVisible(reels, 0L))
        // A probe blip — no onSurfaceLeft call — well within the session gap.
        assertEquals(0, c.onViewerVisible(reels, 1000L))
    }

    @Test
    fun `returning after the session gap counts a new reel`() {
        val c = counter(sessionGapMs = 3000L)
        assertEquals(1, c.onViewerVisible(reels, 0L))
        assertEquals(1, c.onViewerVisible(reels, 3001L))
    }

    @Test
    fun `a scroll with no open session adopts it and counts one`() {
        val c = counter()
        // Missed entry event entirely — first thing seen is a scroll.
        assertEquals(1, c.onPagerScrolled(reels, fromIndex = 0, toIndex = 0, scrollDeltaY = 0, nowMs = 0L))
    }

    @Test
    fun `instagram and youtube counters are independent`() {
        val c = counter()
        assertEquals(1, c.onViewerVisible(reels, 0L))
        assertEquals(1, c.onViewerVisible(shorts, 0L))
        assertEquals(0, c.onViewerVisible(reels, 100L))
        c.onSurfaceLeft(reels)
        assertEquals(1, c.onViewerVisible(reels, 150L))
        // Youtube session untouched by instagram's leave.
        assertEquals(0, c.onViewerVisible(shorts, 200L))
    }

    @Test
    fun `reset clears all surface state`() {
        val c = counter()
        c.onViewerVisible(reels, 0L)
        c.onPagerScrolled(reels, fromIndex = 0, toIndex = 0, scrollDeltaY = 0, nowMs = 10L)
        c.reset()
        assertEquals(1, c.onViewerVisible(reels, 20L))
    }
}
