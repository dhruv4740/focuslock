package com.focuslock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.focuslock.service.OnDemandAccessibility
import com.focuslock.service.UsageMonitorService
import com.focuslock.update.UpdateChecker

// Shared with UsageMonitorService's heartbeat-gap watchdog — see
// EnforcementHealth.detectGap. Same "FocusLockPrefs" file the rest of the app
// uses (FocusPolicyClient, MainActivity).
private const val PREFS_NAME = "FocusLockPrefs"
private const val PREF_BOOT_COMPLETED_AT_MS = "boot_completed_at_ms"

// Best-effort restart: UsageMonitorService is a plain (non-foreground) service, so
// starting it here can be dropped by OEM background-start restrictions on some
// devices. That's acceptable — FocusAccessibilityService.onServiceConnected() is the
// authoritative restart path, since the OS rebinds enabled accessibility services
// automatically after boot regardless of what this receiver manages.
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {

            Log.d(TAG, "Boot/update received, starting UsageMonitorService")

            // Record so the watchdog can tell "device was off" apart from
            // "process was killed / accessibility disabled while running" —
            // a boot landing inside a heartbeat gap explains it.
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(PREF_BOOT_COMPLETED_AT_MS, System.currentTimeMillis()).apply()

            try {
                context.startService(Intent(context, UsageMonitorService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service on boot", e)
            }
            // A service parked at shutdown/update isn't rebound by the OS; switch it
            // back on so onServiceConnected restarts the monitor as usual. It parks
            // again on the first non-managed app.
            OnDemandAccessibility.enable(context)
        }

        if (intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            UpdateChecker.cleanupDownloadedApk(context)
        }
    }
}
