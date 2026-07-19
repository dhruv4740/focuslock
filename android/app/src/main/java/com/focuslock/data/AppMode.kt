package com.focuslock.data

/**
 * Per-app enforcement mode, stored in the policy doc's additive `appModes` map
 * (pkg → wire value). Legacy compatibility: an app present in `targetApps` with
 * no `appModes` entry behaves as [LIMIT] — exactly the pre-modes behavior — so
 * old policy docs and apps added from the Windows dashboard keep working.
 */
enum class AppMode(val wire: String, val label: String) {
    OFF("off", "Off"),
    FRICTION_ONLY("friction_only", "Friction only"),
    LIMIT("limit", "Daily limit"),
    HARD_BLOCK("hard_block", "Hard block");

    companion object {
        fun fromWire(value: String?, inTargetApps: Boolean): AppMode =
            entries.firstOrNull { it.wire == value } ?: if (inTargetApps) LIMIT else OFF
    }
}
