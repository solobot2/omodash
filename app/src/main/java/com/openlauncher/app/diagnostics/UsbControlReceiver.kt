package com.openlauncher.app.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.openlauncher.app.util.LauncherExit

/** Protected by android.permission.DUMP: normal third-party apps cannot call this endpoint. */
class UsbControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.openlauncher.app.action.RESTORE_COMPONENTS") {
            runCatching { LauncherExit.restoreForUsb(context); resultCode = 10729 }
                .onFailure { DiagnosticLog.event("App", "USB component restoration failed", it); resultCode = 0 }
            return
        }
        if (intent.action == "com.openlauncher.app.action.VERIFY_SETUP") {
            val values = mapOf(
                "install" to com.openlauncher.app.update.UpdateInstaller.canInstall(context),
                "media" to com.openlauncher.app.util.MediaAccess.isGranted(context),
                "helper" to com.openlauncher.app.update.UpdateAccessibilityService.isEnabled(context),
                "logs" to DiagnosticReport.hasSystemLogs(context),
                "secure" to com.openlauncher.app.util.DevicePermissionSetup.canWriteSecureSettings(context)
            )
            resultData = values.entries.joinToString(";") { "${it.key}=${it.value}" }
            resultCode = 10728
            return
        }
        if (intent.action != "com.openlauncher.app.action.PREPARE_STOP") return
        runCatching {
            LauncherExit.prepareStop(context)
            DiagnosticLog.event("App", "Prepared own components for USB force-stop")
            resultCode = 10727
        }.onFailure {
            DiagnosticLog.event("App", "USB stop preparation failed", it)
            resultCode = 0
        }
    }
}
