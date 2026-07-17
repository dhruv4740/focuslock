package com.focuslock.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast

/**
 * Receives PackageInstaller session status for in-app updates. Registered
 * explicitly in the manifest with no intent-filter — UpdateChecker always
 * targets it directly via the PendingIntent passed to
 * PackageInstaller.Session.commit().
 */
class InstallStatusReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "InstallStatusReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = confirmIntentFrom(intent) ?: return
                try {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not launch install confirmation", e)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                UpdateChecker.cleanupDownloadedApk(context)
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "Install failed: status=$status message=$message")
                Toast.makeText(
                    context,
                    "Update install failed: ${message ?: "unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun confirmIntentFrom(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
}
