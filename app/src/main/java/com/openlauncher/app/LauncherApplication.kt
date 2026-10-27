package com.openlauncher.app

import android.app.Application
import com.openlauncher.app.diagnostics.DiagnosticLog

class LauncherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.initialize(this)
        com.openlauncher.app.diagnostics.PerformanceTrace.start(this)
    }
}
