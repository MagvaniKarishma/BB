package com.brokerbuddy.calls

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.brokerbuddy.notifications.ReminderNotifier

/**
 * What the caller screen needs, checked against the device. Nothing here is bypassed:
 * each item is granted by the user through the system's own screens.
 */
object CallerSetup {
    /** Android 10+ is required for the call-screening role that delivers incoming numbers. */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun hasScreeningRole(context: Context): Boolean {
        if (!isSupported) return false
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    /** System dialog asking the user to make BrokerBuddy the call-screening app; null if unavailable. */
    fun screeningRoleRequest(context: Context): Intent? {
        if (!isSupported) return null
        val rm = context.getSystemService(RoleManager::class.java) ?: return null
        if (!rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) return null
        return rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
    }

    fun canNotify(context: Context) = ReminderNotifier.canNotify(context)

    fun canDrawOverlays(context: Context) = Settings.canDrawOverlays(context)

    fun overlaySettings(context: Context) =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    fun hasContactsPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun notificationSettings(context: Context) = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}
