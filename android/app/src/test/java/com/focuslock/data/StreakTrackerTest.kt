package com.focuslock.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreakTrackerTest {

    private val empty = StreakTracker.StreakState()

    @Test
    fun `increments on under-limit day`() {
        val result = StreakTracker.recordDay(
            usedMinutes = 30.0, effectiveLimit = 60,
            prior = empty.copy(currentStreak = 3, longestStreak = 5, lastRecordedDate = "2026-07-24"),
            dateStr = "2026-07-25"
        )
        assertEquals(4, result.currentStreak)
        assertEquals(5, result.longestStreak)
        assertEquals("2026-07-25", result.lastSuccessDate)
        assertEquals("2026-07-25", result.lastRecordedDate)
    }

    @Test
    fun `resets on over-limit day`() {
        val result = StreakTracker.recordDay(
            usedMinutes = 90.0, effectiveLimit = 60,
            prior = empty.copy(currentStreak = 5, longestStreak = 5, lastRecordedDate = "2026-07-24"),
            dateStr = "2026-07-25"
        )
        assertEquals(0, result.currentStreak)
        assertEquals(5, result.longestStreak)
    }

    @Test
    fun `longestStreak is monotonically non-decreasing`() {
        var state = empty
        state = StreakTracker.recordDay(10.0, 60, state, "2026-07-20")
        state = StreakTracker.recordDay(10.0, 60, state, "2026-07-21")
        state = StreakTracker.recordDay(10.0, 60, state, "2026-07-22")
        assertEquals(3, state.currentStreak)
        assertEquals(3, state.longestStreak)

        // breaks the streak
        state = StreakTracker.recordDay(90.0, 60, state, "2026-07-23")
        assertEquals(0, state.currentStreak)
        assertEquals(3, state.longestStreak)

        // starts a new, shorter streak — longest must not regress
        state = StreakTracker.recordDay(10.0, 60, state, "2026-07-24")
        assertEquals(1, state.currentStreak)
        assertEquals(3, state.longestStreak)
    }

    @Test
    fun `idempotent re-run for the same date is a no-op`() {
        val prior = empty.copy(currentStreak = 2, longestStreak = 2, lastRecordedDate = "2026-07-25")
        // Same day already recorded — usedMinutes/effectiveLimit here would break
        // the streak if applied, but the idempotency guard must skip entirely.
        val result = StreakTracker.recordDay(999.0, 60, prior, "2026-07-25")
        assertEquals(prior, result)

        // An earlier date than lastRecordedDate is also a no-op (out-of-order call).
        val resultEarlier = StreakTracker.recordDay(10.0, 60, prior, "2026-07-24")
        assertEquals(prior, resultEarlier)
    }

    @Test
    fun `effectiveLimit 0 always counts as kept`() {
        assertTrue(StreakTracker.isUnderLimit(usedMinutes = 100_000.0, effectiveLimit = 0))
        val result = StreakTracker.recordDay(
            usedMinutes = 100_000.0, effectiveLimit = 0,
            prior = empty.copy(currentStreak = 1, longestStreak = 1, lastRecordedDate = "2026-07-24"),
            dateStr = "2026-07-25"
        )
        assertEquals(2, result.currentStreak)
    }

    @Test
    fun `rolling 30-day counters track met vs total days`() {
        var state = empty
        state = StreakTracker.recordDay(10.0, 60, state, "2026-07-01") // kept
        state = StreakTracker.recordDay(90.0, 60, state, "2026-07-02") // missed
        assertEquals(1, state.metGoalDaysLast30)
        assertEquals(2, state.totalDaysLast30)
    }
}
