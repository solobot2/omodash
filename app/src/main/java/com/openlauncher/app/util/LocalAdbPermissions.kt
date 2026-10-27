package com.openlauncher.app.util

import android.content.Context
import android.util.Base64
import com.openlauncher.app.diagnostics.DiagnosticLog
import com.openlauncher.app.diagnostics.DiagnosticReport
import com.tananaev.adblib.AdbBase64
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbCrypto
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Explicit, authenticated setup to 127.0.0.1 only. No arbitrary shell or remote host UI. */
object LocalAdbPermissions {
    fun configure(context: Context, progress: (String) -> Unit): String {
        val app = context.applicationContext
        app.getSharedPreferences("vehicle_permission_setup", Context.MODE_PRIVATE).edit().putBoolean("attempted", true).commit()
        val pkg = app.packageName
        check(pkg.matches(Regex("[a-zA-Z0-9_.]+")))
        val component = MediaAccess.component(app).flattenToString()
        val base64 = AdbBase64 { data -> Base64.encodeToString(data, Base64.NO_WRAP) }
        val directory = File(app.noBackupFilesDir, "local-adb").apply { mkdirs() }
        val privateKey = File(directory, "private.key")
        val publicKey = File(directory, "public.key")
        progress("Preparing this launcher's private debugging key…")
        val crypto = if (privateKey.isFile && publicKey.isFile) AdbCrypto.loadAdbKeyPair(base64, privateKey, publicKey)
            else AdbCrypto.generateAdbKeyPair(base64).also { it.saveAdbKeyPair(privateKey, publicKey) }
        val socket = Socket()
        val worker = Executors.newSingleThreadExecutor()
        try {
            progress("Connecting to this device's local ADB. If Android asks to allow debugging, confirm the key on this device.")
            socket.connect(InetSocketAddress("127.0.0.1", 5555), 4_000)
            socket.soTimeout = 30_000
            val future = worker.submit<String> {
                AdbConnection.create(socket, crypto).use { connection ->
                    connection.connect()
                    fun command(text: String): String {
                        val result = StringBuilder()
                        connection.open("shell:$text; printf '\\n__OMODASH_EXIT:%s\\n' \"\$?\"").use { stream ->
                            while (!result.contains("__OMODASH_EXIT:")) {
                                check(result.length < 16_384) { "Unexpected ADB response size" }
                                result.append(String(stream.read(), Charsets.UTF_8))
                            }
                        }
                        check(Regex("__OMODASH_EXIT:0(?:\\s|$)").containsMatchIn(result)) { result.toString().substringBefore("__OMODASH_EXIT:").take(1000) }
                        return result.toString().substringBefore("__OMODASH_EXIT:").trim()
                    }
                    check(Regex("uid=(?:0|2000)\\b").containsMatchIn(command("id"))) { "Local ADB did not provide the expected shell identity" }
                    val userId = command("am get-current-user").toInt()
                    val commands = listOf(
                        "pm grant --user $userId $pkg android.permission.WRITE_SECURE_SETTINGS",
                        "pm grant --user $userId $pkg android.permission.READ_LOGS",
                        "cmd notification allow_listener $component $userId"
                    )
                    commands.forEachIndexed { index, text ->
                        progress("Configuring launcher permission ${index + 1}/${commands.size}…")
                        runCatching { command(text) }
                            .onSuccess { DiagnosticLog.event("VehicleSetup", "Granted own setup command ${index + 1}") }
                            .onFailure { DiagnosticLog.event("VehicleSetup", "Setup command ${index + 1} was rejected", it) }
                    }
                    if (android.os.Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
                        progress("Android may restart the launcher when installation access changes. Returning to the launcher automatically…")
                        // Changing this app-op kills the app UID on Android 11. Finish media setup first,
                        // then let an independent shell finish the grant and reopen only our Activity.
                        val script = "appops set --user $userId $pkg REQUEST_INSTALL_PACKAGES allow; sleep 1; " +
                            "am start --user $userId -n $pkg/com.openlauncher.app.MainActivity"
                        command("(nohup sh -c '$script' >/dev/null 2>&1 </dev/null &)")
                    }
                    val granted = MediaAccess.isGranted(app)
                    val updates = android.os.Build.VERSION.SDK_INT < 26 || app.packageManager.canRequestPackageInstalls()
                    "Media access: ${if (granted) "granted" else "not granted"}. " +
                        "App installations: ${if (updates) "allowed" else "not allowed"}. " +
                        "System logs: ${if (DiagnosticReport.hasSystemLogs(app)) "available" else "unavailable"}. " +
                        "For automatic update confirmation, press Enable update helper separately."
                }
            }
            return future.get(45, TimeUnit.SECONDS)
        } catch (error: Exception) {
            DiagnosticLog.event("VehicleSetup", "Local ADB setup failed", error)
            return "Could not complete local ADB setup. Ensure JCarTools special access / local ADB is enabled, " +
                "then retry and approve Android's debugging prompt if shown. ${error.cause?.message ?: error.message ?: "Connection timed out"}"
        } finally {
            runCatching { socket.close() }
            worker.shutdownNow()
        }
    }
}
