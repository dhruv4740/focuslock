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
        val appModes: Map<String, String> = emptyMap()
    )

    data class UsageInputs(val usedMinutes: Double, val extensionsUsedToday: Int)

    /** [dayOfWeek] 0=Sunday..6=Saturday, matching the block-window wire format. */
    data class Now(val dayOfWeek: Int, val minuteOfDay: Int, val focusSessionActive: Boolean)

    fun decide(
        policy: PolicyInputs,
        usage: UsageInputs,
        now: Now,
        packageName: String? = null
    ): FocusPolicyClient.Decision {
        val effectiveLimit = policy.dailyLimitMinutes + usage.extensionsUsedToday * policy.extensionMinutes

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
                hardBlockAfterLimit = policy.hardBlockAfterLimit
            )
        }

        val overLimit = effectiveLimit > 0 && usage.usedMinutes >= effectiveLimit
        if (!overLimit) {
            return FocusPolicyClient.Decision(
                allowNow = true,
                action = "none",
                reason = "within-limit",
                effectiveLimit = effectiveLimit,
                usedMinutes = usage.usedMinutes,
                userUsedMinutes = usage.usedMinutes,
                hardBlockAfterLimit = policy.hardBlockAfterLimit
            )
        }

        // Over the daily limit: hardBlockAfterLimit decides lock vs. friction —
        // matching the Windows agent, where OFF means "never hard-block on limit".
        return if (policy.hardBlockAfterLimit) {
            FocusPolicyClient.Decision(
                allowNow = false,
                action = "hard-block",
                reason = "daily-limit-reached",
                effectiveLimit = effectiveLimit,
                usedMinutes = usage.usedMinutes,
                userUsedMinutes = usage.usedMinutes,
                hardBlockAfterLimit = true
            )
        } else {
            FocusPolicyClient.Decision(
                allowNow = true,
                action = "soft-limit",
                reason = "daily-limit-reached-soft",
                effectiveLimit = effectiveLimit,
                usedMinutes = usage.usedMinutes,
                userUsedMinutes = usage.usedMinutes,
                hardBlockAfterLimit = false
            )
        }
    }
}
