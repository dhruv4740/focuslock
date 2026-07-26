package com.focuslock.data

import com.focuslock.surface.SurfaceMode

/**
 * Pure ratchet logic: tightening a policy applies immediately, loosening it is
 * delayed. No Android dependencies — all time passed in as epoch millis so the
 * decision (and the delay boundary) is unit-testable. [FocusPolicyClient] (or
 * whatever UI layer collects the edit) calls [PolicyRatchet.split] before
 * writing, applies [SplitResult.applyNow] now, and stashes [SplitResult.deferred]
 * plus its [SplitResult.deferredChanges] to apply once [isDue] is true.
 *
 * Field-strictness note: three fields use "0 = disabled" as their wire
 * convention, and disabled is always the LOOSEST state, not the strictest —
 * dailyLimitMinutes, reelDailyLimit (per key) and sessionMaxMinutes. Treat 0 as
 * "infinity" when comparing. sessionBreakMinutes also treats 0 as disabled/
 * loosest, but among nonzero values a *lower* break is looser (less forced
 * rest), the opposite direction from the other three.
 */

enum class ChangeDirection { TIGHTEN, LOOSEN, NEUTRAL }

data class FieldChange(val field: String, val direction: ChangeDirection, val summary: String)

data class BlockWindowSnapshot(val start: String, val end: String, val days: Set<Int>)

data class PolicySnapshot(
    val dailyLimitMinutes: Int = 0,
    val hardBlockAfterLimit: Boolean = false,
    val extensionMinutes: Int = 15,
    val dailyExtensionLimit: Int = 1,
    val targetApps: Set<String> = emptySet(),
    val appModes: Map<String, AppMode> = emptyMap(),
    val blockWindows: List<BlockWindowSnapshot> = emptyList(),
    val blockedSurfaces: Map<String, SurfaceMode> = emptyMap(),
    val reelDailyLimit: Map<String, Int> = emptyMap(),
    val sessionMaxMinutes: Int = 0,
    val sessionBreakMinutes: Int = 0,
    val frictionSteps: Set<String> = emptySet(),
    val mathDifficulty: String = "medium"
)

object PolicyRatchet {

    private val appModeRank = mapOf(
        AppMode.OFF to 0, AppMode.FRICTION_ONLY to 1, AppMode.LIMIT to 2, AppMode.HARD_BLOCK to 3
    )

    private val surfaceModeRank = mapOf(
        SurfaceMode.OFF to 0, SurfaceMode.QUOTA to 1, SurfaceMode.ONE_AT_A_TIME to 2, SurfaceMode.BLOCK to 3
    )

    private val mathDifficultyRank = mapOf("easy" to 0, "medium" to 1, "hard" to 2)

    // "0 means no limit" fields: larger effective value (with 0 -> +infinity)
    // is looser. Shared by dailyLimitMinutes, sessionMaxMinutes and each
    // reelDailyLimit entry.
    private fun zeroIsInfinite(v: Int): Int = if (v == 0) Int.MAX_VALUE else v

    private fun timeToMin(hhmm: String): Int {
        val parts = hhmm.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return h * 60 + m
    }

    /** True if [inner] range/day-set is fully covered by [outer] (i.e. inner is at least as strict). */
    private fun windowCoveredBy(inner: BlockWindowSnapshot, outer: BlockWindowSnapshot): Boolean {
        if (!outer.days.containsAll(inner.days)) return false
        val outerStart = timeToMin(outer.start)
        val outerEnd = timeToMin(outer.end)
        val innerStart = timeToMin(inner.start)
        val innerEnd = timeToMin(inner.end)
        return innerStart >= outerStart && innerEnd <= outerEnd
    }

    private fun minutes(v: Int) = if (v == 0) "off" else "$v min"

