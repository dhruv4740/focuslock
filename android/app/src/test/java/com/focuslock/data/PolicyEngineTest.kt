package com.focuslock.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyEngineTest {

    private fun policy(
        dailyLimitMinutes: Int = 60,
        extensionMinutes: Int = 15,
        hardBlockAfterLimit: Boolean = true,
        blockWindows: List<PolicyEngine.BlockWindow> = emptyList()
    ) = PolicyEngine.PolicyInputs(dailyLimitMinutes, extensionMinutes, hardBlockAfterLimit, blockWindows)

    private fun now(
        dayOfWeek: Int = 1,
        minuteOfDay: Int = 12 * 60,
        focusSessionActive: Boolean = false
    ) = PolicyEngine.Now(dayOfWeek, minuteOfDay, focusSessionActive)

    @Test
    fun `under the limit is allowed`() {
        val d = PolicyEngine.decide(policy(), PolicyEngine.UsageInputs(30.0, 0), now())
        assertTrue(d.allowNow)
        assertEquals("none", d.action)
    }

    @Test
    fun `no limit configured is always allowed`() {
        val d = PolicyEngine.decide(policy(dailyLimitMinutes = 0), PolicyEngine.UsageInputs(500.0, 0), now())
        assertTrue(d.allowNow)
        assertEquals("none", d.action)
    }

    @Test
    fun `over the limit with hard block locks`() {
        val d = PolicyEngine.decide(policy(hardBlockAfterLimit = true), PolicyEngine.UsageInputs(60.0, 0), now())
        assertFalse(d.allowNow)
        assertEquals("hard-block", d.action)
        assertEquals("daily-limit-reached", d.reason)
    }

    @Test
    fun `over the limit with soft block allows with soft-limit action`() {
        val d = PolicyEngine.decide(policy(hardBlockAfterLimit = false), PolicyEngine.UsageInputs(60.0, 0), now())
        assertTrue(d.allowNow)
        assertEquals("soft-limit", d.action)
        assertEquals("daily-limit-reached-soft", d.reason)
        assertFalse(d.hardBlockAfterLimit)
    }

    @Test
    fun `focus session hard-blocks regardless of limit or hard-block setting`() {
        val d = PolicyEngine.decide(
            policy(dailyLimitMinutes = 0, hardBlockAfterLimit = false),
            PolicyEngine.UsageInputs(0.0, 0),
            now(focusSessionActive = true)
        )
        assertFalse(d.allowNow)
        assertEquals("hard-block", d.action)
        assertEquals("focus-session-active", d.reason)
    }

    @Test
    fun `inside a block window hard-blocks`() {
        val windows = listOf(PolicyEngine.BlockWindow(startMin = 9 * 60, endMin = 17 * 60, days = setOf(1)))
        val d = PolicyEngine.decide(
            policy(blockWindows = windows, hardBlockAfterLimit = false),
            PolicyEngine.UsageInputs(0.0, 0),
            now(dayOfWeek = 1, minuteOfDay = 12 * 60)
        )
        assertFalse(d.allowNow)
        assertEquals("scheduled-block-window", d.reason)
    }

    @Test
    fun `outside a block window's days is allowed`() {
        val windows = listOf(PolicyEngine.BlockWindow(startMin = 9 * 60, endMin = 17 * 60, days = setOf(1)))
        val d = PolicyEngine.decide(
            policy(blockWindows = windows),
            PolicyEngine.UsageInputs(0.0, 0),
            now(dayOfWeek = 2, minuteOfDay = 12 * 60)
        )
        assertTrue(d.allowNow)
    }

    @Test
    fun `block window applies to reels-managed apps too`() {
        // Regression for the removed surface-managed exemption: passing a package
        // that maps to a managed surface no longer bypasses the block window.
        val windows = listOf(PolicyEngine.BlockWindow(startMin = 0, endMin = 24 * 60, days = (0..6).toSet()))
        val d = PolicyEngine.decide(
            policy(blockWindows = windows),
            PolicyEngine.UsageInputs(0.0, 0),
            now(),
            packageName = "com.instagram.android"
        )
        assertFalse(d.allowNow)
        assertEquals("scheduled-block-window", d.reason)
    }

    @Test
    fun `extensions raise the effective limit`() {
        // 60 base + 2 x 15 extension = 90; 75 used is still under.
        val d = PolicyEngine.decide(policy(), PolicyEngine.UsageInputs(75.0, 2), now())
        assertTrue(d.allowNow)
        assertEquals(90, d.effectiveLimit)
    }

    @Test
    fun `block window boundary is start-inclusive end-exclusive`() {
        val windows = listOf(PolicyEngine.BlockWindow(startMin = 600, endMin = 660, days = setOf(1)))
        val atStart = PolicyEngine.decide(policy(blockWindows = windows), PolicyEngine.UsageInputs(0.0, 0), now(minuteOfDay = 600))
        val atEnd = PolicyEngine.decide(policy(blockWindows = windows), PolicyEngine.UsageInputs(0.0, 0), now(minuteOfDay = 660))
        assertFalse(atStart.allowNow)
        assertTrue(atEnd.allowNow)
    }
}
