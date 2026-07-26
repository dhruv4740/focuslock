package com.focuslock.data

/**
 * Pure streak computation — no Android/Firebase dependencies — mirroring the
 * Windows agent's `recordStreakOutcome`/`checkDayRollover` semantics
 * (windows-agent/src/agent.js) exactly, field-name for field-name, so
 * `streak/current` can be safely read/written by either platform without
 * corrupting the other's data.
 *
 * "Kept the streak" for a day = usedMinutes < effectiveLimit, with
 * effectiveLimit == 0 meaning "no limit configured" — always counts as kept
 * (see PolicyEngine/FocusPolicyClient for the same 0-is-infinite convention
 * used elsewhere in the ratchet).
 *
 * Also carries the rolling 30-day consistency counters
 * (metGoalDaysLast30/totalDaysLast30) used for the leaderboard's
 * consistencyPercent — see FocusPolicyClient.recordTodaysStreakOutcome for
 * the design note on why these are a fixed-size rolling counter rather than
 * a stored per-day history.
 */
object StreakTracker {

    data class StreakState(
        val currentStreak: Int = 0,
        val longestStreak: Int = 0,
        val lastSuccessDate: String? = null,
        val lastRecordedDate: String? = null,
        val metGoalDaysLast30: Int = 0,
        val totalDaysLast30: Int = 0
    )

    /** effectiveLimit == 0 means "no limit" — always counts as kept, matching the ratchet's 0-is-infinite convention. */
    fun isUnderLimit(usedMinutes: Double, effectiveLimit: Int): Boolean =
        effectiveLimit == 0 || usedMinutes < effectiveLimit

    /**
     * Computes the new streak state for [dateStr] given [usedMinutes] against
     * [effectiveLimit] and the [prior] state. Idempotent: if [prior].lastRecordedDate
     * is already >= [dateStr], [prior] is returned unchanged — a retried or
     * duplicated call for a day already recorded is a no-op, exactly like the
     * Windows agent's guard (`prev.lastRecordedDate >= dateStr`).
     *
     * The rolling 30-day window has no per-day history to recompute from, so
     * metGoalDaysLast30/totalDaysLast30 are simple counters: totalDaysLast30
     * increments (capped at 30, rolling off the oldest implicit day by holding
     * the ratio steady) each time a day is recorded, and metGoalDaysLast30
     * increments only on a kept day. Once totalDaysLast30 hits 30, further days
     * scale both counters down proportionally before incrementing, approximating
     * a 30-day window without storing 30 individual day outcomes.
     */
    fun recordDay(
        usedMinutes: Double,
        effectiveLimit: Int,
        prior: StreakState,
        dateStr: String
    ): StreakState {
        if (prior.lastRecordedDate != null && prior.lastRecordedDate >= dateStr) {
            return prior
        }

        val kept = isUnderLimit(usedMinutes, effectiveLimit)
        val newStreak = if (kept) prior.currentStreak + 1 else 0

        // Rolling 30-day window: once full, scale both counters down by 29/30
        // (rounding) before adding today, so the window "forgets" roughly one
        // day's worth of history per new day rather than growing unbounded.
        var metGoal = prior.metGoalDaysLast30
        var total = prior.totalDaysLast30
        if (total >= 30) {
            metGoal = Math.round(metGoal * 29.0 / 30.0).toInt()
            total = Math.round(total * 29.0 / 30.0).toInt()
        }
        total += 1
        if (kept) metGoal += 1

        return StreakState(
            currentStreak = newStreak,
            longestStreak = maxOf(newStreak, prior.longestStreak),
            lastSuccessDate = dateStr,
            lastRecordedDate = dateStr,
            metGoalDaysLast30 = metGoal,
            totalDaysLast30 = total
        )
    }
}
