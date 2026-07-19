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
import com.focuslock.surface.SurfaceBlocker
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class UsageMonitorService : Service() {

    companion object {
        private const val TAG = "UsageMonitorService"
        private const val CHANNEL_ID_MONITOR_LEGACY = "focuslock-monitor"
        private const val CHANNEL_ID_ALERTS = "focuslock-alerts"
        private const val NOTIFICATION_ALERT_ID = 1102
        private const val NOTIFICATION_OVERLAY_PERM_ID = 1103
        private const val NOTIFICATION_LIMIT_REACHED_ID = 1104
        private const val OVERLAY_COOLDOWN_MS = 3000L

        const val ACTION_DISMISS_LOCK_OVERLAY = "com.focuslock.DISMISS_LOCK_OVERLAY"
        const val ACTION_FOREGROUND_CHANGED = "com.focuslock.ACTION_FOREGROUND_CHANGED"
        const val ACTION_POLICY_UPDATED = "com.focuslock.ACTION_POLICY_UPDATED"
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
    private var isLockOverlayActive = false
    private var isFrictionOverlayActive = false

    private val handler = Handler(Looper.getMainLooper())
    private val closeRunnableByPackage = mutableMapOf<String, Runnable>()
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val isRunning = AtomicBoolean(false)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isServiceActive = true
        FocusPolicyClient.init(this)
        SurfaceBlocker.init(this)
        createNotificationChannels()

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
        }

        isRunning.set(true)
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

        currentForegroundTarget = packageName

        // Cancel any pending session-close debounce for this package
        closeRunnableByPackage.remove(packageName)?.let { handler.removeCallbacks(it) }

        if (!sessionStartByPackage.containsKey(packageName)) {
            sessionStartByPackage[packageName] = timestamp
        }

        showTimerOverlay(packageName)

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
                handler.post {
                    appModes.clear()
                    appModes.putAll(resolved)
                    targetApps.clear()
                    targetApps.addAll(resolved.filterValues { it != com.focuslock.data.AppMode.OFF }.keys)
                    Log.d(TAG, "Policy refreshed: targetApps=$targetApps modes=$appModes")
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
            // The monitor no longer runs as a foreground service and posts no
            // notification of its own — drop the channel so upgrading users don't
            // keep a dead entry in system notification settings.
            mgr.deleteNotificationChannel(CHANNEL_ID_MONITOR_LEGACY)
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID_ALERTS, "FocusLock Alerts", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }
}
