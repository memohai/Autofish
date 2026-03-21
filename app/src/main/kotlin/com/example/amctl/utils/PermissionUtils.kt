package com.example.amctl.utils

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityManager
import android.provider.Settings

object PermissionUtils {
    fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val serviceClassName = serviceClass.name
        val packageName = context.packageName

        // Primary path: query enabled services from AccessibilityManager.
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (am != null) {
            val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            if (enabled.any { info ->
                    info.resolveInfo?.serviceInfo?.packageName == packageName &&
                        info.resolveInfo?.serviceInfo?.name == serviceClassName
                }
            ) {
                return true
            }
        }

        // Fallback path: parse secure settings string for ROM compatibility.
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        return enabledServices
            .split(':')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it.packageName == packageName && it.className == serviceClassName }
    }
}
