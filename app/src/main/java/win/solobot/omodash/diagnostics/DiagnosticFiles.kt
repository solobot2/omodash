package win.solobot.omodash.diagnostics

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Small bounded journal, kept across process death and APK updates. No preferences are dumped. */
class DiagnosticFiles(private val directory: File, private val maxBytes: Long = 256 * 1024) {
    @Synchronized
    fun record(message: String) {
        directory.mkdirs()
        val current = File(directory, "events.log")
        val text = sanitize(message).take(32 * 1024) + "\n"
        if (current.length() + text.toByteArray(Charsets.UTF_8).size > maxBytes) {
            val previous = File(directory, "events-previous.log")
            previous.delete()
            check(!current.exists() || current.renameTo(previous)) { "Cannot rotate diagnostic log" }
        }
        current.appendText(text)
    }

    @Synchronized
    fun crash(text: String) {
        directory.mkdirs()
        val name = "crash-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}-${UUID.randomUUID()}.txt"
        val file = File(directory, name)
        file.writeText(sanitize(text).take(128 * 1024))
        directory.listFiles().orEmpty().filter { it.name.startsWith("crash-") && it != file }
            .sortedByDescending { it.lastModified() }.drop(2).forEach { it.delete() }
    }

    @Synchronized
    fun snapshot(): Map<String, String> = directory.listFiles().orEmpty()
        .filter { it.name == "events.log" || it.name == "events-previous.log" || it.name.startsWith("crash-") }
        .sortedBy { it.name }.associate { it.name to it.readText().take(maxBytes.toInt()) }

    companion object {
        // Known credential fields occasionally appear in exception URLs / HTTP messages.
        // This is defense in depth; the report never exports settings, notifications or media titles.
        private val secrets = Regex("(?i)([\"']?(?:access_token|refresh_token|token|password|authorization|api_key)[\"']?\\s*[:=]\\s*[\"']?)([^\\s\"'&,}]+)")
        fun sanitize(text: String): String = secrets.replace(
            text.replace(Regex("(?i)Bearer\\s+[a-z0-9._~+/-]+=*"), "Bearer [redacted]")
        ) { it.groupValues[1] + "[redacted]" }
    }
}
