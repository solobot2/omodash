package com.openlauncher.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

internal object ApkVerifier {
    @Suppress("DEPRECATION")
    fun verify(context: Context, info: UpdateInfo, file: File) {
        check(file.isFile && file.length() == info.size) { "The APK download is incomplete" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        check(digest.digest().hex() == info.sha256) { "The downloaded APK checksum does not match" }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("The download is not a valid APK")
        val installed = pm.getPackageInfo(context.packageName, flags)
        check(archive.packageName == context.packageName && archive.packageName == info.applicationId) { "APK package does not match this launcher" }
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        check(code == info.versionCode && archive.versionName == info.versionName) { "APK version does not match the release" }
        check(code > installedVersion(context).first) { "This version is already installed or older" }
        if (Build.VERSION.SDK_INT >= 24) {
            check(archive.applicationInfo?.minSdkVersion == info.minSdk) { "APK compatibility does not match the release" }
        }
        val currentSigners = signers(installed)
        val newSigners = signers(archive)
        check(currentSigners.isNotEmpty() && currentSigners == newSigners) { "APK is not signed with this launcher's signing key" }
        check(info.certificateSha256 in newSigners) { "APK signing certificate does not match the release" }
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
