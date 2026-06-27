package com.focuslock

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class PermissionWarningActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission_warning)

        findViewById<Button>(R.id.btnWarnUsageAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        findViewById<Button>(R.id.btnWarnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnWarnOverlay).setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnWarnClose).setOnClickListener {
            finish()
        }
    }
}
