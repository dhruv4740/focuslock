package com.focuslock.data

/**
 * Pure decision logic for whether an app is allowed right now — no Android or
 * Firebase dependencies, all time/usage state passed in, so the enforcement
 * semantics are unit-testable. [FocusPolicyClient.evaluate] gathers the inputs
 * from Firestore and delegates here.
 *
 * Decision.action contract:
 *  - "hard-block":  show the lock overlay (focus session, block window, or over
 *                   the daily limit with hardBlockAfterLimit ON)
 *  - "soft-limit":  over the daily limit with hardBlockAfterLimit OFF — allowed
 *                   in, but friction fires on every open and the badge turns red
 *  - "none":        within limits
 *
 * Budgets: the global daily limit and (if the app is in a category) that
 * category's daily limit are both checked; either one exhausted counts as over.
 */
object PolicyEngine {

    data class BlockWindow(val startMin: Int, val endMin: Int, val days: Set<Int>)

    data class PolicyInputs(
        val dailyLimitMinutes: Int,
        val extensionMinutes: Int,
        val hardBlockAfterLimit: Boolean,
        val blockWindows: List<BlockWindow>,
        // pkg → "off" | "friction_only" | "limit" | "hard_block"; consulted when a
        // packageName is passed to decide(). Absent entries behave as "limit".
        val appModes: Map<String, String> = emptyMap(),
        // id → category with its own daily budget. Empty = only the global limit.
        val categories: Map<String, AppCategory> = emptyMap()
    )

    data class UsageInputs(
        val usedMinutes: Double,
        val extensionsUsedToday: Int,
        // usage_days.categoryMinutes: category id → minutes used today.
        val categoryMinutes: Map<String, Double> = emptyMap()
    )

    /** What the overall phone cap does to an app opening right now. */
    enum class CapAction { NONE, FRICTION, LOCK }

    /**
     * Overall cap: [usedMinutes] is total foreground time across every app not on
     * the allow-list (computed on-device from UsageStatsManager, so it is not part
     * of [decide]). Callers exclude FocusLock itself, the launcher and system UI
     * before summing and before asking about a package.
     */
    fun decideOverallCap(cap: OverallCap, usedMinutes: Double, packageName: String): CapAction = when {
        cap.dailyLimitMinutes <= 0 || cap.mode == AppMode.OFF -> CapAction.NONE
        packageName in cap.allowList -> CapAction.NONE
        usedMinutes < cap.dailyLimitMinutes -> CapAction.NONE
        cap.mode == AppMode.HARD_BLOCK -> CapAction.LOCK
        else -> CapAction.FRICTION
    }

    private data class Budget(val limit: Int, val used: Double, val hard: Boolean, val overReason: String)

    /** [dayOfWeek] 0=Sunday..6=Saturday, matching the block-window wire format. */
    data class Now(val dayOfWeek: Int, val minuteOfDay: Int, val focusSessionActive: Boolean)

    fun decide(
        policy: PolicyInputs,
        usage: UsageInputs,
        now: Now,
        packageName: String? = null
    ): FocusPolicyClient.Decision {
        // Per-app mode: entries absent from appModes behave as LIMIT (legacy docs,
        // apps added from the Windows dashboard). No packageName = the aggregate/
        // dashboard evaluation, which also uses LIMIT semantics.
        val mode = packageName?.let { AppMode.fromWire(policy.appModes[it], inTargetApps = true) }
            ?: AppMode.LIMIT

        // HARD_BLOCK ignores the global soft/hard switch; FRICTION_ONLY has no
        // limit at all — the daily limit only ever locks LIMIT-mode apps.
        val hardBlockForApp = when (mode) {
            AppMode.HARD_BLOCK -> true
            else -> policy.hardBlockAfterLimit
        }
        val effectiveLimit = when (mode) {
            AppMode.FRICTION_ONLY -> 0
            else -> policy.dailyLimitMinutes + usage.extensionsUsedToday * policy.extensionMinutes
        }

        val inBlockWindow = policy.blockWindows.any { w ->
            now.dayOfWeek in w.days && now.minuteOfDay in w.startMin until w.endMin
        }

        if (now.focusSessionActive || inBlockWindow) {
            return FocusPolicyClient.Decision(
                allowNow = false,
                action = "hard-block",
                reason = if (now.focusSessionActive) "focus-session-active" else "scheduled-block-window",
                effectiveLimit = 0,
                usedMinutes = usage.usedMinutes,
                userUsedMinutes = usage.usedMinutes,
                hardBlockAfterLimit = hardBlockForApp
            )
        }

        // Global daily limit plus, when the app sits in a category with its own
        // budget, that category's limit. An explicit per-app FRICTION_ONLY exempts
        // the app from both. Extensions raise both limits.
        val category = if (mode == AppMode.FRICTION_ONLY) null
        else packageName?.let { Budgets.categoryOf(it, policy.categories) }
        val extension = usage.extensionsUsedToday * policy.extensionMinutes
        val budgets = buildList {
            if (effectiveLimit > 0) add(Budget(effectiveLimit, usage.usedMinutes, hardBlockForApp, "daily-limit-reached"))
            if (category != null && category.second.dailyLimitMinutes > 0 &&
                (category.second.mode == AppMode.LIMIT || category.second.mode == AppMode.HARD_BLOCK)
            ) {
                val c = category.second
                add(
                    Budget(
                        c.dailyLimitMinutes + extension,
                        usage.categoryMinutes[category.first] ?: 0.0,
                        c.mode == AppMode.HARD_BLOCK || hardBlockForApp,
                        "category-limit-reached"
                    )
                )
            }
        }

        val over = budgets.filter { it.used >= it.limit }
        if (over.isEmpty()) {
            // Report the tightest remaining budget so the local timer counts down
            // against the constraint that will actually trip first.
            val binding = budgets.minByOrNull { it.limit - it.used }
            return FocusPolicyClient.Decision(
                allowNow = true,
                action = "none",
                reason = "within-limit",
                effectiveLimit = binding?.limit ?: effectiveLimit,
                usedMinutes = binding?.used ?: usage.usedMinutes,
                userUsedMinutes = binding?.used ?: usage.usedMinutes,
                hardBlockAfterLimit = binding?.hard ?: hardBlockForApp
            )
        }

        // Over a limit: hardBlockAfterLimit decides lock vs. friction —
        // matching the Windows agent, where OFF means "never hard-block on limit".
        // Any exhausted budget that is hard wins over a soft one.
        val hardBudget = over.firstOrNull { it.hard }
        return if (hardBudget != null) {
            FocusPolicyClient.Decision(
                allowNow = false,
                action = "hard-block",
                reason = hardBudget.overReason,
                effectiveLimit = hardBudget.limit,
                usedMinutes = hardBudget.used,
                userUsedMinutes = hardBudget.used,
                hardBlockAfterLimit = true
            )
        } else {
            val soft = over.first()
            FocusPolicyClient.Decision(
                allowNow = true,
                action = "soft-limit",
                reason = "${soft.overReason}-soft",
                effectiveLimit = soft.limit,
                usedMinutes = soft.used,
                userUsedMinutes = soft.used,
                hardBlockAfterLimit = false
            )
        }
    }
}
