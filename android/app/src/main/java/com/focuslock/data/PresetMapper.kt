package com.focuslock.data

/**
 * Pure mapping between preset bundles (stored additively in the policy doc's
 * `presets` map) and the live policy fields both platforms enforce from.
 * Applying a preset = merge-writing [bundleToLiveFields] output; enforcement
 * never reads presets directly.
 *
 * Bundle shape (all keys optional except name):
 *   { name, appModes {pkg→wire}, dailyLimitMinutes, hardBlockAfterLimit,
 *     frictionSteps {stepKey→Boolean, mathDifficulty}, blockedSurfaces, reelDailyLimit }
 */
object PresetMapper {

    const val BUILTIN_CHILL = "chill"
    const val BUILTIN_FOCUS = "focus"
    const val BUILTIN_STRICT = "strict"

    val builtInIds = listOf(BUILTIN_CHILL, BUILTIN_FOCUS, BUILTIN_STRICT)

    fun displayName(id: String, bundle: Map<String, Any>?): String =
        (bundle?.get("name") as? String)?.ifEmpty { null }
            ?: id.replaceFirstChar { it.uppercase() }

    /**
     * Live policy fields to merge-write when applying [bundle]. targetApps is
     * derived from appModes (mode != off) to keep the Windows agent working —
     * it only reads targetApps.
     */
    fun bundleToLiveFields(id: String, bundle: Map<String, Any>): Map<String, Any> {
        val fields = mutableMapOf<String, Any>("activePresetId" to id)
        @Suppress("UNCHECKED_CAST")
        (bundle["appModes"] as? Map<String, String>)?.let { modes ->
            fields["appModes"] = modes
            fields["targetApps"] = modes.filterValues { it != AppMode.OFF.wire }.keys.toList()
        }
        (bundle["dailyLimitMinutes"] as? Number)?.let { fields["dailyLimitMinutes"] = it.toInt() }
        (bundle["hardBlockAfterLimit"] as? Boolean)?.let { fields["hardBlockAfterLimit"] = it }
        (bundle["sessionMaxMinutes"] as? Number)?.let { fields["sessionMaxMinutes"] = it.toInt() }
        (bundle["sessionBreakMinutes"] as? Number)?.let { fields["sessionBreakMinutes"] = it.toInt() }
        @Suppress("UNCHECKED_CAST")
        (bundle["blockedSurfaces"] as? Map<String, String>)?.let { fields["blockedSurfaces"] = it }
        @Suppress("UNCHECKED_CAST")
        (bundle["reelDailyLimit"] as? Map<String, Number>)?.let { limits ->
            fields["reelDailyLimit"] = limits.mapValues { it.value.toInt() }
        }
        return fields
    }

    /** Friction-step toggles from a bundle — applied to local SharedPreferences. */
    @Suppress("UNCHECKED_CAST")
    fun frictionSteps(bundle: Map<String, Any>): Map<String, Any> =
        bundle["frictionSteps"] as? Map<String, Any> ?: emptyMap()

    /**
     * Built-in templates, generated over the user's current target apps so a
     * preset never silently drops apps the user is already managing. Returns
     * null for unknown ids.
     */
    fun builtInBundle(
        id: String,
        currentTargets: Collection<String>,
        dailyLimitMinutes: Int,
        reelDailyLimit: Map<String, Int>
    ): Map<String, Any>? {
        val allSurfacesTo = { mode: String ->
            mapOf("instagram_reels" to mode, "youtube_shorts" to mode)
        }
        val fixedReelLimit = { limit: Int ->
            mapOf("instagram_reels" to limit, "youtube_shorts" to limit)
        }
        return when (id) {
            BUILTIN_CHILL -> mapOf(
                "name" to "Chill",
                "appModes" to currentTargets.associateWith { AppMode.FRICTION_ONLY.wire },
                "hardBlockAfterLimit" to false,
                "blockedSurfaces" to allSurfacesTo("quota"),
                "reelDailyLimit" to fixedReelLimit(60),
                "sessionMaxMinutes" to 20,
                "sessionBreakMinutes" to 1,
                "frictionSteps" to mapOf("enable_breathing" to true, "enable_delay" to true)
            )
            BUILTIN_FOCUS -> mapOf(
                "name" to "Focus",
                "appModes" to currentTargets.associateWith { AppMode.LIMIT.wire },
                "dailyLimitMinutes" to (if (dailyLimitMinutes > 0) dailyLimitMinutes else 60),
                "hardBlockAfterLimit" to false,
                "blockedSurfaces" to allSurfacesTo("quota"),
                "reelDailyLimit" to fixedReelLimit(25),
                "sessionMaxMinutes" to 15,
                "sessionBreakMinutes" to 2,
                "frictionSteps" to mapOf(
                    "enable_breathing" to true, "enable_delay" to true,
                    "enable_intention" to true, "enable_stats" to true
                )
            )
            BUILTIN_STRICT -> mapOf(
                "name" to "Strict",
                "appModes" to currentTargets.associateWith { AppMode.HARD_BLOCK.wire },
                "dailyLimitMinutes" to (if (dailyLimitMinutes > 0) dailyLimitMinutes else 30),
                "hardBlockAfterLimit" to true,
                "blockedSurfaces" to allSurfacesTo("block"),
                "reelDailyLimit" to reelDailyLimit,
                "sessionMaxMinutes" to 10,
                "sessionBreakMinutes" to 3,
                "frictionSteps" to mapOf(
                    "enable_breathing" to true, "enable_delay" to true,
                    "enable_intention" to true, "enable_shake" to true,
                    "enable_reflection" to true, "enable_stats" to true,
                    "enable_math" to true
                )
            )
            else -> null
        }
    }
}
