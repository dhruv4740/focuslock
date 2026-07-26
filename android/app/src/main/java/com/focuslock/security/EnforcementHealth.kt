package com.focuslock.security

/**
 * Pure decision logic backing UsageMonitorService's enforcement-integrity
 * checks. No Android imports — all timing is passed in via nowMs so this is
 * unit-testable without instrumentation. Mirrors the split in
 * service/SessionChunkTracker.kt: this file is the state machine, the service
 * is the glue (SharedPreferences reads/writes, scheduling).
 */
object EnforcementHealth {

    // ── Cold-start fail-closed ───────────────────────────────────────────────

    /**
     * A device that has never once had a policy successfully fetched is a
     * brand-new install — allow it, same as today. Once a policy has ever been
     * fetched, a cold start with nothing reachable (no network, no Firestore
     * cache) must restore the last known enforcement state rather than allow
     * silently — that's the offline full-bypass hole (airplane mode + force
     * stop + relaunch).
     */
    fun shouldAllowFreshInstall(everFetchedPolicy: Boolean): Boolean = !everFetchedPolicy

    // ── Heartbeat gap detection ──────────────────────────────────────────────

    /**
     * Why a heartbeat gap happened, in precedence order (checked top to
     * bottom — EXPLAINED wins over DISABLED wins over IDLE when more than one
     * condition technically holds):
     *  - EXPLAINED: a boot/package-replace timestamp falls inside the gap
     *    window — the device was off or updating, not tampered with.
     *  - DISABLED: accessibility is off right now. This is a direct
     *    observation, not an inference — enforcement is verifiably down.
     *  - IDLE: accessibility is still on. The gap is almost certainly just the
     *    user not touching the phone. Not tampering — do not present it as such.
     */
    enum class GapCause { EXPLAINED, DISABLED, IDLE }

    data class HeartbeatGap(
        val startMs: Long,
        val endMs: Long,
        val durationMs: Long,
        val cause: GapCause
    ) {
        // Convenience for callers that only care about "is this tampering."
        val isTampering: Boolean get() = cause == GapCause.DISABLED
    }

    private const val MAX_HISTORY = 20

    /**
     * Compares now against the last recorded heartbeat and classifies the
     * result. Returns null when there's no prior heartbeat at all (first-ever
     * run — not a gap, there's nothing to compare against) or when the
     * elapsed time is at or under [thresholdMs] (heartbeat is still fresh).
     *
     * [bootCompletedAtMs] is the last timestamp BootCompletedReceiver recorded
     * on ACTION_BOOT_COMPLETED / ACTION_MY_PACKAGE_REPLACED, or null if it
     * never fired. [accessibilityEnabledNow] is a direct read of
     * EnforcementChecks at classification time — the real signal, since a
     * heartbeat gap by itself only ever proxies for "was the watchdog running."
     */
    fun detectGap(
        nowMs: Long,
        lastSeenAtMs: Long?,
        thresholdMs: Long,
        bootCompletedAtMs: Long?,
        accessibilityEnabledNow: Boolean
    ): HeartbeatGap? {
        if (lastSeenAtMs == null) return null
        val elapsed = nowMs - lastSeenAtMs
        if (elapsed <= thresholdMs) return null
        val explained = bootCompletedAtMs != null && bootCompletedAtMs in lastSeenAtMs..nowMs
        val cause = when {
            explained -> GapCause.EXPLAINED
            !accessibilityEnabledNow -> GapCause.DISABLED
            else -> GapCause.IDLE
        }
        return HeartbeatGap(startMs = lastSeenAtMs, endMs = nowMs, durationMs = elapsed, cause = cause)
    }

    /**
     * Direct observation, independent of any heartbeat gap: accessibility was
     * found off at a check point. Worth strictly more than an inferred gap —
     * call at every classification check, not just when a gap is also present.
     */
    fun observedDisabled(nowMs: Long): HeartbeatGap =
        HeartbeatGap(startMs = nowMs, endMs = nowMs, durationMs = 0L, cause = GapCause.DISABLED)

    /**
     * Prepends [gap] to [history] and prunes to the most recent [MAX_HISTORY]
     * entries, oldest dropped first.
     */
    fun recordGap(history: List<HeartbeatGap>, gap: HeartbeatGap): List<HeartbeatGap> =
        (listOf(gap) + history).take(MAX_HISTORY)

    // An "open" DISABLED entry is one observedDisabled() produced and nothing
    // has closed since: zero duration, endMs == startMs. There's at most one
    // meaningful open entry at a time (the most recent), since every close
    // makes the entry non-zero-duration.
    private fun isOpenDisabled(gap: HeartbeatGap) =
        gap.cause == GapCause.DISABLED && gap.durationMs == 0L && gap.endMs == gap.startMs

    /**
     * Matches AccessibilityService.onServiceConnected against the onUnbind
     * that opened a DISABLED entry, turning a zero-duration placeholder into a
     * real "enforcement was off for N" span. Closes only the most recent open
     * entry (history is newest-first); a history with no open entry is
     * returned unchanged — including the case where the process died before
     * ever reconnecting, so the entry is left open forever.
     *
     * onUnbind fires on a deliberate accessibility disable, but also on app
     * update, process kill, and device shutdown — none of which are user
     * tampering. Rather than label an ambiguous span with false certainty,
     * cross-reference the same boot/package-replace timestamp detectGap uses:
     * if it falls inside the now-closed span, downgrade the entry to
     * EXPLAINED. Anything left as DISABLED after that check is the closest
     * available signal to "enforcement was actually off", but is still not
     * proof of a deliberate disable — device shutdown-without-reboot-record or
     * a kill that raced the boot receiver can still land here.
     */
    fun closeOpenDisabledGap(
        history: List<HeartbeatGap>,
        nowMs: Long,
        bootCompletedAtMs: Long?
    ): List<HeartbeatGap> {
        val idx = history.indexOfFirst { isOpenDisabled(it) }
        if (idx == -1) return history
        val open = history[idx]
        val explained = bootCompletedAtMs != null && bootCompletedAtMs in open.startMs..nowMs
        val closed = open.copy(
            endMs = nowMs,
            durationMs = (nowMs - open.startMs).coerceAtLeast(0L),
            cause = if (explained) GapCause.EXPLAINED else GapCause.DISABLED
        )
        return history.toMutableList().apply { this[idx] = closed }
    }
}
