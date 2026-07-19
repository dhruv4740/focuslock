package com.focuslock.service

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.focuslock.R
import com.focuslock.util.HapticHelper

/**
 * Fullscreen overlay for the forced session-chunk break: shows a countdown
 * until the break ends, auto-dismisses at zero. Dismissable early via OK —
 * the break itself stays enforced by UsageMonitorService, which re-shows this
 * overlay if any managed app is opened before the break is over.
 */
class BreakOverlayService : Service() {

    companion object {
        private const val TAG = "BreakOverlayService"
        const val EXTRA_BREAK_UNTIL = "break_until"
        const val ACTION_DISMISS = "com.focuslock.DISMISS_BREAK_OVERLAY"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var countdownText: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var breakUntilMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS) {
            removeOverlayAndStop()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot draw overlays, permission not granted")
            stopSelf()
            return START_NOT_STICKY
        }
        breakUntilMs = intent?.getLongExtra(EXTRA_BREAK_UNTIL, 0L) ?: 0L
        if (breakUntilMs <= System.currentTimeMillis()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (overlayView == null) {
            showOverlay()
        }
        tick()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        // Clear the monitor's overlay-barrier flag (same contract as LockOverlayService)
        // so foreground tracking resumes normally after the break screen goes away.
        try {
            startService(Intent(this, UsageMonitorService::class.java)
                .apply { action = "com.focuslock.ACTION_LOCK_OVERLAY_DISMISSED" })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to notify UsageMonitorService of break overlay dismissal", e)
        }
        super.onDestroy()
    }

    private fun showOverlay() {
        try {
            HapticHelper.playLockAlert(this)
            val density = resources.displayMetrics.density

            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#FA121820"))
                val pad = (24 * density).toInt()
                setPadding(pad, pad, pad, pad)
                isClickable = true
                isFocusable = true
            }

            layout.addView(TextView(this).apply {
                text = "Break time"
                setTextColor(getColor(R.color.text_primary))
                textSize = 26f
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })

            layout.addView(TextView(this).apply {
                text = "You've used up this chunk. Look away from the screen for a moment — your apps unlock when the timer ends."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, (12 * density).toInt(), 0, 0)
            })

            countdownText = TextView(this).apply {
                setTextColor(getColor(R.color.color_accent))
                textSize = 44f
                gravity = Gravity.CENTER
                setPadding(0, (20 * density).toInt(), 0, (20 * density).toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            layout.addView(countdownText)

            layout.addView(Button(this).apply {
                text = "OK — taking a break"
                setTextColor(getColor(R.color.bg_dark))
                background = getDrawable(R.drawable.button_primary)
                setOnClickListener { removeOverlayAndStop() }
            })

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager?.addView(layout, params)
            overlayView = layout
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show break overlay", e)
            stopSelf()
        }
    }

    private fun tick() {
        handler.removeCallbacksAndMessages(null)
        val remainingMs = breakUntilMs - System.currentTimeMillis()
        if (remainingMs <= 0) {
            removeOverlayAndStop()
            return
        }
        val totalSeconds = (remainingMs / 1000).toInt()
        countdownText?.text = "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
        handler.postDelayed({ tick() }, 1000)
    }

    private fun removeOverlay() {
        handler.removeCallbacksAndMessages(null)
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "removeView failed", e)
            }
        }
        overlayView = null
        countdownText = null
    }

    private fun removeOverlayAndStop() {
        removeOverlay()
        stopSelf()
    }
}
