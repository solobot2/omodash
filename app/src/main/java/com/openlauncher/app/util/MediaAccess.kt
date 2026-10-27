package com.openlauncher.app.util

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.openlauncher.app.diagnostics.DiagnosticLog
import com.openlauncher.app.service.MediaListenerService

object MediaAccess {
    fun component(context: Context) = ComponentName(context, MediaListenerService::class.java)

    fun isGranted(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 27) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .isNotificationListenerAccessGranted(component(context))
        } else {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                .orEmpty().split(':').mapNotNull(ComponentName::unflattenFromString)
                .contains(component(context))
        }
    }.getOrElse {
        DiagnosticLog.event("MediaAccess", "Cannot read notification listener access", it)
        false
    }

    /** null = opened a permission screen; otherwise return a visible explanation. */
    fun openSettings(context: Context): String? {
        DiagnosticLog.event("MediaAccess", "User requested media access; granted=${isGranted(context)}")
        if (isGranted(context) && Build.VERSION.SDK_INT >= 24) {
            runCatching { android.service.notification.NotificationListenerService.requestRebind(component(context)) }
                .onFailure { DiagnosticLog.event("MediaAccess", "Listener rebind failed", it) }
        }
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= 30) add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context).flattenToString()))
            add(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }
        for (intent in candidates) {
            if (DevicePermissionSetup.openSystemScreen(context, intent, "MediaAccess")) return null
        }
        if (isGranted(context)) return null
        if (DevicePermissionSetup.enableNotificationListener(context)) return null
        return "This firmware has no notification access screen. " + DevicePermissionSetup.INSTRUCTIONS
    }
}
