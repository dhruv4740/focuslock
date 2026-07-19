package com.focuslock.data

import android.content.Context
import org.json.JSONObject

/**
 * Device-local per-app daily usage minutes, backing the "Nm today" part of the
 * timer badge. Sessions are recorded on finalize (exact fractional minutes from
 * UsageMonitorService.postUsageSessionIfAny), so totals survive process and
 * device restarts — unlike the in-memory session stopwatch. Firestore keeps its
 * own aggregate (usage_days perApp map); this store is what the 1 Hz badge
 * reads, so it must work offline and never touch the network.
 *
 * Storage: one SharedPreferences string per date, a JSON object {pkg: minutes};
 * dates older than [KEEP_DAYS] are pruned on write. Serialization is factored
 * into pure functions for JVM tests.
 */
object AppUsageStore {

    private const val PREFS_NAME = "FocusLockAppUsage"
    internal const val KEEP_DAYS = 7

    // Cache of today's map so the 1 Hz badge tick never re-parses JSON.
    @Volatile private var cachedDate: String = ""
    @Volatile private var cachedMap: Map<String, Double> = emptyMap()

    fun addSession(context: Context, date: String, packageName: String, minutes: Double) {
        if (minutes <= 0.0) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updated = mergeSession(parseDay(prefs.getString(date, null)), packageName, minutes)
        val editor = prefs.edit().putString(date, serializeDay(updated))
        // Prune anything older than the keep window (keys are ISO dates, so
        // lexicographic comparison is chronological).
        val cutoff = pruneCutoff(date)
        prefs.all.keys.filter { it < cutoff }.forEach { editor.remove(it) }
        editor.apply()
        if (date == cachedDate || cachedDate.isEmpty()) {
            cachedDate = date
            cachedMap = updated
        }
    }

    fun minutesToday(context: Context, date: String, packageName: String): Double {
        if (date != cachedDate) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            cachedMap = parseDay(prefs.getString(date, null))
            cachedDate = date
        }
        return cachedMap[packageName] ?: 0.0
    }

    fun todayMap(context: Context, date: String): Map<String, Double> {
        if (date != cachedDate) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            cachedMap = parseDay(prefs.getString(date, null))
            cachedDate = date
        }
        return cachedMap
    }

    // ── Pure logic (JVM-testable) ─────────────────────────────────────────────

    internal fun parseDay(json: String?): Map<String, Double> {
        if (json.isNullOrEmpty()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { obj.getDouble(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    internal fun serializeDay(map: Map<String, Double>): String =
        JSONObject(map).toString()

    internal fun mergeSession(day: Map<String, Double>, packageName: String, minutes: Double): Map<String, Double> =
        day + (packageName to ((day[packageName] ?: 0.0) + minutes))

    /** Dates strictly before this ISO date string get pruned. */
    internal fun pruneCutoff(today: String): String =
        java.time.LocalDate.parse(today).minusDays((KEEP_DAYS - 1).toLong()).toString()
}
