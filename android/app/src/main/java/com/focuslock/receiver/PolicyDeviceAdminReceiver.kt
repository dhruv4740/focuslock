package com.focuslock.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class PolicyDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d("DeviceAdmin", "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d("DeviceAdmin", "Device admin disabled")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence? {
        val prefs = context.getSharedPreferences("FocusLockPrefs", Context.MODE_PRIVATE)
        val isBlocked = prefs.getBoolean("is_currently_blocked", false)
        if (isBlocked) {
            return "WARNING: FocusLock is currently enforcing active limits! You cannot deactivate the protection until your session ends."
        }
        return super.onDisableRequested(context, intent)
    }
}
