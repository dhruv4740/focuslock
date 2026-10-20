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
    fun `friction_only mode never limit-blocks`() {
        val p = policy(hardBlockAfterLimit = true).copy(
            appModes = mapOf("com.instagram.android" to "friction_only")
        )
        val d = PolicyEngine.decide(p, PolicyEngine.UsageInputs(500.0, 0), now(), "com.instagram.android")
        assertTrue(d.allowNow)
        assertEquals("none", d.action)
        assertEquals(0, d.effectiveLimit)
    }

    @Test
    fun `hard_block mode locks at limit even with global soft setting`() {
        val p = policy(hardBlockAfterLimit = false).copy(
            appModes = mapOf("com.instagram.android" to "hard_block")
        )
        val d = PolicyEngine.decide(p, PolicyEngine.UsageInputs(60.0, 0), now(), "com.instagram.android")
        assertFalse(d.allowNow)
        assertEquals("hard-block", d.action)
    }

    @Test
    fun `legacy app without appModes entry behaves as limit mode`() {
        val d = PolicyEngine.decide(
            policy(hardBlockAfterLimit = true),
            PolicyEngine.UsageInputs(60.0, 0),
            now(),
            "com.some.app"
        )
        assertFalse(d.allowNow)
        assertEquals("hard-block", d.action)
    }

    @Test
    fun `friction_only still hard-blocks inside a block window`() {
        val windows = listOf(PolicyEngine.BlockWindow(0, 24 * 60, (0..6).toSet()))
        val p = policy(blockWindows = windows).copy(
            appModes = mapOf("com.instagram.android" to "friction_only")
        )
        val d = PolicyEngine.decide(p, PolicyEngine.UsageInputs(0.0, 0), now(), "com.instagram.android")
        assertFalse(d.allowNow)
        assertEquals("scheduled-block-window", d.reason)
    }

    @Test
    fun `block window boundary is start-inclusive end-exclusive`() {
        val windows = listOf(PolicyEngine.BlockWindow(startMin = 600, endMin = 660, days = setOf(1)))
        val atStart = PolicyEngine.decide(policy(blockWindows = windows), PolicyEngine.UsageInputs(0.0, 0), now(minuteOfDay = 600))
        val atEnd = PolicyEngine.decide(policy(blockWindows = windows), PolicyEngine.UsageInputs(0.0, 0), now(minuteOfDay = 660))
        assertFalse(atStart.allowNow)
        assertTrue(atEnd.allowNow)
    }

    // ── Category budgets ──────────────────────────────────────────────────────

    private val games = AppCategory("Games", setOf("com.game"), dailyLimitMinutes = 30)

    private fun withGames(cat: AppCategory = games, hardBlockAfterLimit: Boolean = true) =
        policy(hardBlockAfterLimit = hardBlockAfterLimit).copy(categories = mapOf("games" to cat))

    @Test
    fun `category under budget is allowed and reports the tighter category budget`() {
        val d = PolicyEngine.decide(
            withGames(), PolicyEngine.UsageInputs(30.0, 0, mapOf("games" to 10.0)), now(), "com.game"
        )
        assertTrue(d.allowNow)
        assertEquals("none", d.action)
        assertEquals(30, d.effectiveLimit)
        assertEquals(10.0, d.usedMinutes, 0.0)
    }

    @Test
    fun `category over budget locks even though global pool has room`() {
        val d = PolicyEngine.decide(
            withGames(), PolicyEngine.UsageInputs(35.0, 0, mapOf("games" to 30.0)), now(), "com.game"
        )
        assertFalse(d.allowNow)
        assertEquals("hard-block", d.action)
        assertEquals("category-limit-reached", d.reason)
    }

    @Test
    fun `category budget does not affect apps outside the category`() {
        val d = PolicyEngine.decide(
            withGames(), PolicyEngine.UsageInputs(35.0, 0, mapOf("games" to 30.0)), now(), "com.other"
        )
        assertTrue(d.allowNow)
        assertEquals(60, d.effectiveLimit)
    }

    @Test
    fun `global limit still locks a category app whose category budget has room`() {
        val d = PolicyEngine.decide(
            withGames(), PolicyEngine.UsageInputs(60.0, 0, mapOf("games" to 5.0)), now(), "com.game"
        )
        assertFalse(d.allowNow)
        assertEquals("daily-limit-reached", d.reason)
    }

    @Test
    fun `both exhausted reports the global limit first`() {
        val d = PolicyEngine.decide(
            withGames(), PolicyEngine.UsageInputs(70.0, 0, mapOf("games" to 40.0)), now(), "com.game"
        )
        assertEquals("daily-limit-reached", d.reason)
    }

    @Test
    fun `hard category wins over a soft global limit`() {
        val cat = games.copy(mode = AppMode.HARD_BLOCK)
        val d = PolicyEngine.decide(
            withGames(cat, hardBlockAfterLimit = false),
            PolicyEngine.UsageInputs(70.0, 0, mapOf("games" to 40.0)), now(), "com.game"
        )
        assertFalse(d.allowNow)
        assertEquals("category-limit-reached", d.reason)
    }

    @Test
    fun `soft category over budget allows with soft-limit when hard block is off`() {
        val d = PolicyEngine.decide(
            withGames(hardBlockAfterLimit = false),
            PolicyEngine.UsageInputs(35.0, 0, mapOf("games" to 30.0)), now(), "com.game"
        )
        assertTrue(d.allowNow)
        assertEquals("soft-limit", d.action)
        assertEquals("category-limit-reached-soft", d.reason)
    }

    @Test
    fun `friction-only category has no budget`() {
        val d = PolicyEngine.decide(
            withGames(games.copy(mode = AppMode.FRICTION_ONLY)),
            PolicyEngine.UsageInputs(35.0, 0, mapOf("games" to 300.0)), now(), "com.game"
        )
        assertTrue(d.allowNow)
    }

    @Test
    fun `friction-only app mode exempts the app from its category budget`() {
        val p = withGames().copy(appModes = mapOf("com.game" to "friction_only"))
        val d = PolicyEngine.decide(p, PolicyEngine.UsageInputs(35.0, 0, mapOf("games" to 300.0)), now(), "com.game")
        assertTrue(d.allowNow)
    }

    @Test
    fun `extensions raise the category budget too`() {
        // 30 + 1 x 15 = 45; 40 used is under.
        val d = PolicyEngine.decide(
            withGames(), PolicyEngine.UsageInputs(40.0, 1, mapOf("games" to 40.0)), now(), "com.game"
        )
        assertTrue(d.allowNow)
        assertEquals(45, d.effectiveLimit)
    }

    @Test
    fun `no categories behaves exactly like the global-only policy`() {
        // Migration: an old doc has no categories; category usage is ignored.
        val p = policy()
        assertTrue(p.categories.isEmpty())
        val under = PolicyEngine.decide(p, PolicyEngine.UsageInputs(30.0, 0, mapOf("games" to 999.0)), now(), "com.game")
        assertTrue(under.allowNow)
        assertEquals(60, under.effectiveLimit)
        assertEquals(30.0, under.usedMinutes, 0.0)
        val over = PolicyEngine.decide(p, PolicyEngine.UsageInputs(60.0, 0), now(), "com.game")
        assertFalse(over.allowNow)
        assertEquals("daily-limit-reached", over.reason)
    }

    // ── Overall phone cap ─────────────────────────────────────────────────────

    private val cap = OverallCap(dailyLimitMinutes = 120, mode = AppMode.FRICTION_ONLY, allowList = setOf("com.bank"))

    @Test
    fun `overall cap under the limit does nothing`() {
        assertEquals(PolicyEngine.CapAction.NONE, PolicyEngine.decideOverallCap(cap, 119.0, "com.app"))
    }

    @Test
    fun `overall cap over the limit in friction mode asks for friction`() {
        assertEquals(PolicyEngine.CapAction.FRICTION, PolicyEngine.decideOverallCap(cap, 120.0, "com.app"))
    }

    @Test
    fun `overall cap over the limit in hard block mode locks`() {
        val hard = cap.copy(mode = AppMode.HARD_BLOCK)
        assertEquals(PolicyEngine.CapAction.LOCK, PolicyEngine.decideOverallCap(hard, 150.0, "com.app"))
    }

    @Test
    fun `overall cap never touches allow-listed apps`() {
        val hard = cap.copy(mode = AppMode.HARD_BLOCK)
        assertEquals(PolicyEngine.CapAction.NONE, PolicyEngine.decideOverallCap(hard, 500.0, "com.bank"))
    }

    @Test
    fun `overall cap with no limit or off mode is disabled`() {
        assertEquals(PolicyEngine.CapAction.NONE, PolicyEngine.decideOverallCap(cap.copy(dailyLimitMinutes = 0), 500.0, "com.app"))
        assertEquals(PolicyEngine.CapAction.NONE, PolicyEngine.decideOverallCap(cap.copy(mode = AppMode.OFF), 500.0, "com.app"))
    }
}
