package com.focuslock.service

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.focuslock.R
import com.focuslock.data.FocusPolicyClient
import com.focuslock.util.HapticHelper
import java.time.LocalDate
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class FrictionBarrierService : Service() {

    companion object {
        private const val TAG = "FrictionBarrierService"
        const val EXTRA_TARGET = "target"
        const val ACTION_DISMISS = "com.focuslock.DISMISS_FRICTION_OVERLAY"
    }

    enum class StepType {
        BREATHING, DELAY, INTENTION, SHAKE, REFLECTION, STATS, MATH
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var currentTarget: String? = null
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    // Step views
    private var layoutBreathing: LinearLayout? = null
    private var layoutDelay: LinearLayout? = null
    private var layoutIntention: LinearLayout? = null
    private var layoutShake: LinearLayout? = null
    private var layoutReflection: LinearLayout? = null
    private var layoutStatsTodos: LinearLayout? = null
    private var layoutMath: LinearLayout? = null
    private var layoutDecision: LinearLayout? = null

    // Sequencing state
    private val activeSteps = mutableListOf<StepType>()
    private var currentStepIndex = 0

    // Breathing variables
    private var breathingCircle: View? = null
    private var txtBreathingAction: TextView? = null
    private var txtBreathingTimer: TextView? = null
    private var breathingTimerValue = 12
    private var breathingRunnable: Runnable? = null
    private var breathingAnimatorSet: AnimatorSet? = null

    // Wait Delay variables
    private var delayTicks = 0
    private var delayRunnable: Runnable? = null
    private var barDelayProgress: ProgressBar? = null
    private var txtDelaySeconds: TextView? = null

    // Shake challenge variables
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var shakeScore = 0f
    private val maxShakeLimit = 150f
    private var isShakeRegistered = false
    private var barShakeProgress: ProgressBar? = null
    private var txtShakeStatus: TextView? = null

    // Reflection variables
    private var currentReflectionPhrase = ""
    private var txtReflectionPhrase: TextView? = null
    private var edtReflectionInput: EditText? = null

    // Math Challenge variables
    private var mathAnswer = 0
    private var txtMathQuestion: TextView? = null
    private var edtMathAnswer: EditText? = null

    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null) return
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val g = sqrt(x * x + y * y + z * z)
            val delta = abs(g - SensorManager.GRAVITY_EARTH)
            if (delta > 11f) {
                shakeScore += delta * 0.25f
                HapticHelper.playTick(this@FrictionBarrierService)
                val progress = ((shakeScore / maxShakeLimit) * 100).toInt().coerceIn(0, 100)
                barShakeProgress?.progress = progress
                txtShakeStatus?.text = "Shake meter: $progress%"

                if (shakeScore >= maxShakeLimit) {
                    stopShakeChallenge()
                    HapticHelper.playSuccess(this@FrictionBarrierService)
                    moveToNextStep()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS) {
            Log.d(TAG, "Dismiss action received")
            removeOverlayAndStop()
            return START_NOT_STICKY
        }

        val target = intent?.getStringExtra(EXTRA_TARGET) ?: "target app"
        
        // Prevent showing if the target app is no longer in the foreground
        if (UsageMonitorService.currentForegroundTarget != target) {
            Log.d(TAG, "Aborting friction overlay: target $target is no longer foreground")
            stopSelf()
            return START_NOT_STICKY
        }

        if (overlayView == null) {
            if (!Settings.canDrawOverlays(this)) {
                Log.w(TAG, "Cannot draw overlays, permission not granted")
                stopSelf()
                return START_NOT_STICKY
            }

            currentTarget = target
            showOverlay(target)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        networkExecutor.shutdownNow()
        
        val intent = Intent(this, UsageMonitorService::class.java).apply {
            action = "com.focuslock.ACTION_FRICTION_OVERLAY_DISMISSED"
        }
        try {
            startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to notify UsageMonitorService of friction overlay dismissal", e)
        }
        
        super.onDestroy()
    }

    private fun showOverlay(target: String) {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val inflater = LayoutInflater.from(this)
            overlayView = inflater.inflate(R.layout.overlay_friction, null)

            // Setup persistent exit option
            overlayView?.findViewById<Button>(R.id.btnFrictionCancel)?.setOnClickListener {
                UsageMonitorService.lastBreakClickedAt = System.currentTimeMillis()
                goToHomeScreen()
                removeOverlayAndStop()
            }

            // Identify layout step containers
            layoutBreathing = overlayView?.findViewById(R.id.layoutStepBreathing)
            layoutDelay = overlayView?.findViewById(R.id.layoutStepDelay)
            layoutIntention = overlayView?.findViewById(R.id.layoutStepIntention)
            layoutShake = overlayView?.findViewById(R.id.layoutStepShake)
            layoutReflection = overlayView?.findViewById(R.id.layoutStepReflection)
            layoutStatsTodos = overlayView?.findViewById(R.id.layoutStepStatsTodos)
            layoutMath = overlayView?.findViewById(R.id.layoutStepMath)
            layoutDecision = overlayView?.findViewById(R.id.layoutStepDecision)

            // Configure steps
            setupStepBreathing()
            setupStepDelay()
            setupStepIntention()
            setupStepShake()
            setupStepReflection()
            setupStepStatsTodos(target)
            setupStepMath()
            setupStepDecision()

            // Initialize active steps based on user settings
            val prefs = getSharedPreferences("FocusLockPrefs", Context.MODE_PRIVATE)
            activeSteps.clear()
            if (prefs.getBoolean("enable_breathing", true)) activeSteps.add(StepType.BREATHING)
            if (prefs.getBoolean("enable_delay", true)) activeSteps.add(StepType.DELAY)
            if (prefs.getBoolean("enable_intention", true)) activeSteps.add(StepType.INTENTION)
            if (prefs.getBoolean("enable_shake", true)) activeSteps.add(StepType.SHAKE)
            if (prefs.getBoolean("enable_reflection", true)) activeSteps.add(StepType.REFLECTION)
            if (prefs.getBoolean("enable_stats", true)) activeSteps.add(StepType.STATS)
            if (prefs.getBoolean("enable_math", true)) activeSteps.add(StepType.MATH)

            currentStepIndex = 0

            // Layout Params for Fullscreen Overlay
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
            Log.d(TAG, "Friction overlay shown for: $target")

            // Play warning lock sound/alert haptic
            HapticHelper.playLockAlert(this)

            // Start dynamic sequencing
            moveToNextStep()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to show friction overlay", e)
            stopSelf()
        }
    }

    private fun transitionToStep(stepLayoutToShow: LinearLayout?) {
        handler.post {
            layoutBreathing?.visibility = View.GONE
            layoutDelay?.visibility = View.GONE
            layoutIntention?.visibility = View.GONE
            layoutShake?.visibility = View.GONE
            layoutReflection?.visibility = View.GONE
            layoutStatsTodos?.visibility = View.GONE
            layoutMath?.visibility = View.GONE
            layoutDecision?.visibility = View.GONE

            stepLayoutToShow?.visibility = View.VISIBLE
        }
    }

    private fun moveToNextStep() {
        stopBreathingAnimation()
        stopDelayTimer()
        stopShakeChallenge()

        HapticHelper.playStepTransition(this)

        if (currentStepIndex < activeSteps.size) {
            val nextStep = activeSteps[currentStepIndex]
            currentStepIndex++

            val layoutToShow = when (nextStep) {
                StepType.BREATHING -> layoutBreathing
                StepType.DELAY -> layoutDelay
                StepType.INTENTION -> layoutIntention
                StepType.SHAKE -> layoutShake
                StepType.REFLECTION -> layoutReflection
                StepType.STATS -> layoutStatsTodos
                StepType.MATH -> layoutMath
            }
            transitionToStep(layoutToShow)

            when (nextStep) {
                StepType.BREATHING -> startBreathingExercise()
                StepType.DELAY -> startDelayTimer()
                StepType.INTENTION -> { /* Click listener already set */ }
                StepType.SHAKE -> startShakeChallenge()
                StepType.REFLECTION -> startReflectionChallenge()
                StepType.STATS -> { /* Async fetch started in setup */ }
                StepType.MATH -> startMathChallenge()
            }
        } else {
            transitionToStep(layoutDecision)
        }
    }

    // --- STEP 1: BREATHING ---
    private fun setupStepBreathing() {
        breathingCircle = overlayView?.findViewById(R.id.viewBreathingCircle)
        txtBreathingAction = overlayView?.findViewById(R.id.txtBreathingAction)
        txtBreathingTimer = overlayView?.findViewById(R.id.txtBreathingTimer)
    }

    private fun startBreathingExercise() {
        breathingTimerValue = 12
        txtBreathingTimer?.text = "$breathingTimerValue seconds remaining"

        // Inhale 4s, Hold 4s, Exhale 4s
        runBreathingAnimationCycle()

        breathingRunnable = object : Runnable {
            override fun run() {
                breathingTimerValue--
                txtBreathingTimer?.text = "$breathingTimerValue seconds remaining"

                val label = when {
                    breathingTimerValue > 8 -> {
                        txtBreathingAction?.setTextColor(Color.parseColor("#2ECC71"))
                        "Inhale..."
                    }
                    breathingTimerValue > 4 -> {
                        txtBreathingAction?.setTextColor(Color.parseColor("#F1C40F"))
                        "Hold..."
                    }
                    else -> {
                        txtBreathingAction?.setTextColor(Color.parseColor("#E67E22"))
                        "Exhale..."
                    }
                }
                txtBreathingAction?.text = label

                // Play second-by-second breathing haptics
                when {
                    breathingTimerValue in 9..12 -> {
                        val secondOfInhale = 12 - breathingTimerValue
                        HapticHelper.playBreathingInhaleTick(this@FrictionBarrierService, secondOfInhale)
                    }
                    breathingTimerValue in 5..8 -> {
                        HapticHelper.playBreathingHoldTick(this@FrictionBarrierService)
                    }
                    breathingTimerValue in 1..4 -> {
                        val secondOfExhale = 4 - breathingTimerValue
                        HapticHelper.playBreathingExhaleTick(this@FrictionBarrierService, secondOfExhale)
                    }
                }

                if (breathingTimerValue > 0) {
                    handler.postDelayed(this, 1000)
                } else {
                    HapticHelper.playSuccess(this@FrictionBarrierService)
                    moveToNextStep()
                }
            }
        }
        handler.postDelayed(breathingRunnable!!, 1000)
    }

    private fun runBreathingAnimationCycle() {
        val circle = breathingCircle ?: return

        // Inhale: Scale 1.0 -> 1.6 in 4s
        val scaleUpX = ObjectAnimator.ofFloat(circle, "scaleX", 1.0f, 1.6f).setDuration(4000)
        val scaleUpY = ObjectAnimator.ofFloat(circle, "scaleY", 1.0f, 1.6f).setDuration(4000)

        // Hold: Stay at 1.6 for 4s
        val holdX = ObjectAnimator.ofFloat(circle, "scaleX", 1.6f, 1.6f).setDuration(4000)
        val holdY = ObjectAnimator.ofFloat(circle, "scaleY", 1.6f, 1.6f).setDuration(4000)

        // Exhale: Scale 1.6 -> 1.0 in 4s
        val scaleDownX = ObjectAnimator.ofFloat(circle, "scaleX", 1.6f, 1.0f).setDuration(4000)
        val scaleDownY = ObjectAnimator.ofFloat(circle, "scaleY", 1.6f, 1.0f).setDuration(4000)

        breathingAnimatorSet = AnimatorSet().apply {
            play(scaleUpX).with(scaleUpY)
            play(holdX).with(holdY).after(scaleUpX)
            play(scaleDownX).with(scaleDownY).after(holdX)
            start()
        }
    }

    private fun stopBreathingAnimation() {
        breathingRunnable?.let { handler.removeCallbacks(it) }
        breathingRunnable = null
        breathingAnimatorSet?.cancel()
        breathingAnimatorSet = null
    }

    // --- STEP: WAIT DELAY ---
    private fun setupStepDelay() {
        barDelayProgress = overlayView?.findViewById(R.id.barDelayProgress)
        txtDelaySeconds = overlayView?.findViewById(R.id.txtDelaySeconds)
    }

    private fun startDelayTimer() {
        delayTicks = 0
        barDelayProgress?.progress = 0
        txtDelaySeconds?.text = "10 seconds remaining"

        delayRunnable = object : Runnable {
            override fun run() {
                delayTicks++
                val progress = (delayTicks * 100) / 100
                barDelayProgress?.progress = progress

                val remaining = 10 - (delayTicks / 10)
                txtDelaySeconds?.text = "$remaining seconds remaining"

                if (delayTicks % 10 == 0 && remaining > 0) {
                    HapticHelper.playTick(this@FrictionBarrierService)
                }

                if (delayTicks >= 100) {
                    HapticHelper.playSuccess(this@FrictionBarrierService)
                    moveToNextStep()
                } else {
                    handler.postDelayed(this, 100)
                }
            }
        }
        handler.postDelayed(delayRunnable!!, 100)
    }

    private fun stopDelayTimer() {
        delayRunnable?.let { handler.removeCallbacks(it) }
        delayRunnable = null
    }

    // --- STEP 2: INTENTION PROMPT ---
    private fun setupStepIntention() {
        val edtCustomIntention = overlayView?.findViewById<EditText>(R.id.edtCustomIntention)
        val btnNext = overlayView?.findViewById<Button>(R.id.btnIntentionNext)

        val selectIntention = { text: String ->
            edtCustomIntention?.setText(text)
            edtCustomIntention?.setSelection(text.length)
        }

        overlayView?.findViewById<Button>(R.id.btnIntention1)?.setOnClickListener {
            HapticHelper.playUiUpdate(this)
            selectIntention("Just checking notifications (quick)")
        }
        overlayView?.findViewById<Button>(R.id.btnIntention2)?.setOnClickListener {
            HapticHelper.playUiUpdate(this)
            selectIntention("Need to post/message someone specific")
        }
        overlayView?.findViewById<Button>(R.id.btnIntention3)?.setOnClickListener {
            HapticHelper.playUiUpdate(this)
            selectIntention("Boredom / Dopamine craving")
        }

        btnNext?.setOnClickListener {
            val intention = edtCustomIntention?.text?.toString()?.trim() ?: ""
            if (intention.isEmpty()) {
                HapticHelper.playFailure(this)
                Toast.makeText(this, "Please select or type your intention", Toast.LENGTH_SHORT).show()
            } else {
                HapticHelper.playSuccess(this)
                moveToNextStep()
            }
        }
    }

    // --- STEP: SHAKE CHALLENGE ---
    private fun setupStepShake() {
        barShakeProgress = overlayView?.findViewById(R.id.barShakeProgress)
        txtShakeStatus = overlayView?.findViewById(R.id.txtShakeStatus)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    private fun startShakeChallenge() {
        shakeScore = 0f
        barShakeProgress?.progress = 0
        txtShakeStatus?.text = "Shake meter: 0%"

        if (accelerometer != null) {
            sensorManager?.registerListener(shakeListener, accelerometer, SensorManager.SENSOR_DELAY_UI)
            isShakeRegistered = true
        } else {
            // Accelerometer not available, bypass step
            moveToNextStep()
        }
    }

    private fun stopShakeChallenge() {
        if (isShakeRegistered) {
            sensorManager?.unregisterListener(shakeListener)
            isShakeRegistered = false
        }
    }

    // --- STEP: COGNITIVE REFLECTION ---
    private fun setupStepReflection() {
        txtReflectionPhrase = overlayView?.findViewById(R.id.txtReflectionPhrase)
        edtReflectionInput = overlayView?.findViewById(R.id.edtReflectionInput)
        val btnReflectionVerify = overlayView?.findViewById<Button>(R.id.btnReflectionVerify)

        btnReflectionVerify?.setOnClickListener {
            val input = edtReflectionInput?.text?.toString()?.trim() ?: ""
            if (input.equals(currentReflectionPhrase, ignoreCase = true)) {
                HapticHelper.playSuccess(this)
                moveToNextStep()
            } else {
                HapticHelper.playFailure(this)
                Toast.makeText(this, "Text doesn't match! Try again.", Toast.LENGTH_SHORT).show()
                edtReflectionInput?.setText("")
                generateNewReflectionPhrase()
            }
        }
    }

    private fun startReflectionChallenge() {
        edtReflectionInput?.setText("")
        generateNewReflectionPhrase()
    }

    private fun generateNewReflectionPhrase() {
        val phrases = listOf(
            "I am choosing temporary distraction over my long-term goals",
            "Is this app more important than my dreams?",
            "Take a breath. Stay present. Put the phone down.",
            "I want to focus on what truly matters today"
        )
        currentReflectionPhrase = phrases.random()
        txtReflectionPhrase?.text = currentReflectionPhrase
    }

    // --- STEP 3: STATS & TODOS ---
    private fun setupStepStatsTodos(target: String) {
        val txtStatsOpens = overlayView?.findViewById<TextView>(R.id.txtStatsOpens)
        val txtStatsDuration = overlayView?.findViewById<TextView>(R.id.txtStatsDuration)
        val containerTodos = overlayView?.findViewById<LinearLayout>(R.id.containerFrictionTodos)
        val placeholder = overlayView?.findViewById<TextView>(R.id.txtNoTodosPlaceholder)
        val btnStatsNext = overlayView?.findViewById<Button>(R.id.btnStatsNext)

        btnStatsNext?.setOnClickListener {
            HapticHelper.playSuccess(this)
            moveToNextStep()
        }

        // Fetch stats & todos asynchronously from backend
        networkExecutor.submit {
            try {
                // 1. Fetch Stats
                val statsJson = FocusPolicyClient.getStats(LocalDate.now().toString())
                val opens = statsJson.optInt("opensToday", 0)
                val duration = statsJson.optDouble("usedMinutes", 0.0).toInt()

                // 2. Fetch Todos
                val todos = FocusPolicyClient.getTodos().filter { !it.completed }

                handler.post {
                    txtStatsOpens?.text = "$opens times"
                    txtStatsDuration?.text = "$duration min"

                    // Populate Todos list
                    if (todos.isNotEmpty()) {
                        placeholder?.visibility = View.GONE
                        containerTodos?.removeAllViews()

                        for (todo in todos) {
                            val todoView = TextView(this@FrictionBarrierService).apply {
                                text = "• ${todo.text}"
                                setTextColor(Color.WHITE)
                                setTextSize(14f)
                                setPadding(8, 6, 8, 6)
                            }
                            containerTodos?.addView(todoView)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load stats/todos for friction overlay", e)
            }
        }
    }

    // --- STEP 4: MATH CHALLENGE ---
    private fun setupStepMath() {
        txtMathQuestion = overlayView?.findViewById(R.id.txtMathQuestion)
        edtMathAnswer = overlayView?.findViewById(R.id.edtMathAnswer)
        val btnMathVerify = overlayView?.findViewById<Button>(R.id.btnMathVerify)

        btnMathVerify?.setOnClickListener {
            val input = edtMathAnswer?.text?.toString()?.trim()?.toIntOrNull()
            if (input == mathAnswer) {
                HapticHelper.playSuccess(this@FrictionBarrierService)
                moveToNextStep()
            } else {
                HapticHelper.playFailure(this@FrictionBarrierService)
                Toast.makeText(this, "Incorrect! Try again.", Toast.LENGTH_SHORT).show()
                edtMathAnswer?.setText("")
                generateMathQuestion()
            }
        }
    }

    private fun startMathChallenge() {
        edtMathAnswer?.setText("")
        generateMathQuestion()
    }

    private fun generateMathQuestion() {
        val prefs = getSharedPreferences("FocusLockPrefs", Context.MODE_PRIVATE)
        val difficulty = prefs.getString("math_difficulty", "easy") ?: "easy"

        when (difficulty) {
            "medium" -> {
                val a = Random.nextInt(4, 10)
                val b = Random.nextInt(11, 26)
                mathAnswer = a * b
                txtMathQuestion?.text = "$a * $b = ?"
            }
            "hard" -> {
                val a = Random.nextInt(3, 8)
                val b = Random.nextInt(11, 21)
                val c = Random.nextInt(10, 41)
                mathAnswer = a * b + c
                txtMathQuestion?.text = "$a * $b + $c = ?"
            }
            else -> { // easy
                val a = Random.nextInt(10, 45)
                val b = Random.nextInt(10, 45)
                mathAnswer = a + b
                txtMathQuestion?.text = "$a + $b = ?"
            }
        }
    }

    // --- STEP 5: FINAL CONFIRMATION ---
    private fun setupStepDecision() {
        val btnDecisionExit = overlayView?.findViewById<Button>(R.id.btnDecisionExit)
        val btnDecisionProceed = overlayView?.findViewById<Button>(R.id.btnDecisionProceed)

        btnDecisionExit?.setOnClickListener {
            HapticHelper.playSuccess(this)
            UsageMonitorService.lastBreakClickedAt = System.currentTimeMillis()
            goToHomeScreen()
            removeOverlayAndStop()
        }

        btnDecisionProceed?.setOnClickListener {
            HapticHelper.playSuccess(this)
            currentTarget?.let { target ->
                val bypassIntent = Intent(this@FrictionBarrierService, UsageMonitorService::class.java).apply {
                    action = "com.focuslock.ACTION_FRICTION_BYPASS"
                    putExtra(EXTRA_TARGET, target)
                }
                startService(bypassIntent)
            }
            removeOverlayAndStop()
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
            Log.w(TAG, "Failed to navigate to Home screen", e)
        }
    }

    private fun removeOverlayAndStop() {
        removeOverlay()
        stopSelf()
    }

    private fun removeOverlay() {
        stopBreathingAnimation()
        stopDelayTimer()
        stopShakeChallenge()
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove overlay view from window manager", e)
            }
            overlayView = null
        }
        currentTarget = null
    }
}
