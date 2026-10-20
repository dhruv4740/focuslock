package com.focuslock.data

/**
 * Total foreground time for the overall phone cap, summed from UsageStatsManager
 * events (no accessibility). Pure so the pairing logic is unit-testable; the
 * service feeds it the day's events. Event types match UsageEvents.Event:
 * MOVE_TO_FOREGROUND/ACTIVITY_RESUMED = 1, MOVE_TO_BACKGROUND/ACTIVITY_PAUSED = 2,
 * SCREEN_NON_INTERACTIVE = 16.
 */
object OverallCapUsage {
    const val RESUMED = 1
    const val PAUSED = 2
    const val SCREEN_OFF = 16

    data class Event(val pkg: String, val type: Int, val timeMs: Long)

    /** Minutes in the foreground at [nowMs] across packages not matched by [isExcluded]; [events] must be time-ordered. */
    fun foregroundMinutes(events: List<Event>, nowMs: Long, isExcluded: (String) -> Boolean): Double {
        val openSince = mutableMapOf<String, Long>()
        var totalMs = 0L
        fun close(pkg: String, atMs: Long) {
            val start = openSince.remove(pkg) ?: return
            totalMs += (atMs - start).coerceAtLeast(0)
        }
        for (e in events) {
            when (e.type) {
                RESUMED -> if (!isExcluded(e.pkg)) openSince.putIfAbsent(e.pkg, e.timeMs)
                PAUSED -> close(e.pkg, e.timeMs)
                SCREEN_OFF -> openSince.keys.toList().forEach { close(it, e.timeMs) }
            }
        }
        openSince.keys.toList().forEach { close(it, nowMs) }
        return totalMs / 60_000.0
    }
}
