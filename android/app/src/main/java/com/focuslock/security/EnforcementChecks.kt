package com.focuslock.security

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.focuslock.service.FocusAccessibilityService
import com.focuslock.service.OnDemandAccessibility

data class EnforcementStatus(
    val usageAccess: Boolean,
    val accessibility: Boolean,
    val overlay: Boolean,
    val allCriticalGranted: Boolean
)

object EnforcementChecks {

    fun readStatus(context: Context): EnforcementStatus {
        val usage = hasUsageAccess(context)
        val accessibility = isAccessibilityEnabled(context)
        val overlay = Settings.canDrawOverlays(context)

        return EnforcementStatus(
            usageAccess = usage,
            accessibility = accessibility,
            overlay = overlay,
            allCriticalGranted = usage && accessibility && overlay
        )
    }

    private fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }

        return mode == AppOpsManager.MODE_ALLOWED
    }

    // A service that parked itself (OnDemandAccessibility) counts as enabled: the
    // poller switches it back on, so enforcement isn't actually off. Requires the
    // grant to still be held, otherwise nothing can switch it back on.
    private fun isAccessibilityEnabled(context: Context): Boolean {
        if (OnDemandAccessibility.isParked(context) && OnDemandAccessibility.canManage(context)) return true
        val expectedService = ComponentName(context, FocusAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabled.contains(expectedService, ignoreCase = true)
    }
}
