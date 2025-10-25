package com.polaralias.audiofocus.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import com.polaralias.audiofocus.service.UiDetectService

data class PermissionSnapshot(
    val overlay: Boolean,
    val notificationListener: Boolean,
    val usageAccess: Boolean,
    val accessibility: Boolean,
    val sdkLevel: Int
)

object PermissionStatus {
    fun snapshot(context: Context): PermissionSnapshot {
        val overlayGranted = Settings.canDrawOverlays(context)
        val notificationGranted = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        val usageGranted = isUsageAccessGranted(context)
        val accessibilityGranted = isAccessibilityEnabled(context)
        return PermissionSnapshot(
            overlay = overlayGranted,
            notificationListener = notificationGranted,
            usageAccess = usageGranted,
            accessibility = accessibilityGranted,
            sdkLevel = Build.VERSION.SDK_INT
        )
    }

    private fun isUsageAccessGranted(context: Context): Boolean {
        val manager = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = manager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val expectedClass = UiDetectService::class.java.name
        val expectedPackage = context.packageName
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (manager != null) {
            val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            if (enabled.any { info ->
                    val component = ComponentName.unflattenFromString(info.id)
                    component?.packageName == expectedPackage && component?.className == expectedClass
                }) {
                return true
            }
        }
        val secureSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        if (secureSetting.isEmpty()) {
            return false
        }
        return secureSetting.split(ACCESSIBILITY_SEPARATOR).any { entry ->
            val component = ComponentName.unflattenFromString(entry.trim())
            component?.packageName == expectedPackage && component?.className == expectedClass
        }
    }

    private const val ACCESSIBILITY_SEPARATOR = ':'
}
