package com.focuslock.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InsightsCalculatorTest {

    private fun day(date: String, label: String, minutes: Double) =
        FocusPolicyClient.DayUsage(date, label, minutes)

    @Test
    fun `week-over-week trend sums each week and computes the delta`() {
        val thisWeek = listOf(day("2026-07-20", "Mon", 30.0), day("2026-07-21", "Tue", 40.0))
        val lastWeek = listOf(day("2026-07-13", "Mon", 20.0), day("2026-07-14", "Tue", 20.0))
        val trend = InsightsCalculator.weekOverWeekTrend(thisWeek, lastWeek)
        assertEquals(70.0, trend.thisWeekMinutes, 0.001)
        assertEquals(40.0, trend.lastWeekMinutes, 0.001)
        assertEquals(30.0, trend.deltaMinutes, 0.001)
    }

    @Test
    fun `worst hour picks the hour with highest summed minutes across days`() {
        val days = listOf(
            mapOf(9 to 5.0, 21 to 12.0),
            mapOf(21 to 8.0, 22 to 3.0)
        )
        assertEquals(21, InsightsCalculator.worstHour(days))
    }

    @Test
    fun `worst hour is null when there is no usage at all`() {
        assertNull(InsightsCalculator.worstHour(emptyList()))
        assertNull(InsightsCalculator.worstHour(listOf(emptyMap())))
    }

    @Test
    fun `reels per unlock divides totals and is null with zero opens`() {
        assertEquals(2.5, InsightsCalculator.reelsPerUnlock(25, 10)!!, 0.001)
        assertNull(InsightsCalculator.reelsPerUnlock(5, 0))
    }

    @Test
    fun `best and worst day pick lowest and highest usedMinutes`() {
        val week = listOf(day("2026-07-20", "Mon", 30.0), day("2026-07-21", "Tue", 90.0), day("2026-07-22", "Wed", 5.0))
        val result = InsightsCalculator.bestAndWorstDay(week)
        assertEquals("2026-07-22", result.best?.date)
        assertEquals("2026-07-21", result.worst?.date)
    }

    @Test
    fun `merge hourly sums across days into one 0-23 map`() {
        val merged = InsightsCalculator.mergeHourly(listOf(mapOf(5 to 2.0), mapOf(5 to 3.0, 6 to 1.0)))
        assertEquals(5.0, merged[5]!!, 0.001)
        assertEquals(1.0, merged[6]!!, 0.001)
        assertEquals(0.0, merged[0]!!, 0.001)
        assertEquals(24, merged.size)
    }
}
