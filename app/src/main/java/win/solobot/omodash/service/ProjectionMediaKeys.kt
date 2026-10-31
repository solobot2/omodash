package win.solobot.omodash.service

import android.media.session.MediaController
import android.os.SystemClock
import android.view.KeyEvent
import win.solobot.omodash.diagnostics.DiagnosticLog

/** Desay's projection sessions implement onMediaButtonEvent, not transport callbacks. */
object ProjectionMediaKeys {
    fun dispatch(controller: MediaController, keyCode: Int): Boolean {
        val binding = win.solobot.omodash.data.VehiclePresets.current().apps.values
            .firstOrNull { it.packageName == controller.packageName }
        if (binding?.mediaButtonEvents != true) return false
        val accepted = sendPair(controller, keyCode)
        DiagnosticLog.event("MediaCommand", "Projection key=$keyCode for ${controller.packageName}, down/up accepted=$accepted")
        // Do not follow a partially accepted press with a duplicate transport command.
        return true
    }
    fun sendPair(controller: MediaController, keyCode: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = controller.dispatchMediaButtonEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        val up = controller.dispatchMediaButtonEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0))
        return down && up
    }
}
