package com.focuslock.surface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SurfaceDetectorTest {

    private val reels = BlockableSurface.INSTAGRAM_REELS

    private fun tracker(
        backDebounceMs: Long = 1500L,
        postBackCooldownMs: Long = 3000L,
        advanceDeltaPx: Int = 900
    ) = SurfaceSessionTracker(backDebounceMs, postBackCooldownMs, advanceDeltaPx)

    @Test
    fun `off mode never blocks`() {
        val t = tracker()
        assertEquals(SurfaceDecision.ALLOW, t.onViewerVisible(reels, SurfaceMode.OFF, 0L))
        assertEquals(
            SurfaceDecision.ALLOW,
            t.onPagerScrolled(reels, SurfaceMode.OFF, fromIndex = 0, toIndex = 1, scrollDeltaY = 0, nowMs = 0L)
        )
    }

    @Test
    fun `block mode presses back on detection`() {
        val t = tracker()
        assertEquals(SurfaceDecision.PRESS_BACK, t.onViewerVisible(reels, SurfaceMode.BLOCK, 0L))
    }

    @Test
    fun `block mode debounces repeated detections within cooldown`() {
        val t = tracker(backDebounceMs = 1500L, postBackCooldownMs = 3000L)
        assertEquals(SurfaceDecision.PRESS_BACK, t.onViewerVisible(reels, SurfaceMode.BLOCK, 0L))
        // Still well inside the 3s cooldown — repeated sightings must not re-trigger.
        assertEquals(SurfaceDecision.ALLOW, t.onViewerVisible(reels, SurfaceMode.BLOCK, 500L))
        assertEquals(SurfaceDecision.ALLOW, t.onViewerVisible(reels, SurfaceMode.BLOCK, 2000L))
        // Cooldown has elapsed — a fresh sighting re-triggers.
        assertEquals(SurfaceDecision.PRESS_BACK, t.onViewerVisible(reels, SurfaceMode.BLOCK, 3500L))
    }

    @Test
    fun `one-at-a-time grants entry to the first reel`() {
        val t = tracker()
        assertEquals(SurfaceDecision.GRANT_ENTRY, t.onViewerVisible(reels, SurfaceMode.ONE_AT_A_TIME, 0L))
        // Same-index scroll events (rebuffer, seekbar drag) must not count as an advance.
        assertEquals(
            SurfaceDecision.ALLOW,
            t.onPagerScrolled(reels, SurfaceMode.ONE_AT_A_TIME, fromIndex = 3, toIndex = 3, scrollDeltaY = 0, nowMs = 100L)
        )
    }

    @Test
    fun `GRANT_ENTRY fires once per viewer session, not on repeat presence probes`() {
        val t = tracker()
        assertEquals(SurfaceDecision.GRANT_ENTRY, t.onViewerVisible(reels, SurfaceMode.ONE_AT_A_TIME, 0L))
        // Presence probes fire repeatedly (every ~500ms) while the same reel is on
        // screen — none of those repeats should grant (and thus count) again.
        assertEquals(SurfaceDecision.ALLOW, t.onViewerVisible(reels, SurfaceMode.ONE_AT_A_TIME, 50L))
        assertEquals(SurfaceDecision.ALLOW, t.onViewerVisible(reels, SurfaceMode.ONE_AT_A_TIME, 500L))
    }

    @Test
    fun `one-at-a-time blocks on pager advance`() {
        val t = tracker()
        t.onViewerVisible(reels, SurfaceMode.ONE_AT_A_TIME, 0L)
        // First indexed scroll establishes the entry reel.
        t.onPagerScrolled(reels, SurfaceMode.ONE_AT_A_TIME, fromIndex = -1, toIndex = 3, scrollDeltaY = 0, nowMs = 50L)
        val decision = t.onPagerScrolled(
            reels, SurfaceMode.ONE_AT_A_TIME, fromIndex = 3, toIndex = 4, scrollDeltaY = 0, nowMs = 100L
        )
        assertEquals(SurfaceDecision.PRESS_BACK, decision)
    }

    @Test
    fun `advance detected even when the entry event was missed`() {
        val t = tracker()
        // No onViewerVisible call at all — first thing we see is a scroll.
        val first = t.onPagerScrolled(
            reels, SurfaceMode.ONE_AT_A_TIME, fromIndex = 0, toIndex = 0, scrollDeltaY = 0, nowMs = 0L
        )
        assertEquals(SurfaceDecision.ALLOW, first)
        val second = t.onPagerScrolled(
            reels, SurfaceMode.ONE_AT_A_TIME, fromIndex = 0, toIndex = 1, scrollDeltaY = 0, nowMs = 50L
        )
        assertEquals(SurfaceDecision.PRESS_BACK, second)
    }

    @Test
    fun `index-less fallback uses accumulated scroll distance`() {
        val t = tracker(advanceDeltaPx = 900)
        t.onViewerVisible(reels, SurfaceMode.ONE_AT_A_TIME, 0L)
        assertEquals(
            SurfaceDecision.ALLOW,
            t.onPagerScrolled(reels, SurfaceMode.ONE_AT_A_TIME, fromIndex = -1, toIndex = -1, scrollDeltaY = 300, nowMs = 10L)
        )
        assertEquals(
            SurfaceDecision.ALLOW,
            t.onPagerScrolled(reels, SurfaceMode.ONE_AT_A_TIME, fromIndex = -1, toIndex = -1, scrollDeltaY = 300, nowMs = 20L)
        )
        // Third scroll crosses the 900px threshold (300+300+400 = 1000).
        assertEquals(
            SurfaceDecision.PRESS_BACK,
            t.onPagerScrolled(reels, SurfaceMode.ONE_AT_A_TIME, fromIndex = -1, toIndex = -1, scrollDeltaY = 400, nowMs = 30L)
        )
    }

    @Test
    fun `cooldown suppresses back-loop then re-arms after it expires`() {
        val t = tracker(backDebounceMs = 1500L, postBackCooldownMs = 3000L)
        assertEquals(SurfaceDecision.PRESS_BACK, t.onViewerVisible(reels, SurfaceMode.BLOCK, 0L))
        // Viewer still momentarily visible right after Back — must not re-trigger.
        assertEquals(SurfaceDecision.ALLOW, t.onViewerVisible(reels, SurfaceMode.BLOCK, 1000L))
        // A stray pager-scroll event during cooldown must not disturb it either.
        assertEquals(
            SurfaceDecision.ALLOW,
            t.onPagerScrolled(reels, SurfaceMode.BLOCK, fromIndex = 0, toIndex = 1, scrollDeltaY = 0, nowMs = 1200L)
        )
        // After the cooldown window, a fresh sighting is treated as a new entry.
        assertEquals(SurfaceDecision.PRESS_BACK, t.onViewerVisible(reels, SurfaceMode.BLOCK, 3500L))
    }

    @Test
    fun `re-entry during cooldown after a real exit re-blocks instead of allowing`() {
        val t = tracker(backDebounceMs = 1500L, postBackCooldownMs = 3000L)
        assertEquals(SurfaceDecision.PRESS_BACK, t.onViewerVisible(reels, SurfaceMode.BLOCK, 0L))
        // Back landed — the viewer actually left the screen.
        t.onViewerGone(600L)
        // User re-opens reels while the old cooldown window is still running:
        // must be treated as a fresh sighting and blocked (debounce permitting),
        // not silently allowed until the cooldown expires.
        assertEquals(SurfaceDecision.PRESS_BACK, t.onViewerVisible(reels, SurfaceMode.BLOCK, 1600L))
    }

    @Test
    fun `quick re-entry during cooldown is a fresh counted grant, not free time`() {
        val t = tracker(backDebounceMs = 1500L, postBackCooldownMs = 3000L)
        assertEquals(SurfaceDecision.GRANT_ENTRY, t.onViewerVisible(reels, SurfaceMode.ONE_AT_A_TIME, 0L))
        t.onPagerScrolled(reels, SurfaceMode.ONE_AT_A_TIME, fromIndex = -1, toIndex = 0, scrollDeltaY = 0, nowMs = 10L)
        // Swipe to the next reel — Back pressed, cooldown starts.
        assertEquals(
            SurfaceDecision.PRESS_BACK,
            t.onPagerScrolled(reels, SurfaceMode.ONE_AT_A_TIME, fromIndex = 0, toIndex = 1, scrollDeltaY = 0, nowMs = 100L)
        )
        t.onViewerGone(700L)
        // Re-entering 1s later (inside the old 3s cooldown) starts a NEW grant that
        // gets counted against the daily limit — not an untracked ALLOW window.
        assertEquals(SurfaceDecision.GRANT_ENTRY, t.onViewerVisible(reels, SurfaceMode.ONE_AT_A_TIME, 1100L))
    }

    @Test
    fun `viewer lingering through cooldown without exiting stays suppressed`() {
        val t = tracker(backDebounceMs = 1500L, postBackCooldownMs = 3000L)
        assertEquals(SurfaceDecision.PRESS_BACK, t.onViewerVisible(reels, SurfaceMode.BLOCK, 0L))
        // No onViewerGone — the Back press is still animating. Sightings must not re-arm.
        assertEquals(SurfaceDecision.ALLOW, t.onViewerVisible(reels, SurfaceMode.BLOCK, 400L))
        assertEquals(SurfaceDecision.ALLOW, t.onViewerVisible(reels, SurfaceMode.BLOCK, 900L))
    }

    @Test
    fun `exiting the viewer re-grants one reel on re-entry`() {
        val t = tracker()
        t.onViewerVisible(reels, SurfaceMode.ONE_AT_A_TIME, 0L)
        t.onPagerScrolled(reels, SurfaceMode.ONE_AT_A_TIME, fromIndex = -1, toIndex = 0, scrollDeltaY = 0, nowMs = 10L)
        t.onViewerGone(200L)
        // Re-entering starts a brand new one-reel grant — counts as a fresh GRANT_ENTRY.
        assertEquals(SurfaceDecision.GRANT_ENTRY, t.onViewerVisible(reels, SurfaceMode.ONE_AT_A_TIME, 300L))
        assertEquals(
            SurfaceDecision.ALLOW,
            t.onPagerScrolled(reels, SurfaceMode.ONE_AT_A_TIME, fromIndex = -1, toIndex = 0, scrollDeltaY = 0, nowMs = 310L)
        )
    }

    @Test
    fun `SurfaceMode fromWire maps wire values`() {
        assertEquals(SurfaceMode.BLOCK, SurfaceMode.fromWire("block"))
        assertEquals(SurfaceMode.ONE_AT_A_TIME, SurfaceMode.fromWire("one_at_a_time"))
        assertEquals(SurfaceMode.OFF, SurfaceMode.fromWire("off"))
        assertEquals(SurfaceMode.OFF, SurfaceMode.fromWire(null))
        assertEquals(SurfaceMode.OFF, SurfaceMode.fromWire("garbage"))
    }

    @Test
    fun `BlockableSurface byPackage resolves known packages only`() {
        assertSame(BlockableSurface.INSTAGRAM_REELS, BlockableSurface.byPackage("com.instagram.android"))
        assertSame(BlockableSurface.YOUTUBE_SHORTS, BlockableSurface.byPackage("com.google.android.youtube"))
        assertNull(BlockableSurface.byPackage("com.whatsapp"))
    }

    @Test
    fun `reset mid-viewer returns to idle`() {
        val t = tracker()
        t.onViewerVisible(reels, SurfaceMode.ONE_AT_A_TIME, 0L)
        t.onPagerScrolled(reels, SurfaceMode.ONE_AT_A_TIME, fromIndex = -1, toIndex = 0, scrollDeltaY = 0, nowMs = 10L)
        t.reset()
        // After reset, an advance-shaped scroll is treated as a fresh entry, not a block.
        assertEquals(
            SurfaceDecision.ALLOW,
            t.onPagerScrolled(reels, SurfaceMode.ONE_AT_A_TIME, fromIndex = 5, toIndex = 5, scrollDeltaY = 0, nowMs = 20L)
        )
    }
}
