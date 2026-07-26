package com.focuslock.data

/**
 * Pure daily-progress computation for group challenges — no Android/Firebase
 * dependencies, mirroring StreakTracker.kt's shape exactly (same idempotency
 * guard style: skip if [ChallengeProgressState.lastRecordedDate] is already
 * >= the date being recorded).
 *
 * Two challenge types (kept deliberately small per the task's "2 options is
 * enough for this slice"):
 *  - [ChallengeType.NO_REELS]: strict, permanent elimination. The first day a
 *    participant fails (any reels watched) flips [ChallengeProgressState.stillIn]
 *    to false, and it never flips back — a good day after a miss does not
 *    reinstate you. This matches a "streak" framing: one slip ends the run.
 *  - [ChallengeType.UNDER_LIMIT]: no elimination. This is a friendly counter,
 *    not a streak — [ChallengeProgressState.stillIn] stays true for the whole
 *    challenge regardless of any single day's outcome, and daysMet/daysTotal
 *    are simply displayed as a running ratio (e.g. "5/7 days under limit").
 *    A pass/fail threshold at challenge end was considered but deliberately
 *    not built — the UI just shows the raw counts, per the task's own
 *    "don't overthink" guidance for this type.
 */
object ChallengeProgress {

    enum class ChallengeType(val wire: String) {
        NO_REELS("no_reels"), UNDER_LIMIT("under_limit");
        companion object {
            fun fromWire(wire: String) = values().firstOrNull { it.wire == wire } ?: NO_REELS
        }
    }

    data class ChallengeProgressState(
        val daysMet: Int = 0,
        val daysTotal: Int = 0,
        val stillIn: Boolean = true,
        val lastRecordedDate: String? = null
    )

    /**
     * Computes the new progress state for [dateStr] given whether the
     * participant met today's goal ([metGoalToday] — zero reels for
     * [ChallengeType.NO_REELS], under-the-effective-limit for
     * [ChallengeType.UNDER_LIMIT]) against the [prior] state.
     *
     * Idempotent: if [prior].lastRecordedDate is already >= [dateStr], [prior]
     * is returned unchanged, exactly like StreakTracker.recordDay's guard.
     *
     * Also a no-op once the challenge window has closed: if [dateStr] is
     * after [endDate], no further progress is recorded (a challenge doesn't
     * silently keep accumulating days once it's over).
     */
    fun recordDay(
        type: ChallengeType,
        metGoalToday: Boolean,
        prior: ChallengeProgressState,
        dateStr: String,
        endDate: String
    ): ChallengeProgressState {
        if (prior.lastRecordedDate != null && prior.lastRecordedDate >= dateStr) {
            return prior
        }
        if (dateStr > endDate) {
            return prior
        }

        val stillIn = when (type) {
            ChallengeType.NO_REELS -> prior.stillIn && metGoalToday
            ChallengeType.UNDER_LIMIT -> true
        }

        return ChallengeProgressState(
            daysMet = prior.daysMet + if (metGoalToday) 1 else 0,
            daysTotal = prior.daysTotal + 1,
            stillIn = stillIn,
            lastRecordedDate = dateStr
        )
    }
}
