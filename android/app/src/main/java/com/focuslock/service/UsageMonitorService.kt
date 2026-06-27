package com.focuslock.service

import android.app.Notification
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
import androidx.core.app.NotificationCompat
import com.focuslock.R
import com.focuslock.data.FocusPolicyClient
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class UsageMonitorService : Service() {

    companion object {
        private const val TAG = "UsageMonitorService"
        private const val CHANNEL_ID = "focuslock-monitor"
        private const val CHANNEL_ID_ALERTS = "focuslock-alerts"
        private const val NOTIFICATION_ID = 1101
        private const val NOTIFICATION_ALERT_ID = 1102
        private const val OVERLAY_COOLDOWN_MS = 3000L

        const val ACTION_DISMISS_LOCK_OVERLAY = "com.focuslock.DISMISS_LOCK_OVERLAY"
        const val ACTION_FOREGROUND_CHANGED = "com.focuslock.ACTION_FOREGROUND_CHANGED"
        const val EXTRA_PACKAGE_NAME = "package_name"

        // Cross-service shared state — written only from main thread
        @Volatile var currentForegroundTarget: String? = null
            internal set
        val blockedTargets = mutableSetOf<String>()
        @Volatile var lastBreakClickedAt = 0L
    }

    // Dynamic target set — starts empty, populated from policy
    private val targetApps = mutableSetOf<String>()

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
    private var hadSuccessfulEvaluate = false

    private var warningNotificationSent = false
    private var lastPolicyRefreshAt = 0L
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
        FocusPolicyClient.init(this)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        startForeground(NOTIFICATION_ID, buildNotification("Monitoring unproductive usage…"))

        when (intent?.action) {
            ACTION_FOREGROUND_CHANGED -> {
                intent.getStringExtra(EXTRA_PACKAGE_NAME)?.let { handleForegroundChanged(it) }
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
        if (packageName == this.packageName && (isLockOverlayActive || isFrictionOverlayActive)) {
            Log.d(TAG, "Ignoring own package while overlay is active")
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

        updateForegroundNotice("Tracking: $packageName")
        showTimerOverlay(packageName)

        if (blockedTargets.contains(packageName)) {
            showLockOverlay(packageName)
            evaluateAndLockIfNeeded(packageName)
        } else {
            val now = System.currentTimeMillis()
            val lastBypass = lastFrictionBypassTimeByPackage[packageName] ?: 0L
            if (now - lastBypass > FRICTION_COOLDOWN_MS) {
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
        updateForegroundNotice("Monitoring unproductive usage…")

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
                if (appsArray != null) {
                    val apps = (0 until appsArray.length()).map { appsArray.getString(it) }.toSet()
                    handler.post {
                        targetApps.clear()
                        targetApps.addAll(apps)
                        Log.d(TAG, "Policy refreshed: targetApps=$targetApps")
                    }
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
                val decision = FocusPolicyClient.evaluate(LocalDate.now().toString())

                // Persist the result so the offline fallback below uses real data
                localUsedMinutes = decision.usedMinutes
                localEffectiveLimit = decision.effectiveLimit
                localAllowNow = decision.allowNow
                hadSuccessfulEvaluate = true

                handler.post {
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
        warningNotificationSent = false
        localTimerRunnable = object : Runnable {
            override fun run() {
                if (currentForegroundTarget != packageName) return

                localUsedMinutes += 1.0
                Log.d(TAG, "Local tracker: %.1f / %d min".format(localUsedMinutes, localEffectiveLimit))

                if (localEffectiveLimit > 0) {
                    if (!warningNotificationSent && localUsedMinutes >= localEffectiveLimit * 0.8) {
                        warningNotificationSent = true
                        sendApproachingLimitNotification(localEffectiveLimit)
                    }
                    if (localUsedMinutes >= localEffectiveLimit) {
                        blockedTargets.add(packageName)
                        updateBlockedPreference(true)
                        showLockOverlay(packageName)
                        return
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

    private fun stopLocalTimer() {
        localTimerRunnable?.let { handler.removeCallbacks(it) }
        localTimerRunnable = null
    }

    // ── Usage event post ──────────────────────────────────────────────────────

    private fun postUsageSessionIfAny(packageName: String, endMs: Long) {
        val startMs = sessionStartByPackage.remove(packageName) ?: return
        val durationMinutes = (endMs - startMs) / 60_000.0
        if (durationMinutes <= 0.0) return

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
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val now = System.currentTimeMillis()
        if (now - (lastOverlayShownAt[packageName] ?: 0L) < OVERLAY_COOLDOWN_MS) return
        lastOverlayShownAt[packageName] = now

        dismissTimerOverlay()
        isLockOverlayActive = true
        startService(Intent(this, LockOverlayService::class.java)
            .putExtra(LockOverlayService.EXTRA_TARGET, packageName))
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
                Uri.parse("package:$packageName")
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
        try {
            val sessionStart = sessionStartByPackage[packageName] ?: System.currentTimeMillis()
            startService(Intent(this, TimerOverlayService::class.java)
                .putExtra(TimerOverlayService.EXTRA_TARGET, packageName)
                .putExtra(TimerOverlayService.EXTRA_SESSION_START, sessionStart))
        } catch (e: Exception) {
            Log.w(TAG, "showTimerOverlay failed", e)
        }
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
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "FocusLock Monitor", NotificationManager.IMPORTANCE_LOW)
            )
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID_ALERTS, "FocusLock Alerts", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun buildNotification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("FocusLock")
            .setContentText(message)
            .setOngoing(true)
            .build()

    private fun updateForegroundNotice(message: String) {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification(message))
        } catch (e: Exception) {
            Log.w(TAG, "updateForegroundNotice failed", e)
        }
    }
}
