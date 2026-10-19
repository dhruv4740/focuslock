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

        val isConnected: Boolean get() = instance != null
    }

    // Package forwarded to UsageMonitorService by the last TYPE_WINDOW_STATE_CHANGED
    // event — lets onAccessibilityEvent skip the IPC round trip when the foreground
    // app hasn't actually changed (that event type also fires for dialogs/fragments
    // within the same app).
    private var lastForwardedPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        OnDemandAccessibility.setParked(this, false)
        SurfaceBlocker.reset()
        // Re-enable signal, paired with the disable recorded in onUnbind below —
        // closes out any open "enforcement stopped" entry with a real duration.
        try {
            UsageMonitorService.closeAccessibilityDisabled(this)
        } catch (e: Exception) {
            // Best-effort — must not block the monitor restart below.
        }
        // UsageMonitorService is a plain (non-foreground) service; the accessibility
        // binding above is what actually keeps this process alive, so restart the
        // monitor here rather than relying solely on BootCompletedReceiver — this is
        // the authoritative restart path after boot or a process kill.
        try {
            startService(Intent(this, UsageMonitorService::class.java)
                .setAction(UsageMonitorService.ACTION_CHECK_PROTECTION))
        } catch (e: Exception) {
            // Best-effort; a later foreground-change event will retry via startService below.
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        // Direct, immediate signal that enforcement just stopped — fires on a
        // deliberate accessibility disable, but also on app update, process
        // kill, and device shutdown. Not proof of tampering by itself; see
        // UsageMonitorService.closeAccessibilityDisabled for the boot/replace
        // cross-reference that downgrades the explainable cases. Wrapped
        // defensively so a failure here can never cost the reel-count flush.
        // A self-park (see maybePark) is not an enforcement gap.
        try {
            if (!OnDemandAccessibility.isParked(this)) UsageMonitorService.recordAccessibilityDisabled(this)
        } catch (e: Exception) {
            // Best-effort — must not block SurfaceBlocker.flushNow() below.
        }
        // Nudge the watchdog notification on immediately rather than waiting for
        // the next poll — best-effort since the monitor service (or the whole
        // process) may already be going down for the same reason this unbound.
        try {
            startService(Intent(this, UsageMonitorService::class.java)
                .setAction(UsageMonitorService.ACTION_CHECK_PROTECTION))
        } catch (e: Exception) {
            // Best-effort.
        }
        SurfaceBlocker.flushNow()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null) {
            if (pkg != lastForwardedPackage || !UsageMonitorService.isServiceActive) {
                val intent = Intent(this, UsageMonitorService::class.java).apply {
                    action = "com.focuslock.ACTION_FOREGROUND_CHANGED"
                    putExtra("package_name", pkg)
                }
                try {
                    startService(intent)
                    lastForwardedPackage = pkg
                } catch (e: Exception) {
                    // Fail-safe for background limitations (though accessibility services are immune)
                }
            }
        }
        SurfaceBlocker.onAccessibilityEvent(event, this)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null) maybePark(pkg)
    }

    // Leaving managed apps: turn the service off so banking apps opened next don't
    // see it enabled. UsageMonitorService's poller turns it back on. Our own
    // overlays and transient system windows (shade, keyboard) don't count as leaving.
    private fun maybePark(pkg: String) {
        if (pkg == packageName || OnDemandAccessibility.isTransientSystemPackage(pkg)) return
        if (OnDemandAccessibility.needsService(pkg) || !OnDemandAccessibility.canManage(this)) return
        OnDemandAccessibility.setParked(this, true)
        disableSelf()
    }

    override fun onInterrupt() {
        SurfaceBlocker.flushNow()
    }
}
