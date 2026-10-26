package com.openlauncher.app.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.concurrent.Executors

internal data class DownloadProgress(val running: Boolean = false, val percent: Int? = null)

internal object UpdateDownloads {
    private val completionExecutor = Executors.newSingleThreadExecutor()

    @Synchronized
    fun start(context: Context, info: UpdateInfo) {
        val store = UpdateStore(context)
        cancel(context)
        store.candidate = info
        store.error = null
        store.verified = false
        store.installRequested = true
        val file = store.apkFile()
        check(!file.exists() || file.delete()) { "Cannot replace the previous download" }
        val request = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("Open Launcher ${info.versionName}")
            .setDescription("Downloading launcher update")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(file))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        store.downloadId = manager(context).enqueue(request)
    }

    @Synchronized
    fun cancel(context: Context) {
        val store = UpdateStore(context)
        if (store.downloadId >= 0) manager(context).remove(store.downloadId)
        if (store.candidate != null) runCatching { store.apkFile().delete() }
        store.downloadId = -1
        store.verified = false
        store.installRequested = false
        store.disarm()
    }

    @Synchronized
    fun refresh(context: Context): DownloadProgress {
        val store = UpdateStore(context)
        val id = store.downloadId
        if (id < 0 || store.verified || store.error != null) return DownloadProgress()
        manager(context).query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) {
                store.error = "The download is no longer available. Please retry."
                store.installRequested = false
                return DownloadProgress()
            }
            when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    try {
                        ApkVerifier.verify(context, store.candidate ?: error("Update details are missing"), store.apkFile())
                        store.verified = true
                    } catch (error: Exception) {
                        store.error = error.message ?: "APK verification failed"
                        store.installRequested = false
                        runCatching { store.apkFile().delete() }
                    }
                    return DownloadProgress()
                }
                DownloadManager.STATUS_FAILED -> {
                    val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    store.error = when (reason) {
                        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage for the update"
                        DownloadManager.ERROR_HTTP_DATA_ERROR -> "Connection interrupted. Please retry."
                        else -> "Download failed ($reason). Please retry."
                    }
                    store.installRequested = false
                    return DownloadProgress()
                }
                else -> {
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    return DownloadProgress(true, if (total > 0) (100L * downloaded / total).toInt().coerceIn(0, 100) else null)
                }
            }
        }
        return DownloadProgress()
    }

    fun completeAsync(context: Context, pending: BroadcastReceiver.PendingResult) {
        completionExecutor.execute {
            try { refresh(context.applicationContext) } finally { pending.finish() }
        }
    }

    private fun manager(context: Context) = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
}

class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -2)
        if (id != UpdateStore(context).downloadId) return
        UpdateDownloads.completeAsync(context, goAsync())
    }
}
