package com.focuslock.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.focuslock.R
import com.focuslock.data.FocusPolicyClient
import com.focuslock.security.EnforcementChecks
import com.focuslock.security.EnforcementHealth
import com.focuslock.surface.SurfaceBlocker
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class UsageMonitorService : Service() {

    companion object {
        private const val TAG = "UsageMonitorService"
        private const val CHANNEL_ID_MONITOR_LEGACY = "focuslock-monitor"
        private const val CHANNEL_ID_ALERTS = "focuslock-alerts"
        private const val CHANNEL_ID_MONITOR = "focuslock-monitor-fgs"
        private const val NOTIFICATION_MONITOR_ID = 1001
        private const val NOTIFICATION_ALERT_ID = 1102
        private const val NOTIFICATION_OVERLAY_PERM_ID = 1103
        private const val NOTIFICATION_LIMIT_REACHED_ID = 1104
        private const val NOTIFICATION_WATCHDOG_ID = 1105
        private const val OVERLAY_COOLDOWN_MS = 3000L
        private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L
        private const val FOREGROUND_POLL_MS = 1000L

        // ── Cold-start fail-closed + heartbeat-gap watchdog ──────────────────
        // Same "FocusLockPrefs" file FocusPolicyClient/MainActivity use.
        private const val PREFS_NAME = "FocusLockPrefs"
        private const val PREF_POLICY_EVER_FETCHED = "policy_ever_fetched"
        private const val PREF_SHADOW_USED_MINUTES = "shadow_used_minutes"
        private const val PREF_SHADOW_EFFECTIVE_LIMIT = "shadow_effective_limit"
        private const val PREF_SHADOW_ALLOW_NOW = "shadow_allow_now"
        private const val PREF_SHADOW_HARD_BLOCK = "shadow_hard_block"
        private const val PREF_HEARTBEAT_LAST_SEEN_MS = "heartbeat_last_seen_ms"
        private const val PREF_BOOT_COMPLETED_AT_MS = "boot_completed_at_ms"
        private const val PREF_GAP_HISTORY = "enforcement_gap_history"

        // IDLE (accessibility still enabled) is now a benign category, so this
        // only controls noise volume, not tamper sensitivity — the real tamper
        // signal is the direct accessibility-enabled read at classification
        // time. Set well above ordinary idle gaps (screen-off, user not
        // touching a managed app) so IDLE entries aren't constant.
        private const val HEARTBEAT_GAP_THRESHOLD_MS = 30 * 60 * 1000L

        const val ACTION_DISMISS_LOCK_OVERLAY = "com.focuslock.DISMISS_LOCK_OVERLAY"
        const val ACTION_FOREGROUND_CHANGED = "com.focuslock.ACTION_FOREGROUND_CHANGED"
        const val ACTION_POLICY_UPDATED = "com.focuslock.ACTION_POLICY_UPDATED"
        const val ACTION_CHECK_PROTECTION = "com.focuslock.ACTION_CHECK_PROTECTION"
        const val EXTRA_PACKAGE_NAME = "package_name"

        // True while the monitor service is alive — lets the UI decide whether to
        // restart it (e.g. after the sync code changes).
        @Volatile var isServiceActive = false
            private set

        // Over the daily limit with hardBlockAfterLimit OFF: allowed in, but the
        // badge turns red and friction fires on every open. Read by
        // TimerOverlayService for the badge color.
        @Volatile var isOverLimitSoft = false
            private set

        // Cross-service shared state — written only from main thread
        @Volatile var currentForegroundTarget: String? = null
            internal set
        val blockedTargets = mutableSetOf<String>()
        @Volatile var lastBreakClickedAt = 0L

        // ── Accessibility disable/re-enable glue ─────────────────────────────
        // Called directly from FocusAccessibilityService.onUnbind/onServiceConnected
        // — onUnbind is a direct, immediate signal that enforcement just stopped,
        // strictly better than inferring it from a heartbeat gap later. Static so
        // they're callable without a live UsageMonitorService instance (the
        // accessibility service can unbind/reconnect independently of it).

        fun recordAccessibilityDisabled(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val history = decodeGapHistory(prefs.getString(PREF_GAP_HISTORY, null))
            val updated = EnforcementHealth.recordGap(
                history, EnforcementHealth.observedDisabled(System.currentTimeMillis())
            )
            prefs.edit().putString(PREF_GAP_HISTORY, encodeGapHistory(updated)).apply()
        }

        fun closeAccessibilityDisabled(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val history = decodeGapHistory(prefs.getString(PREF_GAP_HISTORY, null))
            val bootAt = if (prefs.contains(PREF_BOOT_COMPLETED_AT_MS)) {
                prefs.getLong(PREF_BOOT_COMPLETED_AT_MS, 0L)
            } else null
            val updated = EnforcementHealth.closeOpenDisabledGap(
                history, System.currentTimeMillis(), bootAt
            )
            prefs.edit().putString(PREF_GAP_HISTORY, encodeGapHistory(updated)).apply()
        }

        private fun encodeGapHistory(history: List<EnforcementHealth.HeartbeatGap>): String {
            val arr = JSONArray()
            history.forEach { gap ->
                arr.put(JSONObject().apply {
                    put("startMs", gap.startMs)
                    put("endMs", gap.endMs)
                    put("durationMs", gap.durationMs)
                    put("cause", gap.cause.name)
                })
            }
            return arr.toString()
        }

        private fun decodeGapHistory(json: String?): List<EnforcementHealth.HeartbeatGap> {
            if (json.isNullOrEmpty()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    EnforcementHealth.HeartbeatGap(
                        startMs = o.getLong("startMs"),
                        endMs = o.getLong("endMs"),
                        durationMs = o.getLong("durationMs"),
                        cause = EnforcementHealth.GapCause.valueOf(o.getString("cause"))
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gap history decode failed — discarding", e)
                emptyList()
            }
        }
    }

    // Dynamic target set — starts empty, populated from policy. An app is a
    // target iff its resolved AppMode != OFF; appModes holds the resolved mode
    // per target (legacy targetApps entries without an appModes entry = LIMIT).
    private val targetApps = mutableSetOf<String>()
    private val appModes = mutableMapOf<String, com.focuslock.data.AppMode>()

    private fun modeFor(packageName: String): com.focuslock.data.AppMode =
        appModes[packageName] ?: com.focuslock.data.AppMode.OFF

    // Per-package tracking
    private val sessionStartByPackage = mutableMapOf<String, Long>()
    private val lastOverlayShownAt = mutableMapOf<String, Long>()
    private val lastFrictionBypassTimeByPackage = mutableMapOf<String, Long>()
    private val FRICTION_COOLDOWN_MS = 5 * 60 * 1000L

    // Local shadow of the last successful evaluate() result — persists across
    // evaluate() failures so an offline device can't bypass limits by losing network.
    private var localUsedMinutes = 0.0
    private var localEffectiveLimit = 0
    private var localAllowNow = true   // optimistic until first evaluate succeeds
    private var localHardBlock = true  // hardBlockAfterLimit from the last evaluate
    private var hadSuccessfulEvaluate = false
    private var limitReachedNotificationSentDate = ""

    private var warningNotificationSentDate = ""
    private var overlayPermAlertSentDate = ""
    private var lastPolicyRefreshAt = 0L

    // Last non-transient foreground package seen, whether or not it was a target at
    // the time. FocusAccessibilityService dedupes same-package forwards, so if the
    // first forward for an app arrives before the async policy fetch has populated
    // targetApps, no further forward comes for that app — replaying this after each
    // policy refresh closes that race (and makes newly-added targets apply while
    // the app is already open).
    @Volatile private var lastSeenForegroundPackage: String? = null
    private val POLICY_REFRESH_THROTTLE_MS = 15 * 60 * 1000L
    private var localTimerRunnable: Runnable? = null
    private var watchdogRunnable: Runnable? = null
    private var foregroundPollRunnable: Runnable? = null
    // Last foreground package the usage-stats poller saw, and the end of its last query window.
    private var polledForegroundPackage: String? = null
    private var lastPollQueryEndMs = 0L
    private var protectionDegraded = false
    private var isLockOverlayActive = false
    private var isFrictionOverlayActive = false

    private val handler = Handler(Looper.getMainLooper())
    private val closeRunnableByPackage = mutableMapOf<String, Runnable>()
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val isRunning = AtomicBoolean(false)

    // Session chunking: after sessionMaxMinutes of cumulative managed-app use,
    // a sessionBreakMinutes hard break locks all managed apps.
    private val chunkTracker = SessionChunkTracker()
    private var chunkCapRunnable: Runnable? = null

    // Bounded history of detected enforcement-heartbeat gaps (most recent
    // first) — read accessor for future UI surfacing. Not persisted across
    // process restarts beyond what's in PREF_GAP_HISTORY, which is reloaded here.
    private var gapHistory: List<EnforcementHealth.HeartbeatGap> = emptyList()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isServiceActive = true
        FocusPolicyClient.init(this)
        SurfaceBlocker.init(this)
        createNotificationChannels()
        startMonitorForeground()
        restorePersistedShadowState()
        checkHeartbeatGapOnStart()
        startWatchdog()
        startForegroundPoller()

        // One-time cold-start seed so the daily reel quota survives a process
        // restart mid-day instead of silently resetting to zero.
        networkExecutor.submit {
            try {
                val today = LocalDate.now().toString()
                val counts = FocusPolicyClient.getReelCounts(today)
                SurfaceBlocker.seedReelCounts(today, counts)
            } catch (e: Exception) {
                Log.w(TAG, "Reel count seed failed — starting from zero", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_FOREGROUND_CHANGED -> {
                intent.getStringExtra(EXTRA_PACKAGE_NAME)?.let { handleForegroundChanged(it) }
            }
            ACTION_POLICY_UPDATED -> {
                // A policy Save just happened — re-fetch now, skipping the
                // 15-minute throttle, so edits take effect immediately.
                lastPolicyRefreshAt = System.currentTimeMillis()
                refreshPolicy()
            }
            "com.focuslock.ACTION_FRICTION_BYPASS" -> {
                val target = intent.getStringExtra(FrictionBarrierService.EXTRA_TARGET)
                if (target != null) {
                    lastFrictionBypassTimeByPackage[target] = System.currentTimeMillis()
                    evaluateAndLockIfNeeded(target)
                }
            }
            "com.focuslock.ACTION_LOCK_OVERLAY_DISMISSED" -> {
                isLockOverlayActive = false
            }
            "com.focuslock.ACTION_FRICTION_OVERLAY_DISMISSED" -> {
                isFrictionOverlayActive = false
            }
            ACTION_CHECK_PROTECTION -> {
                // Sent by FocusAccessibilityService right on connect/disconnect so
                // the watchdog notification reacts immediately instead of waiting
                // up to WATCHDOG_INTERVAL_MS for the next scheduled poll.
                checkProtectionHealth()
            }
        }

        isRunning.set(true)
        touchHeartbeat()
        maybeRefreshPolicy()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        isServiceActive = false
        isRunning.set(false)
        networkExecutor.shutdownNow()

        closeRunnableByPackage.values.forEach { handler.removeCallbacks(it) }
        closeRunnableByPackage.clear()
        stopLocalTimer()
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        watchdogRunnable = null
        foregroundPollRunnable?.let { handler.removeCallbacks(it) }
        foregroundPollRunnable = null

        dismissLockOverlay()
        dismissTimerOverlay()
        dismissFrictionOverlay()

        currentForegroundTarget = null
        blockedTargets.clear()
        updateBlockedPreference(false)
        sessionStartByPackage.clear()
        lastOverlayShownAt.clear()
        lastFrictionBypassTimeByPackage.clear()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        dismissLockOverlay()
        dismissTimerOverlay()
        dismissFrictionOverlay()
    }

    // ── Foreground-change routing ─────────────────────────────────────────────

    private fun isTransientSystemPackage(pkg: String) =
        pkg == "android" ||
        pkg == "com.android.systemui" ||
        pkg.contains("inputmethod") ||
        pkg.contains("keyboard") ||
        pkg.contains("telecom") ||
        pkg.contains("phone")

    private fun handleForegroundChanged(packageName: String) {
        if (!isTransientSystemPackage(packageName)) {
            lastSeenForegroundPackage = packageName
        }

        // While our own friction/lock overlay is the active barrier, ignore any
        // foreground change that isn't a switch to a *different* target app.
        // When the user interacts with the overlay (e.g. tapping the reflection
        // field), the keyboard/autofill/system popups briefly come to the front.
        // Treating those as "left the target app" tore the overlay down and
        // rebuilt it from step 1 — the breathing→…→type-sentence loop restart.
        // The overlay dismisses itself via its Exit/Proceed buttons, so there is
        // no need to react to transient windows here.
        if ((isLockOverlayActive || isFrictionOverlayActive) && !targetApps.contains(packageName)) {
            Log.d(TAG, "Ignoring '$packageName' while overlay barrier is active")
            return
        }

        if (targetApps.contains(packageName)) {
            if (currentForegroundTarget != packageName) {
                currentForegroundTarget?.let { handleTargetAppClosed(it, System.currentTimeMillis()) }
                handleTargetAppOpened(packageName, System.currentTimeMillis())
            }
        } else {
            if (currentForegroundTarget != null && !isTransientSystemPackage(packageName)) {
                handleTargetAppClosed(currentForegroundTarget!!, System.currentTimeMillis())
            }
        }
    }

    private fun handleTargetAppOpened(packageName: String, timestamp: Long) {
        Log.d(TAG, "Target opened: $packageName")
        if (System.currentTimeMillis() - lastBreakClickedAt < 1500L) {
            Log.d(TAG, "Ignoring open — recent break click")
            return
        }

        // Session-chunk gate: during a forced break every managed app is locked.
        val gate = chunkTracker.onTargetOpened(timestamp)
        if (gate is SessionChunkTracker.Gate.InBreak) {
            Log.d(TAG, "Chunk break active — blocking $packageName")
            showBreakOverlay(gate.untilMs)
            return
        }

        currentForegroundTarget = packageName

        // Cancel any pending session-close debounce for this package
        closeRunnableByPackage.remove(packageName)?.let { handler.removeCallbacks(it) }

        if (!sessionStartByPackage.containsKey(packageName)) {
            sessionStartByPackage[packageName] = timestamp
        }

        showTimerOverlay(packageName)
        scheduleChunkCap()

        if (blockedTargets.contains(packageName)) {
            showLockOverlay(packageName)
            evaluateAndLockIfNeeded(packageName)
        } else {
            val now = System.currentTimeMillis()
            val lastBypass = lastFrictionBypassTimeByPackage[packageName] ?: 0L
            // Past the soft limit the 5-min friction cooldown no longer applies to
            // LIMIT-mode apps — every open costs the full friction gate. Other
            // modes keep the normal cooldown (FRICTION_ONLY has no limit at all).
            val softLimitFriction = isOverLimitSoft && modeFor(packageName) == com.focuslock.data.AppMode.LIMIT
            if (softLimitFriction || now - lastBypass > FRICTION_COOLDOWN_MS) {
                showFrictionOverlay(packageName)
            } else {
                evaluateAndLockIfNeeded(packageName)
            }
        }
    }

    private fun handleTargetAppClosed(packageName: String, timestamp: Long) {
        if (currentForegroundTarget != packageName) return
        Log.d(TAG, "Target closed: $packageName — starting 5 s debounce")
        currentForegroundTarget = null

        chunkTracker.onTargetClosed(System.currentTimeMillis())
        chunkCapRunnable?.let { handler.removeCallbacks(it) }
        chunkCapRunnable = null

        stopLocalTimer()
        dismissLockOverlay()
        dismissTimerOverlay()
        dismissFrictionOverlay()

        closeRunnableByPackage.remove(packageName)?.let { handler.removeCallbacks(it) }

        val runnable = Runnable {
            Log.d(TAG, "Debounce complete — finalizing session for $packageName")
            postUsageSessionIfAny(packageName, timestamp)
            closeRunnableByPackage.remove(packageName)
        }
        closeRunnableByPackage[packageName] = runnable
        handler.postDelayed(runnable, 5000L)
    }

    // ── Policy refresh ────────────────────────────────────────────────────────

    // Throttle policy fetches: window-state changes can fire many times a minute,
    // but targetApps rarely change. Skip the network read unless the cache is empty
    // (first run / previous fetch failed) or the throttle window has elapsed.
    private fun maybeRefreshPolicy() {
        val now = System.currentTimeMillis()
        if (targetApps.isNotEmpty() && now - lastPolicyRefreshAt < POLICY_REFRESH_THROTTLE_MS) return
        lastPolicyRefreshAt = now
        refreshPolicy()
    }

    private fun refreshPolicy() {
        networkExecutor.submit {
            try {
                val policyJson = FocusPolicyClient.fetchPolicy()
                val appsArray = policyJson.optJSONArray("targetApps")
                val docTargets = if (appsArray != null) {
                    (0 until appsArray.length()).map { appsArray.getString(it) }.toSet()
                } else emptySet()
                val modesJson = policyJson.optJSONObject("appModes")
                val wireModes = modesJson?.keys()?.asSequence()
                    ?.associateWith { modesJson.getString(it) } ?: emptyMap()
                // Resolve a mode for every app mentioned anywhere; targets are the
                // ones that resolve to something other than OFF.
                val resolved = (docTargets + wireModes.keys).associateWith { pkg ->
                    com.focuslock.data.AppMode.fromWire(wireModes[pkg], pkg in docTargets)
                }
                val sessionMax = policyJson.optInt("sessionMaxMinutes", 0)
                val sessionBreak = policyJson.optInt("sessionBreakMinutes", 0)
                handler.post {
                    appModes.clear()
                    appModes.putAll(resolved)
                    targetApps.clear()
                    targetApps.addAll(resolved.filterValues { it != com.focuslock.data.AppMode.OFF }.keys)
                    OnDemandAccessibility.targetPackages = targetApps.toSet()
                    chunkTracker.configure(sessionMax, sessionBreak)
                    Log.d(TAG, "Policy refreshed: targetApps=$targetApps modes=$appModes chunk=$sessionMax/$sessionBreak")
                    // Replay the current foreground app: its forwards may have
                    // been deduped away while targetApps was still empty.
                    // Idempotent — a no-op if it's already the tracked target.
                    lastSeenForegroundPackage?.let { handleForegroundChanged(it) }
                }
                val surfacesJson = policyJson.optJSONObject("blockedSurfaces")
                if (surfacesJson != null) {
                    val surfaces = surfacesJson.keys().asSequence()
                        .associateWith { surfacesJson.getString(it) }
                    val limitsJson = policyJson.optJSONObject("reelDailyLimit")
                    val limits = limitsJson?.keys()?.asSequence()
                        ?.associateWith { limitsJson.getInt(it) } ?: emptyMap()
                    handler.post { SurfaceBlocker.updateConfig(surfaces, limits) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Policy refresh failed — using cached or previous targets", e)
            }
        }
    }

    // ── Evaluate & lock ───────────────────────────────────────────────────────

    private fun evaluateAndLockIfNeeded(packageName: String) {
        networkExecutor.submit {
            try {
                val decision = FocusPolicyClient.evaluate(LocalDate.now().toString(), packageName)

                // Persist the result so the offline fallback below uses real data
                localUsedMinutes = decision.usedMinutes
                localEffectiveLimit = decision.effectiveLimit
                localAllowNow = decision.allowNow
                localHardBlock = decision.hardBlockAfterLimit
                hadSuccessfulEvaluate = true
                persistShadowState()

                handler.post {
                    isOverLimitSoft = decision.action == "soft-limit"
                    if (isOverLimitSoft && localEffectiveLimit > 0) {
                        sendLimitReachedNotification(localEffectiveLimit)
                    }
                    if (!localAllowNow) {
                        blockedTargets.add(packageName)
                        updateBlockedPreference(true)
                        if (currentForegroundTarget == packageName) showLockOverlay(packageName)
                    } else {
                        blockedTargets.remove(packageName)
                        updateBlockedPreference(blockedTargets.isNotEmpty())
                        if (currentForegroundTarget == packageName) {
                            dismissLockOverlay()
                            // Seed the local timer from the server value so it doesn't restart from 0
                            startLocalTimer(packageName)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "evaluate() failed — using last known state (offline cache may apply)", e)
                // Do NOT reset localUsedMinutes/localEffectiveLimit/localAllowNow.
                // With Firestore offline persistence enabled those values will still
                // be correct from the cache. If the cache itself failed (cold start +
                // airplane mode), hadSuccessfulEvaluate is false and we fall through
                // to allow access without incrementing local counters.
                handler.post {
                    if (hadSuccessfulEvaluate && !localAllowNow) {
                        // Was blocked before offline — keep blocked
                        blockedTargets.add(packageName)
                        updateBlockedPreference(true)
                        if (currentForegroundTarget == packageName) showLockOverlay(packageName)
                    } else if (hadSuccessfulEvaluate && localEffectiveLimit > 0) {
                        // Had valid policy — resume local timer from last known position
                        startLocalTimer(packageName)
                    }
                    // else: no prior data at all (first ever launch, offline) — allow silently
                }
            }
        }
    }

    // ── Local minute counter (fires every 60 s while app is in foreground) ───

    private fun startLocalTimer(packageName: String) {
        stopLocalTimer()
        localTimerRunnable = object : Runnable {
            override fun run() {
                if (currentForegroundTarget != packageName) return

                localUsedMinutes += 1.0
                touchHeartbeat()
                Log.d(TAG, "Local tracker: %.1f / %d min".format(localUsedMinutes, localEffectiveLimit))

                if (localEffectiveLimit > 0) {
                    // Dated (not per-session) guard — without this, reopening the app
                    // near the limit re-fired this alert every single time.
                    val today = LocalDate.now().toString()
                    if (warningNotificationSentDate != today && localUsedMinutes >= localEffectiveLimit * 0.8) {
                        warningNotificationSentDate = today
                        sendApproachingLimitNotification(localEffectiveLimit)
                    }
                    if (localUsedMinutes >= localEffectiveLimit) {
                        if (localHardBlock) {
                            blockedTargets.add(packageName)
                            updateBlockedPreference(true)
                            showLockOverlay(packageName)
                            return
                        }
                        // Soft limit: no lock — flag it (red badge, per-open
                        // friction) and keep counting.
                        isOverLimitSoft = true
                        sendLimitReachedNotification(localEffectiveLimit)
                    }
                }
                handler.postDelayed(this, 60_000L)
            }
        }
        handler.postDelayed(localTimerRunnable!!, 60_000L)
    }

    private fun sendApproachingLimitNotification(limitMinutes: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) return
        }
        val notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("FocusLock: Limit Approaching")
            .setContentText("You've used 80% of your $limitMinutes min daily limit.")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .notify(NOTIFICATION_ALERT_ID, notification)
    }

    // Fired once per day when the soft limit is crossed (hardBlockAfterLimit OFF):
    // the app stays usable, so this is the signal that friction mode kicked in.
    private fun sendLimitReachedNotification(limitMinutes: Int) {
        val today = LocalDate.now().toString()
        if (limitReachedNotificationSentDate == today) return
        limitReachedNotificationSentDate = today
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) return
        }
        val notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("FocusLock: Daily Limit Reached")
            .setContentText("Past your $limitMinutes min limit — every open now goes through the friction gate.")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .notify(NOTIFICATION_LIMIT_REACHED_ID, notification)
    }

    private fun stopLocalTimer() {
        localTimerRunnable?.let { handler.removeCallbacks(it) }
        localTimerRunnable = null
    }

    // ── Usage event post ──────────────────────────────────────────────────────

    private fun postUsageSessionIfAny(packageName: String, endMs: Long) {
        val startMs = sessionStartByPackage.remove(packageName) ?: return
        val durationMinutes = (endMs - startMs) / 60_000.0
        if (durationMinutes <= 0.0) return

        // Local-first: the badge's "Nm today" reads this store, so it must be
        // written even when the network post below fails or the process dies.
        com.focuslock.data.AppUsageStore.addSession(
            this, LocalDate.now().toString(), packageName, durationMinutes
        )

        networkExecutor.submit {
            try {
                val startedAt = java.time.Instant.ofEpochMilli(startMs)
                    .atOffset(java.time.ZoneOffset.UTC).toString()
                val endedAt = java.time.Instant.ofEpochMilli(endMs)
                    .atOffset(java.time.ZoneOffset.UTC).toString()
                FocusPolicyClient.postEvent(packageName, startedAt, endedAt, durationMinutes)
            } catch (e: Exception) {
                Log.w(TAG, "postEvent failed", e)
            }
        }
    }

    // ── SharedPreferences helper ──────────────────────────────────────────────

    private fun updateBlockedPreference(isBlocked: Boolean) {
        getSharedPreferences("FocusLockPrefs", Context.MODE_PRIVATE)
            .edit().putBoolean("is_currently_blocked", isBlocked).apply()
    }

    // ── Cold-start fail-closed ────────────────────────────────────────────────

    // Loads whatever shadow state survived from the last successful evaluate()
    // before this process existed. Without this, hadSuccessfulEvaluate resets to
    // false on every cold start, and the "no prior data — allow silently" branch
    // in evaluateAndLockIfNeeded() fires even on a device that was blocked
    // offline right before it was force-stopped — the airplane-mode bypass.
    private fun restorePersistedShadowState() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val everFetched = prefs.getBoolean(PREF_POLICY_EVER_FETCHED, false)
        if (EnforcementHealth.shouldAllowFreshInstall(everFetched)) return // genuine first run — nothing to restore
        hadSuccessfulEvaluate = true
        localUsedMinutes = prefs.getFloat(PREF_SHADOW_USED_MINUTES, 0f).toDouble()
        localEffectiveLimit = prefs.getInt(PREF_SHADOW_EFFECTIVE_LIMIT, 0)
        localAllowNow = prefs.getBoolean(PREF_SHADOW_ALLOW_NOW, true)
        localHardBlock = prefs.getBoolean(PREF_SHADOW_HARD_BLOCK, true)
        Log.d(TAG, "Restored persisted shadow: used=$localUsedMinutes limit=$localEffectiveLimit allow=$localAllowNow")
    }

    // Called after every successful evaluate() — durably records "a policy has
    // been fetched at least once" plus the enforcement-relevant snapshot, so a
    // cold start with no network and no Firestore cache fails closed instead of
    // allowing silently.
    private fun persistShadowState() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_POLICY_EVER_FETCHED, true)
            .putFloat(PREF_SHADOW_USED_MINUTES, localUsedMinutes.toFloat())
            .putInt(PREF_SHADOW_EFFECTIVE_LIMIT, localEffectiveLimit)
            .putBoolean(PREF_SHADOW_ALLOW_NOW, localAllowNow)
            .putBoolean(PREF_SHADOW_HARD_BLOCK, localHardBlock)
            .apply()
    }

    // ── Heartbeat-gap watchdog ────────────────────────────────────────────────

    // Called on every existing tick that already runs while the service is
    // functioning — the 60 s in-app local timer and each foreground-change
    // dispatch — rather than adding a new scheduler.
    private fun touchHeartbeat() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(PREF_HEARTBEAT_LAST_SEEN_MS, System.currentTimeMillis())
            .apply()
    }

    // Compares now against the last heartbeat left by a previous process, then
    // classifies any gap using the real signal — a direct read of whether
    // accessibility is enabled right now — rather than guessing from the gap
    // alone. A gap with accessibility still on is almost certainly the user
    // not touching the phone (IDLE); a gap with accessibility off is the real
    // tamper signal (DISABLED); a boot/package-replace landing in the window
    // explains it either way (EXPLAINED). Also records a direct DISABLED
    // observation whenever accessibility is off, even with no gap at all —
    // that's stronger evidence than any inference from timing.
    private fun checkHeartbeatGapOnStart() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSeen = if (prefs.contains(PREF_HEARTBEAT_LAST_SEEN_MS)) {
            prefs.getLong(PREF_HEARTBEAT_LAST_SEEN_MS, 0L)
        } else null
        val bootAt = if (prefs.contains(PREF_BOOT_COMPLETED_AT_MS)) {
            prefs.getLong(PREF_BOOT_COMPLETED_AT_MS, 0L)
        } else null
        val accessibilityEnabled = EnforcementChecks.readStatus(this).accessibility
        val now = System.currentTimeMillis()

        gapHistory = decodeGapHistory(prefs.getString(PREF_GAP_HISTORY, null))

        val gap = EnforcementHealth.detectGap(
            nowMs = now,
            lastSeenAtMs = lastSeen,
            thresholdMs = HEARTBEAT_GAP_THRESHOLD_MS,
            bootCompletedAtMs = bootAt,
            accessibilityEnabledNow = accessibilityEnabled
        )
        if (gap != null) {
            Log.w(TAG, "Enforcement heartbeat gap: ${gap.durationMs / 1000}s, cause=${gap.cause}")
            gapHistory = EnforcementHealth.recordGap(gapHistory, gap)
        }
        // Direct observation beats inference — record it independently of
        // whether a timing gap was also found (e.g. accessibility disabled
        // just now, heartbeat still fresh from a moment ago).
        if (!accessibilityEnabled) {
            gapHistory = EnforcementHealth.recordGap(gapHistory, EnforcementHealth.observedDisabled(now))
        }
        if (gap != null || !accessibilityEnabled) {
            prefs.edit().putString(PREF_GAP_HISTORY, encodeGapHistory(gapHistory)).apply()
        }
        touchHeartbeat()
    }

    /** Read accessor for the bounded gap history — surfaced in the UI later. */
    fun getEnforcementGapHistory(): List<EnforcementHealth.HeartbeatGap> = gapHistory

    // Unconditional self-rescheduling check, independent of any target app being
    // foreground — unlike localTimerRunnable, which only runs while a target app
    // is open and is itself fed by the accessibility service. Without this, an
    // accessibility revocation (routine on ColorOS/OnePlus after an app update)
    // stopped everything with no signal anywhere in the app.
    private fun startWatchdog() {
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                checkProtectionHealth()
                handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
        watchdogRunnable = runnable
        checkProtectionHealth()
        handler.postDelayed(runnable, WATCHDOG_INTERVAL_MS)
    }

    // While the accessibility service is parked (OnDemandAccessibility), this is the
    // only foreground signal: it forwards app switches to handleForegroundChanged and
    // re-enables the service when a managed app or reel surface comes to the front.
    // While the service is connected its events stay authoritative and the poller
    // only tracks. A no-op without the WRITE_SECURE_SETTINGS grant.
    private fun startForegroundPoller() {
        foregroundPollRunnable?.let { handler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                try {
                    pollForeground()
                } catch (e: Exception) {
                    Log.w(TAG, "Foreground poll failed", e)
                }
                handler.postDelayed(this, FOREGROUND_POLL_MS)
            }
        }
        foregroundPollRunnable = runnable
        handler.post(runnable)
    }

    @Suppress("DEPRECATION") // MOVE_TO_FOREGROUND == ACTIVITY_RESUMED; the latter needs API 29
    private fun pollForeground() {
        if (!OnDemandAccessibility.canManage(this)) return
        if (!(getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive) return
        val usm = getSystemService(USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val now = System.currentTimeMillis()
        val from = if (lastPollQueryEndMs == 0L) now - 60_000L else lastPollQueryEndMs - 2_000L
        val events = usm.queryEvents(from, now)
        val event = android.app.usage.UsageEvents.Event()
        var latest: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                latest = event.packageName
            }
        }
        lastPollQueryEndMs = now
        val pkg = latest ?: polledForegroundPackage ?: return
        val connected = FocusAccessibilityService.isConnected
        if (pkg != polledForegroundPackage) {
            polledForegroundPackage = pkg
            if (!connected) {
                handleForegroundChanged(pkg)
                touchHeartbeat()
            }
        }
        if (!connected && OnDemandAccessibility.needsService(pkg)) {
            OnDemandAccessibility.enable(this)
        }
    }

    private fun checkProtectionHealth() {
        val accessibilityOn = EnforcementChecks.readStatus(this).accessibility
        if (!accessibilityOn && !protectionDegraded) {
            protectionDegraded = true
            notifyProtectionOff()
            updateForegroundNotification(degraded = true)
        } else if (accessibilityOn && protectionDegraded) {
            protectionDegraded = false
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_WATCHDOG_ID)
            updateForegroundNotification(degraded = false)
        }
    }

    private fun notifyProtectionOff() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) return
        }
        val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, settingsIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("⚠️ FocusLock protection is OFF")
            .setContentText("Accessibility was turned off — tap to re-enable it. Limits and Reels blocking are not enforced right now.")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_WATCHDOG_ID, notification)
    }

    // ── Overlay helpers ───────────────────────────────────────────────────────

    private fun showLockOverlay(packageName: String) {
        if (!Settings.canDrawOverlays(this)) {
            // getPackageName(): the parameter shadows the Context property — the
            // permission page must open for FocusLock, not the target app.
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${getPackageName()}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val now = System.currentTimeMillis()
        if (now - (lastOverlayShownAt[packageName] ?: 0L) < OVERLAY_COOLDOWN_MS) return
        lastOverlayShownAt[packageName] = now

        dismissTimerOverlay()
        isLockOverlayActive = true
        // Push the blocked app off the foreground so its media (e.g. reels) stops.
        // Setting isLockOverlayActive first makes handleForegroundChanged() ignore
        // the launcher window-change this triggers, so currentForegroundTarget is
        // preserved and the overlay service's foreground guard still passes.
        sendBlockedAppToBackground()
        startService(Intent(this, LockOverlayService::class.java)
            .putExtra(LockOverlayService.EXTRA_TARGET, packageName))
    }

    // Prefer the accessibility HOME action; fall back to a HOME activity launch.
    private fun sendBlockedAppToBackground() {
        if (FocusAccessibilityService.sendToHome()) return
        try {
            startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Log.w(TAG, "sendBlockedAppToBackground fallback failed", e)
        }
    }

    private fun dismissLockOverlay() {
        isLockOverlayActive = false
        try {
            startService(Intent(this, LockOverlayService::class.java)
                .apply { action = ACTION_DISMISS_LOCK_OVERLAY })
        } catch (e: Exception) {
            Log.w(TAG, "dismissLockOverlay failed", e)
        }
    }

    private fun showFrictionOverlay(packageName: String) {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${getPackageName()}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        isFrictionOverlayActive = true
        try {
            startService(Intent(this, FrictionBarrierService::class.java)
                .putExtra(FrictionBarrierService.EXTRA_TARGET, packageName))
        } catch (e: Exception) {
            Log.w(TAG, "showFrictionOverlay failed", e)
        }
    }

    private fun dismissFrictionOverlay() {
        isFrictionOverlayActive = false
        try {
            startService(Intent(this, FrictionBarrierService::class.java)
                .apply { action = FrictionBarrierService.ACTION_DISMISS })
        } catch (e: Exception) {
            Log.w(TAG, "dismissFrictionOverlay failed", e)
        }
    }

    // ── Session-chunk break ───────────────────────────────────────────────────

    // Fires when the cumulative chunk budget runs out while a managed app is
    // open: finalize the session, kick to home, show the break countdown.
    private fun scheduleChunkCap() {
        chunkCapRunnable?.let { handler.removeCallbacks(it) }
        chunkCapRunnable = null
        val delayMs = chunkTracker.msUntilCap(System.currentTimeMillis()) ?: return
        val runnable = Runnable {
            chunkCapRunnable = null
            val pkg = currentForegroundTarget ?: return@Runnable
            val now = System.currentTimeMillis()
            val untilMs = chunkTracker.startBreak(now)
            Log.d(TAG, "Session chunk cap hit — break for ${(untilMs - now) / 1000}s")
            handleTargetAppClosed(pkg, now)
            showBreakOverlay(untilMs)
        }
        chunkCapRunnable = runnable
        handler.postDelayed(runnable, delayMs.coerceAtLeast(250L))
    }

    private fun showBreakOverlay(untilMs: Long) {
        if (!Settings.canDrawOverlays(this)) {
            notifyOverlayPermissionMissing()
            return
        }
        // Same barrier semantics as the lock overlay: ignore the launcher
        // window-change triggered by sending the app to the background.
        isLockOverlayActive = true
        sendBlockedAppToBackground()
        try {
            startService(Intent(this, BreakOverlayService::class.java)
                .putExtra(BreakOverlayService.EXTRA_BREAK_UNTIL, untilMs))
        } catch (e: Exception) {
            Log.w(TAG, "showBreakOverlay failed", e)
        }
    }

    private fun showTimerOverlay(packageName: String) {
        if (!Settings.canDrawOverlays(this)) {
            notifyOverlayPermissionMissing()
            return
        }
        try {
            val sessionStart = sessionStartByPackage[packageName] ?: System.currentTimeMillis()
            startService(Intent(this, TimerOverlayService::class.java)
                .putExtra(TimerOverlayService.EXTRA_TARGET, packageName)
                .putExtra(TimerOverlayService.EXTRA_SESSION_START, sessionStart))
        } catch (e: Exception) {
            Log.w(TAG, "showTimerOverlay failed", e)
        }
    }

    // The timer badge (and the blockers) silently can't render without the overlay
    // permission — surface that once per day instead of failing invisibly.
    private fun notifyOverlayPermissionMissing() {
        val today = LocalDate.now().toString()
        if (overlayPermAlertSentDate == today) return
        overlayPermAlertSentDate = today
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) return
        }
        val settingsIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${getPackageName()}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, settingsIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("FocusLock can't show its timer")
            .setContentText("Grant \"Display over other apps\" to see the session timer and blockers.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_OVERLAY_PERM_ID, notification)
    }

    private fun dismissTimerOverlay() {
        try {
            startService(Intent(this, TimerOverlayService::class.java)
                .apply { action = TimerOverlayService.ACTION_DISMISS })
        } catch (e: Exception) {
            Log.w(TAG, "dismissTimerOverlay failed", e)
        }
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            // Superseded by CHANNEL_ID_MONITOR below — drop the old entry so
            // upgrading users don't keep a dead channel in system settings.
            mgr.deleteNotificationChannel(CHANNEL_ID_MONITOR_LEGACY)
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID_ALERTS, "FocusLock Alerts", NotificationManager.IMPORTANCE_HIGH)
            )
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_MONITOR,
                    "FocusLock Monitoring",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Persistent notice that FocusLock is actively monitoring app usage"
                }
            )
        }
    }

    // Promotes this service to foreground so OEM background-kill policies are far
    // less likely to tear it down silently (accessibility-service rebind is the
    // only other lifeline — and none while the service is parked). Required on API
    // 34+ to declare a foregroundServiceType; specialUse because on Android 15
    // dataSync is capped at 6 h/day and can't be started from BOOT_COMPLETED,
    // while this service must run all day to re-enable the parked service.
    private fun startMonitorForeground() {
        val notification = buildMonitorNotification(degraded = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_MONITOR_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_MONITOR_ID, notification)
        }
    }

    private fun buildMonitorNotification(degraded: Boolean) =
        androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID_MONITOR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                if (degraded) "⚠️ Protection OFF — tap to fix" else "FocusLock is monitoring app usage"
            )
            .setContentText(
                if (degraded) "Accessibility is disabled — limits and Reels blocking aren't enforced."
                else "Enforcing your focus limits in the background."
            )
            .setPriority(
                if (degraded) androidx.core.app.NotificationCompat.PRIORITY_HIGH
                else androidx.core.app.NotificationCompat.PRIORITY_LOW
            )
            .setOngoing(true)
            .build()

    // Flips the always-visible foreground-service notification between its
    // normal text and a degraded warning — this notification can't be swiped
    // away, so it costs nothing to keep it honest about protection state.
    private fun updateForegroundNotification(degraded: Boolean) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_MONITOR_ID, buildMonitorNotification(degraded))
    }
}
