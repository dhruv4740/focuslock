package com.focuslock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.focuslock.data.FocusPolicyClient
import com.focuslock.receiver.PolicyDeviceAdminReceiver

/**
 * First-run explainer, gated by [PREF_ONBOARDING_COMPLETE] in FocusLockPrefs.
 * Single scrollable screen rather than a multi-step wizard — this is a
 * personal tool, not a Play Store onboarding flow, so one screen covering
 * "what this does" + the two permission jumps + a sync code is enough.
 */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "FocusLockPrefs"
        const val PREF_ONBOARDING_COMPLETE = "onboarding_complete"

        fun isComplete(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_ONBOARDING_COMPLETE, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        findViewById<Button>(R.id.btnOnboardAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnOnboardDeviceAdmin).setOnClickListener {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(this, PolicyDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                Toast.makeText(this, "Device admin already enabled", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Activate FocusLock Shield to prevent uninstallation when limits are enforced."
                    )
                })
            }
        }

        findViewById<Button>(R.id.btnOnboardFinish).setOnClickListener { btn ->
            val syncCode = findViewById<EditText>(R.id.editOnboardSyncId).text.toString().trim()
            btn.isEnabled = false
            Thread {
                try {
                    if (syncCode.isNotEmpty()) {
                        FocusPolicyClient.setSyncId(this, syncCode)
                    }
                } catch (e: Exception) {
                    // Best-effort — onboarding still completes even if the sync
                    // write fails offline; the user can set it again in Settings.
                }
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putBoolean(PREF_ONBOARDING_COMPLETE, true)
                    .apply()
                runOnUiThread {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            }.start()
        }
    }
}
