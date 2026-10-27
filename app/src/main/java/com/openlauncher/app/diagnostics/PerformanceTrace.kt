package com.openlauncher.app.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Bounded two-minute trace, with no media, permission or location requests. */
object PerformanceTrace {
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "diagnostic-performance").apply { isDaemon = true }
    }
    private var pending: ScheduledFuture<*>? = null
    private var generation = 0

    @Synchronized
    fun start(context: Context) {
        pending?.cancel(false)
        val traceId = ++generation
        val app = context.applicationContext
        var samples = 0
        var previousWall = SystemClock.elapsedRealtime()
        var previousCpu = Process.getElapsedCpuTime()
        DiagnosticLog.event("Performance", "Starting 2-minute process trace; CPU percent uses one core as 100%")
        pending = worker.scheduleAtFixedRate({
            runCatching {
                val now = SystemClock.elapsedRealtime()
                val cpu = Process.getElapsedCpuTime()
                val percent = if (now > previousWall) 100.0 * (cpu - previousCpu) / (now - previousWall) else 0.0
                val info = snapshot(app).put("processCpuIntervalMs", now - previousWall)
                    .put("processCpuPercentOneCore", if (now - previousWall >= 1000) percent else JSONObject.NULL)
                DiagnosticLog.event("Performance", info.toString())
                previousWall = now
                previousCpu = cpu
            }.onFailure { DiagnosticLog.event("Performance", "Cannot sample own process", it) }
            synchronized(this) {
                if (traceId == generation && ++samples >= 13) { pending?.cancel(false); pending = null }
            }
        }, 0, 10, TimeUnit.SECONDS)
    }

    fun snapshot(context: Context): JSONObject {
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val process = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(process)
        val runtime = Runtime.getRuntime()
        return JSONObject()
            .put("pid", Process.myPid()).put("uid", Process.myUid())
            .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("processCpuTimeMs", Process.getElapsedCpuTime())
            .put("processImportance", process.importance)
            .put("processPssKB", Debug.getPss())
            .put("javaHeapUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("javaHeapMaxBytes", runtime.maxMemory())
            .put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize())
            .put("systemAvailableBytes", memory.availMem).put("systemTotalBytes", memory.totalMem)
            .put("systemLowMemory", memory.lowMemory).put("systemLowMemoryThresholdBytes", memory.threshold)
    }
}
