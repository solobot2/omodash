package win.solobot.omodash

import android.app.Application
import win.solobot.omodash.diagnostics.DiagnosticLog

class LauncherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.initialize(this)
        win.solobot.omodash.diagnostics.PerformanceTrace.start(this)
    }
}
