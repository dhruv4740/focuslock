package com.focuslock.surface

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
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
    private const val GONE_HYSTERESIS_MS = 1200L
    private const val COUNTS_PREFS_NAME = "FocusLockReelCounts"

    @Volatile private var modes: Map<BlockableSurface, SurfaceMode> = emptyMap()
    @Volatile private var reelLimits: Map<BlockableSurface, Int> = emptyMap()
    @Volatile private var reelCounts: Map<BlockableSurface, Int> = emptyMap()
    @Volatile private var pendingDelta: Map<BlockableSurface, Int> = emptyMap()
    @Volatile private var trackedDate: String = ""
    @Volatile private var activeSessionSurface: BlockableSurface? = null
    @Volatile private var lastFlushAtMs = 0L
    @Volatile private var appContext: android.content.Context? = null

    private val tracker = SurfaceSessionTracker()
    private val flushExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var lastProbeAtMs = 0L
    @Volatile private var lastPresentAtMs = 0L

    /**
     * Restores today's locally persisted counts so the badge and the daily limit
     * are correct immediately after a process restart — the Firestore seed arrives
     * later (or never, offline) and merges via [seedReelCounts]. Local persistence
     * exists because up to [FLUSH_THROTTLE_MS] of count deltas used to die with
     * the process, silently losing reels from the daily quota.
     */
    fun init(context: android.content.Context) {
        appContext = context.applicationContext
        val prefs = context.applicationContext
            .getSharedPreferences(COUNTS_PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val savedDate = prefs.getString("date", "") ?: ""
        if (savedDate == LocalDate.now().toString() && trackedDate.isEmpty()) {
            trackedDate = savedDate
            reelCounts = BlockableSurface.entries
                .associateWith { prefs.getInt("count_${it.wireKey}", 0) }
                .filterValues { it > 0 }
        }
    }

    private fun persistCounts() {
        val ctx = appContext ?: return
        val editor = ctx.getSharedPreferences(COUNTS_PREFS_NAME, android.content.Context.MODE_PRIVATE).edit()
        editor.putString("date", trackedDate)
        BlockableSurface.entries.forEach { editor.putInt("count_${it.wireKey}", reelCounts[it] ?: 0) }
        editor.apply()
    }

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
        // Merge, don't overwrite: in-memory counts may already hold today's locally
        // persisted total (init) plus increments recorded before this seed arrived.
        // Only this device increments counts, so max() is the true total; when the
        // local side is ahead (deltas died unflushed with a previous process), the
        // difference is re-queued so Firestore catches up.
        val carryOver = pendingDelta
        val localCounts = if (trackedDate == date) reelCounts else emptyMap()
        trackedDate = date
        val merged = BlockableSurface.entries.associateWith { surface ->
            maxOf(
                localCounts[surface] ?: 0,
                (counts[surface.wireKey] ?: 0) + (carryOver[surface] ?: 0)
            )
        }
        val missingFromServer = BlockableSurface.entries.mapNotNull { surface ->
            val missing = (merged[surface] ?: 0) - (counts[surface.wireKey] ?: 0) - (carryOver[surface] ?: 0)
            if (missing > 0) surface to ((carryOver[surface] ?: 0) + missing) else null
        }.toMap()
        reelCounts = merged
        if (missingFromServer.isNotEmpty()) {
            pendingDelta = carryOver + missingFromServer
        }
        persistCounts()
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
        persistCounts()
    }

    // Null root (rare — window mid-transition) counts the reel without the ad
    // check: better to occasionally count an ad than to silently drop real reels.
    private fun recordReelView(surface: BlockableSurface, root: AccessibilityNodeInfo?) {
        if (root != null) {
            val keywords = SurfaceMarkers.adKeywords[surface] ?: emptySet()
            val looksLikeAd = keywords.any { kw -> !root.findAccessibilityNodeInfosByText(kw).isNullOrEmpty() }
            if (looksLikeAd) {
                Log.d(TAG, "Skipping reel count for ${surface.wireKey} — ad heuristic matched")
                return
            }
        }
        reelCounts = reelCounts + (surface to ((reelCounts[surface] ?: 0) + 1))
        pendingDelta = pendingDelta + (surface to ((pendingDelta[surface] ?: 0) + 1))
        persistCounts()
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
                val sourceEntry = SurfaceMarkers.entryName(event.source?.viewIdResourceName)
                if (sourceEntry != null && sourceEntry in (SurfaceMarkers.pagerIdNames[surface] ?: emptySet())) {
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
                probePresence(surface, mode, pkg, service, nowMs)
            else -> null
        }

        when (decision) {
            SurfaceDecision.PRESS_BACK -> {
                Log.d(TAG, "Blocking ${surface.wireKey} — pressing Back")
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
            SurfaceDecision.GRANT_ENTRY -> {
                // From a presence probe (normal entry) or a pager scroll (adopted
                // session whose entry event was missed) — both count one reel.
                activeSessionSurface = surface
                recordReelView(surface, service.rootInActiveWindow)
            }
            else -> {}
        }
    }

    // Throttled: content-changed events fire rapidly while a reel plays (captions,
    // like-count ticking, etc.) and a full-tree ID lookup on every one is wasteful.
    private fun probePresence(
        surface: BlockableSurface,
        mode: SurfaceMode,
        pkg: String,
        service: AccessibilityService,
        nowMs: Long
    ): SurfaceDecision? {
        if (nowMs - lastProbeAtMs < PRESENCE_PROBE_THROTTLE_MS) return null
        lastProbeAtMs = nowMs
        rolloverIfNeeded()

        val ids = SurfaceMarkers.presenceIdCandidates(surface, pkg)
        if (ids.isEmpty()) return null
        val root = service.rootInActiveWindow ?: return null
        val windowBounds = Rect().also { root.getBoundsInScreen(it) }
        val nodeBounds = Rect()
        // A matched view ID alone isn't enough — Instagram/YouTube reuse reel view IDs
        // for feed-embedded posts, which would otherwise false-positive as the viewer
        // and trigger a Back press that just refreshes the feed. Require the node to
        // actually fill the window, which only the real fullscreen viewer does.
        val present = ids.any { id ->
            root.findAccessibilityNodeInfosByViewId(id).orEmpty().any { node ->
                node.isVisibleToUser &&
                    run {
                        node.getBoundsInScreen(nodeBounds)
                        isViewerSizedBounds(
                            nodeBounds.width(),
                            nodeBounds.height(),
                            windowBounds.width(),
                            windowBounds.height()
                        )
                    }
            }
        }

        if (!present) {
            // Hysteresis: a comments sheet, share dialog, or IME briefly becoming
            // the active window makes a single probe miss the viewer that is still
            // there behind it. Tearing the session down on one miss caused a fresh
            // GRANT_ENTRY (double count) when the next probe saw the viewer again.
            if (nowMs - lastPresentAtMs < GONE_HYSTERESIS_MS) return null
            tracker.onViewerGone(nowMs)
            if (activeSessionSurface == surface) {
                activeSessionSurface = null
                flushNow()
            }
            return null
        }

        lastPresentAtMs = nowMs
        return tracker.onViewerVisible(surface, mode, nowMs)
    }
}
