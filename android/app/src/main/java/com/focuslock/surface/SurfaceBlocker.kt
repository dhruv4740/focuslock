package com.focuslock.surface

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.focuslock.data.FocusPolicyClient
import java.time.LocalDate
import java.time.ZoneId
import java.util.EnumMap
import java.util.EnumSet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Android-side wiring for reel/short surface detection. Owns the single shared
 * [SurfaceSessionTracker] (enforcement) and [ReelImpressionCounter] (counting —
 * a separate pipeline that runs in every mode, including OFF) and translates
 * accessibility events into presence/scroll probes. Called from
 * [com.focuslock.service.FocusAccessibilityService] on every event; app-level
 * blocking (targetApps) is a separate flow in UsageMonitorService and always
 * wins on its own — its HOME action supersedes any Back press here.
 *
 * Counts state ([CountsState]) is read from the accessibility main thread and
 * written from [flushExecutor]'s background thread, so it lives in one
 * [AtomicReference] mutated via CAS (`updateAndGet`) rather than as separate
 * @Volatile fields, which could tear relative to each other. Everything else
 * (modes, trackers, probe-throttle maps) is confined to the main thread: reads
 * happen on the accessibility event thread, and the one place that used to
 * write from a background thread — [updateConfig] — now posts its body to
 * [mainHandler] instead of mutating in place.
 */
object SurfaceBlocker {
    private const val TAG = "SurfaceBlocker"
    private const val PRESENCE_PROBE_THROTTLE_MS = 500L
    private const val FLUSH_THROTTLE_MS = 15_000L
    private const val GONE_HYSTERESIS_MS = 1200L
    private const val COUNTS_PREFS_NAME = "FocusLockReelCounts"
    private const val POLICY_PREFS_NAME = "FocusLockSurfacePolicy"
    private const val ALIASES_PREFS_NAME = "FocusLockSurfaceAliases"
    private const val DETECTION_PREFS_NAME = "FocusLockSurfaceDetection"
    private const val MAX_LEARNED_ALIASES = 8

    /**
     * Immutable snapshot of the day's reel counts. [syncedCounts] is what Firestore
     * has acknowledged; `counts != syncedCounts` is the dirty check. [seedResolved]
     * gates the very first absolute write — until the cold-start Firestore seed has
     * returned (or failed, via [onSeedFailed]), a small local count must not clobber
     * a larger server total that hasn't been read yet.
     */
    private data class CountsState(
        val date: String = "",
        val counts: Map<BlockableSurface, Int> = emptyMap(),
        val syncedCounts: Map<BlockableSurface, Int> = emptyMap(),
        val seedResolved: Boolean = false
    )

    @Volatile private var modes: Map<BlockableSurface, SurfaceMode> = emptyMap()
    @Volatile private var reelLimits: Map<BlockableSurface, Int> = emptyMap()
    @Volatile private var appContext: Context? = null
    @Volatile private var lastFlushAtMs = 0L
    // Cached local-midnight boundary so the common case of rolloverIfNeeded is a
    // single `long` compare; recomputed on every actual rollover so DST shifts
    // (a 23h or 25h "day") are absorbed instead of drifting.
    @Volatile private var nextRolloverAtMs: Long = 0L
    // Once updateConfig runs for real this process, a persisted-policy restore
    // (which races it during init on a fresh process) must never clobber it.
    @Volatile private var configSetThisProcess = false
    // Packages resolved to a surface only via a learned pager-ID alias (e.g. the
    // user's actual Morphe applicationId), not the static set or vendor heuristic.
    @Volatile private var learnedAliases: Map<String, BlockableSurface> = emptyMap()
    @Volatile private var recentDetections: Map<BlockableSurface, DetectionInfo> = emptyMap()

    /** A user-facing receipt that the full-screen viewer was actually seen. */
    data class DetectionInfo(val atMs: Long, val packageName: String)

