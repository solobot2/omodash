package win.solobot.omodash.diagnostics

import android.content.Context
import android.os.Process
import android.util.Log
import win.solobot.omodash.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLog {
    private var files: DiagnosticFiles? = null

    fun initialize(context: Context) {
        if (files != null) return
        files = DiagnosticFiles(File(context.filesDir, "diagnostics"))
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Always delegate to Android's handler: saving a report must not suppress termination.
            try {
                files?.crash("${timestamp()} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nThread: ${thread.name}\n${Log.getStackTraceString(error)}")
            } catch (_: Throwable) {
                // The filesystem or memory may be unavailable while a process is dying.
            } finally {
                if (previous != null) previous.uncaughtException(thread, error)
                else { Process.killProcess(Process.myPid()); kotlin.system.exitProcess(10) }
            }
        }
        event("App", "Started ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), pid=${Process.myPid()}")
    }

    fun event(area: String, message: String, error: Throwable? = null) {
        val line = "${timestamp()} [$area] $message" + (error?.let { "\n${Log.getStackTraceString(it)}" } ?: "")
        if (error == null) Log.i("OmodashDiagnostics", DiagnosticFiles.sanitize(line))
        else Log.w("OmodashDiagnostics", DiagnosticFiles.sanitize(line))
        runCatching { files?.record(line) }
    }

    fun snapshot(): Map<String, String> = files?.snapshot().orEmpty()
    private fun timestamp() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
}
