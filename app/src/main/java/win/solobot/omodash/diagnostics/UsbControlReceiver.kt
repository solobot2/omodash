package win.solobot.omodash.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import win.solobot.omodash.util.LauncherExit

/** Protected by android.permission.DUMP: normal third-party apps cannot call this endpoint. */
class UsbControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "win.solobot.omodash.action.RESTORE_COMPONENTS") {
            runCatching { LauncherExit.restoreForUsb(context); resultCode = 10729 }
                .onFailure { DiagnosticLog.event("App", "USB component restoration failed", it); resultCode = 0 }
            return
        }
        if (intent.action == "win.solobot.omodash.action.VERIFY_SETUP") {
            val values = mapOf(
                "install" to win.solobot.omodash.update.UpdateInstaller.canInstall(context),
                "media" to win.solobot.omodash.util.MediaAccess.isGranted(context),
                "helper" to win.solobot.omodash.update.UpdateAccessibilityService.isEnabled(context),
                "logs" to DiagnosticReport.hasSystemLogs(context),
                "secure" to win.solobot.omodash.util.DevicePermissionSetup.canWriteSecureSettings(context)
            )
            resultData = values.entries.joinToString(";") { "${it.key}=${it.value}" }
            resultCode = 10728
            return
        }
        if (intent.action != "win.solobot.omodash.action.PREPARE_STOP") return
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
