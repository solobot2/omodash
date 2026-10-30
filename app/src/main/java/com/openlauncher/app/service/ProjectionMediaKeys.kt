package com.openlauncher.app.service

import android.media.session.MediaController
import android.os.SystemClock
import android.view.KeyEvent
import com.openlauncher.app.diagnostics.DiagnosticLog

/** Desay's projection sessions implement onMediaButtonEvent, not transport callbacks. */
object ProjectionMediaKeys {
    private val packages = setOf("com.desaysv.vehicle.carplayapp", "com.google.android.projection.sink")
    fun dispatch(controller: MediaController, keyCode: Int): Boolean {
        if (controller.packageName !in packages) return false
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
