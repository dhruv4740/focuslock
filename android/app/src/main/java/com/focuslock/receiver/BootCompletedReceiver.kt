package com.focuslock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.focuslock.service.UsageMonitorService
import com.focuslock.update.UpdateChecker

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

            try {
                context.startService(Intent(context, UsageMonitorService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service on boot", e)
            }
        }

        if (intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            UpdateChecker.cleanupDownloadedApk(context)
        }
    }
}
