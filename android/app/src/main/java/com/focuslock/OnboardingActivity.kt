package com.focuslock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.focuslock.data.FocusPolicyClient
import com.focuslock.security.EnforcementChecks

/** A short, replayable first-run guide that explains each required permission plainly. */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "FocusLockPrefs"
        const val PREF_ONBOARDING_COMPLETE = "onboarding_complete"
        const val EXTRA_REVIEW_MODE = "review_mode"

        fun isComplete(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(PREF_ONBOARDING_COMPLETE, false) ||
                !prefs.getString("sync_id", null).isNullOrBlank()
        }
    }

    private data class Step(
        val eyebrow: String,
        val title: String,
        val body: String,
        val tipTitle: String,
        val tip: String,
        val primary: String,
        val secondary: String? = null
    )

    private val steps = listOf(
        Step(
            "WELCOME", "Build a calmer phone, not a stricter life.",
            "FocusLock only manages the apps and moments you choose. You can use it for gentle pauses, daily limits, or a firm block when you need one.",
            "What happens next", "We will turn on the permissions Android needs, then show you where to choose apps and Reels/Shorts rules.",
            "Start setup", "Skip for now"
        ),
        Step(
            "STEP 1 OF 4", "Allow FocusLock to see the app on screen.",
            "Accessibility lets FocusLock recognise a full-screen Reel or Short and place a pause or block. It does not read your messages or save what you watch.",
            "Android may hide the switch", "If the switch is greyed out, open FocusLock's App info, use the three-dot menu, and allow restricted settings. Then return to Accessibility.",
            "Open Accessibility settings", "I enabled it"
        ),
        Step(
            "STEP 2 OF 4", "Let FocusLock keep your limits working.",
            "Usage access measures time in the apps you select. Display over other apps lets a pause screen appear when a rule is triggered.",
            "You stay in control", "Both permissions can be turned off in Android Settings. Without them, FocusLock will tell you protection is not ready.",
            "Open Usage access", "Open overlay permission"
        ),
        Step(
            "STEP 3 OF 4", "Choose what you want help with.",
            "In Rules, use Choose apps on this phone to add Instagram, YouTube, or any other app. Reels and Shorts are separate: regular YouTube videos are left alone.",
            "A simple first rule", "Try One at a time for Reels. It lets you watch one, then asks you to step back before the next one.",
            "Show me the Rules tab"
        ),
        Step(
            "STEP 4 OF 4", "Your phone is ready to begin.",
            "A private sync code is already made for this phone. Keep it if this is only your phone, or use the same code on your desktop to share your rules.",
            "Google is optional", "Sign in later from Settings if you want friends or accountability features. Your limits do not depend on Google.",
            "Finish setup"
        )
    )

    private var stepIndex = 0
    private var reviewMode = false
    private lateinit var eyebrow: TextView
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var tipTitle: TextView
    private lateinit var tip: TextView
    private lateinit var progress: TextView
    private lateinit var syncInput: EditText
    private lateinit var primary: Button
    private lateinit var secondary: Button
    private lateinit var back: Button
    private lateinit var next: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Deliberately NOT calling FocusPolicyClient.init() here: init() persists a
        // sync_id as a side effect, and isComplete() below treats a saved sync_id as
        // "already onboarded". Calling init() from onboarding made merely opening this
        // screen mark onboarding complete, so backgrounding mid-flow (the normal path,
        // since these steps deep-link into Settings) permanently skipped it thereafter.
        reviewMode = intent.getBooleanExtra(EXTRA_REVIEW_MODE, false)
        setContentView(R.layout.activity_onboarding)

        eyebrow = findViewById(R.id.txtOnboardEyebrow)
        title = findViewById(R.id.txtOnboardTitle)
        body = findViewById(R.id.txtOnboardBody)
        tipTitle = findViewById(R.id.txtOnboardTipTitle)
        tip = findViewById(R.id.txtOnboardTip)
        progress = findViewById(R.id.txtOnboardProgress)
        syncInput = findViewById(R.id.editOnboardSyncId)
        primary = findViewById(R.id.btnOnboardPrimary)
        secondary = findViewById(R.id.btnOnboardSecondary)
        back = findViewById(R.id.btnOnboardBack)
        next = findViewById(R.id.btnOnboardNext)

        primary.setOnClickListener { onPrimary() }
        secondary.setOnClickListener { onSecondary() }
        back.setOnClickListener { showStep(stepIndex - 1) }
        next.setOnClickListener { if (stepIndex == steps.lastIndex) finishSetup() else showStep(stepIndex + 1) }
        showStep(0)
    }

    override fun onResume() {
        super.onResume()
        if (::title.isInitialized) showStep(stepIndex)
    }

    private fun showStep(index: Int) {
        stepIndex = index.coerceIn(steps.indices)
        val step = steps[stepIndex]
        eyebrow.text = step.eyebrow
        title.text = step.title
        body.text = step.body
        tipTitle.text = step.tipTitle
        tip.text = step.tip
        progress.text = "${stepIndex + 1} of ${steps.size}"
        primary.text = step.primary
        secondary.text = step.secondary.orEmpty()
        secondary.visibility = if (step.secondary == null) View.GONE else View.VISIBLE
        back.visibility = if (stepIndex == 0) View.INVISIBLE else View.VISIBLE
        next.text = if (stepIndex == steps.lastIndex) "Finish" else "Continue"
        syncInput.visibility = if (stepIndex == steps.lastIndex) View.VISIBLE else View.GONE
        if (stepIndex == steps.lastIndex && syncInput.text.isNullOrBlank()) {
            syncInput.setText(FocusPolicyClient.getSyncId())
        }

        val status = EnforcementChecks.readStatus(this)
        val readiness = when (stepIndex) {
            1 -> if (status.accessibility) "Accessibility is on ✓" else "Accessibility still needs to be turned on"
            2 -> when {
                !status.usageAccess -> "Usage access still needs to be turned on"
                !status.overlay -> "Usage access is on. Overlay permission still needs to be turned on"
                else -> "Usage access and overlay permission are on ✓"
            }
            else -> if (status.allCriticalGranted) "Protection is ready ✓" else "You can finish the remaining permissions later in Settings"
        }
        findViewById<TextView>(R.id.txtOnboardReadiness).text = readiness
    }

    private fun onPrimary() {
        when (stepIndex) {
            0 -> showStep(1)
            1 -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            2 -> startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            3 -> openMainAtRules()
            else -> finishSetup()
        }
    }

    private fun onSecondary() {
        when (stepIndex) {
            0 -> finishSetup()
            1 -> showStep(2)
            2 -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = android.net.Uri.parse("package:$packageName")
            })
        }
    }

    private fun openMainAtRules() {
        finishSetup(openRules = true)
    }

    private fun finishSetup(openRules: Boolean = false) {
        val entered = syncInput.text.toString().trim()
        if (stepIndex == steps.lastIndex && entered.isNotEmpty()) FocusPolicyClient.setSyncId(this, entered)
        if (!reviewMode) getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_ONBOARDING_COMPLETE, true).apply()
        val intent = Intent(this, MainActivity::class.java)
        if (openRules) intent.putExtra(MainActivity.EXTRA_OPEN_RULES, true)
        startActivity(intent)
        finish()
    }
}
