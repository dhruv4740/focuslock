package com.focuslock.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnforcementHealthTest {

    private val MIN = 60_000L
    private val THRESHOLD = 5 * MIN

    // ── shouldAllowFreshInstall ───────────────────────────────────────────────

    @Test
    fun `fresh install with no prior fetch is allowed`() {
        assertTrue(EnforcementHealth.shouldAllowFreshInstall(everFetchedPolicy = false))
    }

    @Test
    fun `device with a prior successful fetch is not treated as fresh`() {
        assertFalse(EnforcementHealth.shouldAllowFreshInstall(everFetchedPolicy = true))
    }

    // ── detectGap ─────────────────────────────────────────────────────────────

    @Test
    fun `first-ever run with no persisted heartbeat is not reported as a gap`() {
        val gap = EnforcementHealth.detectGap(
            nowMs = 100 * MIN, lastSeenAtMs = null, thresholdMs = THRESHOLD,
            bootCompletedAtMs = null, accessibilityEnabledNow = true
        )
        assertNull(gap)
    }

    @Test
    fun `no gap when the heartbeat is recent`() {
        val gap = EnforcementHealth.detectGap(
            nowMs = 10 * MIN, lastSeenAtMs = 9 * MIN, thresholdMs = THRESHOLD,
            bootCompletedAtMs = null, accessibilityEnabledNow = true
        )
        assertNull(gap)
    }

    @Test
    fun `exactly at the threshold boundary is not yet a gap`() {
        val gap = EnforcementHealth.detectGap(
            nowMs = 10 * MIN, lastSeenAtMs = 10 * MIN - THRESHOLD, thresholdMs = THRESHOLD,
            bootCompletedAtMs = null, accessibilityEnabledNow = true
        )
        assertNull(gap)
    }

    @Test
    fun `one ms past the threshold is a gap`() {
        val lastSeen = 10 * MIN - THRESHOLD - 1
        val gap = EnforcementHealth.detectGap(
            nowMs = 10 * MIN, lastSeenAtMs = lastSeen, thresholdMs = THRESHOLD,
            bootCompletedAtMs = null, accessibilityEnabledNow = true
        )
        assertTrue(gap != null)
        assertEquals(lastSeen, gap!!.startMs)
        assertEquals(10 * MIN, gap.endMs)
        assertEquals(THRESHOLD + 1, gap.durationMs)
    }

    @Test
    fun `a gap when the heartbeat is stale`() {
        val gap = EnforcementHealth.detectGap(
            nowMs = 100 * MIN, lastSeenAtMs = 10 * MIN, thresholdMs = THRESHOLD,
            bootCompletedAtMs = null, accessibilityEnabledNow = true
        )
        assertTrue(gap != null)
        assertEquals(90 * MIN, gap!!.durationMs)
    }

    // ── three-way classification ─────────────────────────────────────────────

    @Test
    fun `gap with accessibility still enabled is classified IDLE, not tampering`() {
        val gap = EnforcementHealth.detectGap(
            nowMs = 100 * MIN, lastSeenAtMs = 10 * MIN, thresholdMs = THRESHOLD,
            bootCompletedAtMs = null, accessibilityEnabledNow = true
        )
        assertEquals(EnforcementHealth.GapCause.IDLE, gap!!.cause)
        assertFalse("IDLE must never be reported as tampering", gap.isTampering)
    }

    @Test
    fun `gap with accessibility disabled is classified DISABLED`() {
        val gap = EnforcementHealth.detectGap(
            nowMs = 100 * MIN, lastSeenAtMs = 10 * MIN, thresholdMs = THRESHOLD,
            bootCompletedAtMs = null, accessibilityEnabledNow = false
        )
        assertEquals(EnforcementHealth.GapCause.DISABLED, gap!!.cause)
        assertTrue(gap.isTampering)
    }

    @Test
    fun `gap with a boot timestamp inside the window is classified EXPLAINED`() {
        val gap = EnforcementHealth.detectGap(
            nowMs = 100 * MIN, lastSeenAtMs = 10 * MIN, thresholdMs = THRESHOLD,
            bootCompletedAtMs = 50 * MIN, accessibilityEnabledNow = true
        )
        assertEquals(EnforcementHealth.GapCause.EXPLAINED, gap!!.cause)
    }

    @Test
    fun `EXPLAINED wins over DISABLED when both conditions hold`() {
        val gap = EnforcementHealth.detectGap(
            nowMs = 100 * MIN, lastSeenAtMs = 10 * MIN, thresholdMs = THRESHOLD,
            bootCompletedAtMs = 50 * MIN, accessibilityEnabledNow = false
        )
        assertEquals(EnforcementHealth.GapCause.EXPLAINED, gap!!.cause)
    }

    @Test
    fun `gap is unexplained-but-idle when the boot timestamp is outside the window`() {
        val gap = EnforcementHealth.detectGap(
            nowMs = 100 * MIN, lastSeenAtMs = 10 * MIN, thresholdMs = THRESHOLD,
            bootCompletedAtMs = 5 * MIN, accessibilityEnabledNow = true
        )
        assertEquals(EnforcementHealth.GapCause.IDLE, gap!!.cause)
    }

    @Test
    fun `observedDisabled is DISABLED with zero duration regardless of any gap`() {
        val observed = EnforcementHealth.observedDisabled(nowMs = 42 * MIN)
        assertEquals(EnforcementHealth.GapCause.DISABLED, observed.cause)
        assertEquals(0L, observed.durationMs)
        assertEquals(42 * MIN, observed.startMs)
        assertEquals(42 * MIN, observed.endMs)
        assertTrue(observed.isTampering)
    }

    @Test
    fun `no gap but accessibility disabled is still a DISABLED observation`() {
        // No heartbeat gap at all (recent heartbeat) — detectGap correctly
        // returns null here; the DISABLED signal comes from observedDisabled,
        // called independently by the service regardless of gap presence.
        val gap = EnforcementHealth.detectGap(
            nowMs = 10 * MIN, lastSeenAtMs = 9 * MIN, thresholdMs = THRESHOLD,
            bootCompletedAtMs = null, accessibilityEnabledNow = false
        )
        assertNull(gap)
        val observed = EnforcementHealth.observedDisabled(nowMs = 10 * MIN)
        assertEquals(EnforcementHealth.GapCause.DISABLED, observed.cause)
    }

    // ── closeOpenDisabledGap (onUnbind/onServiceConnected pairing) ───────────

    @Test
    fun `open DISABLED entry left unclosed stays open when process dies before reconnect`() {
        val opened = EnforcementHealth.observedDisabled(nowMs = 10 * MIN)
        val history = listOf(opened)
        // No closeOpenDisabledGap call — simulates the process dying before onServiceConnected.
        assertEquals(0L, history.first().durationMs)
        assertEquals(EnforcementHealth.GapCause.DISABLED, history.first().cause)
    }

    @Test
    fun `open DISABLED entry is closed by a later onServiceConnected with a real duration`() {
        val opened = EnforcementHealth.observedDisabled(nowMs = 10 * MIN)
        val history = listOf(opened)
        val closed = EnforcementHealth.closeOpenDisabledGap(history, nowMs = 30 * MIN, bootCompletedAtMs = null)
        assertEquals(1, closed.size)
        val entry = closed.first()
        assertEquals(10 * MIN, entry.startMs)
        assertEquals(30 * MIN, entry.endMs)
        assertEquals(20 * MIN, entry.durationMs)
        assertEquals(EnforcementHealth.GapCause.DISABLED, entry.cause)
    }

    @Test
    fun `closeOpenDisabledGap downgrades to EXPLAINED when a boot lands inside the closed span`() {
        val opened = EnforcementHealth.observedDisabled(nowMs = 10 * MIN)
        val history = listOf(opened)
        val closed = EnforcementHealth.closeOpenDisabledGap(history, nowMs = 30 * MIN, bootCompletedAtMs = 20 * MIN)
        assertEquals(EnforcementHealth.GapCause.EXPLAINED, closed.first().cause)
    }

    @Test
    fun `closeOpenDisabledGap is a no-op when there is no open entry`() {
        val closedAlready = EnforcementHealth.HeartbeatGap(0L, 20 * MIN, 20 * MIN, EnforcementHealth.GapCause.DISABLED)
        val idleGap = EnforcementHealth.HeartbeatGap(30 * MIN, 40 * MIN, 10 * MIN, EnforcementHealth.GapCause.IDLE)
        val history = listOf(idleGap, closedAlready)
        val result = EnforcementHealth.closeOpenDisabledGap(history, nowMs = 50 * MIN, bootCompletedAtMs = null)
        assertEquals(history, result)
    }

    @Test
    fun `closeOpenDisabledGap only closes the most recent open entry`() {
        val newerOpen = EnforcementHealth.observedDisabled(nowMs = 40 * MIN)
        val olderOpen = EnforcementHealth.observedDisabled(nowMs = 10 * MIN)
        // Newest-first, as recordGap produces.
        val history = listOf(newerOpen, olderOpen)
        val closed = EnforcementHealth.closeOpenDisabledGap(history, nowMs = 50 * MIN, bootCompletedAtMs = null)
        assertEquals(40 * MIN, closed[0].startMs) // still the newer entry, now closed
        assertEquals(10 * MIN, closed[0].durationMs) // 50 - 40 = 10
        assertEquals(0L, closed[1].durationMs) // olderOpen left untouched/open
    }

    // ── recordGap / pruning ───────────────────────────────────────────────────

    @Test
    fun `recordGap prepends the newest gap first`() {
        val g1 = EnforcementHealth.HeartbeatGap(0L, 1L, 1L, EnforcementHealth.GapCause.IDLE)
        val g2 = EnforcementHealth.HeartbeatGap(2L, 3L, 1L, EnforcementHealth.GapCause.IDLE)
        val history = EnforcementHealth.recordGap(listOf(g1), g2)
        assertEquals(listOf(g2, g1), history)
    }

    @Test
    fun `pruning keeps the most recent 20 and drops the oldest`() {
        val oldest = EnforcementHealth.HeartbeatGap(0L, 1L, 1L, EnforcementHealth.GapCause.IDLE)
        var history = listOf(oldest)
        for (i in 1..20) {
            history = EnforcementHealth.recordGap(
                history, EnforcementHealth.HeartbeatGap(i.toLong(), i.toLong(), 1L, EnforcementHealth.GapCause.IDLE)
            )
        }
        assertEquals(20, history.size)
        assertFalse(history.contains(oldest))
        // Most recent (i = 20) is first.
        assertEquals(20L, history.first().startMs)
    }
}
