package win.solobot.omodash.diagnostics

import android.content.Context
import android.os.Build
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class RecordingState(val active: Boolean = false, val message: String = "")

/** Explicit, bounded logcat recording. No service, wakelock or automatic restart. */
object DiagnosticRecording {
    private val mutable = MutableStateFlow(RecordingState())
    val state = mutable.asStateFlow()
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "log-recording-timer").apply { isDaemon = true } }
    private var generation = 0
    private var process: java.lang.Process? = null
    private var currentMetadata: File? = null
    private fun directory(context: Context) = File(context.filesDir, "diagnostic-recording")

    @Synchronized
    fun start(context: Context, includeSystem: Boolean) {
        if (mutable.value.active) return
        val app = context.applicationContext
        val system = includeSystem && DiagnosticReport.hasSystemLogs(app)
        val id = ++generation
        val dir = directory(app).apply { mkdirs() }
        dir.listFiles().orEmpty().forEach { it.delete() }
        val metadata = File(dir, "recording.json")
        currentMetadata = metadata
        metadata.writeText(JSONObject().put("startedAt", Date().toString()).put("systemLogs", system)
            .put("pid", Process.myPid()).put("status", "recording; if the process exits, recording stops").toString(2))
        val files = DiagnosticFiles(dir, 512 * 1024)
        mutable.value = RecordingState(true, "Recording for up to 2 minutes. Close settings and reproduce the problem, then send a diagnostic ZIP.")
        PerformanceTrace.start(app)
        DiagnosticLog.event("Recording", "Started 2-minute logcat recording; system=$system")
        timer.schedule({ finish(id, metadata, "Two-minute recording finished. Send / save a diagnostic ZIP.") }, 120, TimeUnit.SECONDS)
        Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                // Never use unfiltered logcat as an app-only fallback on a privileged process.
                check(system || Build.VERSION.SDK_INT >= 24) { "App-only recording requires Android 7+" }
                val extra = if (system) listOf("-b", "all") else listOf("--pid=${Process.myPid()}")
                val child = ProcessBuilder(listOf("logcat", "-v", "threadtime", "-T", "1") + extra)
                    .redirectErrorStream(true).start()
                synchronized(this) {
                    if (generation != id || !mutable.value.active) { child.destroy(); return@Thread }
                    process = child
                }
                child.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        synchronized(this) {
                            if (generation == id && mutable.value.active) files.record(line)
                        }
                    }
                }
                finish(id, metadata, "Log recording ended (logcat exit ${child.waitFor()}). Send / save a diagnostic ZIP.")
            } catch (error: Exception) {
                finish(id, metadata, "Log recording ended: ${error.message ?: error.javaClass.simpleName}")
            }
        }, "diagnostic-log-recording").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        currentMetadata?.let { finish(generation, it, "Recording stopped when the launcher was closed. Saved logs are available in the next ZIP.") }
    }

    @Synchronized
    private fun finish(id: Int, metadata: File, message: String) {
        if (id != generation || !mutable.value.active) return
        mutable.value = RecordingState(false, message)
        process?.destroy()
        process = null
        runCatching {
            val json = JSONObject(metadata.readText()).put("endedAt", Date().toString()).put("status", message)
            metadata.writeText(json.toString(2))
        }
        DiagnosticLog.event("Recording", message)
    }

    @Synchronized
    fun snapshot(context: Context, includeSystem: Boolean): Map<String, String> {
        val dir = directory(context)
        val metadata = File(dir, "recording.json")
        if (!metadata.isFile) return emptyMap()
        val json = metadata.readText()
        val result = linkedMapOf("recording.json" to json)
        if (JSONObject(json).optBoolean("systemLogs") && !includeSystem) {
            result["recorded-logcat-omitted.txt"] = "Recording contains system logs. Select Include system logs to attach it."
        } else {
            DiagnosticFiles(dir, 512 * 1024).snapshot().forEach { (name, text) -> result["recorded-$name"] = text }
        }
        return result
    }
}
