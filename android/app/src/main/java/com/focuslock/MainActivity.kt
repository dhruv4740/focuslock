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
import com.focuslock.data.ChangeDirection
import com.focuslock.data.FocusPolicyClient
import com.focuslock.data.FocusPolicyClient.PolicyData
import com.focuslock.data.PolicyRatchet
import com.focuslock.data.PolicySnapshot
import com.focuslock.data.PresetMapper
import com.focuslock.update.UpdateChecker
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.focuslock.receiver.PolicyDeviceAdminReceiver
import com.focuslock.security.EnforcementChecks
import com.focuslock.service.UsageMonitorService
import com.focuslock.surface.BlockableSurface
import com.focuslock.surface.SurfaceBlocker
import com.focuslock.surface.SurfaceMode
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
    private lateinit var txtMyFriendCode: TextView
    private lateinit var editFriendCode: EditText
    private lateinit var layoutFriendships: LinearLayout

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
    private lateinit var txtReelDetection: TextView

    // Blocked-surfaces spinner options: wire value paired with its display label,
    // so reordering the list can never silently corrupt the saved mode.
    private val surfaceModeOptions = listOf(
        "off" to "Off — just count",
        "quota" to "Count, block after limit",
        "one_at_a_time" to "One at a time",
        "block" to "Block entirely"
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

    /** Applies a background result on the UI thread, unless this Activity is already gone. */
    private fun postToUi(block: () -> Unit) {
        if (isFinishing || isDestroyed) return
        runOnUiThread { if (!isFinishing && !isDestroyed) block() }
    }

    private fun formatEpochMs(ms: Long): String =
        java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

    // ── Ratchet save path ─────────────────────────────────────────────────────
    //
    // Every editor card (Blocking Rules, Reels, Presets, friction toggles) goes
    // through the same gate: build the full effective `old`/`new` snapshot pair
    // (identical except for the fields this card just edited), refuse outright
    // if a loosening while locked, else PolicyRatchet.split() and write
    // `applyNow` now / `deferred` to policy/pending. `old` is built from
    // in-memory `currentPolicy` (already loaded), not a fresh Firestore fetch —
    // same staleness profile the pre-ratchet code had for untouched fields, see
    // the task report for why that's an accepted tradeoff, not an oversight.

    /**
     * @param fieldKeys restricts the Firestore write to this card's own fields —
     *   preserves the merge-write discipline (Reels can't clobber Blocking Rules
     *   and vice versa) even though `split.applyNow` is a full snapshot.
     * @param afterApply runs on the background thread with the *applied* (gate-
     *   respecting) snapshot — never the raw requested `new`, so e.g. SurfaceBlocker
     *   is never told about a loosening before its delay has elapsed.
     */
    private fun saveViaRatchet(
        btn: View,
        confirmationView: TextView?,
        old: PolicySnapshot,
        new: PolicySnapshot,
        fieldKeys: Set<String>,
        writeLocal: Boolean = false,
        afterApply: (PolicySnapshot) -> Unit = {}
    ) {
        btn.isEnabled = false
        Thread {
            try {
                val guard = FocusPolicyClient.getRatchetGuard()
                val now = System.currentTimeMillis()
                if (PolicyRatchet.classify(old, new) == ChangeDirection.LOOSEN &&
                    PolicyRatchet.isLocked(guard.lockedUntilMs, now)
                ) {
                    postToUi {
                        Toast.makeText(
                            this,
                            "Locked until ${formatEpochMs(guard.lockedUntilMs!!)} — loosening refused, stricter changes still apply",
                            Toast.LENGTH_LONG
                        ).show()
                        btn.isEnabled = true
                    }
                    return@Thread
                }

                val split = PolicyRatchet.split(old, new)
                val scoped = FocusPolicyClient.firestoreFieldsOf(split.applyNow).filterKeys { it in fieldKeys }
                FocusPolicyClient.updatePolicyFields(scoped)
                if (writeLocal) FocusPolicyClient.applyLocalFields(this, split.applyNow)
                afterApply(split.applyNow)

                var message = "Saved."
                if (split.deferred != null) {
                    val effectiveAt = PolicyRatchet.effectiveAtMs(now, guard.loosenDelayHours)
                    FocusPolicyClient.writePendingChange(effectiveAt, now, split.deferred, split.deferredChanges.map { it.summary })
                    message = "Stricter changes applied now. ${split.deferredChanges.size} change(s) that weaken your limits take effect in ${guard.loosenDelayHours}h."
                }

                startService(Intent(this, UsageMonitorService::class.java)
                    .setAction(UsageMonitorService.ACTION_POLICY_UPDATED))

                postToUi {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    confirmationView?.let { v ->
                        v.visibility = View.VISIBLE
                        v.postDelayed({ v.visibility = View.GONE }, 2000)
                    }
                    btn.isEnabled = true
                    currentPolicy = null
                    loadPolicyIntoEditor()
                    refreshPendingBanner()
                }
            } catch (e: Exception) {
                postToUi {
                    Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    btn.isEnabled = true
                }
            }
        }.start()
    }

    // ── Pending-changes banner (Rules screen) ────────────────────────────────

    private fun refreshPendingBanner() {
        Thread {
            val pending = try {
                FocusPolicyClient.getPendingChange()
            } catch (e: Exception) {
                null
            }
            postToUi { renderPendingBanner(pending) }
        }.start()
    }

    private fun renderPendingBanner(pending: FocusPolicyClient.PendingChange?) {
        val card = findViewById<View>(R.id.cardPendingChange)
        if (pending == null) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        findViewById<TextView>(R.id.txtPendingSummaries).text =
            pending.summaries.joinToString("\n") { "• $it" }
        val remainingMs = (pending.effectiveAtMs - System.currentTimeMillis()).coerceAtLeast(0)
        val remainingH = remainingMs / 3_600_000
        val remainingM = (remainingMs / 60_000) % 60
        findViewById<TextView>(R.id.txtPendingCountdown).text = if (remainingMs <= 0)
            "Due — takes effect next time the app checks in"
        else
            "Takes effect in ${remainingH}h ${remainingM}m (${formatEpochMs(pending.effectiveAtMs)})"
        findViewById<Button>(R.id.btnCancelPending).setOnClickListener { btn ->
            btn.isEnabled = false
            Thread {
                try {
                    // Cancelling a pending loosening is itself a tightening — no delay of its own.
                    FocusPolicyClient.cancelPendingChange()
                    postToUi {
                        Toast.makeText(this, "Pending change cancelled", Toast.LENGTH_SHORT).show()
                        btn.isEnabled = true
                        refreshPendingBanner()
                    }
                } catch (e: Exception) {
                    postToUi {
                        Toast.makeText(this, "Cancel failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        btn.isEnabled = true
                    }
                }
            }.start()
        }
    }

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
        txtMyFriendCode = findViewById(R.id.txtMyFriendCode)
        editFriendCode = findViewById(R.id.editFriendCode)
        layoutFriendships = findViewById(R.id.layoutFriendships)

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
        txtReelDetection = findViewById(R.id.txtReelDetection)
        layoutWeeklyChart = findViewById(R.id.layoutWeeklyChart)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        FocusPolicyClient.init(this)

        setupBottomNav()
        setupSyncCode()
        setupIdentitySection()
        setupTodoSection()
        setupFocusSession()
        setupPolicyEditor()
        setupPresets()
        setupFrictionSettingsAndHelpCard(prefs)
        setupRatchetSettings()
        refreshPendingBanner()

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
        refreshReelDetection()

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
            .setMessage(
                "This overwrites your per-app modes, daily limit behavior, reels settings and " +
                "friction steps. Changes that tighten your limits apply now; any that weaken " +
                "them wait out the loosen delay (see Settings)."
            )
            .setPositiveButton("Apply") { _, _ ->
                Thread {
                    try {
                        val liveFields = PresetMapper.bundleToLiveFields(presetId, bundle)
                        val frictionKeys = listOf(
                            "enable_breathing", "enable_delay", "enable_intention",
                            "enable_shake", "enable_reflection", "enable_stats", "enable_math"
                        )
                        val stepsBundle = PresetMapper.frictionSteps(bundle)

                        val old = FocusPolicyClient.snapshotFrom(this, policy)
                        var new = old
                        (liveFields["dailyLimitMinutes"] as? Int)?.let { new = new.copy(dailyLimitMinutes = it) }
                        (liveFields["hardBlockAfterLimit"] as? Boolean)?.let { new = new.copy(hardBlockAfterLimit = it) }
                        (liveFields["sessionMaxMinutes"] as? Int)?.let { new = new.copy(sessionMaxMinutes = it) }
                        (liveFields["sessionBreakMinutes"] as? Int)?.let { new = new.copy(sessionBreakMinutes = it) }
                        @Suppress("UNCHECKED_CAST")
                        (liveFields["appModes"] as? Map<String, String>)?.let { wire ->
                            @Suppress("UNCHECKED_CAST")
                            val targets = (liveFields["targetApps"] as? List<String>)?.toSet() ?: old.targetApps
                            new = new.copy(
                                targetApps = targets,
                                appModes = wire.mapValues { (pkg, w) -> AppMode.fromWire(w, inTargetApps = pkg in targets) }
                            )
                        }
                        @Suppress("UNCHECKED_CAST")
                        (liveFields["blockedSurfaces"] as? Map<String, String>)?.let { wire ->
                            new = new.copy(blockedSurfaces = wire.mapValues { SurfaceMode.fromWire(it.value) })
                        }
                        @Suppress("UNCHECKED_CAST")
                        (liveFields["reelDailyLimit"] as? Map<String, Int>)?.let { new = new.copy(reelDailyLimit = it) }
                        if (stepsBundle.isNotEmpty()) {
                            new = new.copy(
                                frictionSteps = frictionKeys.filterTo(mutableSetOf()) { stepsBundle[it] as? Boolean == true },
                                mathDifficulty = stepsBundle["math_difficulty"] as? String ?: new.mathDifficulty
                            )
                        }

                        val guard = FocusPolicyClient.getRatchetGuard()
                        val now = System.currentTimeMillis()
                        if (PolicyRatchet.classify(old, new) == ChangeDirection.LOOSEN &&
                            PolicyRatchet.isLocked(guard.lockedUntilMs, now)
                        ) {
                            postToUi {
                                Toast.makeText(
                                    this,
                                    "Locked until ${formatEpochMs(guard.lockedUntilMs!!)} — can't apply a mode that loosens your limits",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            return@Thread
                        }

                        val split = PolicyRatchet.split(old, new)
                        val presetFieldKeys = liveFields.keys - "activePresetId"
                        val scoped = FocusPolicyClient.firestoreFieldsOf(split.applyNow).filterKeys { it in presetFieldKeys }
                        FocusPolicyClient.updatePolicyFields(scoped + mapOf("activePresetId" to presetId))
                        FocusPolicyClient.applyLocalFields(this, split.applyNow)
                        SurfaceBlocker.updateConfig(
                            split.applyNow.blockedSurfaces.mapValues { it.value.wire },
                            split.applyNow.reelDailyLimit
                        )

                        var message = "\"$name\" mode applied."
                        if (split.deferred != null) {
                            val effectiveAt = PolicyRatchet.effectiveAtMs(now, guard.loosenDelayHours)
                            FocusPolicyClient.writePendingChange(effectiveAt, now, split.deferred, split.deferredChanges.map { it.summary })
                            message += " ${split.deferredChanges.size} change(s) that weaken your limits take effect in ${guard.loosenDelayHours}h."
                        }

                        startService(Intent(this, UsageMonitorService::class.java)
                            .setAction(UsageMonitorService.ACTION_POLICY_UPDATED))

                        postToUi {
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                            currentPolicy = null
                            loadPolicyIntoEditor()
                            syncFrictionSwitchStates()
                            refreshPendingBanner()
                        }
                    } catch (e: Exception) {
                        postToUi {
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
                    postToUi {
                        focusSessionText.text = if (result.active)
                            "Focus session active until ${result.endsAt}"
                        else
                            "Focus session inactive"
                        btn.isEnabled = true
                    }
                } catch (e: Exception) {
                    postToUi {
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
                    postToUi {
                        focusSessionText.text = "Focus session inactive"
                        btn.isEnabled = true
                    }
                } catch (e: Exception) {
                    postToUi {
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
                    postToUi {
                        editNewTodo.setText("")
                        Toast.makeText(this, "Task added!", Toast.LENGTH_SHORT).show()
                        loadTodos()
                        btn.isEnabled = true
                    }
                } catch (e: Exception) {
                    postToUi {
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
        setupReelLimitEnablement(spinnerInstagramReels, editReelLimitInstagram)
        setupReelLimitEnablement(spinnerYoutubeShorts, editReelLimitYoutube)

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

    // "off" only counts — the daily limit field is meaningless there, so grey
    // it out to avoid implying it does something in that mode.
    private fun setupReelLimitEnablement(spinner: Spinner, limitField: EditText) {
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                limitField.isEnabled = surfaceModeOptions[position].first != "off"
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
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
        val updatedPolicy = policy.copy(
            dailyLimitMinutes = dailyLimit,
            extensionMinutes = extMins,
            dailyExtensionLimit = extLimit,
            hardBlockAfterLimit = hardBlock,
            targetApps = appModes.keys.toList(),
            appModes = appModes,
            sessionMaxMinutes = sessionMax,
            sessionBreakMinutes = sessionBreak
        )
        val old = FocusPolicyClient.snapshotFrom(this, policy)
        val new = FocusPolicyClient.snapshotFrom(this, updatedPolicy)
        currentPolicy = updatedPolicy
        saveViaRatchet(
            btn, confirmationView, old, new,
            fieldKeys = setOf(
                "dailyLimitMinutes", "extensionMinutes", "dailyExtensionLimit", "hardBlockAfterLimit",
                "targetApps", "appModes", "blockWindows", "sessionMaxMinutes", "sessionBreakMinutes"
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
        val updatedPolicy = policy.copy(blockedSurfaces = blockedSurfaces, reelDailyLimit = reelDailyLimit)
        val old = FocusPolicyClient.snapshotFrom(this, policy)
        val new = FocusPolicyClient.snapshotFrom(this, updatedPolicy)
        currentPolicy = updatedPolicy
        saveViaRatchet(
            btn, confirmationView, old, new,
            fieldKeys = setOf("blockedSurfaces", "reelDailyLimit"),
            afterApply = { applied ->
                // Use the gate-respecting applied snapshot, not the raw requested
                // values — a loosened surface must not reach the live blocker
                // before its delay elapses.
                SurfaceBlocker.updateConfig(
                    applied.blockedSurfaces.mapValues { it.value.wire },
                    applied.reelDailyLimit
                )
            }
        )
    }

    private fun loadPolicyIntoEditor() {
        Thread {
            try {
                val policy = FocusPolicyClient.getPolicy()
                postToUi {
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

    // Switching sync codes repoints this device at a different (possibly empty)
    // Firestore tree — every limit currently enforced stops applying the moment
    // it happens. That used to be two taps with no confirmation; it's the
    // cheapest way to defeat every other guard in this file, so it gets its own
    // gate: refused outright while locked or while a block is active, otherwise
    // an explicit dialog spelling out what's about to happen.
    private fun setupSyncCode() {
        editSyncId.setText(FocusPolicyClient.getSyncId())
        findViewById<Button>(R.id.btnSaveSyncId).setOnClickListener { btn ->
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean("is_currently_blocked", false)) {
                Toast.makeText(this, "Can't switch sync codes while a block is active.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            btn.isEnabled = false
            Thread {
                val guard = try { FocusPolicyClient.getRatchetGuard() } catch (e: Exception) { null }
                val locked = guard != null && PolicyRatchet.isLocked(guard.lockedUntilMs, System.currentTimeMillis())
                postToUi {
                    btn.isEnabled = true
                    if (locked) {
                        Toast.makeText(
                            this,
                            "Locked until ${formatEpochMs(guard!!.lockedUntilMs!!)} — switching sync codes is refused while locked",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("Switch sync code?")
                            .setMessage(
                                "This points the app at a different (possibly empty) data tree. " +
                                "Every limit, block window and app mode currently enforced on this " +
                                "device stops applying the moment you confirm."
                            )
                            .setPositiveButton("Switch") { _, _ -> doSetSyncId() }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }.start()
        }
    }

    private fun doSetSyncId() {
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
        refreshPendingBanner()
    }

    // ── Identity & Friends ────────────────────────────────────────────────────
    //
    // Minimal plumbing UI only — a "Sign in with Google" button, your own
    // friend code, a field to add someone else's, and a flat pending/accepted
    // list. The leaderboard/streak features this lays groundwork for are out
    // of scope for this pass.

    private fun setupIdentitySection() {
        findViewById<Button>(R.id.btnGoogleSignIn).setOnClickListener { btn ->
            val webClientId = getString(R.string.google_oauth_web_client_id)
            if (webClientId == "REPLACE_WITH_FIREBASE_WEB_CLIENT_ID") {
                Toast.makeText(
                    this,
                    "Google sign-in isn't configured yet — google_oauth_web_client_id in strings.xml still has its placeholder value.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            btn.isEnabled = false
            com.focuslock.data.GoogleAuthClient.signIn(this, webClientId) { result ->
                result.onSuccess { idToken ->
                    Thread {
                        val linkResult = FocusPolicyClient.linkGoogleAccount(idToken)
                        postToUi {
                            btn.isEnabled = true
                            if (linkResult.success) {
                                Toast.makeText(this, "Signed in with Google", Toast.LENGTH_SHORT).show()
                                refreshMyFriendCode()
                            } else {
                                Toast.makeText(this, linkResult.error ?: "Sign-in failed", Toast.LENGTH_LONG).show()
                            }
                        }
                    }.start()
                }.onFailure { e ->
                    postToUi {
                        btn.isEnabled = true
                        Toast.makeText(this, "Google sign-in failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        findViewById<Button>(R.id.btnSendFriendRequest).setOnClickListener { btn ->
            val rawCode = editFriendCode.text.toString()
            if (!com.focuslock.data.FriendCode.isValid(rawCode)) {
                Toast.makeText(this, "That doesn't look like a valid friend code.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btn.isEnabled = false
            Thread {
                try {
                    val profile = FocusPolicyClient.findProfileByFriendCode(rawCode)
                    if (profile == null) {
                        postToUi {
                            btn.isEnabled = true
                            Toast.makeText(this, "No user found with that friend code.", Toast.LENGTH_SHORT).show()
                        }
                        return@Thread
                    }
                    FocusPolicyClient.sendFriendRequest(profile.uid)
                    postToUi {
                        btn.isEnabled = true
                        editFriendCode.setText("")
                        Toast.makeText(this, "Friend request sent to ${profile.displayName.ifBlank { profile.friendCode }}", Toast.LENGTH_SHORT).show()
                        refreshFriendships()
                    }
                } catch (e: Exception) {
                    postToUi {
                        btn.isEnabled = true
                        Toast.makeText(this, "Couldn't send request: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }

        refreshMyFriendCode()
        refreshFriendships()
    }

    private fun refreshMyFriendCode() {
        Thread {
            try {
                var profile = FocusPolicyClient.getMyProfile()
                if (profile == null) {
                    // First time we have a real (linked or anonymous) uid —
                    // create the profile doc so findProfileByFriendCode can
                    // resolve it for others.
                    profile = FocusPolicyClient.upsertMyProfile(displayName = "")
                }
                postToUi {
                    txtMyFriendCode.text = "Your friend code: ${profile?.friendCode ?: "—"}"
                }
            } catch (e: Exception) {
                // Not signed in yet, or offline — leave the placeholder.
            }
        }.start()
    }

    private fun refreshFriendships() {
        Thread {
            try {
                val friendships = FocusPolicyClient.listFriendships()
                postToUi { renderFriendships(friendships) }
            } catch (e: Exception) {
                // leave whatever was last rendered
            }
        }.start()
    }

    private fun renderFriendships(friendships: List<FocusPolicyClient.Friendship>) {
        layoutFriendships.removeAllViews()
        if (friendships.isEmpty()) {
            layoutFriendships.addView(TextView(this).apply {
                text = "No friends added yet"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
            })
            return
        }
        val myUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        for (f in friendships) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, 4, 0, 4)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val statusLabel = when {
                f.status == FocusPolicyClient.FriendshipStatus.ACCEPTED -> "Friends"
                f.requestedBy == myUid -> "Pending (sent)"
                else -> "Pending (respond)"
            }
            val label = TextView(this).apply {
                text = "${f.otherUid.take(8)}… — $statusLabel"
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(label)
            if (f.status == FocusPolicyClient.FriendshipStatus.PENDING && f.requestedBy != myUid) {
                val acceptBtn = Button(this).apply {
                    text = "Accept"
                    textSize = 12f
                    setOnClickListener {
                        isEnabled = false
                        Thread {
                            FocusPolicyClient.acceptFriendRequest(f.pairId)
                            postToUi { refreshFriendships() }
                        }.start()
                    }
                }
                row.addView(acceptBtn)
            }
            layoutFriendships.addView(row)
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
                contentDescription = "Remove $pkg"
                setTextColor(getColor(R.color.color_danger))
                background = getDrawable(R.drawable.button_secondary)
                val size = resources.getDimensionPixelSize(R.dimen.min_touch_target)
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
                contentDescription = "Remove block window ${w["start"]} to ${w["end"]}"
                setTextColor(getColor(R.color.color_danger))
                background = getDrawable(R.drawable.button_secondary)
                val size = resources.getDimensionPixelSize(R.dimen.min_touch_target)
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

        // Each friction step is one entry in PolicySnapshot.frictionSteps — turning
        // one OFF is a loosening (ratcheted/deferred), turning one ON is a
        // tightening (instant). Local SharedPreferences, but still gated: a step
        // disabled here is exactly as much of a bypass as loosening a Firestore
        // field would be.
        attachFrictionToggle(switchBreathing, "enable_breathing")
        attachFrictionToggle(switchDelay, "enable_delay")
        attachFrictionToggle(switchIntention, "enable_intention")
        attachFrictionToggle(switchShake, "enable_shake")
        attachFrictionToggle(switchReflection, "enable_reflection")
        attachFrictionToggle(switchStats, "enable_stats")
        attachFrictionToggle(switchMath, "enable_math")

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
        // easy < medium < hard (see PolicyRatchet.mathDifficultyRank) — picking an
        // easier tier is a loosening, ratcheted the same as everything else.
        spinnerMathDifficulty.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            private var isFirstCallback = true
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isFirstCallback) { isFirstCallback = false; return } // fires once on attach; not a user edit
                val chosen = difficulties[position]
                spinnerMathDifficulty.isEnabled = false
                Thread {
                    try {
                        val old = FocusPolicyClient.snapshotFrom(this@MainActivity, FocusPolicyClient.getPolicy())
                        val new = old.copy(mathDifficulty = chosen)
                        val guard = FocusPolicyClient.getRatchetGuard()
                        val now = System.currentTimeMillis()
                        if (PolicyRatchet.classify(old, new) == ChangeDirection.LOOSEN &&
                            PolicyRatchet.isLocked(guard.lockedUntilMs, now)
                        ) {
                            postToUi {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Locked until ${formatEpochMs(guard.lockedUntilMs!!)} — can't make this easier yet",
                                    Toast.LENGTH_LONG
                                ).show()
                                spinnerMathDifficulty.setSelection(difficulties.indexOf(old.mathDifficulty).coerceAtLeast(0))
                                spinnerMathDifficulty.isEnabled = true
                            }
                            return@Thread
                        }
                        val split = PolicyRatchet.split(old, new)
                        FocusPolicyClient.applyLocalFields(this@MainActivity, split.applyNow)
                        var toastMsg: String? = null
                        if (split.deferred != null) {
                            val effectiveAt = PolicyRatchet.effectiveAtMs(now, guard.loosenDelayHours)
                            FocusPolicyClient.writePendingChange(effectiveAt, now, split.deferred, split.deferredChanges.map { it.summary })
                            toastMsg = "Takes effect in ${guard.loosenDelayHours}h"
                        }
                        postToUi {
                            toastMsg?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() }
                            spinnerMathDifficulty.setSelection(difficulties.indexOf(split.applyNow.mathDifficulty).coerceAtLeast(0))
                            spinnerMathDifficulty.isEnabled = true
                            refreshPendingBanner()
                        }
                    } catch (e: Exception) {
                        postToUi {
                            Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            spinnerMathDifficulty.isEnabled = true
                        }
                    }
                }.start()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** Wires one friction SwitchCompat through the same ratchet gate as the Firestore-backed cards. */
    private fun attachFrictionToggle(switch: SwitchCompat, key: String) {
        switch.setOnCheckedChangeListener(object : android.widget.CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(buttonView: android.widget.CompoundButton, checked: Boolean) {
                switch.isEnabled = false
                Thread {
                    try {
                        val old = FocusPolicyClient.snapshotFrom(this@MainActivity, FocusPolicyClient.getPolicy())
                        val new = old.copy(
                            frictionSteps = if (checked) old.frictionSteps + key else old.frictionSteps - key
                        )
                        val guard = FocusPolicyClient.getRatchetGuard()
                        val now = System.currentTimeMillis()
                        if (PolicyRatchet.classify(old, new) == ChangeDirection.LOOSEN &&
                            PolicyRatchet.isLocked(guard.lockedUntilMs, now)
                        ) {
                            postToUi {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Locked until ${formatEpochMs(guard.lockedUntilMs!!)} — can't turn this off yet",
                                    Toast.LENGTH_LONG
                                ).show()
                                switch.setOnCheckedChangeListener(null)
                                switch.isChecked = !checked
                                switch.setOnCheckedChangeListener(this)
                                switch.isEnabled = true
                            }
                            return@Thread
                        }
                        val split = PolicyRatchet.split(old, new)
                        FocusPolicyClient.applyLocalFields(this@MainActivity, split.applyNow)
                        val effectiveChecked = key in split.applyNow.frictionSteps
                        var toastMsg: String? = null
                        if (split.deferred != null) {
                            val effectiveAt = PolicyRatchet.effectiveAtMs(now, guard.loosenDelayHours)
                            FocusPolicyClient.writePendingChange(effectiveAt, now, split.deferred, split.deferredChanges.map { it.summary })
                            toastMsg = "Takes effect in ${guard.loosenDelayHours}h"
                        }
                        postToUi {
                            toastMsg?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() }
                            switch.setOnCheckedChangeListener(null)
                            switch.isChecked = effectiveChecked
                            switch.setOnCheckedChangeListener(this)
                            switch.isEnabled = true
                            refreshPendingBanner()
                        }
                    } catch (e: Exception) {
                        postToUi {
                            Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            switch.isEnabled = true
                        }
                    }
                }.start()
            }
        })
    }

    // ── Ratchet settings (Settings screen) ───────────────────────────────────

    private fun setupRatchetSettings() {
        val txtLockStatus = findViewById<TextView>(R.id.txtLockStatus)
        val editDelay = findViewById<EditText>(R.id.editLoosenDelayHours)
        val btnSaveDelay = findViewById<Button>(R.id.btnSaveLoosenDelay)
        val btnLock = findViewById<Button>(R.id.btnLockSettings)
        val btnClearLock = findViewById<Button>(R.id.btnClearLock)

        fun refresh() {
            Thread {
                try {
                    // A queued lowering that has now waited out the current delay
                    // applies here, before the guard is displayed or re-checked —
                    // otherwise it would sit applied-but-unreflected until the next
                    // unrelated policy save happened to read it.
                    FocusPolicyClient.applyDuePendingDelayChange()
                    val guard = FocusPolicyClient.getRatchetGuard()
                    postToUi {
                        editDelay.setText(guard.loosenDelayHours.toString())
                        val locked = PolicyRatchet.isLocked(guard.lockedUntilMs, System.currentTimeMillis())
                        txtLockStatus.text = when {
                            locked -> "Locked until ${formatEpochMs(guard.lockedUntilMs!!)} — no setting can be loosened until then."
                            guard.lockedUntilMs != null -> "Lock expired ${formatEpochMs(guard.lockedUntilMs)}. Not currently locked."
                            else -> "Not locked. Loosened changes still take effect after ${guard.loosenDelayHours}h either way."
                        }
                        if (guard.pendingLoosenDelayHours != null && guard.pendingLoosenDelayEffectiveAtMs != null) {
                            txtLockStatus.text = "${txtLockStatus.text}\nDelay change to ${guard.pendingLoosenDelayHours}h takes effect ${formatEpochMs(guard.pendingLoosenDelayEffectiveAtMs)}."
                        }
                        btnClearLock.isEnabled = locked
                    }
                } catch (e: Exception) {
                    // leave last-known state on screen
                }
            }.start()
        }
        refresh()

        btnSaveDelay.setOnClickListener { btn ->
            val newHours = editDelay.text.toString().trim().toIntOrNull()?.coerceAtLeast(0)
            if (newHours == null) {
                Toast.makeText(this, "Enter a whole number of hours", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btn.isEnabled = false
            Thread {
                try {
                    val guard = FocusPolicyClient.getRatchetGuard()
                    val now = System.currentTimeMillis()
                    val direction = PolicyRatchet.classifyDelayChange(guard.loosenDelayHours, newHours)
                    if (direction == ChangeDirection.LOOSEN && PolicyRatchet.isLocked(guard.lockedUntilMs, now)) {
                        postToUi {
                            Toast.makeText(
                                this,
                                "Locked until ${formatEpochMs(guard.lockedUntilMs!!)} — can't shorten the delay yet",
                                Toast.LENGTH_LONG
                            ).show()
                            btn.isEnabled = true
                        }
                        return@Thread
                    }
                    if (direction == ChangeDirection.LOOSEN) {
                        // The delay is ratcheted like everything else it protects:
                        // shortening it is itself a loosening, gated by the delay
                        // CURRENTLY in force. Without this, "set delay to 0" is a
                        // one-tap bypass of the whole mechanism — every other
                        // loosening becomes instant the moment this one field
                        // changes. So a shorter delay only starts protecting future
                        // loosenings once it has itself survived waiting out the
                        // old one.
                        val effectiveAt = PolicyRatchet.effectiveAtMs(now, guard.loosenDelayHours)
                        FocusPolicyClient.requestLoosenDelayChange(newHours, effectiveAt)
                        postToUi {
                            Toast.makeText(
                                this,
                                "Shorter delay takes effect in ${guard.loosenDelayHours}h, not immediately",
                                Toast.LENGTH_LONG
                            ).show()
                            btn.isEnabled = true
                            refresh()
                        }
                        return@Thread
                    }
                    FocusPolicyClient.updateRatchetGuard(loosenDelayHours = newHours)
                    postToUi {
                        Toast.makeText(this, "Loosen delay set to ${newHours}h", Toast.LENGTH_SHORT).show()
                        btn.isEnabled = true
                        refresh()
                    }
                } catch (e: Exception) {
                    postToUi {
                        Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        btn.isEnabled = true
                    }
                }
            }.start()
        }

        btnLock.setOnClickListener {
            val choices = arrayOf("7 days", "30 days", "90 days")
            val days = arrayOf(7, 30, 90)
            AlertDialog.Builder(this)
                .setTitle("Lock settings")
                .setMessage(
                    "While locked, nothing on Rules or Settings can be loosened — only " +
                    "tightened. This is friction against your own future impulse, not a hard " +
                    "technical lock: clearing app data or uninstalling still works."
                )
                .setItems(choices) { _, which ->
                    val untilMs = System.currentTimeMillis() + days[which] * 24L * 60 * 60 * 1000
                    Thread {
                        try {
                            val guard = FocusPolicyClient.getRatchetGuard()
                            val now = System.currentTimeMillis()
                            // Extending (or setting for the first time) is a tightening —
                            // instant. Picking a SHORTER date than an existing lock would be
                            // a loosening of that lock, gated by the lock itself.
                            val wouldShorten = guard.lockedUntilMs != null && untilMs <= guard.lockedUntilMs
                            if (wouldShorten && PolicyRatchet.isLocked(guard.lockedUntilMs, now)) {
                                postToUi {
                                    Toast.makeText(
                                        this,
                                        "Already locked further out (until ${formatEpochMs(guard.lockedUntilMs!!)}) — can't shorten it",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                return@Thread
                            }
                            FocusPolicyClient.updateRatchetGuard(lockedUntilMs = untilMs)
                            postToUi {
                                Toast.makeText(this, "Locked until ${formatEpochMs(untilMs)}", Toast.LENGTH_SHORT).show()
                                refresh()
                            }
                        } catch (e: Exception) {
                            postToUi { Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show() }
                        }
                    }.start()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnClearLock.setOnClickListener {
            Thread {
                try {
                    val guard = FocusPolicyClient.getRatchetGuard()
                    val now = System.currentTimeMillis()
                    val stillLocked = PolicyRatchet.isLocked(guard.lockedUntilMs, now)
                    postToUi {
                        if (stillLocked) {
                            Toast.makeText(
                                this,
                                "Can't remove a lock before it expires (${formatEpochMs(guard.lockedUntilMs!!)}) — that would defeat the point",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Thread {
                                try {
                                    FocusPolicyClient.updateRatchetGuard(clearLock = true)
                                    postToUi { Toast.makeText(this, "Lock cleared", Toast.LENGTH_SHORT).show(); refresh() }
                                } catch (e: Exception) {
                                    postToUi { Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show() }
                                }
                            }.start()
                        }
                    }
                } catch (e: Exception) {
                    postToUi { Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
            }.start()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        refreshProtectionStatus()
        refreshDashboardData()
        refreshReelDetection()
        refreshPendingBanner()
        val switchDeviceAdmin = findViewById<SwitchCompat>(R.id.switchDeviceAdmin)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, PolicyDeviceAdminReceiver::class.java)
        switchDeviceAdmin.isChecked = dpm.isAdminActive(adminComponent)
        UpdateChecker.resumePendingInstall(this)

        // A deferred loosening whose delay has elapsed takes effect on the next
        // time the user opens the app — no separate scheduler on this side (the
        // other planned call site is UsageMonitorService, wired separately).
        Thread {
            try {
                if (FocusPolicyClient.applyDuePendingChange(this)) {
                    postToUi {
                        Toast.makeText(this, "A previously-deferred change just took effect", Toast.LENGTH_LONG).show()
                        currentPolicy = null
                        loadPolicyIntoEditor()
                        refreshPendingBanner()
                    }
                }
                // Same lazy-apply-on-resume treatment for a queued loosening of the
                // delay itself — otherwise it only takes effect the next time the
                // user happens to open the Settings screen's ratchet card.
                FocusPolicyClient.applyDuePendingDelayChange()
            } catch (e: Exception) {
                // Best-effort; the next resume retries.
            }
        }.start()
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

    // Read-only diagnostics: helps the user find their modded YouTube client's
    // real package name once detection has learned it (see learnedAliases()).
    private fun refreshReelDetection() {
        val statusLines = BlockableSurface.entries.joinToString("\n") { surface ->
            val status = SurfaceBlocker.statusFor(surface)
            "${surface.wireKey}: mode=${status.mode.wire} count=${status.count} limit=${status.limit} blocked=${status.blocked}"
        }
        val aliases = SurfaceBlocker.learnedAliases()
        val aliasLines = if (aliases.isEmpty()) {
            "none yet"
        } else {
            aliases.entries.joinToString("\n") { (pkg, surface) -> "$pkg -> ${surface.wireKey}" }
        }
        txtReelDetection.text = buildString {
            append(statusLines)
            append("\n\nLearned aliases:\n")
            append(aliasLines)
            append("\n\nOpen Reels/Shorts and swipe once, then return here.")
        }
    }

    private fun refreshDashboardData() {
        Thread {
            try {
                val focus = FocusPolicyClient.getFocusSession()
                val dateStr = LocalDate.now().toString()
                val stats = FocusPolicyClient.getStats(dateStr)
                val evaluateResult = FocusPolicyClient.evaluate(dateStr)

                postToUi {
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
                postToUi {
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
                postToUi {
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
                postToUi {
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
                postToUi {
                    layoutTodoList.removeAllViews()
                    if (todos.isEmpty()) {
                        layoutTodoList.addView(TextView(this).apply {
                            text = "No pending tasks. You are all set!"
                            setTextColor(getColor(R.color.text_secondary))
                            textSize = 13f
                            setPadding(0, 10, 0, 10)
                        })
                        return@postToUi
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
                                        postToUi {
                                            Toast.makeText(this@MainActivity, "Failed to update task", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }.start()
                            }
                        }

                        val btnDel = Button(this).apply {
                            text = "✕"
                            textSize = 10f
                            contentDescription = "Remove task ${item.text}"
                            setTextColor(getColor(R.color.color_danger))
                            background = getDrawable(R.drawable.button_secondary)
                            val size = resources.getDimensionPixelSize(R.dimen.min_touch_target)
                            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = 8 }
                            setOnClickListener {
                                isEnabled = false
                                Thread {
                                    try {
                                        FocusPolicyClient.deleteTodo(item.id)
                                        postToUi { loadTodos() }
                                    } catch (e: Exception) {
                                        postToUi {
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
                postToUi {
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
