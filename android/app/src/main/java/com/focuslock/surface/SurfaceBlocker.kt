package com.focuslock.surface

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Android-side wiring for reel/short surface detection. Owns the single shared
 * [SurfaceSessionTracker] and translates accessibility events into presence/scroll
 * probes. Called from [com.focuslock.service.FocusAccessibilityService] on every
 * event; app-level blocking (targetApps) is a separate flow in UsageMonitorService
 * and always wins on its own — its HOME action supersedes any Back press here.
 */
object SurfaceBlocker {
    private const val TAG = "SurfaceBlocker"
    private const val PRESENCE_PROBE_THROTTLE_MS = 500L

    @Volatile private var modes: Map<BlockableSurface, SurfaceMode> = emptyMap()
    private val tracker = SurfaceSessionTracker()
    @Volatile private var lastProbeAtMs = 0L

    val isActive: Boolean
        get() = modes.values.any { it != SurfaceMode.OFF }

    /** Called from UsageMonitorService.refreshPolicy() and after a policy Save. */
    fun updateConfig(wire: Map<String, String>) {
        modes = BlockableSurface.entries.associateWith { SurfaceMode.fromWire(wire[it.wireKey]) }
        tracker.reset()
    }

    fun reset() {
        tracker.reset()
    }

    private fun isTransientSystemPackage(pkg: String) =
        pkg == "android" ||
        pkg == "com.android.systemui" ||
        pkg.contains("inputmethod") ||
        pkg.contains("keyboard") ||
        pkg.contains("telecom") ||
        pkg.contains("phone")

    fun onAccessibilityEvent(event: AccessibilityEvent, service: AccessibilityService) {
        if (!isActive) return
        val pkg = event.packageName?.toString() ?: return
        val nowMs = System.currentTimeMillis()

        val surface = BlockableSurface.byPackage(pkg)
        if (surface == null) {
            // Left the app the tracker was watching — but not for a transient system
            // window (keyboard/autofill popping up over IG/YT), which would wrongly
            // reset an in-progress one-at-a-time grant.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !isTransientSystemPackage(pkg)) {
                tracker.onViewerGone(nowMs)
            }
            return
        }

        val mode = modes[surface] ?: SurfaceMode.OFF
        if (mode == SurfaceMode.OFF) return

        val decision = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val sourceId = event.source?.viewIdResourceName
                if (sourceId != null && sourceId in (SurfaceMarkers.pagerIds[surface] ?: emptySet())) {
                    tracker.onPagerScrolled(
                        surface = surface,
                        mode = mode,
                        fromIndex = event.fromIndex,
                        toIndex = event.toIndex,
                        scrollDeltaY = event.scrollDeltaY,
                        nowMs = nowMs
                    )
                } else {
                    null
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                probePresence(surface, mode, service, nowMs)
            else -> null
        }

        if (decision == SurfaceDecision.PRESS_BACK) {
            Log.d(TAG, "Blocking ${surface.wireKey} — pressing Back")
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    // Throttled: content-changed events fire rapidly while a reel plays (captions,
    // like-count ticking, etc.) and a full-tree ID lookup on every one is wasteful.
    private fun probePresence(
        surface: BlockableSurface,
        mode: SurfaceMode,
        service: AccessibilityService,
        nowMs: Long
    ): SurfaceDecision? {
        if (nowMs - lastProbeAtMs < PRESENCE_PROBE_THROTTLE_MS) return null
        lastProbeAtMs = nowMs

        val ids = SurfaceMarkers.presenceIds[surface] ?: return null
        val root = service.rootInActiveWindow ?: return null
        val present = ids.any { id -> !root.findAccessibilityNodeInfosByViewId(id).isNullOrEmpty() }

        return if (present) {
            tracker.onViewerVisible(surface, mode, nowMs)
        } else {
            tracker.onViewerGone(nowMs)
            null
        }
    }
}
