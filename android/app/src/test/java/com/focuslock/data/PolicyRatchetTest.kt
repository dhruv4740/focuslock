package com.focuslock.data

import com.focuslock.surface.SurfaceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyRatchetTest {

    private fun base() = PolicySnapshot()

    private fun directionOf(old: PolicySnapshot, new: PolicySnapshot, field: String): ChangeDirection? =
        PolicyRatchet.diff(old, new).firstOrNull { it.field == field }?.direction

    // ── dailyLimitMinutes ───────────────────────────────────────────────────

    @Test
    fun `dailyLimitMinutes lower is a tighten`() {
        val d = directionOf(base().copy(dailyLimitMinutes = 60), base().copy(dailyLimitMinutes = 30), "dailyLimitMinutes")
        assertEquals(ChangeDirection.TIGHTEN, d)
    }

    @Test
    fun `dailyLimitMinutes higher is a loosen`() {
        val d = directionOf(base().copy(dailyLimitMinutes = 30), base().copy(dailyLimitMinutes = 60), "dailyLimitMinutes")
        assertEquals(ChangeDirection.LOOSEN, d)
    }

    @Test
    fun `dailyLimitMinutes 30 to 0 is a loosen — 0 means no limit`() {
        val d = directionOf(base().copy(dailyLimitMinutes = 30), base().copy(dailyLimitMinutes = 0), "dailyLimitMinutes")
        assertEquals(ChangeDirection.LOOSEN, d)
    }

    @Test
    fun `dailyLimitMinutes 0 to 30 is a tighten — imposing a limit`() {
        val d = directionOf(base().copy(dailyLimitMinutes = 0), base().copy(dailyLimitMinutes = 30), "dailyLimitMinutes")
        assertEquals(ChangeDirection.TIGHTEN, d)
    }

    // ── hardBlockAfterLimit ─────────────────────────────────────────────────

    @Test
    fun `hardBlockAfterLimit false to true is a tighten`() {
        val d = directionOf(base().copy(hardBlockAfterLimit = false), base().copy(hardBlockAfterLimit = true), "hardBlockAfterLimit")
        assertEquals(ChangeDirection.TIGHTEN, d)
    }

    @Test
    fun `hardBlockAfterLimit true to false is a loosen`() {
        val d = directionOf(base().copy(hardBlockAfterLimit = true), base().copy(hardBlockAfterLimit = false), "hardBlockAfterLimit")
        assertEquals(ChangeDirection.LOOSEN, d)
    }

    // ── extensionMinutes ────────────────────────────────────────────────────

    @Test
    fun `extensionMinutes higher is a loosen`() {
        val d = directionOf(base().copy(extensionMinutes = 15), base().copy(extensionMinutes = 30), "extensionMinutes")
        assertEquals(ChangeDirection.LOOSEN, d)
    }

    @Test
    fun `extensionMinutes lower is a tighten`() {
        val d = directionOf(base().copy(extensionMinutes = 30), base().copy(extensionMinutes = 15), "extensionMinutes")
        assertEquals(ChangeDirection.TIGHTEN, d)
    }

    // ── dailyExtensionLimit ─────────────────────────────────────────────────

    @Test
    fun `dailyExtensionLimit higher is a loosen`() {
        val d = directionOf(base().copy(dailyExtensionLimit = 1), base().copy(dailyExtensionLimit = 3), "dailyExtensionLimit")
        assertEquals(ChangeDirection.LOOSEN, d)
    }

    @Test
    fun `dailyExtensionLimit lower is a tighten`() {
        val d = directionOf(base().copy(dailyExtensionLimit = 3), base().copy(dailyExtensionLimit = 1), "dailyExtensionLimit")
        assertEquals(ChangeDirection.TIGHTEN, d)
    }

    // ── targetApps ──────────────────────────────────────────────────────────

    @Test
    fun `targetApps removing an app is a loosen`() {
        val d = directionOf(
            base().copy(targetApps = setOf("a", "b")),
            base().copy(targetApps = setOf("a")),
            "targetApps.b"
        )
        assertEquals(ChangeDirection.LOOSEN, d)
    }

    @Test
    fun `targetApps adding an app is a tighten`() {
        val d = directionOf(
            base().copy(targetApps = setOf("a")),
            base().copy(targetApps = setOf("a", "b")),
            "targetApps.b"
        )
        assertEquals(ChangeDirection.TIGHTEN, d)
    }

    @Test
    fun `adding and removing a target app in one save applies the addition immediately`() {
        val old = base().copy(targetApps = setOf("a", "b"))
        val new = base().copy(targetApps = setOf("a", "c")) // drop b, add c

        val result = PolicyRatchet.split(old, new)

        assertTrue("newly added app c must be enforced now", "c" in result.applyNow.targetApps)
        assertTrue("removed app b must stay enforced until the delay elapses", "b" in result.applyNow.targetApps)
        assertEquals(new, result.deferred)
        assertTrue(result.deferredChanges.any { it.field == "targetApps.b" && it.direction == ChangeDirection.LOOSEN })
        assertFalse(result.deferredChanges.any { it.field == "targetApps.c" })
    }

    @Test
    fun `removing a target app is deferred`() {
        val old = base().copy(targetApps = setOf("a", "b"))
        val new = base().copy(targetApps = setOf("a"))

        val result = PolicyRatchet.split(old, new)

        assertTrue("b must remain enforced (in applyNow) until the delay elapses", "b" in result.applyNow.targetApps)
        assertEquals(new, result.deferred)
        assertEquals(1, result.deferredChanges.size)
        assertEquals("targetApps.b", result.deferredChanges.first().field)
    }

    // ── appModes ────────────────────────────────────────────────────────────

    @Test
    fun `appModes escalating OFF to HARD_BLOCK is a tighten`() {
        val d = directionOf(
            base().copy(appModes = mapOf("pkg" to AppMode.OFF)),
            base().copy(appModes = mapOf("pkg" to AppMode.HARD_BLOCK)),
            "appModes.pkg"
        )
        assertEquals(ChangeDirection.TIGHTEN, d)
    }

    @Test
    fun `appModes de-escalating HARD_BLOCK to FRICTION_ONLY is a loosen`() {
        val d = directionOf(
            base().copy(appModes = mapOf("pkg" to AppMode.HARD_BLOCK)),
            base().copy(appModes = mapOf("pkg" to AppMode.FRICTION_ONLY)),
            "appModes.pkg"
        )
        assertEquals(ChangeDirection.LOOSEN, d)
    }

    @Test
    fun `appModes adding a key from absent to LIMIT is a tighten — absent behaves as OFF`() {
        val d = directionOf(
            base().copy(appModes = emptyMap()),
            base().copy(appModes = mapOf("pkg" to AppMode.LIMIT)),
            "appModes.pkg"
        )
        assertEquals(ChangeDirection.TIGHTEN, d)
    }

    @Test
    fun `appModes removing a key from LIMIT to absent is a loosen`() {
        val d = directionOf(
            base().copy(appModes = mapOf("pkg" to AppMode.LIMIT)),
            base().copy(appModes = emptyMap()),
            "appModes.pkg"
        )
        assertEquals(ChangeDirection.LOOSEN, d)
    }

    // ── blockedSurfaces ─────────────────────────────────────────────────────

    @Test
    fun `blockedSurfaces escalating OFF to BLOCK is a tighten`() {
        val d = directionOf(
            base().copy(blockedSurfaces = mapOf("instagram_reels" to SurfaceMode.OFF)),
            base().copy(blockedSurfaces = mapOf("instagram_reels" to SurfaceMode.BLOCK)),
            "blockedSurfaces.instagram_reels"
        )
        assertEquals(ChangeDirection.TIGHTEN, d)
    }

    @Test
    fun `blockedSurfaces de-escalating ONE_AT_A_TIME to OFF is a loosen`() {
        val d = directionOf(
            base().copy(blockedSurfaces = mapOf("instagram_reels" to SurfaceMode.ONE_AT_A_TIME)),
            base().copy(blockedSurfaces = mapOf("instagram_reels" to SurfaceMode.OFF)),
            "blockedSurfaces.instagram_reels"
        )
        assertEquals(ChangeDirection.LOOSEN, d)
    }

    @Test
    fun `blockedSurfaces adding a key from absent to QUOTA is a tighten`() {
        val d = directionOf(
            base().copy(blockedSurfaces = emptyMap()),
            base().copy(blockedSurfaces = mapOf("youtube_shorts" to SurfaceMode.QUOTA)),
            "blockedSurfaces.youtube_shorts"
        )
        assertEquals(ChangeDirection.TIGHTEN, d)
    }

    @Test
    fun `blockedSurfaces removing a key from QUOTA to absent is a loosen`() {
        val d = directionOf(
            base().copy(blockedSurfaces = mapOf("youtube_shorts" to SurfaceMode.QUOTA)),
            base().copy(blockedSurfaces = emptyMap()),
            "blockedSurfaces.youtube_shorts"
        )
        assertEquals(ChangeDirection.LOOSEN, d)
    }

    // ── reelDailyLimit (0 = no limit inversion) ────────────────────────────

    @Test
    fun `reelDailyLimit lower is a tighten`() {
        val d = directionOf(
            base().copy(reelDailyLimit = mapOf("instagram_reels" to 60)),
            base().copy(reelDailyLimit = mapOf("instagram_reels" to 20)),
            "reelDailyLimit.instagram_reels"
        )
        assertEquals(ChangeDirection.TIGHTEN, d)
    }

    @Test
    fun `reelDailyLimit higher is a loosen`() {
        val d = directionOf(
            base().copy(reelDailyLimit = mapOf("instagram_reels" to 20)),
            base().copy(reelDailyLimit = mapOf("instagram_reels" to 60)),
            "reelDailyLimit.instagram_reels"
        )
        assertEquals(ChangeDirection.LOOSEN, d)
    }

    @Test
    fun `reelDailyLimit 30 to 0 is a loosen — 0 means no limit`() {
        val d = directionOf(
            base().copy(reelDailyLimit = mapOf("instagram_reels" to 30)),
            base().copy(reelDailyLimit = mapOf("instagram_reels" to 0)),
            "reelDailyLimit.instagram_reels"
        )
        assertEquals(ChangeDirection.LOOSEN, d)
    }

    @Test
    fun `reelDailyLimit 0 to 30 is a tighten`() {
        val d = directionOf(
            base().copy(reelDailyLimit = mapOf("instagram_reels" to 0)),
            base().copy(reelDailyLimit = mapOf("instagram_reels" to 30)),
            "reelDailyLimit.instagram_reels"
        )
        assertEquals(ChangeDirection.TIGHTEN, d)
    }

    // ── sessionMaxMinutes (0 = disabled inversion) ─────────────────────────

    @Test
    fun `sessionMaxMinutes lower is a tighten`() {
        val d = directionOf(base().copy(sessionMaxMinutes = 30), base().copy(sessionMaxMinutes = 15), "sessionMaxMinutes")
        assertEquals(ChangeDirection.TIGHTEN, d)
    }

    @Test
    fun `sessionMaxMinutes higher is a loosen`() {
        val d = directionOf(base().copy(sessionMaxMinutes = 15), base().copy(sessionMaxMinutes = 30), "sessionMaxMinutes")
        assertEquals(ChangeDirection.LOOSEN, d)
    }

    @Test
    fun `sessionMaxMinutes 30 to 0 is a loosen — 0 means disabled`() {
        val d = directionOf(base().copy(sessionMaxMinutes = 30), base().copy(sessionMaxMinutes = 0), "sessionMaxMinutes")
        assertEquals(ChangeDirection.LOOSEN, d)
    }

    @Test
    fun `sessionMaxMinutes 0 to 30 is a tighten`() {
        val d = directionOf(base().copy(sessionMaxMinutes = 0), base().copy(sessionMaxMinutes = 30), "sessionMaxMinutes")
        assertEquals(ChangeDirection.TIGHTEN, d)
    }

    // ── sessionBreakMinutes (0 = disabled, but inverse direction otherwise) ─

    @Test
    fun `sessionBreakMinutes 0 to 5 is a tighten`() {
        val d = directionOf(base().copy(sessionBreakMinutes = 0), base().copy(sessionBreakMinutes = 5), "sessionBreakMinutes")
        assertEquals(ChangeDirection.TIGHTEN, d)
    }

    @Test
    fun `sessionBreakMinutes 5 to 0 is a loosen`() {
        val d = directionOf(base().copy(sessionBreakMinutes = 5), base().copy(sessionBreakMinutes = 0), "sessionBreakMinutes")
        assertEquals(ChangeDirection.LOOSEN, d)
    }

    @Test
    fun `sessionBreakMinutes 5 to 2 is a loosen — shorter break is less strict`() {
        val d = directionOf(base().copy(sessionBreakMinutes = 5), base().copy(sessionBreakMinutes = 2), "sessionBreakMinutes")
        assertEquals(ChangeDirection.LOOSEN, d)
    }

    @Test
    fun `sessionBreakMinutes 2 to 5 is a tighten — longer break is stricter`() {
        val d = directionOf(base().copy(sessionBreakMinutes = 2), base().copy(sessionBreakMinutes = 5), "sessionBreakMinutes")
        assertEquals(ChangeDirection.TIGHTEN, d)
    }

    // ── frictionSteps ───────────────────────────────────────────────────────

    @Test
    fun `frictionSteps removing a toggle is a loosen`() {
        val d = directionOf(
            base().copy(frictionSteps = setOf("enable_math", "enable_shake")),
            base().copy(frictionSteps = setOf("enable_math")),
            "frictionSteps"
        )
        assertEquals(ChangeDirection.LOOSEN, d)
    }

    @Test
    fun `frictionSteps adding a toggle is a tighten`() {
        val d = directionOf(
            base().copy(frictionSteps = setOf("enable_math")),
            base().copy(frictionSteps = setOf("enable_math", "enable_shake")),
            "frictionSteps"
        )
        assertEquals(ChangeDirection.TIGHTEN, d)
    }

    // ── mathDifficulty ──────────────────────────────────────────────────────

    @Test
    fun `mathDifficulty easy to hard is a tighten`() {
        val d = directionOf(base().copy(mathDifficulty = "easy"), base().copy(mathDifficulty = "hard"), "mathDifficulty")
        assertEquals(ChangeDirection.TIGHTEN, d)
    }

    @Test
    fun `mathDifficulty hard to easy is a loosen`() {
        val d = directionOf(base().copy(mathDifficulty = "hard"), base().copy(mathDifficulty = "easy"), "mathDifficulty")
        assertEquals(ChangeDirection.LOOSEN, d)
    }

    // ── blockWindows ────────────────────────────────────────────────────────

    @Test
    fun `blockWindows adding a window is a tighten`() {
        val old = base().copy(blockWindows = emptyList())
        val new = base().copy(blockWindows = listOf(BlockWindowSnapshot("22:00", "23:00", setOf(1, 2, 3, 4, 5))))
        val d = directionOf(old, new, "blockWindows")
        assertEquals(ChangeDirection.TIGHTEN, d)
    }

    @Test
    fun `blockWindows removing a window is a loosen`() {
        val old = base().copy(blockWindows = listOf(BlockWindowSnapshot("22:00", "23:00", setOf(1, 2, 3, 4, 5))))
        val new = base().copy(blockWindows = emptyList())
        val d = directionOf(old, new, "blockWindows")
        assertEquals(ChangeDirection.LOOSEN, d)
    }

    @Test
    fun `blockWindows shrinking the time range is a loosen`() {
        val old = base().copy(blockWindows = listOf(BlockWindowSnapshot("21:00", "23:00", setOf(1, 2, 3, 4, 5))))
        val new = base().copy(blockWindows = listOf(BlockWindowSnapshot("22:00", "23:00", setOf(1, 2, 3, 4, 5))))
        val d = directionOf(old, new, "blockWindows")
        assertEquals(ChangeDirection.LOOSEN, d)
    }

    @Test
    fun `blockWindows expanding the time range is a tighten`() {
        val old = base().copy(blockWindows = listOf(BlockWindowSnapshot("22:00", "23:00", setOf(1, 2, 3, 4, 5))))
        val new = base().copy(blockWindows = listOf(BlockWindowSnapshot("21:00", "23:00", setOf(1, 2, 3, 4, 5))))
        val d = directionOf(old, new, "blockWindows")
        assertEquals(ChangeDirection.TIGHTEN, d)
    }

    @Test
    fun `blockWindows removing a day is a loosen`() {
        val old = base().copy(blockWindows = listOf(BlockWindowSnapshot("22:00", "23:00", setOf(1, 2, 3, 4, 5))))
        val new = base().copy(blockWindows = listOf(BlockWindowSnapshot("22:00", "23:00", setOf(1, 2, 3, 4))))
        val d = directionOf(old, new, "blockWindows")
        assertEquals(ChangeDirection.LOOSEN, d)
    }

    @Test
    fun `blockWindows adding a day is a tighten`() {
        val old = base().copy(blockWindows = listOf(BlockWindowSnapshot("22:00", "23:00", setOf(1, 2, 3, 4))))
        val new = base().copy(blockWindows = listOf(BlockWindowSnapshot("22:00", "23:00", setOf(1, 2, 3, 4, 5))))
        val d = directionOf(old, new, "blockWindows")
        assertEquals(ChangeDirection.TIGHTEN, d)
    }

    @Test
    fun `adding a block window applies immediately even when another window is removed`() {
        val keep = BlockWindowSnapshot("22:00", "23:00", setOf(1, 2, 3, 4, 5)) // untouched
        val removedWindow = BlockWindowSnapshot("08:00", "09:00", setOf(1, 2, 3, 4, 5))
        val addedWindow = BlockWindowSnapshot("12:00", "13:00", setOf(1, 2, 3, 4, 5))

        val old = base().copy(blockWindows = listOf(keep, removedWindow))
        val new = base().copy(blockWindows = listOf(keep, addedWindow)) // drop removedWindow, add addedWindow

        val result = PolicyRatchet.split(old, new)

        assertTrue("new window must be enforced now", addedWindow in result.applyNow.blockWindows)
        assertTrue("removed window must keep blocking until the delay elapses", removedWindow in result.applyNow.blockWindows)
        assertEquals(new, result.deferred)
    }

    @Test
    fun `shrinking a block window is deferred while the rest of the list stays enforced`() {
        val keep = BlockWindowSnapshot("22:00", "23:00", setOf(1, 2, 3, 4, 5))
        val wide = BlockWindowSnapshot("08:00", "10:00", setOf(1, 2, 3, 4, 5))
        val narrow = BlockWindowSnapshot("09:00", "10:00", setOf(1, 2, 3, 4, 5)) // shrunk start

        val old = base().copy(blockWindows = listOf(keep, wide))
        val new = base().copy(blockWindows = listOf(keep, narrow))

        val result = PolicyRatchet.split(old, new)

        // Union keeps the wider (stricter) old window enforced until the
        // deferred shrink takes effect; the shrunk window alone would allow
        // 08:00-09:00 through immediately, which the ratchet must not do.
        assertTrue("original wide window must still be enforced", wide in result.applyNow.blockWindows)
        assertEquals(new, result.deferred)
        assertTrue(result.deferredChanges.any { it.field == "blockWindows" && it.direction == ChangeDirection.LOOSEN })
    }

    // ── invariant sweep ─────────────────────────────────────────────────────

    @Test
    fun `no tightening is ever placed in the deferred set`() {
        // A mixed edit touching several fields at once: some tighten, some
        // loosen, on purpose, so a regression that whole-field-defers a
        // tightening alongside a loosening would be caught here.
        val old = base().copy(
            dailyLimitMinutes = 60,               // will loosen to 90
            extensionMinutes = 30,                 // will tighten to 10
            targetApps = setOf("keep", "dropMe"),  // dropMe loosens, addMe tightens
            appModes = mapOf("keep" to AppMode.LIMIT, "dropMode" to AppMode.HARD_BLOCK), // dropMode loosens to OFF, keep stays
            blockWindows = listOf(
                BlockWindowSnapshot("22:00", "23:00", setOf(1, 2, 3, 4, 5)),
                BlockWindowSnapshot("08:00", "09:00", setOf(1, 2, 3, 4, 5)) // removed = loosen
            )
        )
        val new = base().copy(
            dailyLimitMinutes = 90,
            extensionMinutes = 10,
            targetApps = setOf("keep", "addMe"),
            appModes = mapOf("keep" to AppMode.LIMIT, "addMode" to AppMode.HARD_BLOCK),
            blockWindows = listOf(
                BlockWindowSnapshot("22:00", "23:00", setOf(1, 2, 3, 4, 5)),
                BlockWindowSnapshot("12:00", "13:00", setOf(1, 2, 3, 4, 5)) // new window = tighten
            )
        )

        val changes = PolicyRatchet.diff(old, new)
        val result = PolicyRatchet.split(old, new)

        val tighteningFields = changes.filter { it.direction == ChangeDirection.TIGHTEN }.map { it.field }.toSet()
        val deferredFields = result.deferredChanges.map { it.field }.toSet()

        assertTrue(
            "tightening fields leaked into deferredChanges: ${tighteningFields.intersect(deferredFields)}",
            tighteningFields.intersect(deferredFields).isEmpty()
        )

        // And the concrete tightenings really did land in applyNow.
        assertEquals(10, result.applyNow.extensionMinutes)
        assertTrue("addMe must be enforced now" , "addMe" in result.applyNow.targetApps)
        assertEquals(AppMode.HARD_BLOCK, result.applyNow.appModes["addMode"])
        assertTrue(
            "new block window must be enforced now",
            BlockWindowSnapshot("12:00", "13:00", setOf(1, 2, 3, 4, 5)) in result.applyNow.blockWindows
        )

        // And the loosenings really did NOT land in applyNow.
        assertEquals(60, result.applyNow.dailyLimitMinutes)
        assertTrue("dropMe must stay enforced until the delay elapses", "dropMe" in result.applyNow.targetApps)
        assertEquals(AppMode.HARD_BLOCK, result.applyNow.appModes["dropMode"])
        assertTrue(
            "removed block window must stay enforced until the delay elapses",
            BlockWindowSnapshot("08:00", "09:00", setOf(1, 2, 3, 4, 5)) in result.applyNow.blockWindows
        )
    }

    // ── classify / mixed edits ──────────────────────────────────────────────

    @Test
    fun `classify returns LOOSEN when any field loosens even if others tighten`() {
        val old = base().copy(dailyLimitMinutes = 60, extensionMinutes = 15)
        val new = base().copy(dailyLimitMinutes = 30, extensionMinutes = 30) // tighten + loosen
        assertEquals(ChangeDirection.LOOSEN, PolicyRatchet.classify(old, new))
    }

    @Test
    fun `classify returns TIGHTEN when only tightenings present`() {
        val old = base().copy(dailyLimitMinutes = 60, dailyExtensionLimit = 3)
        val new = base().copy(dailyLimitMinutes = 30, dailyExtensionLimit = 1)
        assertEquals(ChangeDirection.TIGHTEN, PolicyRatchet.classify(old, new))
    }

    @Test
    fun `classify returns NEUTRAL for identical snapshots`() {
        val old = base()
        val new = base()
        assertEquals(ChangeDirection.NEUTRAL, PolicyRatchet.classify(old, new))
        assertTrue(PolicyRatchet.diff(old, new).isEmpty())
    }

    @Test
    fun `split applies tightenings now and defers the loosening`() {
        val old = base().copy(dailyLimitMinutes = 60, extensionMinutes = 15, dailyExtensionLimit = 3)
        val new = base().copy(dailyLimitMinutes = 30, extensionMinutes = 30, dailyExtensionLimit = 1)

        val result = PolicyRatchet.split(old, new)

        // Tightenings applied immediately.
        assertEquals(30, result.applyNow.dailyLimitMinutes)
        assertEquals(1, result.applyNow.dailyExtensionLimit)
        // Loosening held back — applyNow keeps the old value.
        assertEquals(15, result.applyNow.extensionMinutes)

        assertEquals(new, result.deferred)
        assertEquals(1, result.deferredChanges.size)
        assertEquals("extensionMinutes", result.deferredChanges.first().field)
    }

    @Test
    fun `split with no loosening applies the full new snapshot and defers nothing`() {
        val old = base().copy(dailyLimitMinutes = 60)
        val new = base().copy(dailyLimitMinutes = 30)

        val result = PolicyRatchet.split(old, new)

        assertEquals(new, result.applyNow)
        assertNull(result.deferred)
        assertTrue(result.deferredChanges.isEmpty())
    }

    @Test
    fun `split with identical snapshots defers nothing`() {
        val old = base()
        val new = base()

        val result = PolicyRatchet.split(old, new)

        assertEquals(old, result.applyNow)
        assertNull(result.deferred)
    }

    // ── commitment lock ─────────────────────────────────────────────────────

    @Test
    fun `isLocked is true before the lock boundary`() {
        assertTrue(PolicyRatchet.isLocked(lockedUntilMs = 1_000L, nowMs = 500L))
    }

    @Test
    fun `isLocked is false exactly at the lock boundary`() {
        assertFalse(PolicyRatchet.isLocked(lockedUntilMs = 1_000L, nowMs = 1_000L))
    }

    @Test
    fun `isLocked is false after the lock boundary`() {
        assertFalse(PolicyRatchet.isLocked(lockedUntilMs = 1_000L, nowMs = 1_500L))
    }

    @Test
    fun `isLocked is false when no lock is set`() {
        assertFalse(PolicyRatchet.isLocked(lockedUntilMs = null, nowMs = 500L))
    }

    // ── delay resolution ────────────────────────────────────────────────────

    @Test
    fun `classifyDelayChange raising the delay is a tighten`() {
        assertEquals(ChangeDirection.TIGHTEN, PolicyRatchet.classifyDelayChange(oldHours = 24, newHours = 48))
    }

    @Test
    fun `classifyDelayChange lowering the delay is a loosen`() {
        assertEquals(ChangeDirection.LOOSEN, PolicyRatchet.classifyDelayChange(oldHours = 24, newHours = 12))
    }

    @Test
    fun `classifyDelayChange unchanged delay is neutral`() {
        assertEquals(ChangeDirection.NEUTRAL, PolicyRatchet.classifyDelayChange(oldHours = 24, newHours = 24))
    }

    @Test
    fun `effectiveAtMs adds the delay in hours to now`() {
        val now = 1_000_000L
        val effective = PolicyRatchet.effectiveAtMs(now, delayHours = 24)
        assertEquals(now + 24L * 60 * 60 * 1000, effective)
    }

    // ── isDue ────────────────────────────────────────────────────────────────

    @Test
    fun `isDue is false before the effective instant`() {
        assertFalse(PolicyRatchet.isDue(effectiveAtMs = 2_000L, nowMs = 1_999L))
    }

    @Test
    fun `isDue is true exactly at the effective instant`() {
        assertTrue(PolicyRatchet.isDue(effectiveAtMs = 2_000L, nowMs = 2_000L))
    }

    @Test
    fun `isDue is true after the effective instant`() {
        assertTrue(PolicyRatchet.isDue(effectiveAtMs = 2_000L, nowMs = 2_001L))
    }
}
