package com.focuslock.service

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.focuslock.R
import com.focuslock.data.FocusPolicyClient
import com.focuslock.util.HapticHelper
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class LockOverlayService : Service() {

    companion object {
        private const val TAG = "LockOverlayService"
        const val EXTRA_TARGET = "target"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    // Separate handler so break-reminder callbacks survive mainHandler.removeCallbacksAndMessages()
    private val reminderHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var countdownHandler: Handler? = null
    private var blockStartTime = 0L
    private var blockDurationSeconds = 300
    private var currentTarget: String? = null
    private val isRequestingExtension = AtomicBoolean(false)
    private val networkExecutor = Executors.newSingleThreadExecutor()

    private var extensionMathAnswer = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle dismiss action from UsageMonitorService
        if (intent?.action == UsageMonitorService.ACTION_DISMISS_LOCK_OVERLAY) {
            Log.d(TAG, "Received dismiss action")
            removeOverlayAndStop()
            return START_NOT_STICKY
        }

        val target = intent?.getStringExtra(EXTRA_TARGET) ?: "target app"
        
        // Prevent showing if the target app is no longer in the foreground
        if (UsageMonitorService.currentForegroundTarget != target) {
            Log.d(TAG, "Aborting lock overlay: target $target is no longer foreground")
            stopSelf()
            return START_NOT_STICKY
        }

        if (overlayView == null) {
            // Check overlay permission first
            if (!Settings.canDrawOverlays(this)) {
                Log.w(TAG, "Cannot draw overlays, permission not granted")
                stopSelf()
                return START_NOT_STICKY
            }

            currentTarget = target
            blockStartTime = System.currentTimeMillis()
            showOverlay(target)
            startCountdown()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        networkExecutor.shutdownNow()
        
        val intent = Intent(this, UsageMonitorService::class.java).apply {
            action = "com.focuslock.ACTION_LOCK_OVERLAY_DISMISSED"
        }
        try {
            startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to notify UsageMonitorService of lock overlay dismissal", e)
        }
        
        super.onDestroy()
    }

    private fun playBreathingCue() {
        // 4-second breathing cue: inhale → hold → exhale via haptics
        HapticHelper.playBreathingInhaleTick(this, 0)
        mainHandler.postDelayed({ HapticHelper.playBreathingInhaleTick(this, 1) }, 1000)
        mainHandler.postDelayed({ HapticHelper.playBreathingInhaleTick(this, 2) }, 2000)
        mainHandler.postDelayed({ HapticHelper.playBreathingInhaleTick(this, 3) }, 3000)
        mainHandler.postDelayed({ HapticHelper.playBreathingHoldTick(this) }, 4000)
        mainHandler.postDelayed({ HapticHelper.playBreathingHoldTick(this) }, 5000)
        mainHandler.postDelayed({ HapticHelper.playBreathingHoldTick(this) }, 6000)
        mainHandler.postDelayed({ HapticHelper.playBreathingExhaleTick(this, 0) }, 7000)
        mainHandler.postDelayed({ HapticHelper.playBreathingExhaleTick(this, 1) }, 8000)
        mainHandler.postDelayed({ HapticHelper.playBreathingExhaleTick(this, 2) }, 9000)
        mainHandler.postDelayed({ HapticHelper.playBreathingExhaleTick(this, 3) }, 10000)
    }

    private fun scheduleBreakReminder() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel("focuslock-reminder", "Break Reminders", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        reminderHandler.postDelayed({
            // Only fire if user hasn't opened a tracked app since taking the break
            if (System.currentTimeMillis() - UsageMonitorService.lastBreakClickedAt >= 290_000L) {
                val notification = NotificationCompat.Builder(this, "focuslock-reminder")
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Still on your break?")
                    .setContentText("5 minutes of rest done. Stay intentional — don't drift back in.")
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
                nm.notify(8523, notification)
            }
        }, 5 * 60 * 1000L)
    }

    private fun showOverlay(target: String) {
        try {
            HapticHelper.playLockAlert(this)
            // After 2 s play a one-cycle breathing cue so the user has a physical anchor
            mainHandler.postDelayed({ playBreathingCue() }, 2000)
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val inflater = LayoutInflater.from(this)
            overlayView = inflater.inflate(R.layout.overlay_lock, null)

            overlayView?.findViewById<TextView>(R.id.txtOverlayTitle)?.text =
                "FocusLock paused $target"

            // Set a rotating motivational quote
            val quotes = listOf(
                "Focus on being productive instead of busy.",
                "Your focus determines your reality.",
                "Disconnect to reconnect with what matters.",
                "Opportunities don't happen. You create them.",
                "Do not let social media control your attention.",
                "Stay intentional, stay present."
            )
            val randomQuote = quotes.random()
            overlayView?.findViewById<TextView>(R.id.txtOverlayQuote)?.text = "\"$randomQuote\""

            // Fetch and display today's usage stats
            val txtStats = overlayView?.findViewById<TextView>(R.id.txtOverlayStats)
            val btnRequestExtension = overlayView?.findViewById<Button>(R.id.btnOverlayRequestExtension)
            networkExecutor.submit {
                try {
                    val statsJson = FocusPolicyClient.getStats(LocalDate.now().toString())
                    val opens = statsJson.optInt("opensToday", 0)
                    val duration = statsJson.optDouble("usedMinutes", 0.0).toInt()
                    Handler(Looper.getMainLooper()).post {
                        txtStats?.text = "$opens opens • $duration min spent today"
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load stats for lock overlay", e)
                    Handler(Looper.getMainLooper()).post {
                        txtStats?.text = "Stats offline"
                    }
                }

                try {
                    val extMinutes = FocusPolicyClient.getPolicy().extensionMinutes
                    Handler(Looper.getMainLooper()).post {
                        btnRequestExtension?.text = "Request $extMinutes Minute Extension"
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load policy for extension button label", e)
                }
            }

            // "I'll take a break now" button — sends user to home screen and dismisses overlay
            overlayView?.findViewById<Button>(R.id.btnOverlayClose)?.setOnClickListener {
                Log.d(TAG, "Break button tapped")
                UsageMonitorService.lastBreakClickedAt = System.currentTimeMillis()
                scheduleBreakReminder()
                goToHomeScreen()
                removeOverlayAndStop()
            }

            // Setup Math Challenge Gate for Requesting Extension
            val btnRequest = overlayView?.findViewById<Button>(R.id.btnOverlayRequestExtension)
            val layoutMath = overlayView?.findViewById<LinearLayout>(R.id.layoutExtensionMath)
            val txtMathQuestion = overlayView?.findViewById<TextView>(R.id.txtExtensionMathQuestion)
            val edtMathAnswer = overlayView?.findViewById<EditText>(R.id.edtExtensionMathAnswer)
            val btnMathVerify = overlayView?.findViewById<Button>(R.id.btnExtensionMathVerify)

            btnRequest?.setOnClickListener {
                // Show the math challenge container
                layoutMath?.visibility = View.VISIBLE
                btnRequest.visibility = View.GONE
                generateExtensionMathQuestion(txtMathQuestion)
            }

            btnMathVerify?.setOnClickListener {
                val input = edtMathAnswer?.text?.toString()?.trim()?.toIntOrNull()
                if (input == extensionMathAnswer) {
                    HapticHelper.playSuccess(this@LockOverlayService)
                    layoutMath?.visibility = View.GONE
                    // Verify successful, request extension from backend
                    requestOneTimeExtension(target)
                } else {
                    HapticHelper.playFailure(this@LockOverlayService)
                    Toast.makeText(this@LockOverlayService, "Incorrect! Try again.", Toast.LENGTH_SHORT).show()
                    edtMathAnswer?.setText("")
                    generateExtensionMathQuestion(txtMathQuestion)
                }
            }

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.CENTER

            windowManager?.addView(overlayView, params)
            Log.d(TAG, "Lock overlay shown for: $target")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay", e)
            stopSelf()
        }
    }

    private fun generateExtensionMathQuestion(textView: TextView?) {
        val prefs = getSharedPreferences("FocusLockPrefs", Context.MODE_PRIVATE)
        val difficulty = prefs.getString("math_difficulty", "easy") ?: "easy"

        when (difficulty) {
            "medium" -> {
                val a = Random.nextInt(4, 10)
                val b = Random.nextInt(11, 26)
                extensionMathAnswer = a * b
                textView?.text = "$a * $b = ?"
            }
            "hard" -> {
                val a = Random.nextInt(3, 8)
                val b = Random.nextInt(11, 21)
                val c = Random.nextInt(10, 41)
                extensionMathAnswer = a * b + c
                textView?.text = "$a * $b + $c = ?"
            }
            else -> { // easy
                val a = Random.nextInt(10, 45)
                val b = Random.nextInt(10, 45)
                extensionMathAnswer = a + b
                textView?.text = "$a + $b = ?"
            }
        }
    }

    private fun requestOneTimeExtension(target: String) {
        // Debounce: prevent multiple simultaneous requests
        if (!isRequestingExtension.compareAndSet(false, true)) {
            Log.d(TAG, "Extension request already in progress")
            return
        }

        val statusText = overlayView?.findViewById<TextView>(R.id.txtOverlayStatus)
        val extensionButton = overlayView?.findViewById<Button>(R.id.btnOverlayRequestExtension)

        statusText?.text = "Checking extension availability..."
        extensionButton?.isEnabled = false

        networkExecutor.submit {
            try {
                val result = FocusPolicyClient.requestExtension(LocalDate.now().toString())
                Handler(Looper.getMainLooper()).post {
                    if (result.granted) {
                        statusText?.text = "Extension granted for ${result.grantedMinutes} minutes. Stay intentional."
                        UsageMonitorService.blockedTargets.remove(target)
                        // Delay removal slightly so user can read the message
                        Handler(Looper.getMainLooper()).postDelayed({
                            removeOverlayAndStop()
                        }, 1500)
                    } else {
                        val message = when (result.reason) {
                            "daily-extension-limit-reached" -> "Extension already used today. Try again tomorrow."
                            "focus-session-active" -> "Cannot extend during a focus session."
                            else -> "No extension available now (${result.reason})."
                        }
                        statusText?.text = message
                        extensionButton?.isEnabled = true
                        extensionButton?.visibility = View.VISIBLE // Bring request button back in case of failure
                        isRequestingExtension.set(false)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Extension request failed", e)
                Handler(Looper.getMainLooper()).post {
                    statusText?.text = "Could not contact backend. Try again."
                    extensionButton?.isEnabled = true
                    extensionButton?.visibility = View.VISIBLE
                    isRequestingExtension.set(false)
                }
            }
        }
    }

    private fun goToHomeScreen() {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(homeIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to go to home screen", e)
        }
    }

    private fun removeOverlayAndStop() {
        removeOverlay()
        stopSelf()
    }

    private fun removeOverlay() {
        mainHandler.removeCallbacksAndMessages(null)
        stopCountdown()
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove overlay view", e)
            }
            overlayView = null
        }
        currentTarget = null
        isRequestingExtension.set(false)
    }

    private fun startCountdown() {
        countdownHandler = Handler(Looper.getMainLooper())
        updateCountdown()
    }

    private fun stopCountdown() {
        countdownHandler?.removeCallbacksAndMessages(null)
        countdownHandler = null
    }

    private fun updateCountdown() {
        if (overlayView == null) return

        val elapsed = (System.currentTimeMillis() - blockStartTime) / 1000
        val remaining = (blockDurationSeconds - elapsed.toInt()).coerceAtLeast(0)
        val minutes = remaining / 60
        val seconds = remaining % 60

        overlayView?.findViewById<TextView>(R.id.txtOverlayStatus)?.text =
            if (remaining > 0) {
                "Blocked for $minutes:${String.format("%02d", seconds)}"
            } else {
                "Block expired. Tap the button below to close."
            }

        // When block expires, enable the close button to actually dismiss
        if (remaining <= 0) {
            overlayView?.findViewById<Button>(R.id.btnOverlayClose)?.text = "Close and continue"
            return
        }

        countdownHandler?.postDelayed({ updateCountdown() }, 1000)
    }
}
