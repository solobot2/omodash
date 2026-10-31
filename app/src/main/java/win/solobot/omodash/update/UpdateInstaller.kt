package win.solobot.omodash.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.os.Build
import win.solobot.omodash.MainActivity

internal object UpdateInstaller {
    fun hasPendingInstallation(context: Context): Boolean {
        val store = UpdateStore(context)
        return store.installRequested &&
            store.candidate?.let { it.versionCode > installedVersion(context).first } == true
    }

    fun canInstall(context: Context): Boolean = Build.VERSION.SDK_INT < 26 || runCatching {
        context.packageManager.canRequestPackageInstalls()
    }.onFailure { win.solobot.omodash.diagnostics.DiagnosticLog.event("UpdateSetup", "Cannot check app installation access", it) }.getOrDefault(false)

    /** Must run on an IO dispatcher; recheck the APK immediately before staging it. */
    fun stage(context: Context) {
        check(canInstall(context)) { "Allow app installations from this launcher first" }
        val store = UpdateStore(context)
        val update = store.candidate ?: error("No update is selected")
        val file = store.apkFile()
        ApkVerifier.verify(context, update, file)
        store.disarm()
        store.error = null
        store.installRequested = false
        if (Build.VERSION.SDK_INT <= 30) {
            // JCT's non-DeviceOwner path on the owner's Android 11 firmware.
            // Keep the file verified and use a temporary read-only content grant.
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
            val confirmation = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newRawUri("Omodash update", uri)
            }
            store.sessionId = -1
            store.legacyInstaller = true
            store.confirmationIntent = confirmation.toUri(Intent.URI_INTENT_SCHEME)
            return
        }
        store.legacyInstaller = false
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setAppLabel(context.applicationInfo.loadLabel(context.packageManager))
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(if (store.autoConfirm) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                    else PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        store.sessionId = id
        try {
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val result = Intent(context, UpdateInstallReceiver::class.java).setAction("${context.packageName}.UPDATE_INSTALL_RESULT")
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val callback = PendingIntent.getBroadcast(context, id, result, flags)
                session.commit(callback.intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(id) }
            store.sessionId = -1
            throw error
        }
    }

    /** Called only while the launcher Settings screen is resumed. */
    @Suppress("DEPRECATION")
    fun launchConfirmation(context: Context) {
        val store = UpdateStore(context)
        val serialized = store.confirmationIntent ?: return
        val pending = Intent.parseUri(serialized, 0)
        if (store.legacyInstaller) {
            val expected = androidx.core.content.FileProvider.getUriForFile(context,
                "${context.packageName}.diagnostics", store.apkFile())
            check(pending.action == Intent.ACTION_INSTALL_PACKAGE && pending.data == expected) { "Unexpected update APK URI" }
            pending.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            pending.clipData = android.content.ClipData.newRawUri("Omodash update", expected)
        }
        val activity = context.packageManager.resolveActivity(pending, 0)?.activityInfo
            ?: error("The system installer is not available")
        val system = activity.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        check(system && activity.packageName in installerPackages) { "Unexpected installation confirmation app" }
        store.confirmationIntent = null
        // Accessibility automation remains restricted to owned PackageInstaller sessions.
        // Android 11's direct installer is confirmed in its system window.
        if (!store.legacyInstaller) store.arm(activity.packageName)
        context.startActivity(pending.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    val installerPackages = setOf("com.android.packageinstaller", "com.google.android.packageinstaller", "com.miui.packageinstaller")

    fun abandon(context: Context) {
        val store = UpdateStore(context)
        if (store.sessionId >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(store.sessionId) }
        store.sessionId = -1
        store.installRequested = false
        store.disarm()
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val store = UpdateStore(context)
        val session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (session < 0 || session != store.sessionId) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmation == null) {
                    store.error = "The system installer did not provide a confirmation screen"
                    store.sessionId = -1
                } else store.confirmationIntent = confirmation.toUri(0)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                store.clearFinishedUpdate()
            }
            else -> {
                store.disarm()
                store.sessionId = -1
                store.installRequested = false
                store.error = if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, 0) == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    "Installation cancelled. You can retry."
                } else "Installation failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "system installer rejected the update"}"
            }
        }
    }
}

class UpdateReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        win.solobot.omodash.util.MediaAccess.ensureConnected(context)
        val store = UpdateStore(context)
        val wasSelfUpdate = (store.sessionId >= 0 || store.legacyInstaller) && store.candidate?.let { it.versionCode <= installedVersion(context).first } == true
        store.clearFinishedUpdate()
        if (wasSelfUpdate) {
            // Some head units allow this. Home remains the fallback when the OS
            // does not allow a receiver to bring an Activity to the foreground.
            runCatching { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
