package win.solobot.omodash.update

import android.content.Context
import win.solobot.omodash.BuildConfig
import win.solobot.omodash.diagnostics.DiagnosticLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/** Fallback for head units without Download Provider. Never requests audio focus. */
internal object AppUpdateDownload {
    const val DOWNLOAD_ID = -2L
    private class Transfer {
        @Volatile var cancelled = false
        @Volatile var percent: Int? = null
        @Volatile var connection: HttpURLConnection? = null
    }
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var active: Transfer? = null

    @Synchronized
    fun cancel() {
        active?.let { it.cancelled = true; it.connection?.disconnect() }
        active = null
    }

    @Synchronized
    fun progress(context: Context): DownloadProgress {
        val store = UpdateStore(context)
        if (active == null && !store.verified && store.error == null) {
            // Process death interrupts this fallback. Retry safely when Settings is reopened.
            start(context, store.candidate ?: return DownloadProgress())
        }
        return DownloadProgress(active != null, active?.percent)
    }

    @Synchronized
    fun start(context: Context, info: UpdateInfo) {
        check(active == null) { "An update download is already running" }
        val app = context.applicationContext
        val job = Transfer()
        active = job
        UpdateStore(app).downloadId = DOWNLOAD_ID
        DiagnosticLog.event("UpdateDownload", "Using app downloader; version=${info.versionName}")
        worker.execute {
            val store = UpdateStore(app)
            val target = store.apkFile()
            target.parentFile?.listFiles()?.filter { it.name.endsWith(".partial") }?.forEach { it.delete() }
            val partial = File(target.parentFile, "${target.name}.${UUID.randomUUID()}.partial")
            try {
                var url = URL(info.apkUrl)
                var response: HttpURLConnection? = null
                for (redirect in 0..8) {
                    check(!job.cancelled) { "Download cancelled" }
                    check(url.protocol == "https" || (BuildConfig.UPDATE_TEST_MODE && url.protocol == "http" && url.host in setOf("10.0.2.2", "127.0.0.1", "localhost"))) { "Unsafe APK download URL" }
                    val connection = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000; readTimeout = 20_000; instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "Omodash-updater")
                    }
                    job.connection = connection
                    val status = connection.responseCode
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        val location = connection.getHeaderField("Location") ?: error("Missing download redirect")
                        url = URL(url, location)
                        connection.disconnect()
                    } else {
                        check(status == 200) { "APK download returned HTTP $status" }
                        response = connection
                        break
                    }
                }
                val connection = checkNotNull(response) { "Too many download redirects" }
                var received = 0L
                connection.inputStream.use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        check(!job.cancelled) { "Download cancelled" }
                        val n = input.read(buffer)
                        if (n < 0) break
                        received += n
                        check(received <= info.size) { "APK exceeds the declared release size" }
                        output.write(buffer, 0, n)
                        job.percent = (100L * received / info.size).toInt().coerceIn(0, 100)
                    }
                } }
                ApkVerifier.verify(app, info, partial)
                synchronized(this) {
                    if (active !== job || job.cancelled) return@synchronized
                    check(!target.exists() || target.delete()) { "Cannot replace APK" }
                    check(partial.renameTo(target)) { "Cannot finish APK download" }
                    store.verified = true
                    DiagnosticLog.event("UpdateDownload", "APK downloaded and verified; version=${info.versionName}")
                }
            } catch (error: Exception) {
                synchronized(this) {
                    if (active === job && !job.cancelled) {
                        store.error = error.message ?: "APK download failed"
                        store.installRequested = false
                        DiagnosticLog.event("UpdateDownload", "App download failed", error)
                    }
                }
            } finally {
                job.connection?.disconnect()
                partial.delete()
                synchronized(this) { if (active === job) active = null }
            }
        }
    }
}
