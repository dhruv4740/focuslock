package com.focuslock.service

/**
 * Pure state machine for session chunking: after [maxMs] of cumulative use of
 * managed apps, a hard break of [breakMs] locks all of them; a voluntary
 * absence of at least [breakMs] counts as having taken the break. The
 * accumulator is global across managed apps (the break locks all of them, so a
 * per-app counter could be dodged by app-hopping). No Android dependencies —
 * all timing passed via nowMs, driven by UsageMonitorService.
 */
class SessionChunkTracker {

    sealed class Gate {
        object Allow : Gate()
        data class InBreak(val untilMs: Long) : Gate()
    }

    private var maxMs = 0L
    private var breakMs = 0L
    private var accumulatedMs = 0L
    private var presenceStartMs: Long? = null
    private var leftAtMs = 0L
    private var breakUntilMs = 0L

    val isEnabled: Boolean
        get() = maxMs > 0 && breakMs > 0

    fun configure(maxMinutes: Int, breakMinutes: Int) {
        val newMaxMs = maxMinutes * 60_000L
        val newBreakMs = breakMinutes * 60_000L
        if (newMaxMs == maxMs && newBreakMs == breakMs) return
        maxMs = newMaxMs
        breakMs = newBreakMs
        // Config change: keep it forgiving — reset the accumulator and any
        // running break instead of surprising the user mid-chunk.
        accumulatedMs = 0
        presenceStartMs = null
        breakUntilMs = 0
    }

    /** Call when a managed app comes to the foreground. */
    fun onTargetOpened(nowMs: Long): Gate {
        if (!isEnabled) return Gate.Allow
        if (nowMs < breakUntilMs) return Gate.InBreak(breakUntilMs)
        if (presenceStartMs == null) {
            // A voluntary absence at least as long as the break counts as one.
            if (leftAtMs > 0 && nowMs - leftAtMs >= breakMs) {
                accumulatedMs = 0
            }
            presenceStartMs = nowMs
        }
        return Gate.Allow
    }

    /** Call when the user leaves all managed apps. */
    fun onTargetClosed(nowMs: Long) {
        presenceStartMs?.let { accumulatedMs += (nowMs - it).coerceAtLeast(0) }
        presenceStartMs = null
        leftAtMs = nowMs
    }

    /**
     * Milliseconds of chunk budget left from now, or null when chunking is
     * disabled or no managed app is currently open. Zero means the cap is due.
     */
    fun msUntilCap(nowMs: Long): Long? {
        if (!isEnabled) return null
        val start = presenceStartMs ?: return null
        val used = accumulatedMs + (nowMs - start).coerceAtLeast(0)
        return (maxMs - used).coerceAtLeast(0)
    }

    /** Cap hit while inside a managed app: starts the break, returns its end time. */
    fun startBreak(nowMs: Long): Long {
        accumulatedMs = 0
        presenceStartMs = null
        leftAtMs = nowMs
        breakUntilMs = nowMs + breakMs
        return breakUntilMs
    }
}
