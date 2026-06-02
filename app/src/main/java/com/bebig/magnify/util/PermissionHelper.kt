package com.bebig.magnify.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.content.ComponentName
import android.text.TextUtils
import com.bebig.magnify.service.MagnifyAccessibilityService

object PermissionHelper {

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedComponent = ComponentName(context, MagnifyAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedComponent.flattenToString(), ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    fun hasOverlayPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(context)
        else
            true

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun openOverlaySettings(context: Context) {
        context.startActivity(Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ))
    }

    fun isMagnificationApiSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    fun enableSystemMagnificationGesture(context: Context): Boolean {
        return try {
            Settings.Secure.putInt(
                context.contentResolver,
                "accessibility_display_magnification_enabled",
                1
            )
            true
        } catch (e: SecurityException) {
            false
        }
    }
}
