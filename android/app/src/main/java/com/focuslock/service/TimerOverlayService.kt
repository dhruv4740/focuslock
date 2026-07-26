package com.focuslock.service

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.focuslock.surface.BlockableSurface
import com.focuslock.surface.SurfaceBlocker
import com.focuslock.surface.SurfaceMode
import kotlin.math.abs

class TimerOverlayService : Service() {

    companion object {
        private const val TAG = "TimerOverlayService"
        const val EXTRA_TARGET = "target"
        const val EXTRA_SESSION_START = "session_start"
        const val ACTION_DISMISS = "com.focuslock.DISMISS_TIMER_OVERLAY"
    }

    private var windowManager: WindowManager? = null
    private var timerView: TextView? = null
    private var updateHandler: Handler? = null
    private var sessionStartMs = 0L
    private var currentTarget: String? = null

    // For drag functionality
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle dismiss action
        if (intent?.action == ACTION_DISMISS) {
            Log.d(TAG, "Received dismiss action")
            removeTimerAndStop()
            return START_NOT_STICKY
        }

        val target = intent?.getStringExtra(EXTRA_TARGET) ?: "app"
        val sessionStart = intent?.getLongExtra(EXTRA_SESSION_START, System.currentTimeMillis())
            ?: System.currentTimeMillis()

        // If already showing for same target, just update the start time
        if (timerView != null && currentTarget == target) {
            return START_NOT_STICKY
        }

        // If showing for a different target, remove old one first
        if (timerView != null) {
            removeTimer()
        }

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot draw overlays, permission not granted")
            stopSelf()
            return START_NOT_STICKY
        }

        currentTarget = target
        sessionStartMs = sessionStart
        showTimer()
        startUpdating()

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeTimer()
        super.onDestroy()
    }

    private fun showTimer() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            // Create the timer TextView programmatically for a minimal, non-intrusive look
            timerView = TextView(this).apply {
                text = "0:00"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setBackgroundColor(Color.parseColor("#AA000000"))
                setPadding(dpToPx(10), dpToPx(4), dpToPx(10), dpToPx(4))
                gravity = Gravity.CENTER
            }

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dpToPx(8)
                y = dpToPx(100) // Below status bar
            }

            // Make draggable
            timerView?.setOnTouchListener(createDragTouchListener())

            windowManager?.addView(timerView, layoutParams)
            Log.d(TAG, "Timer overlay shown for: $currentTarget")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show timer overlay", e)
            stopSelf()
        }
    }

    private fun createDragTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            val params = layoutParams ?: return@OnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager?.updateViewLayout(timerView, params)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update timer position", e)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // If barely moved, treat as a click (toggle visibility of seconds)
                    val dx = abs(event.rawX - initialTouchX)
                    val dy = abs(event.rawY - initialTouchY)
                    if (dx < 10 && dy < 10) {
                        // Minimal tap — do nothing special for now
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun startUpdating() {
        updateHandler?.removeCallbacksAndMessages(null)
        updateHandler = Handler(Looper.getMainLooper())
        updateTimerText()
    }

    private fun updateTimerText() {
        if (timerView == null) return

        val elapsedMs = System.currentTimeMillis() - sessionStartMs
        val totalSeconds = (elapsedMs / 1000).toInt().coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60

        val timeText = "$minutes:${String.format("%02d", seconds)}"

        // Persistent daily total for this app: finalized sessions from the local
        // store plus the live (not yet finalized) session. Survives restarts,
        // unlike the per-session stopwatch.
        val dailyText = currentTarget?.let { target ->
            val stored = com.focuslock.data.AppUsageStore.minutesToday(
                this, java.time.LocalDate.now().toString(), target
            )
            val totalToday = (stored + elapsedMs / 60_000.0).toInt()
            "${totalToday}m today"
        }

        val reelText = currentTarget?.let { BlockableSurface.byPackage(it, SurfaceBlocker.learnedAliases()) }
            ?.let { SurfaceBlocker.statusFor(it) }
            ?.takeIf { it.enabled }
            ?.let { status ->
                val counts = if (status.mode == SurfaceMode.OFF || status.mode == SurfaceMode.BLOCK || status.limit <= 0) {
                    "${status.count} reels"
                } else {
                    "${status.count}/${status.limit}"
                }
                val suffix = if (status.mode == SurfaceMode.BLOCK || status.blocked) " ⛔" else ""
                "$counts$suffix"
            }

        timerView?.text = listOfNotNull(timeText, dailyText, reelText).joinToString(" · ")

        // Change color as time increases; past the (soft) daily limit it's red
        // regardless of how long this particular session has run.
        timerView?.setBackgroundColor(
            when {
                UsageMonitorService.isOverLimitSoft -> Color.parseColor("#CCCC0000") // Red — over daily limit
                minutes >= 25 -> Color.parseColor("#CCCC0000") // Red — way over
                minutes >= 15 -> Color.parseColor("#CCCC6600") // Orange — getting long
                minutes >= 5  -> Color.parseColor("#CC666600") // Yellow-ish — moderate
                else -> Color.parseColor("#AA000000") // Default dark
            }
        )

        updateHandler?.postDelayed({ updateTimerText() }, 1000)
    }

    private fun removeTimerAndStop() {
        removeTimer()
        stopSelf()
    }

    private fun removeTimer() {
        updateHandler?.removeCallbacksAndMessages(null)
        updateHandler = null

        timerView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove timer view", e)
            }
            timerView = null
        }
        currentTarget = null
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
