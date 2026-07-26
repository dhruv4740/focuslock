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
        // Android grants disable once the user confirms this dialog — this message
        // cannot actually block that, so it must not claim otherwise.
        val prefs = context.getSharedPreferences("FocusLockPrefs", Context.MODE_PRIVATE)
        val isBlocked = prefs.getBoolean("is_currently_blocked", false)
        if (isBlocked) {
            return "Disabling this will remove FocusLock's ability to enforce your limits, including the active session right now. Do this only if you mean to stop being protected."
        }
        return super.onDisableRequested(context, intent)
    }
}
