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
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.focuslock.data.FocusPolicyClient
import com.focuslock.data.FocusPolicyClient.PolicyData
import com.focuslock.receiver.PolicyDeviceAdminReceiver
import com.focuslock.security.EnforcementChecks
import com.focuslock.service.UsageMonitorService
import java.time.LocalDate

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var focusSessionText: TextView
    private lateinit var todayMinutesText: TextView
    private lateinit var todayOpensText: TextView
    private lateinit var editNewTodo: EditText
    private lateinit var layoutTodoList: LinearLayout
    private lateinit var editFocusDuration: EditText

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
        layoutWeeklyChart = findViewById(R.id.layoutWeeklyChart)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        FocusPolicyClient.init(this)

        setupTodoSection()
        setupFocusSession()
        setupPolicyEditor()
        setupFrictionSettingsAndHelpCard(prefs)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1201)
        }

        // Standard permission buttons
        findViewById<Button>(R.id.btnUsageAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
            val serviceIntent = Intent(this, UsageMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "Monitor started", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnStopMonitor).setOnClickListener {
            stopService(Intent(this, UsageMonitorService::class.java))
            Toast.makeText(this, "Monitor stopped", Toast.LENGTH_SHORT).show()
        }

        refreshProtectionStatus()
        refreshDashboardData()
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
        // Add target app
        findViewById<Button>(R.id.btnAddTargetApp).setOnClickListener {
            val pkg = editNewTargetApp.text.toString().trim()
            if (pkg.isEmpty()) return@setOnClickListener
            val policy = currentPolicy ?: return@setOnClickListener
            if (!policy.targetApps.contains(pkg)) {
                currentPolicy = policy.copy(targetApps = policy.targetApps + pkg)
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

        // Save policy
        findViewById<Button>(R.id.btnSavePolicy).setOnClickListener { btn ->
            val policy = currentPolicy ?: return@setOnClickListener
            val dailyLimit = editDailyLimit.text.toString().trim().toIntOrNull() ?: policy.dailyLimitMinutes
            val extMins = editExtensionMins.text.toString().trim().toIntOrNull() ?: policy.extensionMinutes
            val extLimit = editExtensionLimit.text.toString().trim().toIntOrNull() ?: policy.dailyExtensionLimit
            val updated = policy.copy(
                dailyLimitMinutes = dailyLimit,
                extensionMinutes = extMins,
                dailyExtensionLimit = extLimit,
                hardBlockAfterLimit = switchHardBlock.isChecked
            )
            currentPolicy = updated
            btn.isEnabled = false
            Thread {
                try {
                    FocusPolicyClient.updatePolicy(updated)
                    runOnUiThread {
                        txtPolicySaved.visibility = View.VISIBLE
                        txtPolicySaved.postDelayed({ txtPolicySaved.visibility = View.GONE }, 2000)
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

        // Load policy from Firestore
        Thread {
            try {
                val policy = FocusPolicyClient.getPolicy()
                runOnUiThread {
                    currentPolicy = policy
                    editDailyLimit.setText(if (policy.dailyLimitMinutes > 0) policy.dailyLimitMinutes.toString() else "")
                    editExtensionMins.setText(policy.extensionMinutes.toString())
                    editExtensionLimit.setText(policy.dailyExtensionLimit.toString())
                    switchHardBlock.isChecked = policy.hardBlockAfterLimit
                    renderTargetApps()
                    renderBlockWindows()
                }
            } catch (e: Exception) {
                // leave fields empty, user can fill in
            }
        }.start()
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
            }
            val label = TextView(this).apply {
                text = pkg
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
                    currentPolicy = currentPolicy?.copy(targetApps = currentPolicy!!.targetApps.filter { it != pkg })
                    renderTargetApps()
                }
            }
            row.addView(label)
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

                    val used = evaluateResult.usedMinutes
                    val limit = evaluateResult.effectiveLimit
                    todayMinutesText.text = if (limit > 0) "${"%.1f".format(used)} / $limit min" else "${"%.1f".format(used)} min used"
                    todayMinutesText.setTextColor(
                        if (used >= limit && limit > 0) getColor(R.color.color_danger)
                        else getColor(R.color.text_primary)
                    )

                    val opens = stats.optInt("opensToday", 0)
                    todayOpensText.text = "$opens opens today"
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
