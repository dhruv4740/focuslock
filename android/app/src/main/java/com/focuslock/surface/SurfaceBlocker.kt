package com.focuslock.surface

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.focuslock.data.FocusPolicyClient
import java.time.LocalDate
import java.util.concurrent.Executors

/**
 * Android-side wiring for reel/short surface detection. Owns the single shared
 * [SurfaceSessionTracker] and translates accessibility events into presence/scroll
 * probes. Called from [com.focuslock.service.FocusAccessibilityService] on every
 * event; app-level blocking (targetApps) is a separate flow in UsageMonitorService
 * and always wins on its own — its HOME action supersedes any Back press here.
 *
 * Also owns the daily reel counter: one-at-a-time grants are counted (best-effort
 * skipping ad-labeled reels), and once a configured daily limit is reached the
 * effective mode for that surface auto-upgrades to BLOCK until local midnight.
 * All mutable state here is read from the accessibility-event (main) thread but
 * written from arbitrary background threads (policy Save, policy refresh) — every
 * field is therefore a @Volatile immutable-value reassignment, never mutated
 * in place, matching the rest of this object.
 */
object SurfaceBlocker {
    private const val TAG = "SurfaceBlocker"
    private const val PRESENCE_PROBE_THROTTLE_MS = 500L
    private const val FLUSH_THROTTLE_MS = 15_000L

    @Volatile private var modes: Map<BlockableSurface, SurfaceMode> = emptyMap()
    @Volatile private var reelLimits: Map<BlockableSurface, Int> = emptyMap()
    @Volatile private var reelCounts: Map<BlockableSurface, Int> = emptyMap()
    @Volatile private var pendingDelta: Map<BlockableSurface, Int> = emptyMap()
    @Volatile private var trackedDate: String = ""
    @Volatile private var activeSessionSurface: BlockableSurface? = null
    @Volatile private var lastFlushAtMs = 0L

    private val tracker = SurfaceSessionTracker()
    private val flushExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var lastProbeAtMs = 0L

    val isActive: Boolean
        get() = modes.values.any { it != SurfaceMode.OFF }

    /** Read-only snapshot for the overlay badge — no side effects. */
    data class ReelStatus(val enabled: Boolean, val count: Int, val limit: Int, val blocked: Boolean)

    fun statusFor(surface: BlockableSurface): ReelStatus {
        val configured = modes[surface] ?: SurfaceMode.OFF
        val limit = reelLimits[surface] ?: 0
        val count = reelCounts[surface] ?: 0
        return ReelStatus(
            enabled = configured != SurfaceMode.OFF,
            count = count,
            limit = limit,
            blocked = limit > 0 && count >= limit
        )
    }

    /** Called from UsageMonitorService.refreshPolicy() and after a policy Save. */
    fun updateConfig(wire: Map<String, String>, reelLimits: Map<String, Int> = emptyMap()) {
        modes = BlockableSurface.entries.associateWith { SurfaceMode.fromWire(wire[it.wireKey]) }
        this.reelLimits = BlockableSurface.entries.associateWith { reelLimits[it.wireKey] ?: 0 }
        tracker.reset()
        activeSessionSurface = null
    }

    /** Called once from UsageMonitorService.onCreate() to seed today's counts from Firestore. */
    fun seedReelCounts(date: String, counts: Map<String, Int>) {
        // Preserve any not-yet-flushed increments recorded before this seed arrived
        // (accessibility events can start flowing before the Firestore read returns).
        val carryOver = pendingDelta
        trackedDate = date
        reelCounts = BlockableSurface.entries.associateWith { surface ->
            (counts[surface.wireKey] ?: 0) + (carryOver[surface] ?: 0)
        }
    }

    fun reset() {
        tracker.reset()
        activeSessionSurface = null
    }

    /** Forces an immediate Firestore flush of any unflushed reel-count deltas. */
    fun flushNow() = maybeFlush(force = true)

    private fun effectiveMode(surface: BlockableSurface): SurfaceMode {
        val configured = modes[surface] ?: SurfaceMode.OFF
        if (configured == SurfaceMode.OFF) return SurfaceMode.OFF
        val limit = reelLimits[surface] ?: 0
        if (limit > 0 && (reelCounts[surface] ?: 0) >= limit) return SurfaceMode.BLOCK
        return configured
    }

    private fun rolloverIfNeeded() {
        val today = LocalDate.now().toString()
        if (today == trackedDate) return
        // Flush whatever the previous day accumulated under its own date before
        // resetting, so a midnight rollover mid-session never silently drops counts.
        if (pendingDelta.isNotEmpty() && trackedDate.isNotEmpty()) {
            val staleDate = trackedDate
            val deltas = pendingDelta.mapKeys { it.key.wireKey }
            flushExecutor.submit {
                try {
                    FocusPolicyClient.incrementReelCounts(staleDate, deltas)
                } catch (e: Exception) {
                    Log.w(TAG, "Reel count flush failed", e)
                }
            }
        }
        trackedDate = today
        reelCounts = emptyMap()
        pendingDelta = emptyMap()
    }

    private fun recordReelView(surface: BlockableSurface, root: AccessibilityNodeInfo) {
        val keywords = SurfaceMarkers.adKeywords[surface] ?: emptySet()
        val looksLikeAd = keywords.any { kw -> !root.findAccessibilityNodeInfosByText(kw).isNullOrEmpty() }
        if (looksLikeAd) {
            Log.d(TAG, "Skipping reel count for ${surface.wireKey} — ad heuristic matched")
            return
        }
        reelCounts = reelCounts + (surface to ((reelCounts[surface] ?: 0) + 1))
        pendingDelta = pendingDelta + (surface to ((pendingDelta[surface] ?: 0) + 1))
        maybeFlush()
    }

    private fun maybeFlush(force: Boolean = false) {
        if (pendingDelta.isEmpty()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastFlushAtMs < FLUSH_THROTTLE_MS) return
        lastFlushAtMs = now
        val date = trackedDate
        val deltas = pendingDelta.mapKeys { it.key.wireKey }
        pendingDelta = emptyMap()
        flushExecutor.submit {
            try {
                FocusPolicyClient.incrementReelCounts(date, deltas)
            } catch (e: Exception) {
                Log.w(TAG, "Reel count flush failed", e)
            }
        }
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
                if (activeSessionSurface != null) {
                    activeSessionSurface = null
                    flushNow()
                }
            }
            return
        }

        val mode = effectiveMode(surface)
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
        rolloverIfNeeded()

        val ids = SurfaceMarkers.presenceIds[surface] ?: return null
        val root = service.rootInActiveWindow ?: return null
        val present = ids.any { id -> !root.findAccessibilityNodeInfosByViewId(id).isNullOrEmpty() }

        if (!present) {
            tracker.onViewerGone(nowMs)
            if (activeSessionSurface == surface) {
                activeSessionSurface = null
                flushNow()
            }
            return null
        }

        return when (val decision = tracker.onViewerVisible(surface, mode, nowMs)) {
            SurfaceDecision.GRANT_ENTRY -> {
                activeSessionSurface = surface
                recordReelView(surface, root)
                SurfaceDecision.ALLOW
            }
            else -> decision
        }
    }
}
