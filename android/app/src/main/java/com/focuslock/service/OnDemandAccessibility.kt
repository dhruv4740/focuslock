package com.focuslock.service

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.focuslock.surface.BlockableSurface
import com.focuslock.surface.SurfaceBlocker

/**
 * Keeps FocusAccessibilityService enabled only while a reel surface is in the
 * foreground. Banking apps refuse to run while any third-party
 * accessibility service is enabled, so the service parks itself (disableSelf) on
 * leaving those apps and UsageMonitorService's usage-stats poller switches it back
 * on when one returns.
 *
 * Needs WRITE_SECURE_SETTINGS, granted once over adb:
 *   adb shell pm grant com.focuslock android.permission.WRITE_SECURE_SETTINGS
 * (OnePlus/OxygenOS: Developer options > "Disable system optimisation" on first).
 * Without the grant nothing here does anything and the service stays always-on.
 */
object OnDemandAccessibility {
    private const val TAG = "OnDemandAccessibility"
    private const val PREFS_NAME = "FocusLockPrefs"
    private const val PREF_PARKED = "a11y_parked"
    // Rebinding takes a moment; don't rewrite the setting on every poll tick meanwhile.
    private const val ENABLE_RETRY_MS = 3000L
    // A usage-stats read just after parking can still report the app being left.
    private const val PARK_SETTLE_MS = 1500L

    @Volatile private var lastEnableAtMs = 0L
    @Volatile private var parkedAtMs = 0L

    fun canManage(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    // Only reel surfaces need the accessibility service (they are detected from the
    // view tree). Time limits, friction and the lock overlay run off the usage-stats
    // poller in UsageMonitorService, so plain managed apps no longer keep it on.
    fun needsService(pkg: String): Boolean =
        BlockableSurface.byPackage(pkg, SurfaceBlocker.learnedAliases()) != null

    fun isTransientSystemPackage(pkg: String) =
        pkg == "android" ||
        pkg == "com.android.systemui" ||
        pkg.contains("inputmethod") ||
        pkg.contains("keyboard") ||
        pkg.contains("telecom") ||
        pkg.contains("phone")

    /** True while the service is off because it parked itself, not because it was revoked. */
    fun isParked(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREF_PARKED, false)

    fun setParked(context: Context, parked: Boolean) {
        if (parked) parkedAtMs = SystemClock.elapsedRealtime()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_PARKED, parked).apply()
    }

    /** Adds the service to the enabled list. Returns false without the grant. */
    fun enable(context: Context): Boolean {
        if (!canManage(context)) return false
        val now = SystemClock.elapsedRealtime()
        if (now - parkedAtMs < PARK_SETTLE_MS || now - lastEnableAtMs < ENABLE_RETRY_MS) return true
        lastEnableAtMs = now
        val component = ComponentName(context, FocusAccessibilityService::class.java)
        val cr = context.contentResolver
        return try {
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val present = cur.split(':').any { ComponentName.unflattenFromString(it) == component }
            if (!present) {
                val cn = component.flattenToString()
                Settings.Secure.putString(
                    cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    if (cur.isEmpty()) cn else "$cur:$cn"
                )
            }
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.d(TAG, "Accessibility service enabled on demand")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS rejected", e)
            false
        }
    }
}
