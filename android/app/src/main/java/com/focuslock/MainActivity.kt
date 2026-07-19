package com.focuslock

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.focuslock.data.AppMode
import com.focuslock.data.FocusPolicyClient
import com.focuslock.data.FocusPolicyClient.PolicyData
import com.focuslock.data.PresetMapper
import com.focuslock.update.UpdateChecker
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.focuslock.receiver.PolicyDeviceAdminReceiver
import com.focuslock.security.EnforcementChecks
import com.focuslock.service.UsageMonitorService
import com.focuslock.surface.SurfaceBlocker
import java.time.LocalDate

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var focusSessionText: TextView
    private lateinit var todayMinutesText: TextView
    private lateinit var todayOpensText: TextView
    private lateinit var editNewTodo: EditText
    private lateinit var layoutTodoList: LinearLayout
    private lateinit var editFocusDuration: EditText
    private lateinit var editSyncId: EditText

    // Policy editor fields
    private lateinit var editDailyLimit: EditText
    private lateinit var editExtensionMins: EditText
    private lateinit var editExtensionLimit: EditText
    private lateinit var switchHardBlock: SwitchCompat
    private lateinit var editNewTargetApp: EditText
    private lateinit var layoutTargetApps: LinearLayout
    private lateinit var editWindowStart: EditText
    private lateinit var editWindowEnd: EditText
    private lateinit var layoutBlockWindows: LinearLayout
    private lateinit var txtPolicySaved: TextView
    private lateinit var spinnerInstagramReels: Spinner
    private lateinit var spinnerYoutubeShorts: Spinner
    private lateinit var editReelLimitInstagram: EditText
    private lateinit var editReelLimitYoutube: EditText
    private lateinit var txtReelCounts: TextView
    private lateinit var txtReelsPolicySaved: TextView

    // Blocked-surfaces spinner options: wire value paired with its display label,
    // so reordering the list can never silently corrupt the saved mode.
    private val surfaceModeOptions = listOf(
        "off" to "Off",
        "block" to "Block entirely",
        "one_at_a_time" to "One at a time"
    )

    private fun spinnerWireValue(spinner: Spinner): String =
        surfaceModeOptions[spinner.selectedItemPosition.coerceIn(surfaceModeOptions.indices)].first

    private fun spinnerIndexOf(wire: String?): Int =
        surfaceModeOptions.indexOfFirst { it.first == wire }.coerceAtLeast(0)

    // Per-app mode choices shown in each target-app row. OFF isn't offered —
    // removing the app (✕) is how you stop managing it, which keeps the
    // targetApps == non-off-modes invariant trivially true.
    private val appModeChoices = listOf(AppMode.FRICTION_ONLY, AppMode.LIMIT, AppMode.HARD_BLOCK)

    // Weekly chart
    private lateinit var layoutWeeklyChart: LinearLayout

    // In-memory policy state for the editor
    private var currentPolicy: PolicyData? = null

    private val PREFS_NAME = "FocusLockPrefs"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.txtStatus)
        focusSessionText = findViewById(R.id.txtFocusSession)
        todayMinutesText = findViewById(R.id.txtTodayMinutes)
        todayOpensText = findViewById(R.id.txtTodayOpens)
        editNewTodo = findViewById(R.id.editNewTodo)
        layoutTodoList = findViewById(R.id.layoutTodoList)
        editFocusDuration = findViewById(R.id.editFocusDuration)
        editSyncId = findViewById(R.id.editSyncId)

        editDailyLimit = findViewById(R.id.editDailyLimit)
        editExtensionMins = findViewById(R.id.editExtensionMins)
        editExtensionLimit = findViewById(R.id.editExtensionLimit)
        switchHardBlock = findViewById(R.id.switchHardBlock)
        editNewTargetApp = findViewById(R.id.editNewTargetApp)
        layoutTargetApps = findViewById(R.id.layoutTargetApps)
        editWindowStart = findViewById(R.id.editWindowStart)
        editWindowEnd = findViewById(R.id.editWindowEnd)
        layoutBlockWindows = findViewById(R.id.layoutBlockWindows)
        txtPolicySaved = findViewById(R.id.txtPolicySaved)
        spinnerInstagramReels = findViewById(R.id.spinnerInstagramReels)
        spinnerYoutubeShorts = findViewById(R.id.spinnerYoutubeShorts)
        editReelLimitInstagram = findViewById(R.id.editReelLimitInstagram)
        editReelLimitYoutube = findViewById(R.id.editReelLimitYoutube)
        txtReelCounts = findViewById(R.id.txtReelCounts)
        txtReelsPolicySaved = findViewById(R.id.txtReelsPolicySaved)
        layoutWeeklyChart = findViewById(R.id.layoutWeeklyChart)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        FocusPolicyClient.init(this)

        setupBottomNav()
        setupSyncCode()
        setupTodoSection()
        setupFocusSession()
        setupPolicyEditor()
        setupPresets()
        setupFrictionSettingsAndHelpCard(prefs)

        setupCollapsible(
            header = findViewById(R.id.rowBlockingRulesHeader),
            chevron = findViewById(R.id.chevronBlockingRules),
            content = findViewById(R.id.layoutBlockingRulesContent)
        )
        setupCollapsible(
            header = findViewById(R.id.rowFrictionHeader),
            chevron = findViewById(R.id.chevronFriction),
            content = findViewById(R.id.layoutFrictionContent)
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1201)
        }

        // Standard permission buttons
        findViewById<Button>(R.id.btnUsageAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                AlertDialog.Builder(this)
                    .setTitle("Accessibility toggle greyed out?")
                    .setMessage(
                        "Android blocks this permission for apps installed outside the " +
                        "Play Store (\"Restricted settings\") — this is normal for a " +
                        "sideloaded APK like FocusLock, not a bug.\n\n" +
                        "To fix it:\n" +
                        "1. Open FocusLock's App info (long-press the app icon → App info).\n" +
                        "2. Tap the ⋮ menu in the top-right corner.\n" +
                        "3. Tap \"Allow restricted settings\".\n" +
                        "4. Come back here and enable FocusLock under Accessibility."
                    )
                    .setPositiveButton("Open App info") { _, _ ->
                        startActivity(Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        ))
                    }
                    .setNegativeButton("Open Accessibility settings") { _, _ ->
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    .show()
            } else {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            } else {
                Toast.makeText(this, "Overlay permission already granted", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnStartMonitor).setOnClickListener {
            val enfStatus = EnforcementChecks.readStatus(this)
            if (!enfStatus.usageAccess) {
                Toast.makeText(this, "Please grant Usage Access first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startService(Intent(this, UsageMonitorService::class.java))
            Toast.makeText(this, "Monitor started", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnStopMonitor).setOnClickListener {
            stopService(Intent(this, UsageMonitorService::class.java))
            Toast.makeText(this, "Monitor stopped", Toast.LENGTH_SHORT).show()
        }

        refreshProtectionStatus()
        refreshDashboardData()

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
        findViewById<TextView>(R.id.txtAppVersion).apply {
            text = "v$versionName · tap to check for updates"
            setOnClickListener {
                Toast.makeText(this@MainActivity, "Checking for updates…", Toast.LENGTH_SHORT).show()
                UpdateChecker.checkForUpdate(this@MainActivity, manual = true)
            }
        }

        UpdateChecker.checkForUpdate(this)
    }

    // ── Bottom navigation ─────────────────────────────────────────────────────

    private fun setupBottomNav() {
        val screens = mapOf(
            R.id.navHome to findViewById<View>(R.id.screenHome),
            R.id.navRules to findViewById<View>(R.id.screenRules),
            R.id.navSettings to findViewById<View>(R.id.screenSettings)
        )
        findViewById<BottomNavigationView>(R.id.bottomNav).setOnItemSelectedListener { item ->
            screens.forEach { (id, screen) ->
                screen.visibility = if (id == item.itemId) View.VISIBLE else View.GONE
            }
            true
        }
    }

    // ── Presets ───────────────────────────────────────────────────────────────

    private fun setupPresets() {
        mapOf(
            R.id.btnPresetChill to PresetMapper.BUILTIN_CHILL,
            R.id.btnPresetFocus to PresetMapper.BUILTIN_FOCUS,
            R.id.btnPresetStrict to PresetMapper.BUILTIN_STRICT
        ).forEach { (btnId, presetId) ->
            findViewById<Button>(btnId).setOnClickListener { applyPreset(presetId) }
        }
    }

    private fun applyPreset(presetId: String) {
        val policy = currentPolicy
        if (policy == null) {
            Toast.makeText(this, "Policy still loading — try again in a moment", Toast.LENGTH_SHORT).show()
            return
        }
        if (policy.targetApps.isEmpty()) {
            Toast.makeText(this, "Add at least one app under Blocking Rules first", Toast.LENGTH_LONG).show()
            return
        }
        // A saved preset with this id wins; otherwise generate the built-in
        // template over the user's current target apps.
        val bundle = policy.presets[presetId] ?: PresetMapper.builtInBundle(
            presetId, policy.targetApps, policy.dailyLimitMinutes, policy.reelDailyLimit
        ) ?: return
        val name = PresetMapper.displayName(presetId, bundle)

        AlertDialog.Builder(this)
            .setTitle("Apply \"$name\" mode?")
            .setMessage("This overwrites your per-app modes, daily limit behavior, reels settings and friction steps.")
            .setPositiveButton("Apply") { _, _ ->
                Thread {
                    try {
                        val fields = PresetMapper.bundleToLiveFields(presetId, bundle)
                        FocusPolicyClient.updatePolicyFields(fields)

                        // Friction steps + math difficulty live in local prefs.
                        val steps = PresetMapper.frictionSteps(bundle)
                        if (steps.isNotEmpty()) {
                            val editor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                            listOf(
                                "enable_breathing", "enable_delay", "enable_intention",
                                "enable_shake", "enable_reflection", "enable_stats", "enable_math"
                            ).forEach { key -> editor.putBoolean(key, steps[key] as? Boolean ?: false) }
                            (steps["math_difficulty"] as? String)?.let { editor.putString("math_difficulty", it) }
                            editor.apply()
                        }

                        @Suppress("UNCHECKED_CAST")
                        val surfaces = fields["blockedSurfaces"] as? Map<String, String>
                        @Suppress("UNCHECKED_CAST")
                        val reelLimits = fields["reelDailyLimit"] as? Map<String, Int> ?: emptyMap()
                        if (surfaces != null) SurfaceBlocker.updateConfig(surfaces, reelLimits)

                        startService(Intent(this, UsageMonitorService::class.java)
                            .setAction(UsageMonitorService.ACTION_POLICY_UPDATED))

                        runOnUiThread {
                            Toast.makeText(this, "\"$name\" mode applied", Toast.LENGTH_SHORT).show()
                            currentPolicy = null
                            loadPolicyIntoEditor()
                            syncFrictionSwitchStates()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, "Failed to apply mode: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Re-read friction prefs into the switches after a preset changed them
    // behind the UI's back. Listeners re-fire with identical values — harmless.
    private fun syncFrictionSwitchStates() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mapOf(
            R.id.switchBreathing to "enable_breathing",
            R.id.switchDelay to "enable_delay",
            R.id.switchIntention to "enable_intention",
            R.id.switchShake to "enable_shake",
            R.id.switchReflection to "enable_reflection",
            R.id.switchStats to "enable_stats",
            R.id.switchMath to "enable_math"
        ).forEach { (id, key) ->
            findViewById<SwitchCompat>(id).isChecked = prefs.getBoolean(key, true)
        }
    }

    // ── Collapsible cards ─────────────────────────────────────────────────────

    private fun setupCollapsible(header: View, chevron: TextView, content: View, startExpanded: Boolean = false) {
        content.visibility = if (startExpanded) View.VISIBLE else View.GONE
        chevron.text = if (startExpanded) "▾" else "▸"
        header.setOnClickListener {
            val expand = content.visibility != View.VISIBLE
            content.visibility = if (expand) View.VISIBLE else View.GONE
            chevron.text = if (expand) "▾" else "▸"
        }
    }

    // ── Focus Session ─────────────────────────────────────────────────────────

    private fun setupFocusSession() {
        findViewById<Button>(R.id.btnStartFocus).setOnClickListener { btn ->
            val minutes = editFocusDuration.text.toString().trim().toIntOrNull()?.coerceAtLeast(1) ?: 25
            btn.isEnabled = false
            Thread {
                try {
                    val result = FocusPolicyClient.startFocusSession(minutes)
                    runOnUiThread {
                        focusSessionText.text = if (result.active)
                            "Focus session active until ${result.endsAt}"
                        else
                            "Focus session inactive"
                        btn.isEnabled = true
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        focusSessionText.text = "Focus session start failed: ${e.message}"
                        btn.isEnabled = true
                    }
                }
            }.start()
        }

        findViewById<Button>(R.id.btnStopFocus).setOnClickListener { btn ->
            btn.isEnabled = false
            Thread {
                try {
                    FocusPolicyClient.stopFocusSession()
                    runOnUiThread {
                        focusSessionText.text = "Focus session inactive"
                        btn.isEnabled = true
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        focusSessionText.text = "Focus session stop failed: ${e.message}"
                        btn.isEnabled = true
                    }
                }
            }.start()
        }
    }

    // ── Todo Section ──────────────────────────────────────────────────────────

    private fun setupTodoSection() {
        findViewById<Button>(R.id.btnAddTodo).setOnClickListener { btn ->
            val text = editNewTodo.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            btn.isEnabled = false
            Thread {
                try {
                    FocusPolicyClient.addTodo(text)
                    runOnUiThread {
                        editNewTodo.setText("")
                        Toast.makeText(this, "Task added!", Toast.LENGTH_SHORT).show()
                        loadTodos()
                        btn.isEnabled = true
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "Failed to add task: ${e.message}", Toast.LENGTH_SHORT).show()
                        btn.isEnabled = true
                    }
                }
            }.start()
        }
    }

    // ── Policy Editor ─────────────────────────────────────────────────────────

    private fun setupPolicyEditor() {
        val surfaceModeAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, surfaceModeOptions.map { it.second }
        )
        surfaceModeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerInstagramReels.adapter = surfaceModeAdapter
        spinnerYoutubeShorts.adapter = surfaceModeAdapter

        // Add target app
        findViewById<Button>(R.id.btnAddTargetApp).setOnClickListener {
            val pkg = editNewTargetApp.text.toString().trim()
            if (pkg.isEmpty()) return@setOnClickListener
            val policy = currentPolicy ?: return@setOnClickListener
            if (!policy.targetApps.contains(pkg)) {
                currentPolicy = policy.copy(
                    targetApps = policy.targetApps + pkg,
                    appModes = policy.appModes + (pkg to AppMode.LIMIT.wire)
                )
                renderTargetApps()
            }
            editNewTargetApp.setText("")
        }

        // Add block window
        findViewById<Button>(R.id.btnAddBlockWindow).setOnClickListener {
            val start = editWindowStart.text.toString().trim()
            val end = editWindowEnd.text.toString().trim()
            if (!isValidTime(start) || !isValidTime(end)) {
                Toast.makeText(this, "Enter valid times in HH:mm format", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val days = mutableListOf<Int>()
            if (findViewById<CheckBox>(R.id.cbSun).isChecked) days.add(0)
            if (findViewById<CheckBox>(R.id.cbMon).isChecked) days.add(1)
            if (findViewById<CheckBox>(R.id.cbTue).isChecked) days.add(2)
            if (findViewById<CheckBox>(R.id.cbWed).isChecked) days.add(3)
            if (findViewById<CheckBox>(R.id.cbThu).isChecked) days.add(4)
            if (findViewById<CheckBox>(R.id.cbFri).isChecked) days.add(5)
            if (findViewById<CheckBox>(R.id.cbSat).isChecked) days.add(6)
            if (days.isEmpty()) {
                Toast.makeText(this, "Select at least one day", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val window: Map<String, Any> = mapOf("start" to start, "end" to end, "days" to days)
            val policy = currentPolicy ?: return@setOnClickListener
            currentPolicy = policy.copy(blockWindows = policy.blockWindows + window)
            renderBlockWindows()
        }

        // Each Save button writes only its own card's fields (merge write) — the
        // Reels card can no longer clobber Blocking-Rules edits and vice versa,
        // and neither touches fields the Windows dashboard owns (targetDomains).
        findViewById<Button>(R.id.btnSavePolicy).setOnClickListener { btn ->
            saveBlockingRules(btn, txtPolicySaved)
        }
        findViewById<Button>(R.id.btnSaveReelsPolicy).setOnClickListener { btn ->
            saveReelsPolicy(btn, txtReelsPolicySaved)
        }

        loadPolicyIntoEditor()
    }

    private fun saveBlockingRules(btn: View, confirmationView: TextView) {
        val policy = currentPolicy ?: return
        val dailyLimit = editDailyLimit.text.toString().trim().toIntOrNull() ?: policy.dailyLimitMinutes
        val extMins = editExtensionMins.text.toString().trim().toIntOrNull() ?: policy.extensionMinutes
        val extLimit = editExtensionLimit.text.toString().trim().toIntOrNull() ?: policy.dailyExtensionLimit
        val hardBlock = switchHardBlock.isChecked
        val sessionMax = findViewById<EditText>(R.id.editSessionMax).text.toString().trim().toIntOrNull()
            ?: policy.sessionMaxMinutes
        val sessionBreak = findViewById<EditText>(R.id.editSessionBreak).text.toString().trim().toIntOrNull()
            ?: policy.sessionBreakMinutes
        // Every target gets a mode entry (default LIMIT); targetApps stays the
        // derived set of non-off modes so the Windows agent keeps working.
        val appModes = policy.targetApps
            .associateWith { policy.appModes[it] ?: AppMode.LIMIT.wire }
            .filterValues { it != AppMode.OFF.wire }
        currentPolicy = policy.copy(
            dailyLimitMinutes = dailyLimit,
            extensionMinutes = extMins,
            dailyExtensionLimit = extLimit,
            hardBlockAfterLimit = hardBlock,
            targetApps = appModes.keys.toList(),
            appModes = appModes,
            sessionMaxMinutes = sessionMax,
            sessionBreakMinutes = sessionBreak
        )
        saveFields(
            btn, confirmationView,
            mapOf(
                "dailyLimitMinutes" to dailyLimit,
                "extensionMinutes" to extMins,
                "dailyExtensionLimit" to extLimit,
                "hardBlockAfterLimit" to hardBlock,
                "targetApps" to appModes.keys.toList(),
                "appModes" to appModes,
                "blockWindows" to (currentPolicy?.blockWindows ?: emptyList<Map<String, Any>>()),
                "sessionMaxMinutes" to sessionMax,
                "sessionBreakMinutes" to sessionBreak
            )
        )
    }

    private fun saveReelsPolicy(btn: View, confirmationView: TextView) {
        val policy = currentPolicy ?: return
        val blockedSurfaces = mapOf(
            "instagram_reels" to spinnerWireValue(spinnerInstagramReels),
            "youtube_shorts" to spinnerWireValue(spinnerYoutubeShorts)
        )
        val reelDailyLimit = mapOf(
            "instagram_reels" to (editReelLimitInstagram.text.toString().trim().toIntOrNull() ?: 0),
            "youtube_shorts" to (editReelLimitYoutube.text.toString().trim().toIntOrNull() ?: 0)
        )
        currentPolicy = policy.copy(blockedSurfaces = blockedSurfaces, reelDailyLimit = reelDailyLimit)
        saveFields(
            btn, confirmationView,
            mapOf("blockedSurfaces" to blockedSurfaces, "reelDailyLimit" to reelDailyLimit)
        ) {
            SurfaceBlocker.updateConfig(blockedSurfaces, reelDailyLimit)
        }
    }

    private fun saveFields(
        btn: View,
        confirmationView: TextView,
        fields: Map<String, Any>,
        afterSave: () -> Unit = {}
    ) {
        btn.isEnabled = false
        Thread {
            try {
                FocusPolicyClient.updatePolicyFields(fields)
                afterSave()
                // Tell the monitor to re-fetch immediately instead of waiting out
                // its 15-minute policy refresh throttle.
                startService(Intent(this, UsageMonitorService::class.java)
                    .setAction(UsageMonitorService.ACTION_POLICY_UPDATED))
                runOnUiThread {
                    confirmationView.visibility = View.VISIBLE
                    confirmationView.postDelayed({ confirmationView.visibility = View.GONE }, 2000)
                    btn.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    btn.isEnabled = true
                }
            }
        }.start()
    }

    private fun loadPolicyIntoEditor() {
        Thread {
            try {
                val policy = FocusPolicyClient.getPolicy()
                runOnUiThread {
                    currentPolicy = policy
                    editDailyLimit.setText(if (policy.dailyLimitMinutes > 0) policy.dailyLimitMinutes.toString() else "")
                    editExtensionMins.setText(policy.extensionMinutes.toString())
                    editExtensionLimit.setText(policy.dailyExtensionLimit.toString())
                    switchHardBlock.isChecked = policy.hardBlockAfterLimit
                    findViewById<EditText>(R.id.editSessionMax)
                        .setText(if (policy.sessionMaxMinutes > 0) policy.sessionMaxMinutes.toString() else "")
                    findViewById<EditText>(R.id.editSessionBreak)
                        .setText(if (policy.sessionBreakMinutes > 0) policy.sessionBreakMinutes.toString() else "")
                    renderTargetApps()
                    renderBlockWindows()
                    spinnerInstagramReels.setSelection(spinnerIndexOf(policy.blockedSurfaces["instagram_reels"]))
                    spinnerYoutubeShorts.setSelection(spinnerIndexOf(policy.blockedSurfaces["youtube_shorts"]))
                    val igLimit = policy.reelDailyLimit["instagram_reels"] ?: 0
                    val ytLimit = policy.reelDailyLimit["youtube_shorts"] ?: 0
                    editReelLimitInstagram.setText(if (igLimit > 0) igLimit.toString() else "")
                    editReelLimitYoutube.setText(if (ytLimit > 0) ytLimit.toString() else "")
                    findViewById<TextView>(R.id.txtActivePreset).text =
                        if (policy.activePresetId.isNotEmpty()) {
                            "Active mode: ${PresetMapper.displayName(policy.activePresetId, policy.presets[policy.activePresetId])}"
                        } else {
                            "One tap applies a whole profile: per-app modes, limits, reels and friction."
                        }
                }
            } catch (e: Exception) {
                // leave fields empty, user can fill in
            }
        }.start()
    }

    // ── Sync Code ─────────────────────────────────────────────────────────────

    private fun setupSyncCode() {
        editSyncId.setText(FocusPolicyClient.getSyncId())
        findViewById<Button>(R.id.btnSaveSyncId).setOnClickListener {
            val clean = FocusPolicyClient.setSyncId(this, editSyncId.text.toString())
            editSyncId.setText(clean)

            // Restart the monitor so it drops the previous account's in-memory
            // state (target apps, cached limits) and re-fetches policy for the
            // new code. The singleton userId is already updated in-process.
            if (UsageMonitorService.isServiceActive) {
                stopService(Intent(this, UsageMonitorService::class.java))
                startService(Intent(this, UsageMonitorService::class.java))
            }

            Toast.makeText(this, "Sync code set to \"$clean\"", Toast.LENGTH_SHORT).show()
            currentPolicy = null
            loadPolicyIntoEditor()
            refreshDashboardData()
        }
    }

    private fun isValidTime(t: String): Boolean =
        t.matches(Regex("^([01]\\d|2[0-3]):[0-5]\\d$"))

    private fun renderTargetApps() {
        val policy = currentPolicy ?: return
        layoutTargetApps.removeAllViews()
        if (policy.targetApps.isEmpty()) {
            layoutTargetApps.addView(TextView(this).apply {
                text = "No apps blocked yet"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
            })
            return
        }
        for (pkg in policy.targetApps) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, 4, 0, 4)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val label = TextView(this).apply {
                text = pkg
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val modeSpinner = Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@MainActivity, android.R.layout.simple_spinner_item, appModeChoices.map { it.label }
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.text_primary))
                val current = AppMode.fromWire(policy.appModes[pkg], inTargetApps = true)
                setSelection(appModeChoices.indexOf(current).coerceAtLeast(0))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        val mode = appModeChoices[position]
                        currentPolicy = currentPolicy?.let { p -> p.copy(appModes = p.appModes + (pkg to mode.wire)) }
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
            val del = Button(this).apply {
                text = "✕"
                textSize = 10f
                setTextColor(getColor(R.color.color_danger))
                background = getDrawable(R.drawable.button_secondary)
                val size = (32 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = 8 }
                setOnClickListener {
                    currentPolicy = currentPolicy?.let { p ->
                        p.copy(
                            targetApps = p.targetApps.filter { it != pkg },
                            appModes = p.appModes - pkg
                        )
                    }
                    renderTargetApps()
                }
            }
            row.addView(label)
            row.addView(modeSpinner)
            row.addView(del)
            layoutTargetApps.addView(row)
        }
    }

    private fun renderBlockWindows() {
        val policy = currentPolicy ?: return
        layoutBlockWindows.removeAllViews()
        val dayNames = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        if (policy.blockWindows.isEmpty()) {
            layoutBlockWindows.addView(TextView(this).apply {
                text = "No block windows set"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
            })
            return
        }
        for ((idx, w) in policy.blockWindows.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, 4, 0, 4)
            }
            @Suppress("UNCHECKED_CAST")
            val days = (w["days"] as? List<*>)?.map { (it as Number).toInt() } ?: emptyList()
            val daysLabel = if (days.size == 7) "Every day" else days.joinToString(", ") { dayNames[it] }
            val label = TextView(this).apply {
                text = "${w["start"]} – ${w["end"]}  ($daysLabel)"
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val del = Button(this).apply {
                text = "✕"
                textSize = 10f
                setTextColor(getColor(R.color.color_danger))
                background = getDrawable(R.drawable.button_secondary)
                val size = (32 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = 8 }
                setOnClickListener {
                    currentPolicy = currentPolicy?.copy(
                        blockWindows = currentPolicy!!.blockWindows.filterIndexed { i, _ -> i != idx }
                    )
                    renderBlockWindows()
                }
            }
            row.addView(label)
            row.addView(del)
            layoutBlockWindows.addView(row)
        }
    }

    // ── Friction Settings ─────────────────────────────────────────────────────

    private fun setupFrictionSettingsAndHelpCard(prefs: android.content.SharedPreferences) {
        val switchBreathing = findViewById<SwitchCompat>(R.id.switchBreathing)
        val switchDelay = findViewById<SwitchCompat>(R.id.switchDelay)
        val switchIntention = findViewById<SwitchCompat>(R.id.switchIntention)
        val switchShake = findViewById<SwitchCompat>(R.id.switchShake)
        val switchReflection = findViewById<SwitchCompat>(R.id.switchReflection)
        val switchStats = findViewById<SwitchCompat>(R.id.switchStats)
        val switchMath = findViewById<SwitchCompat>(R.id.switchMath)
        val switchDeviceAdmin = findViewById<SwitchCompat>(R.id.switchDeviceAdmin)

        switchBreathing.isChecked = prefs.getBoolean("enable_breathing", true)
        switchDelay.isChecked = prefs.getBoolean("enable_delay", true)
        switchIntention.isChecked = prefs.getBoolean("enable_intention", true)
        switchShake.isChecked = prefs.getBoolean("enable_shake", true)
        switchReflection.isChecked = prefs.getBoolean("enable_reflection", true)
        switchStats.isChecked = prefs.getBoolean("enable_stats", true)
        switchMath.isChecked = prefs.getBoolean("enable_math", true)

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, PolicyDeviceAdminReceiver::class.java)
        switchDeviceAdmin.isChecked = dpm.isAdminActive(adminComponent)

        switchBreathing.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("enable_breathing", v).apply() }
        switchDelay.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("enable_delay", v).apply() }
        switchIntention.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("enable_intention", v).apply() }
        switchShake.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("enable_shake", v).apply() }
        switchReflection.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("enable_reflection", v).apply() }
        switchStats.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("enable_stats", v).apply() }
        switchMath.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("enable_math", v).apply() }

        switchDeviceAdmin.setOnCheckedChangeListener { _, isChecked ->
            val isActive = dpm.isAdminActive(adminComponent)
            if (isChecked && !isActive) {
                startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Activate FocusLock Shield to prevent uninstallation when limits are enforced.")
                })
            } else if (!isChecked && isActive) {
                if (prefs.getBoolean("is_currently_blocked", false)) {
                    Toast.makeText(this, "Cannot disable protection while a block is active!", Toast.LENGTH_LONG).show()
                    switchDeviceAdmin.isChecked = true
                } else {
                    dpm.removeActiveAdmin(adminComponent)
                    Toast.makeText(this, "Device admin deactivated", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val spinnerMathDifficulty = findViewById<Spinner>(R.id.spinnerMathDifficulty)
        val difficulties = arrayOf("easy", "medium", "hard")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, difficulties)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerMathDifficulty.adapter = adapter
        val currentDifficulty = prefs.getString("math_difficulty", "easy") ?: "easy"
        spinnerMathDifficulty.setSelection(difficulties.indexOf(currentDifficulty).coerceAtLeast(0))
        spinnerMathDifficulty.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putString("math_difficulty", difficulties[position]).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        refreshProtectionStatus()
        refreshDashboardData()
        val switchDeviceAdmin = findViewById<SwitchCompat>(R.id.switchDeviceAdmin)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, PolicyDeviceAdminReceiver::class.java)
        switchDeviceAdmin.isChecked = dpm.isAdminActive(adminComponent)
        UpdateChecker.resumePendingInstall(this)
    }

    // ── Status & Dashboard ────────────────────────────────────────────────────

    private fun refreshProtectionStatus() {
        val statusObj = EnforcementChecks.readStatus(this)
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

        statusText.text = buildString {
            append("Usage access: "); append(if (statusObj.usageAccess) "✅ ACTIVE" else "❌ DISRUPTED")
            append("\nAccessibility: "); append(if (statusObj.accessibility) "✅ ACTIVE" else "❌ DISRUPTED")
            append("\nOverlay draw: "); append(if (statusObj.overlay) "✅ ACTIVE" else "❌ DISRUPTED")
            append("\nNotifications: "); append(if (notificationGranted) "✅ ACTIVE" else "❌ DISRUPTED")
            if (!statusObj.allCriticalGranted) append("\n\n⚠️ Protection degraded. Grant missing permissions.")
            else append("\n\n🛡️ FocusLock Active protection enabled.")
        }
    }

    private fun refreshDashboardData() {
        Thread {
            try {
                val focus = FocusPolicyClient.getFocusSession()
                val dateStr = LocalDate.now().toString()
                val stats = FocusPolicyClient.getStats(dateStr)
                val evaluateResult = FocusPolicyClient.evaluate(dateStr)

                runOnUiThread {
                    if (focus.active) {
                        focusSessionText.text = "Focus session active until ${focus.endsAt}"
                        focusSessionText.setTextColor(getColor(R.color.color_accent))
                    } else {
                        focusSessionText.text = "Focus session inactive"
                        focusSessionText.setTextColor(getColor(R.color.text_secondary))
                    }

                    // Render from the same decision the enforcement path uses, so
                    // the dashboard never disagrees with actual on-device behavior.
                    val used = evaluateResult.usedMinutes
                    val limit = evaluateResult.effectiveLimit
                    val baseText = if (limit > 0) "${"%.1f".format(used)} / $limit min" else "${"%.1f".format(used)} min used"
                    todayMinutesText.text = when (evaluateResult.action) {
                        "soft-limit" -> "$baseText · over limit (friction mode)"
                        "hard-block" -> "$baseText · blocked"
                        else -> baseText
                    }
                    todayMinutesText.setTextColor(
                        when (evaluateResult.action) {
                            "hard-block" -> getColor(R.color.color_danger)
                            "soft-limit" -> getColor(R.color.color_warning)
                            else -> getColor(R.color.text_primary)
                        }
                    )

                    val opens = stats.optInt("opensToday", 0)
                    todayOpensText.text = "$opens opens today"

                    val reelCounts = stats.optJSONObject("reelCounts")
                    val igReels = reelCounts?.optInt("instagram_reels", 0) ?: 0
                    val ytShorts = reelCounts?.optInt("youtube_shorts", 0) ?: 0
                    txtReelCounts.text = "Today: $igReels Reels · $ytShorts Shorts"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    todayMinutesText.text = "-- minutes"
                    todayOpensText.text = "Server offline or unreachable"
                }
            }
        }.start()

        loadTodos()
        loadWeeklyChart()
    }

    // ── Weekly Chart ──────────────────────────────────────────────────────────

    private fun loadWeeklyChart() {
        Thread {
            try {
                val week = FocusPolicyClient.getUsageWeek()
                val maxMinutes = week.maxOfOrNull { it.usedMinutes }?.coerceAtLeast(1.0) ?: 1.0
                runOnUiThread {
                    layoutWeeklyChart.removeAllViews()
                    for (day in week) {
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            setPadding(0, 4, 0, 4)
                            gravity = android.view.Gravity.CENTER_VERTICAL
                        }

                        val dayLabel = TextView(this).apply {
                            text = day.label
                            setTextColor(getColor(R.color.text_secondary))
                            textSize = 12f
                            val w = (36 * resources.displayMetrics.density).toInt()
                            layoutParams = LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT)
                        }

                        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                            max = (maxMinutes * 10).toInt()
                            progress = (day.usedMinutes * 10).toInt()
                            progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.color_primary))
                            layoutParams = LinearLayout.LayoutParams(0, (8 * resources.displayMetrics.density).toInt(), 1f)
                        }

                        val minsLabel = TextView(this).apply {
                            text = "${day.usedMinutes.toInt()}m"
                            setTextColor(getColor(R.color.text_secondary))
                            textSize = 11f
                            val w = (40 * resources.displayMetrics.density).toInt()
                            layoutParams = LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                marginStart = (6 * resources.displayMetrics.density).toInt()
                            }
                        }

                        row.addView(dayLabel)
                        row.addView(bar)
                        row.addView(minsLabel)
                        layoutWeeklyChart.addView(row)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    layoutWeeklyChart.removeAllViews()
                    layoutWeeklyChart.addView(TextView(this).apply {
                        text = "Could not load weekly data"
                        setTextColor(getColor(R.color.text_secondary))
                        textSize = 13f
                    })
                }
            }
        }.start()
    }

    // ── Todos ─────────────────────────────────────────────────────────────────

    private fun loadTodos() {
        Thread {
            try {
                val todos = FocusPolicyClient.getTodos()
                runOnUiThread {
                    layoutTodoList.removeAllViews()
                    if (todos.isEmpty()) {
                        layoutTodoList.addView(TextView(this).apply {
                            text = "No pending tasks. You are all set!"
                            setTextColor(getColor(R.color.text_secondary))
                            textSize = 13f
                            setPadding(0, 10, 0, 10)
                        })
                        return@runOnUiThread
                    }

                    for (item in todos) {
                        val rowLayout = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            setPadding(0, 4, 0, 4)
                        }

                        val cb = CheckBox(this).apply {
                            text = item.text
                            isChecked = item.completed
                            setTextColor(getColor(R.color.text_primary))
                            textSize = 14f
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            setOnCheckedChangeListener { _, isChecked ->
                                Thread {
                                    try {
                                        FocusPolicyClient.toggleTodo(item.id, isChecked)
                                    } catch (e: Exception) {
                                        runOnUiThread {
                                            Toast.makeText(this@MainActivity, "Failed to update task", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }.start()
                            }
                        }

                        val btnDel = Button(this).apply {
                            text = "✕"
                            textSize = 10f
                            setTextColor(getColor(R.color.color_danger))
                            background = getDrawable(R.drawable.button_secondary)
                            val size = (32 * resources.displayMetrics.density).toInt()
                            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = 8 }
                            setOnClickListener {
                                isEnabled = false
                                Thread {
                                    try {
                                        FocusPolicyClient.deleteTodo(item.id)
                                        runOnUiThread { loadTodos() }
                                    } catch (e: Exception) {
                                        runOnUiThread {
                                            Toast.makeText(this@MainActivity, "Failed to delete task", Toast.LENGTH_SHORT).show()
                                            isEnabled = true
                                        }
                                    }
                                }.start()
                            }
                        }

                        rowLayout.addView(cb)
                        rowLayout.addView(btnDel)
                        layoutTodoList.addView(rowLayout)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    layoutTodoList.removeAllViews()
                    layoutTodoList.addView(TextView(this).apply {
                        text = "Cannot load task checklist (server offline)"
                        setTextColor(getColor(R.color.color_danger))
                        textSize = 13f
                    })
                }
            }
        }.start()
    }
}