    private val countsRef = AtomicReference(CountsState())
    private val tracker = SurfaceSessionTracker()
    private val counter = ReelImpressionCounter()
    private val flushExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Per-surface probe-throttle state, main-thread-only. A single shared field
    // (the old design) let one surface's probe satisfy another's throttle window
    // or gone-hysteresis — e.g. an Instagram probe consuming YouTube's budget.
    private val lastProbeAtMs = EnumMap<BlockableSurface, Long>(BlockableSurface::class.java)
    private val lastPresentAtMs = EnumMap<BlockableSurface, Long>(BlockableSurface::class.java)
    private val activeSessionSurfaces: EnumSet<BlockableSurface> = EnumSet.noneOf(BlockableSurface::class.java)

    /**
     * Restores today's locally persisted counts, policy, and learned aliases so
     * detection and the badge are correct immediately after a process restart —
     * the Firestore seed arrives later (or never, offline) and merges via
     * [seedReelCounts].
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        loadLearnedAliases()
        loadPersistedPolicy()
        loadRecentDetections()

        val prefs = appContext!!.getSharedPreferences(COUNTS_PREFS_NAME, Context.MODE_PRIVATE)
        val savedDate = prefs.getString("date", "") ?: ""
        val today = LocalDate.now().toString()
        if (savedDate == today) {
            val restored = BlockableSurface.entries
                .associateWith { prefs.getInt("count_${it.wireKey}", 0) }
                .filterValues { it > 0 }
            // Restore what Firestore had acknowledged separately from the totals. If the
            // process died between recording a reel and flushing it, resuming with
            // syncedCounts == counts would mark that work done and it would never be
            // written. The cold-start seed usually rescues it via max(local, server) —
            // but not when the device is offline, which is exactly when unflushed
            // counts pile up in the first place.
            val restoredSynced = BlockableSurface.entries
                .associateWith { prefs.getInt("synced_${it.wireKey}", 0) }
                .filterValues { it > 0 }
            countsRef.updateAndGet { cur ->
                if (cur.date.isEmpty()) {
                    cur.copy(date = today, counts = restored, syncedCounts = restoredSynced)
                } else {
                    cur
                }
            }
        }
        rolloverIfNeeded(System.currentTimeMillis())
    }

    private fun persistCounts() {
        val ctx = appContext ?: return
        val snapshot = countsRef.get()
        val editor = ctx.getSharedPreferences(COUNTS_PREFS_NAME, Context.MODE_PRIVATE).edit()
        editor.putString("date", snapshot.date)
        BlockableSurface.entries.forEach {
            editor.putInt("count_${it.wireKey}", snapshot.counts[it] ?: 0)
            editor.putInt("synced_${it.wireKey}", snapshot.syncedCounts[it] ?: 0)
        }
        editor.apply()
    }

    // Persisted so a cold boot with no network still has last-known modes/limits
    // instead of detection being 100% dead (modes was previously populated only
    // by a successful Firestore read).
    private fun persistPolicy(wire: Map<String, String>, limits: Map<BlockableSurface, Int>) {
        val ctx = appContext ?: return
        val editor = ctx.getSharedPreferences(POLICY_PREFS_NAME, Context.MODE_PRIVATE).edit()
        BlockableSurface.entries.forEach { s ->
            editor.putString("mode_${s.wireKey}", wire[s.wireKey] ?: SurfaceMode.OFF.wire)
            editor.putInt("limit_${s.wireKey}", limits[s] ?: 0)
        }
        editor.apply()
    }

    private fun loadPersistedPolicy() {
        if (configSetThisProcess) return
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences(POLICY_PREFS_NAME, Context.MODE_PRIVATE)
        if (configSetThisProcess) return
        modes = BlockableSurface.entries.associateWith { SurfaceMode.fromWire(prefs.getString("mode_${it.wireKey}", null)) }
        reelLimits = BlockableSurface.entries.associateWith { prefs.getInt("limit_${it.wireKey}", 0) }
    }

    private fun persistLearnedAliases(aliases: Map<String, BlockableSurface>) {
        val ctx = appContext ?: return
        val editor = ctx.getSharedPreferences(ALIASES_PREFS_NAME, Context.MODE_PRIVATE).edit()
        editor.clear()
        aliases.forEach { (pkg, surface) -> editor.putString(pkg, surface.wireKey) }
        editor.apply()
    }

    private fun loadLearnedAliases() {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences(ALIASES_PREFS_NAME, Context.MODE_PRIVATE)
        val bySurfaceWireKey = BlockableSurface.entries.associateBy { it.wireKey }
        learnedAliases = prefs.all.mapNotNull { (pkg, value) ->
            val surface = bySurfaceWireKey[value as? String] ?: return@mapNotNull null
            pkg to surface
        }.toMap()
    }

    /** Packages resolved only via a learned pager-ID alias — consumed by the badge code. */
    fun learnedAliases(): Map<String, BlockableSurface> = learnedAliases

