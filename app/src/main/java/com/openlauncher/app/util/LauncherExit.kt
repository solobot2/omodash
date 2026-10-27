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
    private var helperRestoreGeneration = 0
    private fun preferences(context: Context) = context.getSharedPreferences("stopped_components", Context.MODE_PRIVATE)
    private fun backgroundComponents(context: Context) = listOf(
        ComponentName(context, com.openlauncher.app.service.MediaListenerService::class.java),
        ComponentName(context, com.openlauncher.app.update.UpdateAccessibilityService::class.java),
        ComponentName(context, com.openlauncher.app.update.UpdateDownloadReceiver::class.java),
        ComponentName(context, com.openlauncher.app.update.UpdateInstallReceiver::class.java),
        ComponentName(context, com.openlauncher.app.update.UpdateReplacedReceiver::class.java),
        ComponentName(context, androidx.core.content.FileProvider::class.java)
    )

    private fun refreshOwnServiceRegistration(context: Context) {
        // Android 11's Accessibility package monitor ignores component-only changes.
        // Both DEFAULT and ENABLED keep our manifest-enabled application enabled.
        // Returning to the exact original state emits a whole-package change without disabling it.
        val pm = context.packageManager
        val before = pm.getApplicationEnabledSetting(context.packageName)
        check(before == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || before == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        val temporary = if (before == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        try {
            pm.setApplicationEnabledSetting(context.packageName, temporary, PackageManager.DONT_KILL_APP)
        } finally {
            pm.setApplicationEnabledSetting(context.packageName, before, PackageManager.DONT_KILL_APP)
        }
    }

    /** Explicit USB setup also repairs disabled components left by an older build. */
    fun restoreForUsb(context: Context) {
        restoreComponents(context)
        backgroundComponents(context).forEach {
            context.packageManager.setComponentEnabledSetting(it, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        }
        refreshOwnServiceRegistration(context)
        scheduleHelperRestore(context)
        DiagnosticLog.event("App", "USB setup restored own background components to manifest defaults")
    }

    private fun scheduleHelperRestore(context: Context) {
        val app = context.applicationContext
        preferences(app).edit().putBoolean("helper_restore_pending", true).commit()
        val id = ++helperRestoreGeneration
        // Wait for Android 11's batched package change to refresh its installed-service cache,
        // then clear only our crashed/force-stopped Accessibility binding. This is a single retry.
        Handler(Looper.getMainLooper()).postDelayed({
            val prefs = preferences(app)
            if (id == helperRestoreGeneration && prefs.getBoolean("helper_restore_pending", false) && !prefs.contains("components")) {
                DevicePermissionSetup.rebindUpdateHelper(app) { success ->
                    if (success) prefs.edit().remove("helper_restore_pending").commit()
                    DiagnosticLog.event("App", "Restoring previously requested update helper: $success")
                }
            }
        }, 12_000)
    }

    fun restoreComponents(context: Context) {
        val prefs = preferences(context)
        val restoreHelper = prefs.getBoolean("restore_update_helper", false) || prefs.getBoolean("helper_restore_pending", false)
        val names = prefs.getStringSet("components", emptySet()).orEmpty().toSet()
        names.forEach { name ->
            runCatching {
                DiagnosticLog.event("App", "Restoring own component $name to state=${prefs.getInt(name, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)}")
                context.packageManager.setComponentEnabledSetting(ComponentName.unflattenFromString(name)!!,
                    prefs.getInt(name, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT), PackageManager.DONT_KILL_APP)
            }.onFailure { DiagnosticLog.event("App", "Cannot restore own component $name", it) }
        }
        if (names.isNotEmpty()) {
            refreshOwnServiceRegistration(context)
            DiagnosticLog.event("App", "Launch after explicit exit: previousPid=${prefs.getInt("exit_pid", -1)}, " +
                "requestedAtMs=${prefs.getLong("exit_time", 0)}, currentPid=${Process.myPid()}; restoring own components")
            prefs.edit().clear().commit()
        }
        if (restoreHelper) scheduleHelperRestore(context)
    }

    private fun suspendOwnComponents(context: Context) {
        // A second stop request must not overwrite the original enabled states with DISABLED.
        if (preferences(context).contains("components")) return
        val components = backgroundComponents(context)
        val editor = preferences(context).edit().putStringSet("components", components.map { it.flattenToString() }.toSet())
            .putInt("exit_pid", Process.myPid()).putLong("exit_time", System.currentTimeMillis())
            .putBoolean("restore_update_helper", com.openlauncher.app.update.UpdateAccessibilityService.isEnabled(context) ||
                preferences(context).getBoolean("helper_restore_pending", false))
            .remove("helper_restore_pending")
        components.forEach { editor.putInt(it.flattenToString(), context.packageManager.getComponentEnabledSetting(it)) }
        check(editor.commit()) { "Cannot save component state before stopping" }
        components.forEach { context.packageManager.setComponentEnabledSetting(it,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP) }
    }

    fun prepareStop(context: Context) {
        com.openlauncher.app.update.UpdateInstaller.abandon(context)
        com.openlauncher.app.diagnostics.DiagnosticRecording.stop()
        suspendOwnComponents(context)
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
        // Bound listeners and exported receivers could otherwise immediately restart our PID.
        prepareStop(context)
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
