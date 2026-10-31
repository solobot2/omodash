package win.solobot.omodash.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import win.solobot.omodash.BuildConfig
import org.json.JSONObject
import java.net.URI

data class UpdateInfo(
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apkUrl: String,
    val sha256: String,
    val size: Long,
    val certificateSha256: String,
    val notes: String = ""
) {
    fun toJson(): String = JSONObject().apply {
        put("schemaVersion", 1)
        put("applicationId", applicationId)
        put("versionCode", versionCode)
        put("versionName", versionName)
        put("minSdk", minSdk)
        put("apkUrl", apkUrl)
        put("sha256", sha256)
        put("size", size)
        put("certificateSha256", certificateSha256)
        put("notes", notes)
    }.toString()

    companion object {
        fun parse(json: String, context: Context): UpdateInfo {
            val obj = JSONObject(json)
            require(obj.getInt("schemaVersion") == 1) { "Unsupported update format" }
            val info = UpdateInfo(
                obj.getString("applicationId"), obj.getLong("versionCode"), obj.getString("versionName"),
                obj.getInt("minSdk"), obj.getString("apkUrl"), obj.getString("sha256").lowercase(),
                obj.getLong("size"), obj.getString("certificateSha256").lowercase(), obj.optString("notes")
            )
            require(info.applicationId == context.packageName) { "Update belongs to another application" }
            require(info.versionCode in 1..Int.MAX_VALUE.toLong()) { "Invalid update version" }
            require(info.versionName.matches(Regex("\\d+\\.\\d+\\.\\d+"))) { "Invalid release name" }
            require(info.minSdk in 21..Build.VERSION.SDK_INT) { "This update requires a newer Android version" }
            require(info.size in 1..200L * 1024 * 1024) { "Invalid APK size" }
            require(info.sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid APK checksum" }
            require(info.certificateSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid signing certificate" }
            require(allowedUpdateUrl(info.apkUrl)) { "Untrusted update download address" }
            if (!BuildConfig.UPDATE_TEST_MODE) {
                require(URI(info.apkUrl).path == "/solobot2/omodash/releases/download/v${info.versionName}/omodash.apk") {
                    "APK address does not match the release"
                }
            }
            return info
        }
    }
}

internal fun allowedUpdateUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    if (uri.userInfo != null || uri.fragment != null) return@runCatching false
    if (BuildConfig.UPDATE_TEST_MODE) {
        return@runCatching uri.scheme == "http" && uri.host in setOf("10.0.2.2", "127.0.0.1", "localhost")
    }
    uri.scheme == "https" && uri.host == "github.com" && uri.port in listOf(-1, 443) &&
        uri.path.startsWith("/solobot2/omodash/releases/download/")
}.getOrDefault(false)

@Suppress("DEPRECATION")
internal fun installedVersion(context: Context): Pair<Long, String> {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return (if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()) to
        (info.versionName ?: "Unknown")
}
