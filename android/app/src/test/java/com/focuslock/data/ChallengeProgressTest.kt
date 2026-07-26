package com.focuslock.data

import com.focuslock.data.ChallengeProgress.ChallengeType
import com.focuslock.data.ChallengeProgress.ChallengeProgressState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChallengeProgressTest {

    private val empty = ChallengeProgressState()

    @Test
    fun `no_reels — a single bad day permanently eliminates, no comeback`() {
        var state = empty
        state = ChallengeProgress.recordDay(ChallengeType.NO_REELS, metGoalToday = true, prior = state, dateStr = "2026-07-20", endDate = "2026-07-26")
        assertTrue(state.stillIn)

        // A bad day (reels watched) flips stillIn false.
        state = ChallengeProgress.recordDay(ChallengeType.NO_REELS, metGoalToday = false, prior = state, dateStr = "2026-07-21", endDate = "2026-07-26")
        assertFalse(state.stillIn)

        // Subsequent good days do NOT reinstate — permanent elimination.
        state = ChallengeProgress.recordDay(ChallengeType.NO_REELS, metGoalToday = true, prior = state, dateStr = "2026-07-22", endDate = "2026-07-26")
        assertFalse(state.stillIn)
        state = ChallengeProgress.recordDay(ChallengeType.NO_REELS, metGoalToday = true, prior = state, dateStr = "2026-07-23", endDate = "2026-07-26")
        assertFalse(state.stillIn)

        assertEquals(4, state.daysTotal)
        assertEquals(3, state.daysMet)
    }

    @Test
    fun `under_limit — tracks daysMet over daysTotal as a running ratio, never eliminates`() {
        var state = empty
        state = ChallengeProgress.recordDay(ChallengeType.UNDER_LIMIT, metGoalToday = true, prior = state, dateStr = "2026-07-20", endDate = "2026-07-26")
        state = ChallengeProgress.recordDay(ChallengeType.UNDER_LIMIT, metGoalToday = false, prior = state, dateStr = "2026-07-21", endDate = "2026-07-26")
        state = ChallengeProgress.recordDay(ChallengeType.UNDER_LIMIT, metGoalToday = true, prior = state, dateStr = "2026-07-22", endDate = "2026-07-26")

        // A missed day never flips stillIn for under_limit — it's a counter, not a streak.
        assertTrue(state.stillIn)
        assertEquals(3, state.daysTotal)
        assertEquals(2, state.daysMet)
    }

    @Test
    fun `idempotent re-run for the same date is a no-op`() {
        val prior = empty.copy(daysMet = 2, daysTotal = 2, stillIn = true, lastRecordedDate = "2026-07-25")
        // Same day re-recorded with a different outcome must not change anything.
        val result = ChallengeProgress.recordDay(ChallengeType.NO_REELS, metGoalToday = false, prior = prior, dateStr = "2026-07-25", endDate = "2026-07-30")
        assertEquals(prior, result)

        // An earlier date than lastRecordedDate is also a no-op (out-of-order call).
        val resultEarlier = ChallengeProgress.recordDay(ChallengeType.NO_REELS, metGoalToday = false, prior = prior, dateStr = "2026-07-24", endDate = "2026-07-30")
        assertEquals(prior, resultEarlier)
    }

    @Test
    fun `no progress recorded once endDate has passed`() {
        val prior = empty.copy(daysMet = 3, daysTotal = 3, stillIn = true, lastRecordedDate = "2026-07-25")
        val result = ChallengeProgress.recordDay(ChallengeType.UNDER_LIMIT, metGoalToday = true, prior = prior, dateStr = "2026-07-27", endDate = "2026-07-26")
        assertEquals(prior, result)
    }

    @Test
    fun `ChallengeType wire round-trips`() {
        assertEquals(ChallengeType.NO_REELS, ChallengeType.fromWire("no_reels"))
        assertEquals(ChallengeType.UNDER_LIMIT, ChallengeType.fromWire("under_limit"))
        assertEquals(ChallengeType.NO_REELS, ChallengeType.fromWire("garbage"))
    }
}
