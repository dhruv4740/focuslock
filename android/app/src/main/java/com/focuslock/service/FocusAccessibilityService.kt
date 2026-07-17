package com.focuslock.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.focuslock.surface.SurfaceBlocker

class FocusAccessibilityService : AccessibilityService() {

    companion object {
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
        // No startForeground() here: accessibility services are kept alive by the
        // OS's AccessibilityManager/WindowManagerService binding, not the
        // foreground-service-kill policy a persistent notification exists to work
        // around. UsageMonitorService is the one that genuinely needs it.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        SurfaceBlocker.flushNow()
        return super.onUnbind(intent)
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
        SurfaceBlocker.flushNow()
    }
}
