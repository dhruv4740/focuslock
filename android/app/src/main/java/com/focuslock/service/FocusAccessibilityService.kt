package com.focuslock.service

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.focuslock.R
import com.focuslock.surface.SurfaceBlocker

class FocusAccessibilityService : AccessibilityService() {

    companion object {
        private const val CHANNEL_ID = "focuslock-accessibility"
        private const val NOTIFICATION_ID = 1102

        // Live reference to the connected service so other components can issue
        // global gestures without owning their own gesture-dispatch path.
        @Volatile private var instance: FocusAccessibilityService? = null

        // Sends the device to the launcher, pushing the current (blocked) app off
        // the foreground so its media playback stops. An overlay alone leaves the
        // blocked app RESUMED and still playing audio behind it. Returns false when
        // the service isn't connected so callers can fall back to a HOME intent.
        fun sendToHome(): Boolean =
            instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) ?: false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        SurfaceBlocker.reset()
        startForegroundServiceNotification()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FocusLock Active Shield",
                NotificationManager.IMPORTANCE_MIN
            )
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("FocusLock Shield Active")
            .setContentText("Protecting your focus sessions")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            val intent = Intent(this, UsageMonitorService::class.java).apply {
                action = "com.focuslock.ACTION_FOREGROUND_CHANGED"
                putExtra("package_name", packageName)
            }
            try {
                startService(intent)
            } catch (e: Exception) {
                // Fail-safe for background limitations (though accessibility services are immune)
            }
        }
        SurfaceBlocker.onAccessibilityEvent(event, this)
    }

    override fun onInterrupt() {
        // No-op.
    }
}

