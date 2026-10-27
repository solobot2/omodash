package com.openlauncher.app.diagnostics

import android.Manifest
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import com.openlauncher.app.BuildConfig
import com.openlauncher.app.service.MediaListenerService
import com.openlauncher.app.util.MediaAccess
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DiagnosticReport {
    fun hasSystemLogs(context: Context) = context.checkCallingOrSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
    fun latest(context: Context): File? = File(context.filesDir, "reports").listFiles()
        ?.filter { it.extension == "zip" }?.maxByOrNull { it.lastModified() }

    /** Called on a worker thread. Individual unavailable sources do not prevent a report. */
    fun create(context: Context, description: String, includeSystemLogs: Boolean): File {
        val systemLogs = includeSystemLogs && hasSystemLogs(context)
        DiagnosticLog.event("Report", "Creating diagnostic ZIP; systemLogs=$systemLogs")
        val entries = linkedMapOf<String, String>()
        entries["README.txt"] = """
            Omodash diagnostic report, schema 1.
            description.txt: user's description.
            device.json: app/build, display, permission and media session diagnostics.
            events*.log: bounded persistent launcher journal (survives process death).
            crash-*.txt: last three uncaught JVM crashes, recorded with the crashing app version.
            logcat-app.txt: recent entries from this process only; previous processes may be absent.
            logcat-system.txt: only included if READ_LOGS was already granted and explicitly selected.
            source-errors.txt: unavailable sources. Missing logs are not evidence that no error occurred.
            No launcher preferences, notification contents, location or media titles are exported.
            Native crashes, ANRs, force-stop and power loss may not reach the JVM crash handler.
            Obfuscated release stack traces require mapping.txt from the matching GitHub release.
        """.trimIndent()
        entries["description.txt"] = description.take(8000)
        val errors = StringBuilder()
        fun source(name: String, block: () -> String) {
            try { entries[name] = block() }
            catch (error: Exception) { errors.append("$name: ${error.javaClass.simpleName}: ${error.message}\n") }
        }
        source("device.json") { deviceInfo(context, systemLogs).toString(2) }
        source("performance.json") { PerformanceTrace.snapshot(context).toString(2) }
        try { entries.putAll(DiagnosticLog.snapshot()) }
        catch (error: Exception) { errors.append("Persistent journal: $error\n") }
        source("logcat-app.txt") { logcat(listOf("--pid=${Process.myPid()}")) }
        if (systemLogs) source("logcat-system.txt") { logcat(emptyList()) }
        if (includeSystemLogs && !systemLogs) errors.append("System logs requested but READ_LOGS is not granted.\n")
        entries["source-errors.txt"] = errors.toString().ifBlank { "No collection errors.\n" }
        val directory = File(context.filesDir, "reports").apply { mkdirs() }
        val name = "omodash-${BuildConfig.VERSION_NAME}-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}-${UUID.randomUUID().toString().take(8)}.zip"
        val file = File(directory, name)
        val pending = File(directory, "$name.partial")
        try {
            ZipOutputStream(pending.outputStream().buffered()).use { zip ->
                entries.forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(DiagnosticFiles.sanitize(text).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            check(pending.renameTo(file)) { "Cannot finish diagnostic ZIP" }
        } finally { pending.delete() }
        directory.listFiles().orEmpty().filter { it.extension == "zip" }
            .sortedByDescending { it.lastModified() }.drop(5).forEach { it.delete() }
        DiagnosticLog.event("Report", "Created ${file.name}, ${file.length()} bytes")
        return file
    }

    private fun deviceInfo(context: Context, systemLogs: Boolean): JSONObject {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        val display = context.resources.displayMetrics
        val result = JSONObject()
            .put("schema", 1).put("createdAt", Date().toString())
            .put("package", context.packageName).put("versionName", info.versionName)
            .put("versionCode", if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong())
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("android", Build.VERSION.RELEASE).put("api", Build.VERSION.SDK_INT)
            .put("fingerprint", Build.FINGERPRINT)
            .put("display", "${display.widthPixels}x${display.heightPixels}, density=${display.density}, fontScale=${context.resources.configuration.fontScale}")
            .put("notificationAccessGranted", MediaAccess.isGranted(context))
            .put("secureSettingsPermission", com.openlauncher.app.util.DevicePermissionSetup.canWriteSecureSettings(context))
            .put("mediaListenerConnected", MediaListenerService.isConnected.value)
            .put("overlayPermission", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context))
            .put("canInstallPackages", Build.VERSION.SDK_INT < 26 || pm.canRequestPackageInstalls())
            .put("systemLogsPermission", hasSystemLogs(context)).put("systemLogsIncluded", systemLogs)
            .put("downloadProvider", pm.resolveContentProvider("downloads", 0)?.packageName ?: JSONObject.NULL)
        val permissions = JSONObject()
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            "android.permission.MEDIA_CONTENT_CONTROL", Manifest.permission.READ_LOGS, Manifest.permission.WRITE_SECURE_SETTINGS).forEach {
            permissions.put(it, context.checkCallingOrSelfPermission(it) == PackageManager.PERMISSION_GRANTED)
        }
        result.put("permissions", permissions)
        val actions = listOf("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS", "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS",
            Settings.ACTION_ACCESSIBILITY_SETTINGS, Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Settings.ACTION_SETTINGS)
        result.put("settingsActivities", JSONObject().apply {
            actions.forEach { action ->
                put(action, JSONArray(pm.queryIntentActivities(Intent(action), PackageManager.MATCH_DEFAULT_ONLY).map {
                    "${it.activityInfo.packageName}/${it.activityInfo.name}"
                }))
            }
        })
        try {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            result.put("mediaSessions", JSONArray(manager.getActiveSessions(MediaAccess.component(context)).map {
                JSONObject().put("package", it.packageName).put("state", it.playbackState?.state)
                    .put("actions", it.playbackState?.actions)
            }))
        } catch (error: Exception) { result.put("mediaSessionsError", "${error.javaClass.simpleName}: ${error.message}") }
        return result
    }

    private fun logcat(extra: List<String>): String {
        // Never fall back to an unfiltered dump for the app-only file, even on privileged devices.
        if (Build.VERSION.SDK_INT < 24 && extra.isNotEmpty()) return "Process-filtered logcat requires Android 7+. See the persistent launcher journal and crash files.\n"
        val args = extra
        val process = ProcessBuilder(listOf("logcat", "-d", "-v", "threadtime", "-t", if (extra.isEmpty()) "6000" else "1500") + args)
            .redirectErrorStream(true).start()
        val reader = FutureTask {
            val text = StringBuilder()
            process.inputStream.bufferedReader().use { input ->
                val buffer = CharArray(4096)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    text.append(buffer, 0, n)
                    val limit = if (extra.isEmpty()) 1024 * 1024 else 512 * 1024
                    if (text.length > limit) text.delete(0, text.length - limit)
                }
            }
            val output = text.toString()
            check(process.waitFor() == 0) { "logcat failed: ${output.take(1500)}" }
            output
        }
        val worker = Thread(reader, "diagnostic-logcat").apply { isDaemon = true; start() }
        return try {
            reader.get(5, TimeUnit.SECONDS)
        } finally {
            process.destroy()
            runCatching { process.inputStream.close() }
            reader.cancel(true)
            worker.interrupt()
        }
    }

    fun share(context: Context, file: File, email: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (email.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(email.trim()))
            putExtra(Intent.EXTRA_SUBJECT, "Omodash ${BuildConfig.VERSION_NAME}: bug report")
            putExtra(Intent.EXTRA_TEXT, "Diagnostic report from Omodash ${BuildConfig.VERSION_NAME}. See description.txt in the attached ZIP.")
            clipData = ClipData.newRawUri("Omodash diagnostic ZIP", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Send diagnostic report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun saveToDownloads(context: Context, file: File) {
        if (Build.VERSION.SDK_INT < 29) error("Use Share to save the ZIP on Android 9 or earlier.")
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Omodash")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "Downloads storage is unavailable" }
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
