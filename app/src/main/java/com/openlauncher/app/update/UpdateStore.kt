package com.openlauncher.app.update

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.io.File

/** Only transient update state; launcher preferences live in their existing DataStore. */
internal class UpdateStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("self_updates", Context.MODE_PRIVATE)

    var candidate: UpdateInfo?
        get() = prefs.getString("candidate", null)?.let { runCatching { UpdateInfo.parse(it, app) }.getOrNull() }
        set(value) { prefs.edit().putString("candidate", value?.toJson()).commit() }
    var downloadId: Long
        get() = prefs.getLong("download_id", -1)
        set(value) { prefs.edit().putLong("download_id", value).commit() }
    var verified: Boolean
        get() = prefs.getBoolean("verified", false)
        set(value) { prefs.edit().putBoolean("verified", value).commit() }
    var installRequested: Boolean
        get() = prefs.getBoolean("install_requested", false)
        set(value) { prefs.edit().putBoolean("install_requested", value).commit() }
    var error: String?
        get() = prefs.getString("error", null)
        set(value) { prefs.edit().putString("error", value).commit() }
    var autoConfirm: Boolean
        get() = prefs.getBoolean("auto_confirm", true)
        set(value) { prefs.edit().putBoolean("auto_confirm", value).commit() }
    var sessionId: Int
        get() = prefs.getInt("session_id", -1)
        set(value) { prefs.edit().putInt("session_id", value).commit() }
    var confirmationIntent: String?
        get() = prefs.getString("confirmation_intent", null)
        set(value) { prefs.edit().putString("confirmation_intent", value).commit() }

    fun apkFile(): File {
        val directory = File(app.getExternalFilesDir(null) ?: app.filesDir, "updates")
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create the update download folder" }
        return File(directory, "omodash-${candidate?.versionCode ?: 0}.apk")
    }

    fun arm(installerPackage: String) {
        prefs.edit().putString("installer_package", installerPackage)
            .putLong("confirmation_deadline", SystemClock.elapsedRealtime() + 90_000)
            .putInt("confirmation_boot", Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1))
            .putBoolean("awaiting_confirmation", true).commit()
    }

    fun confirmationArmed(): Boolean = autoConfirm && verified && sessionId >= 0 &&
        prefs.getBoolean("awaiting_confirmation", false) &&
        prefs.getInt("confirmation_boot", -2) == Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1) &&
        SystemClock.elapsedRealtime() < prefs.getLong("confirmation_deadline", 0) &&
        candidate?.let { it.versionCode > installedVersion(app).first } == true

    fun mayConfirm(installerPackage: String): Boolean = confirmationArmed() &&
        prefs.getString("installer_package", null) == installerPackage

    fun disarm() {
        prefs.edit().remove("installer_package").remove("confirmation_deadline")
            .remove("confirmation_intent").putBoolean("awaiting_confirmation", false).commit()
    }

    fun clearFinishedUpdate() {
        val info = candidate ?: return
        if (info.versionCode <= installedVersion(app).first) {
            runCatching { apkFile().delete() }
            prefs.edit().remove("candidate").remove("download_id").remove("session_id")
                .remove("error").putBoolean("verified", false).putBoolean("install_requested", false).commit()
            disarm()
        }
    }
}
