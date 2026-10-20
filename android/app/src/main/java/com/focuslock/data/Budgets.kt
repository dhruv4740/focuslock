package com.focuslock.data

/**
 * Category budgets and the overall phone cap, stored additively in policy/global
 * (`categories`, `overallCap`). Old docs have neither and behave exactly as before:
 * no categories = only the global daily limit applies, no cap = limit 0 = off.
 * The Windows agent never reads these fields and round-trips them untouched.
 */
data class AppCategory(
    val name: String,
    val apps: Set<String> = emptySet(),
    // 0 = no budget (loosest), same convention as dailyLimitMinutes.
    val dailyLimitMinutes: Int = 0,
    val mode: AppMode = AppMode.LIMIT
)

data class OverallCap(
    val dailyLimitMinutes: Int = 0,
    val mode: AppMode = AppMode.FRICTION_ONLY,
    val allowList: Set<String> = emptySet()
)

object Budgets {
    const val GAMES_ID = "games"

    /** First category (by id order) listing [pkg], or null. */
    fun categoryOf(pkg: String, categories: Map<String, AppCategory>): Pair<String, AppCategory>? =
        categories.entries.sortedBy { it.key }.firstOrNull { pkg in it.value.apps }?.toPair()

    /** Firestore field keys must stay simple; derive a stable id from a display name. */
    fun idFor(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "category" }

    fun categoriesFromWire(raw: Any?): Map<String, AppCategory> {
        val map = raw as? Map<*, *> ?: return emptyMap()
        val out = mutableMapOf<String, AppCategory>()
        for ((k, v) in map) {
            val id = k as? String ?: continue
            val m = v as? Map<*, *> ?: continue
            out[id] = AppCategory(
                name = m["name"] as? String ?: id,
                apps = (m["apps"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet(),
                dailyLimitMinutes = (m["dailyLimitMinutes"] as? Number)?.toInt() ?: 0,
                mode = AppMode.fromWire(m["mode"] as? String, inTargetApps = true)
            )
        }
        return out
    }

    fun categoriesToWire(categories: Map<String, AppCategory>): Map<String, Any> =
        categories.mapValues { (_, c) ->
            mapOf(
                "name" to c.name,
                "apps" to c.apps.sorted(),
                "dailyLimitMinutes" to c.dailyLimitMinutes,
                "mode" to c.mode.wire
            )
        }

    fun capFromWire(raw: Any?): OverallCap {
        val m = raw as? Map<*, *> ?: return OverallCap()
        return OverallCap(
            dailyLimitMinutes = (m["dailyLimitMinutes"] as? Number)?.toInt() ?: 0,
            mode = AppMode.entries.firstOrNull { it.wire == m["mode"] } ?: AppMode.FRICTION_ONLY,
            allowList = (m["allowList"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
        )
    }

    fun capToWire(cap: OverallCap): Map<String, Any> = mapOf(
        "dailyLimitMinutes" to cap.dailyLimitMinutes,
        "mode" to cap.mode.wire,
        "allowList" to cap.allowList.sorted()
    )
}
