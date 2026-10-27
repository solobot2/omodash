package com.openlauncher.app.util

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.openlauncher.app.diagnostics.DiagnosticLog

object LauncherExit {
    private fun preferences(context: Context) = context.getSharedPreferences("stopped_components", Context.MODE_PRIVATE)

    fun restoreComponents(context: Context) {
        val prefs = preferences(context)
        val names = prefs.getStringSet("components", emptySet()).orEmpty().toSet()
        names.forEach { name ->
            runCatching {
                context.packageManager.setComponentEnabledSetting(ComponentName.unflattenFromString(name)!!,
                    prefs.getInt(name, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT), PackageManager.DONT_KILL_APP)
            }.onFailure { DiagnosticLog.event("App", "Cannot restore own component $name", it) }
        }
        if (names.isNotEmpty()) { prefs.edit().clear().commit(); DiagnosticLog.event("App", "Restored own background components after manual launch") }
    }

    private fun suspendOwnComponents(context: Context) {
        val components = listOf(
            ComponentName(context, com.openlauncher.app.service.MediaListenerService::class.java),
            ComponentName(context, com.openlauncher.app.update.UpdateAccessibilityService::class.java),
            ComponentName(context, com.openlauncher.app.update.UpdateDownloadReceiver::class.java),
            ComponentName(context, com.openlauncher.app.update.UpdateInstallReceiver::class.java),
            ComponentName(context, com.openlauncher.app.update.UpdateReplacedReceiver::class.java)
        ) + listOfNotNull(context.packageManager.resolveContentProvider("${context.packageName}.diagnostics", 0)
            ?.let { ComponentName(context, it.name) })
        val editor = preferences(context).edit().putStringSet("components", components.map { it.flattenToString() }.toSet())
        components.forEach { editor.putInt(it.flattenToString(), context.packageManager.getComponentEnabledSetting(it)) }
        check(editor.commit()) { "Cannot save component state before stopping" }
        components.forEach { context.packageManager.setComponentEnabledSetting(it,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP) }
    }

    /** Hand off Home before killing only our PID. Never force-stops another package. */
    fun stop(context: Context): String? = runCatching {
        val homes = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        ).map { it.activityInfo }.filter {
            it.exported && !it.packageName.startsWith("com.openlauncher.app")
        }
        val target = homes.firstOrNull { it.packageName == "com.desaysv.launcher" }
            ?: homes.firstOrNull { it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
            ?: homes.firstOrNull()
            ?: error("No other Home application is available. Install or enable another launcher before stopping this one.")
        val component = ComponentName(target.packageName, target.name)
        context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).apply {
            this.component = component
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        })
        com.openlauncher.app.update.UpdateInstaller.abandon(context)
        // Bound listeners and exported receivers could otherwise immediately restart our PID.
        suspendOwnComponents(context)
        DiagnosticLog.event("App", "User requested process exit; Home handed to ${component.flattenToShortString()}")
        var owner: Context = context
        while (owner is ContextWrapper && owner !is Activity) owner = owner.baseContext
        (owner as? Activity)?.finishAndRemoveTask()
        Handler(Looper.getMainLooper()).postDelayed({
            Process.killProcess(Process.myPid())
            kotlin.system.exitProcess(0)
        }, 500)
        null
    }.getOrElse {
        restoreComponents(context)
        DiagnosticLog.event("App", "Could not hand off Home; process was not stopped", it)
        it.message ?: "Could not open another Home application"
    }
}
