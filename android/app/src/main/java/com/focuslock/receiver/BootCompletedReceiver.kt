package com.focuslock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.focuslock.service.UsageMonitorService

class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {

            Log.d(TAG, "Boot/update received, starting UsageMonitorService")

            val serviceIntent = Intent(context, UsageMonitorService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service on boot", e)
            }
        }
    }
}