    fun diff(old: PolicySnapshot, new: PolicySnapshot): List<FieldChange> {
        val changes = mutableListOf<FieldChange>()

        // dailyLimitMinutes: 0 = no limit = loosest.
        if (old.dailyLimitMinutes != new.dailyLimitMinutes) {
            val oldEff = zeroIsInfinite(old.dailyLimitMinutes)
            val newEff = zeroIsInfinite(new.dailyLimitMinutes)
            val dir = if (newEff < oldEff) ChangeDirection.TIGHTEN else ChangeDirection.LOOSEN
            changes += FieldChange(
                "dailyLimitMinutes", dir,
                "Daily limit ${minutes(old.dailyLimitMinutes)} → ${minutes(new.dailyLimitMinutes)}"
            )
        }

        if (old.hardBlockAfterLimit != new.hardBlockAfterLimit) {
            val dir = if (new.hardBlockAfterLimit) ChangeDirection.TIGHTEN else ChangeDirection.LOOSEN
            changes += FieldChange(
                "hardBlockAfterLimit", dir,
                "Hard block after limit ${if (old.hardBlockAfterLimit) "on" else "off"} → ${if (new.hardBlockAfterLimit) "on" else "off"}"
            )
        }

        // extensionMinutes: higher = looser (bigger extension grants).
        if (old.extensionMinutes != new.extensionMinutes) {
            val dir = if (new.extensionMinutes < old.extensionMinutes) ChangeDirection.TIGHTEN else ChangeDirection.LOOSEN
            changes += FieldChange(
                "extensionMinutes", dir,
                "Extension size ${old.extensionMinutes} → ${new.extensionMinutes} min"
            )
        }

        // dailyExtensionLimit: higher = looser (more extensions allowed per day).
        if (old.dailyExtensionLimit != new.dailyExtensionLimit) {
            val dir = if (new.dailyExtensionLimit < old.dailyExtensionLimit) ChangeDirection.TIGHTEN else ChangeDirection.LOOSEN
            changes += FieldChange(
                "dailyExtensionLimit", dir,
                "Extensions per day ${old.dailyExtensionLimit} → ${new.dailyExtensionLimit}"
            )
        }

        // targetApps: per-app granularity, exactly like appModes, so a swap (add
        // one app, remove another in the same save) splits cleanly — the
        // addition is its own TIGHTEN change and the removal its own LOOSEN
        // change, instead of one whole-field change that would force the
        // addition to wait alongside the removal.
        val appKeys = old.targetApps + new.targetApps
        for (pkg in appKeys) {
            val wasIn = pkg in old.targetApps
            val isIn = pkg in new.targetApps
            if (wasIn == isIn) continue
            val dir = if (isIn) ChangeDirection.TIGHTEN else ChangeDirection.LOOSEN
            changes += FieldChange(
                "targetApps.$pkg", dir,
                if (isIn) "$pkg added to managed apps" else "$pkg removed from managed apps"
            )
        }

        // appModes: compare per-key strictness; missing key behaves as OFF (the
        // loosest) for comparison purposes, matching legacy "absent = no mode set".
        val modeKeys = old.appModes.keys + new.appModes.keys
        for (pkg in modeKeys) {
            val oldMode = old.appModes[pkg] ?: AppMode.OFF
            val newMode = new.appModes[pkg] ?: AppMode.OFF
            if (oldMode == newMode) continue
            val oldRank = appModeRank.getValue(oldMode)
            val newRank = appModeRank.getValue(newMode)
            val dir = if (newRank > oldRank) ChangeDirection.TIGHTEN else ChangeDirection.LOOSEN
            changes += FieldChange(
                "appModes.$pkg", dir,
                "$pkg: ${oldMode.label} → ${newMode.label}"
            )
        }

        // blockWindows: treat as a set-ish comparison. A window in `new` not
        // covered by any window in `old` is a tightening addition/expansion; a
        // window in `old` not covered by any window in `new` is a loosening
        // removal/shrink. This also implicitly catches shrunk ranges and
        // removed days, since a shrunk window is no longer "covered" the same
        // way in both directions.
        if (old.blockWindows != new.blockWindows) {
            val newNotCoveredByOld = new.blockWindows.filter { nw -> old.blockWindows.none { ow -> windowCoveredBy(nw, ow) } }
            val oldNotCoveredByNew = old.blockWindows.filter { ow -> new.blockWindows.none { nw -> windowCoveredBy(ow, nw) } }
            val dir = when {
                oldNotCoveredByNew.isNotEmpty() -> ChangeDirection.LOOSEN
                newNotCoveredByOld.isNotEmpty() -> ChangeDirection.TIGHTEN
                else -> ChangeDirection.NEUTRAL
            }
            if (dir != ChangeDirection.NEUTRAL) {
                changes += FieldChange(
                    "blockWindows", dir,
                    "Block windows changed (${old.blockWindows.size} → ${new.blockWindows.size})"
                )
            }
        }

        // blockedSurfaces: same strictness-rank comparison as appModes; absent = OFF.
        val surfaceKeys = old.blockedSurfaces.keys + new.blockedSurfaces.keys
        for (surface in surfaceKeys) {
            val oldMode = old.blockedSurfaces[surface] ?: SurfaceMode.OFF
            val newMode = new.blockedSurfaces[surface] ?: SurfaceMode.OFF
            if (oldMode == newMode) continue
            val oldRank = surfaceModeRank.getValue(oldMode)
            val newRank = surfaceModeRank.getValue(newMode)
            val dir = if (newRank > oldRank) ChangeDirection.TIGHTEN else ChangeDirection.LOOSEN
            changes += FieldChange(
                "blockedSurfaces.$surface", dir,
                "$surface: ${oldMode.wire} → ${newMode.wire}"
            )
        }

        // reelDailyLimit: 0 = no limit = loosest, per key; missing key = 0 = no limit.
        val reelKeys = old.reelDailyLimit.keys + new.reelDailyLimit.keys
        for (key in reelKeys) {
            val oldVal = old.reelDailyLimit[key] ?: 0
            val newVal = new.reelDailyLimit[key] ?: 0
            if (oldVal == newVal) continue
            val oldEff = zeroIsInfinite(oldVal)
            val newEff = zeroIsInfinite(newVal)
            val dir = if (newEff < oldEff) ChangeDirection.TIGHTEN else ChangeDirection.LOOSEN
            changes += FieldChange(
                "reelDailyLimit.$key", dir,
                "$key: ${minutes(oldVal)} → ${minutes(newVal)}"
            )
        }

        // sessionMaxMinutes: 0 = disabled = loosest.
        if (old.sessionMaxMinutes != new.sessionMaxMinutes) {
            val oldEff = zeroIsInfinite(old.sessionMaxMinutes)
            val newEff = zeroIsInfinite(new.sessionMaxMinutes)
            val dir = if (newEff < oldEff) ChangeDirection.TIGHTEN else ChangeDirection.LOOSEN
            changes += FieldChange(
                "sessionMaxMinutes", dir,
                "Session length ${minutes(old.sessionMaxMinutes)} → ${minutes(new.sessionMaxMinutes)}"
            )
        }

        // sessionBreakMinutes: 0 = disabled = loosest; among nonzero values,
        // lower = looser (less forced rest) — the inverse of the other three.
        if (old.sessionBreakMinutes != new.sessionBreakMinutes) {
            val dir = when {
                old.sessionBreakMinutes == 0 && new.sessionBreakMinutes > 0 -> ChangeDirection.TIGHTEN
                old.sessionBreakMinutes > 0 && new.sessionBreakMinutes == 0 -> ChangeDirection.LOOSEN
                new.sessionBreakMinutes > old.sessionBreakMinutes -> ChangeDirection.TIGHTEN
                else -> ChangeDirection.LOOSEN
            }
            changes += FieldChange(
                "sessionBreakMinutes", dir,
                "Forced break ${minutes(old.sessionBreakMinutes)} → ${minutes(new.sessionBreakMinutes)}"
            )
        }

        // frictionSteps: removing a toggle = looser, adding = stricter.
        if (old.frictionSteps != new.frictionSteps) {
            val removed = old.frictionSteps - new.frictionSteps
            val added = new.frictionSteps - old.frictionSteps
            val dir = when {
                removed.isNotEmpty() -> ChangeDirection.LOOSEN
                added.isNotEmpty() -> ChangeDirection.TIGHTEN
                else -> ChangeDirection.NEUTRAL
            }
            changes += FieldChange(
                "frictionSteps", dir,
                "Friction steps: +${added.size}, -${removed.size}"
            )
        }

        // mathDifficulty: easy < medium < hard; easier = looser. Unknown wire
        // values (future difficulty tiers a newer client wrote) default to
        // rank 1 ("medium") — an arbitrary safe middle, not a real ordering.
        if (old.mathDifficulty != new.mathDifficulty) {
            val oldRank = mathDifficultyRank[old.mathDifficulty] ?: 1
            val newRank = mathDifficultyRank[new.mathDifficulty] ?: 1
            val dir = if (newRank > oldRank) ChangeDirection.TIGHTEN else ChangeDirection.LOOSEN
            changes += FieldChange(
                "mathDifficulty", dir,
                "Math difficulty ${old.mathDifficulty} → ${new.mathDifficulty}"
            )
        }

        return changes
    }

