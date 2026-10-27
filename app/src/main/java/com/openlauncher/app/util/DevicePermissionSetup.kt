package com.openlauncher.app.util

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.openlauncher.app.diagnostics.DiagnosticLog
import com.openlauncher.app.update.UpdateAccessibilityService

/** Explicit user actions only. Never grants itself privileges or replaces other services. */
object DevicePermissionSetup {
    const val INSTRUCTIONS = "On this head unit, open JCarTools → Give permissions to apps, select Open Launcher, " +
        "then Give all permissions. A working ADB connection in JCarTools is required. " +
        "Return here and use Configure vehicle permissions for media access, then retry the helper button. " +
        "Some commands may be rejected; the launcher checks the actual result."

    fun canWriteSecureSettings(context: Context): Boolean =
        context.checkCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun openSystemScreen(context: Context, intent: Intent, area: String): Boolean {
        val activity = intent.resolveActivity(context.packageManager)
        if (activity == null || activity.packageName == "com.android.car.frameworkpackagestubs") {
            DiagnosticLog.event(area, "Unavailable settings action=${intent.action}, handler=${activity?.flattenToShortString() ?: "none"}")
            return false
        }
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            DiagnosticLog.event(area, "Opened ${intent.action}, handler=${activity.flattenToShortString()}")
            true
        } catch (error: Exception) {
            DiagnosticLog.event(area, "Cannot open ${intent.action}", error)
            false
        }
    }

    fun enableNotificationListener(context: Context): Boolean {
        if (!canWriteSecureSettings(context)) return false
        return runCatching {
            addService(context, "enabled_notification_listeners", MediaAccess.component(context))
            if (Build.VERSION.SDK_INT >= 24) android.service.notification.NotificationListenerService.requestRebind(MediaAccess.component(context))
            // Modern Android may keep the actual approval in notification_policy.xml.
            // A secure-settings write alone is never treated as proof of access.
            MediaAccess.isGranted(context)
        }.onFailure { DiagnosticLog.event("MediaAccess", "Cannot enable own listener through secure settings", it) }.getOrDefault(false)
    }

    fun enableUpdateHelper(context: Context): Boolean {
        if (!canWriteSecureSettings(context)) return false
        return runCatching {
            addService(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, ComponentName(context, UpdateAccessibilityService::class.java))
            check(Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)) { "Accessibility could not be enabled" }
            UpdateAccessibilityService.isEnabled(context)
        }.onFailure { DiagnosticLog.event("UpdateSetup", "Cannot enable own update helper", it) }.getOrDefault(false)
    }

    private fun addService(context: Context, key: String, component: ComponentName) {
        val previous = Settings.Secure.getString(context.contentResolver, key).orEmpty()
        val entries = previous.split(':').filter { it.isNotBlank() }
        if (entries.any { ComponentName.unflattenFromString(it) == component }) return
        val updated = (entries + component.flattenToString()).joinToString(":")
        check(Settings.Secure.putString(context.contentResolver, key, updated)) { "System refused $key" }
        check(Settings.Secure.getString(context.contentResolver, key).orEmpty().split(':')
            .any { ComponentName.unflattenFromString(it) == component }) { "System did not retain $key" }
        DiagnosticLog.event("PermissionSetup", "Enabled own service ${component.className}; retained ${entries.size} existing entries")
    }

    fun openJCarTools(context: Context): String? = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage("com.example.navistart")
            ?: error("JCarTools is not installed or cannot be launched. Open it from the vehicle's app list.")
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    }.getOrElse { it.message ?: "Cannot open JCarTools" }
}
