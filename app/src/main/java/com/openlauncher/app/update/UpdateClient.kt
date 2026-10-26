package com.openlauncher.app.update

import android.content.Context
import com.openlauncher.app.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object UpdateClient {
    private fun readJson(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Omodash-updater")
            when (connection.responseCode) {
                404 -> throw NoReleaseException()
                403, 429 -> error("GitHub request limit reached. Try again later.")
            }
            check(connection.responseCode == 200) { "Update server returned HTTP ${connection.responseCode}" }
            val bytes = connection.inputStream.use { it.readBytesLimited(512 * 1024) }
            return bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    fun latest(context: Context): UpdateInfo {
        if (BuildConfig.UPDATE_TEST_MODE) return UpdateInfo.parse(readJson(BuildConfig.UPDATE_FEED_URL), context)
        val release = JSONObject(readJson(BuildConfig.UPDATE_FEED_URL))
        check(!release.optBoolean("draft") && !release.optBoolean("prerelease")) { "Release is not stable" }
        val assets = release.getJSONArray("assets")
        val byName = (0 until assets.length()).map { assets.getJSONObject(it) }.associateBy { it.getString("name") }
        val manifestUrl = byName["update.json"]?.getString("browser_download_url")
            ?: error("This release has no update manifest")
        check(allowedUpdateUrl(manifestUrl)) { "Untrusted update manifest address" }
        val info = UpdateInfo.parse(readJson(manifestUrl), context)
        check(release.getString("tag_name") == "v${info.versionName}") { "Release and APK versions do not match" }
        check(byName["omodash.apk"]?.getString("browser_download_url") == info.apkUrl) { "APK is missing from this release" }
        return info.copy(notes = release.optString("body").take(16_000))
    }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        check(output.size() + count <= limit) { "Update metadata is too large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal class NoReleaseException : Exception("No published updates yet")