    /** Overall direction: LOOSEN if ANY field loosens, else TIGHTEN if any tightens, else NEUTRAL. */
    fun classify(old: PolicySnapshot, new: PolicySnapshot): ChangeDirection {
        val changes = diff(old, new)
        return when {
            changes.any { it.direction == ChangeDirection.LOOSEN } -> ChangeDirection.LOOSEN
            changes.any { it.direction == ChangeDirection.TIGHTEN } -> ChangeDirection.TIGHTEN
            else -> ChangeDirection.NEUTRAL
        }
    }

    data class SplitResult(
        val applyNow: PolicySnapshot,
        val deferred: PolicySnapshot?,
        val deferredChanges: List<FieldChange>
    )

    /**
     * Splits a requested edit: tightening/neutral fields apply to [old] right
     * away, loosening fields wait. [deferred] is the full requested policy
     * (not just the loosened fields) so that when it eventually takes effect it
     * replaces the whole doc consistently; it is null when nothing loosens.
     */
    fun split(old: PolicySnapshot, new: PolicySnapshot): SplitResult {
        val changes = diff(old, new)
        val loosened = changes.filter { it.direction == ChangeDirection.LOOSEN }
        if (loosened.isEmpty()) {
            return SplitResult(applyNow = new, deferred = null, deferredChanges = emptyList())
        }

        val loosenedFields = loosened.map { it.field.substringBefore('.') }.toSet()

        var applyNow = old
        if ("dailyLimitMinutes" !in loosenedFields) applyNow = applyNow.copy(dailyLimitMinutes = new.dailyLimitMinutes)
        if ("hardBlockAfterLimit" !in loosenedFields) applyNow = applyNow.copy(hardBlockAfterLimit = new.hardBlockAfterLimit)
        if ("extensionMinutes" !in loosenedFields) applyNow = applyNow.copy(extensionMinutes = new.extensionMinutes)
        if ("dailyExtensionLimit" !in loosenedFields) applyNow = applyNow.copy(dailyExtensionLimit = new.dailyExtensionLimit)
        if ("blockedSurfaces" !in loosenedFields) applyNow = applyNow.copy(blockedSurfaces = new.blockedSurfaces)
        if ("reelDailyLimit" !in loosenedFields) applyNow = applyNow.copy(reelDailyLimit = new.reelDailyLimit)
        if ("sessionMaxMinutes" !in loosenedFields) applyNow = applyNow.copy(sessionMaxMinutes = new.sessionMaxMinutes)
        if ("sessionBreakMinutes" !in loosenedFields) applyNow = applyNow.copy(sessionBreakMinutes = new.sessionBreakMinutes)
        if ("frictionSteps" !in loosenedFields) applyNow = applyNow.copy(frictionSteps = new.frictionSteps)
        if ("mathDifficulty" !in loosenedFields) applyNow = applyNow.copy(mathDifficulty = new.mathDifficulty)

        // appModes is per-app keyed (appModes.<pkg>); only defer the packages that
        // actually loosened, apply the rest (including newly-added tightenings).
        if ("appModes" !in loosenedFields) {
            applyNow = applyNow.copy(appModes = new.appModes)
        } else {
            val loosenedPkgs = loosened.filter { it.field.startsWith("appModes.") }
                .map { it.field.removePrefix("appModes.") }.toSet()
            val merged = applyNow.appModes.toMutableMap()
            for (pkg in old.appModes.keys + new.appModes.keys) {
                if (pkg in loosenedPkgs) continue // keep old.appModes[pkg] (already the default in applyNow)
                new.appModes[pkg]?.let { merged[pkg] = it } ?: merged.remove(pkg)
            }
            applyNow = applyNow.copy(appModes = merged)
        }

        // targetApps: union of old and new is always at least as strict as
        // either — an app present in either set is still being managed, so
        // additions (tightenings) land immediately while removals (loosenings,
        // apps missing from `new`) keep being enforced until the deferred change
        // takes effect. Never compute this as `new.targetApps` directly: that
        // would apply a pending removal (a loosening) right away.
        applyNow = applyNow.copy(targetApps = old.targetApps + new.targetApps)

        // blockWindows: without window identity, per-window matching isn't
        // reliable, but the union of both lists is always at least as strict as
        // either individually — a moment is blocked if ANY window in the union
        // covers it, so adding/growing a window is reflected immediately while a
        // removed/shrunk window keeps blocking (via its still-present old
        // version) until the deferred change replaces the list outright. Do NOT
        // "simplify" this to `new.blockWindows` — that would let a loosened
        // (removed/shrunk) window take effect before its delay elapses.
        applyNow = applyNow.copy(
            blockWindows = (old.blockWindows + new.blockWindows).distinct()
        )

        return SplitResult(applyNow = applyNow, deferred = new, deferredChanges = loosened)
    }

    /** A user-set "don't let me weaken this until <date>" commitment. */
    fun isLocked(lockedUntilMs: Long?, nowMs: Long): Boolean =
        lockedUntilMs != null && nowMs < lockedUntilMs

    /** When a loosening requested now should take effect. */
    fun effectiveAtMs(nowMs: Long, delayHours: Int): Long =
        nowMs + delayHours * 60L * 60L * 1000L

    /** Is a pending change due yet? Due exactly at the boundary instant. */
    fun isDue(effectiveAtMs: Long, nowMs: Long): Boolean = nowMs >= effectiveAtMs

    /**
     * The delay itself is ratcheted, otherwise it is a trivial bypass: raising
     * the delay (more caution) applies immediately, lowering it (less caution)
     * is itself a loosening subject to the current delay.
     */
    fun classifyDelayChange(oldHours: Int, newHours: Int): ChangeDirection = when {
        newHours > oldHours -> ChangeDirection.TIGHTEN
        newHours < oldHours -> ChangeDirection.LOOSEN
        else -> ChangeDirection.NEUTRAL
    }
}
