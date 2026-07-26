package com.focuslock.data

/**
 * Pure number-crunching for the Insights screen. Takes already-fetched data
 * (FocusPolicyClient.DayUsage lists, hourly/reel/open maps) and derives the
 * handful of summary stats shown there — no Firestore/Android dependency, so
 * this is unit-testable the same way StreakTracker is.
 */
object InsightsCalculator {

    /** thisWeek/lastWeek totals (sum of usedMinutes) and the delta, this - last. */
    data class WeekTrend(val thisWeekMinutes: Double, val lastWeekMinutes: Double, val deltaMinutes: Double)

    fun weekOverWeekTrend(
        thisWeek: List<FocusPolicyClient.DayUsage>,
        lastWeek: List<FocusPolicyClient.DayUsage>
    ): WeekTrend {
        val thisTotal = thisWeek.sumOf { it.usedMinutes }
        val lastTotal = lastWeek.sumOf { it.usedMinutes }
        return WeekTrend(thisTotal, lastTotal, thisTotal - lastTotal)
    }

    /** The hour (0-23) with the highest summed minutes across the supplied per-day hourly maps, or null if all zero/empty. */
    fun worstHour(hourlyByDay: List<Map<Int, Double>>): Int? {
        val totals = DoubleArray(24)
        for (day in hourlyByDay) {
            for ((hour, minutes) in day) {
                if (hour in 0..23) totals[hour] += minutes
            }
        }
        val maxIdx = totals.indices.maxByOrNull { totals[it] } ?: return null
        return if (totals[maxIdx] <= 0.0) null else maxIdx
    }

    /** Merges per-day hourly maps into one 0..23 total map (for rendering the heatmap row). */
    fun mergeHourly(hourlyByDay: List<Map<Int, Double>>): Map<Int, Double> {
        val totals = DoubleArray(24)
        for (day in hourlyByDay) {
            for ((hour, minutes) in day) {
                if (hour in 0..23) totals[hour] += minutes
            }
        }
        return (0..23).associateWith { totals[it] }
    }

    /** reelsTotal / opensTotal over some range, or null if there were no opens to divide by. */
    fun reelsPerUnlock(reelsTotal: Int, opensTotal: Int): Double? =
        if (opensTotal <= 0) null else reelsTotal.toDouble() / opensTotal

    data class BestWorstDay(val best: FocusPolicyClient.DayUsage?, val worst: FocusPolicyClient.DayUsage?)

    /** Best (lowest usedMinutes) and worst (highest) day in the week, or nulls if the week is empty. */
    fun bestAndWorstDay(week: List<FocusPolicyClient.DayUsage>): BestWorstDay =
        BestWorstDay(
            best = week.minByOrNull { it.usedMinutes },
            worst = week.maxByOrNull { it.usedMinutes }
        )
}