    private fun loadRecentDetections() {
        val prefs = appContext?.getSharedPreferences(DETECTION_PREFS_NAME, Context.MODE_PRIVATE) ?: return
        recentDetections = BlockableSurface.entries.mapNotNull { surface ->
            val atMs = prefs.getLong("seen_${surface.wireKey}", 0L)
            val pkg = prefs.getString("package_${surface.wireKey}", null)
            if (atMs > 0L && !pkg.isNullOrBlank()) surface to DetectionInfo(atMs, pkg) else null
        }.toMap()
    }

    private fun recordDetection(surface: BlockableSurface, packageName: String, nowMs: Long) {
        val previous = recentDetections[surface]
        recentDetections = recentDetections + (surface to DetectionInfo(nowMs, packageName))
        // The presence probe runs frequently while a reel plays. Keep the in-memory
        // receipt fresh, but write it at most once every 30 seconds (or on app change).
        if (previous?.packageName == packageName && nowMs - previous.atMs < 30_000L) return
        appContext?.getSharedPreferences(DETECTION_PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putLong("seen_${surface.wireKey}", nowMs)
            ?.putString("package_${surface.wireKey}", packageName)
            ?.apply()
        Log.i(TAG, "Detected ${surface.wireKey} in $packageName")
    }

    val isActive: Boolean
        get() = modes.values.any { it != SurfaceMode.OFF }

    /** Read-only snapshot for the overlay badge — no side effects besides the rollover check. */
    data class ReelStatus(
        val enabled: Boolean,
        val count: Int,
        val limit: Int,
        val blocked: Boolean,
        val mode: SurfaceMode,
        val lastDetection: DetectionInfo?
    )

    fun statusFor(surface: BlockableSurface): ReelStatus {
        rolloverIfNeeded(System.currentTimeMillis())
        val configured = modes[surface] ?: SurfaceMode.OFF
        val limit = reelLimits[surface] ?: 0
        val count = countsRef.get().counts[surface] ?: 0
        return ReelStatus(
            enabled = configured != SurfaceMode.OFF,
            count = count,
            limit = limit,
            blocked = limit > 0 && count >= limit,
            mode = configured,
            lastDetection = recentDetections[surface]
        )
    }

    /**
     * Called from UsageMonitorService.refreshPolicy() and after a policy Save —
     * both of which call this from background threads (wrapped in `Thread {}`),
     * so the whole body is posted to the main-looper handler: it mutates
     * [tracker] / [counter] / the per-surface probe maps, which are otherwise
     * only ever touched from the accessibility-event (main) thread.
     */
    fun updateConfig(wire: Map<String, String>, reelLimits: Map<String, Int> = emptyMap()) {
        mainHandler.post {
            val newModes = BlockableSurface.entries.associateWith { SurfaceMode.fromWire(wire[it.wireKey]) }
            val newLimits = BlockableSurface.entries.associateWith { reelLimits[it.wireKey] ?: 0 }
            val oldModes = modes
            modes = newModes
            this.reelLimits = newLimits
            configSetThisProcess = true
            persistPolicy(wire, newLimits)
            rolloverIfNeeded(System.currentTimeMillis())

            // Only reset for surfaces whose MODE actually changed — a reelLimits-only
            // refresh (the common 15-minute policy poll) must not wipe an in-progress
            // session. The tracker is a single shared state machine that only ever
            // tracks one surface at a time; SurfaceSessionTracker.activeSurface exposes
            // exactly which one, so a full tracker.reset() only fires when that
            // surface itself is among the changed ones.
            val changedSurfaces = BlockableSurface.entries.filter { oldModes[it] != newModes[it] }
            if (changedSurfaces.isNotEmpty()) {
                val activeSurface = tracker.activeSurface
                if (activeSurface != null && activeSurface in changedSurfaces) {
                    tracker.reset()
                }
                changedSurfaces.forEach { s ->
                    // onSurfaceLeft only closes the open session — a mode change must
                    // not wipe the day's accumulated per-surface counter state.
                    counter.onSurfaceLeft(s)
                    activeSessionSurfaces.remove(s)
                    lastProbeAtMs.remove(s)
                    lastPresentAtMs.remove(s)
                }
            }
        }
    }

    /** Called once from UsageMonitorService.onCreate() to seed today's counts from Firestore. */
    fun seedReelCounts(date: String, counts: Map<String, Int>) {
        countsRef.updateAndGet { cur ->
            val localCounts = if (cur.date == date) cur.counts else emptyMap()
            val serverCounts = BlockableSurface.entries.associateWith { counts[it.wireKey] ?: 0 }
            // Only this device increments counts, so max(local, server) is the true
            // total. syncedCounts is what the server actually has right now (not the
            // merged total) — if local was ahead, that leaves counts != syncedCounts,
            // which is exactly what should trigger the next flush to catch Firestore up.
            val merged = BlockableSurface.entries.associateWith { surface ->
                maxOf(localCounts[surface] ?: 0, serverCounts[surface] ?: 0)
            }
            cur.copy(date = date, counts = merged, syncedCounts = serverCounts, seedResolved = true)
        }
        persistCounts()
        maybeFlush()
    }

    /** The cold-start seed read failed (offline, etc.) — unblock absolute writes anyway. */
    fun onSeedFailed() {
        countsRef.updateAndGet { it.copy(seedResolved = true) }
    }

    fun reset() {
        tracker.reset()
        counter.reset()
        activeSessionSurfaces.clear()
    }

    /** Forces an immediate Firestore flush of any unsynced reel counts. */
    fun flushNow() = maybeFlush(force = true)

    private fun effectiveMode(surface: BlockableSurface): SurfaceMode {
        val configured = modes[surface] ?: SurfaceMode.OFF
        if (configured == SurfaceMode.OFF) return SurfaceMode.OFF
        val limit = reelLimits[surface] ?: 0
        if (limit > 0 && (countsRef.get().counts[surface] ?: 0) >= limit) return SurfaceMode.BLOCK
        return configured
    }

    private fun computeNextRolloverAtMs(): Long =
        LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun rolloverIfNeeded(nowMs: Long) {
        if (nextRolloverAtMs != 0L && nowMs < nextRolloverAtMs) return
        val today = LocalDate.now().toString()
        val snapshot = countsRef.get()
        if (today == snapshot.date) {
            nextRolloverAtMs = computeNextRolloverAtMs()
            return
        }
        // Flush whatever the previous day accumulated, under its own date, before
        // resetting. Safe even this late since the write is an idempotent absolute
        // total, not an increment.
        if (snapshot.date.isNotEmpty() && snapshot.seedResolved && snapshot.counts != snapshot.syncedCounts) {
            val staleDate = snapshot.date
            val totals = snapshot.counts.mapKeys { it.key.wireKey }
            flushExecutor.submit {
                try {
                    FocusPolicyClient.setReelCounts(staleDate, totals)
                } catch (e: Exception) {
                    Log.w(TAG, "Reel count flush failed", e)
                }
            }
        }
        countsRef.set(CountsState(date = today))
        counter.reset()
        tracker.reset()
        activeSessionSurfaces.clear()
        nextRolloverAtMs = computeNextRolloverAtMs()
        persistCounts()
    }

    private fun recordReelViews(surface: BlockableSurface, n: Int) {
        if (n <= 0) return
        countsRef.updateAndGet { cur ->
            cur.copy(counts = cur.counts + (surface to ((cur.counts[surface] ?: 0) + n)))
        }
        persistCounts()
        maybeFlush()
    }

    private fun maybeFlush(force: Boolean = false) {
        val snapshot = countsRef.get()
        // Hard guard: never flush against an unresolved date (rollover not yet run
        // this process) or before the cold-start seed has resolved — a fresh
        // install mid-day must not clobber a larger server total with a small one.
        if (snapshot.date.isEmpty() || !snapshot.seedResolved) return
        if (snapshot.counts == snapshot.syncedCounts) return
        val now = System.currentTimeMillis()
        if (!force && now - lastFlushAtMs < FLUSH_THROTTLE_MS) return
        lastFlushAtMs = now
        val date = snapshot.date
        val totals = snapshot.counts.mapKeys { it.key.wireKey }
        flushExecutor.submit {
            try {
                FocusPolicyClient.setReelCounts(date, totals)
                // Compare against the pre-network-call snapshot, not the live counts —
                // a count recorded while the write was in flight must still show up as
                // dirty afterwards, not get silently marked synced underneath it.
                countsRef.updateAndGet { cur ->
                    if (cur.date == snapshot.date) cur.copy(syncedCounts = snapshot.counts) else cur
                }
                // Persist the acknowledgement too, so a process death right after a
                // successful flush doesn't resume thinking it still owes the write.
                persistCounts()
            } catch (e: Exception) {
                Log.w(TAG, "Reel count flush failed", e)
                // On failure, change nothing: counts != syncedCounts stays true so the
                // next flush retries with the full (possibly larger) total.
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

    // AccessibilityNodeInfo.recycle() is a no-op on API 33+, but minSdk is 28, so
    // API 28-32 genuinely leaks nodes that aren't released.
    private fun recycleCompat(node: AccessibilityNodeInfo?) {
        if (node == null || Build.VERSION.SDK_INT >= 33) return
        try {
            node.recycle()
        } catch (e: IllegalStateException) {
            // Already recycled elsewhere — harmless.
        }
    }

    /**
     * TYPE_VIEW_SCROLLED gives the source view-ID for free (no tree search). If a
     * scroll from an *unresolved* package matches a known pager ID, that's
     * conclusive proof it's a mod build of that surface — persist the mapping so
     * it survives process restarts, e.g. the user's real Morphe applicationId.
     */
    private fun learnAliasIfPagerScroll(pkg: String, event: AccessibilityEvent) {
        val sourceNode = event.source
        val entry = SurfaceMarkers.entryName(sourceNode?.viewIdResourceName)
        recycleCompat(sourceNode)
        if (entry == null) return
        val matched = BlockableSurface.entries.firstOrNull { entry in (SurfaceMarkers.pagerIdNames[it] ?: emptySet()) } ?: return
        val current = learnedAliases
        if (current.containsKey(pkg)) return // already bound — refuse to remap
        if (current.size >= MAX_LEARNED_ALIASES) return
        val updated = current + (pkg to matched)
        learnedAliases = updated
        persistLearnedAliases(updated)
        Log.i(TAG, "Learned surface alias: $pkg -> ${matched.wireKey}")
    }

    fun onAccessibilityEvent(event: AccessibilityEvent, service: AccessibilityService) {
        val nowMs = System.currentTimeMillis()
        // Must run before any gate/throttle below: if the first surface event of a
        // process is a pager scroll while trackedDate is still "", every downstream
        // write targets Firestore document "" and silently dies in the catch block.
        rolloverIfNeeded(nowMs)
        val pkg = event.packageName?.toString() ?: return

        // No `isActive` gate here — counting must run even when every surface is
        // OFF. A foreign package costs exactly one map lookup below.
        val surface = BlockableSurface.byPackage(pkg, extra = learnedAliases)
        if (surface == null) {
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                learnAliasIfPagerScroll(pkg, event)
            }
            // Left the app the tracker was watching — but not for a transient system
            // window (keyboard/autofill popping up over IG/YT), which would wrongly
            // reset an in-progress one-at-a-time grant.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !isTransientSystemPackage(pkg)) {
                tracker.onViewerGone(nowMs)
                if (activeSessionSurfaces.isNotEmpty()) {
                    activeSessionSurfaces.forEach { counter.onSurfaceLeft(it) }
                    activeSessionSurfaces.clear()
                    flushNow()
                }
            }
            return
        }

        val mode = effectiveMode(surface)

        val decision: SurfaceDecision? = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val sourceNode = event.source
                val sourceEntry = SurfaceMarkers.entryName(sourceNode?.viewIdResourceName)
                recycleCompat(sourceNode)
                val isPagerScroll = sourceEntry != null && sourceEntry in (SurfaceMarkers.pagerIdNames[surface] ?: emptySet())
                if (isPagerScroll) {
                    val counted = counter.onPagerScrolled(surface, event.fromIndex, event.toIndex, event.scrollDeltaY, nowMs)
                    recordReelViews(surface, counted)
                }
                if (isPagerScroll && mode != SurfaceMode.OFF) {
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
                // Critical for correct counting: makes the next re-entry count
                // reliably no matter how fast the user comes back, instead of
                // depending on the enforcement tracker's cooldown timing.
                counter.onSurfaceLeft(surface)
            }
            SurfaceDecision.GRANT_ENTRY -> activeSessionSurfaces.add(surface)
            else -> {}
        }
    }

    // Throttled: content-changed events fire rapidly while a reel plays (captions,
    // like-count ticking, etc.) and a full-tree ID lookup on every one is wasteful.
    // Runs (and counts) regardless of mode; only the enforcement decision is gated
    // on `mode != OFF`.
    private fun probePresence(
        surface: BlockableSurface,
        mode: SurfaceMode,
        pkg: String,
        service: AccessibilityService,
        nowMs: Long
    ): SurfaceDecision? {
        val lastProbe = lastProbeAtMs[surface] ?: 0L
        if (nowMs - lastProbe < PRESENCE_PROBE_THROTTLE_MS) return null
        lastProbeAtMs[surface] = nowMs

        val ids = SurfaceMarkers.presenceIdCandidates(surface, pkg)
        if (ids.isEmpty()) return null
        val root = service.rootInActiveWindow ?: return null
        try {
            val windowBounds = Rect().also { root.getBoundsInScreen(it) }
            if (windowBounds.width() <= 0 || windowBounds.height() <= 0) {
                // Some windows report (0,0,0,0) for getBoundsInScreen; without this
                // fallback isViewerSizedBounds' windowW/H > 0 guard fails open and
                // detection silently dies for that window.
                val dm = service.resources.displayMetrics
                windowBounds.set(0, 0, dm.widthPixels, dm.heightPixels)
            }
            val nodeBounds = Rect()
            // A matched view ID alone isn't enough — Instagram/YouTube reuse reel view
            // IDs for feed-embedded posts, which would otherwise false-positive as the
            // viewer. Require the node to actually fill the window, which only the
            // real fullscreen viewer does.
            var present = false
            for (id in ids) {
                val nodes = root.findAccessibilityNodeInfosByViewId(id).orEmpty()
                for (node in nodes) {
                    if (!present && node.isVisibleToUser) {
                        node.getBoundsInScreen(nodeBounds)
                        if (isViewerSizedBounds(nodeBounds.width(), nodeBounds.height(), windowBounds.width(), windowBounds.height())) {
                            present = true
                        }
                    }
                    recycleCompat(node)
                }
            }

            if (!present) {
                // Hysteresis: a comments sheet, share dialog, or IME briefly becoming
                // the active window makes a single probe miss the viewer that is still
                // there behind it. Tearing the session down on one miss caused a fresh
                // GRANT_ENTRY (double count) when the next probe saw the viewer again.
                val lastPresent = lastPresentAtMs[surface] ?: 0L
                if (nowMs - lastPresent < GONE_HYSTERESIS_MS) return null
                if (mode != SurfaceMode.OFF) tracker.onViewerGone(nowMs)
                if (activeSessionSurfaces.remove(surface)) {
                    flushNow()
                }
                return null
            }

            lastPresentAtMs[surface] = nowMs
            recordDetection(surface, pkg, nowMs)
            val newlyOpened = counter.onViewerVisible(surface, nowMs)
            recordReelViews(surface, newlyOpened)
            return if (mode != SurfaceMode.OFF) tracker.onViewerVisible(surface, mode, nowMs) else null
        } finally {
            recycleCompat(root)
        }
    }
}
