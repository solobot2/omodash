package win.solobot.omodash.model

data class RadioMetadata(val band: String, val frequency: String?, val station: String?)

/** Digits in RDS names (Radio 357, RMF FM, ZET 2026) are not tuner frequencies. */
fun radioMetadata(title: String, artist: String): RadioMetadata {
    val frequency = "(\\d{2,4}(?:[.,]\\d{1,3})?)"
    val marked = Regex("""\b(FM[123]?|AM)\s+$frequency\b|\b$frequency\s*(MHz|kHz)\b""", RegexOption.IGNORE_CASE)
    val bare = Regex("^\\s*$frequency\\s*$")
    val fields = listOf(title, artist).filter { it.isNotBlank() && it != "Unknown" }
    val match = fields.firstNotNullOfOrNull { text ->
        (marked.find(text) ?: bare.matchEntire(text))?.let { text to it }
    }
    val number = match?.second?.groupValues?.drop(1)?.firstOrNull { it.replace(',', '.').toFloatOrNull() != null }
    val value = number?.replace(',', '.')?.toFloatOrNull()
    val am = match?.second?.value?.contains("AM", true) == true || match?.second?.value?.contains("kHz", true) == true
    val valid = value != null && (if (am) value in 520f..1710f else value in 65f..108f || (match?.second?.value?.contains("MHz", true) != true && value in 520f..1710f))
    val station = fields.map { text ->
        if (valid && text == match?.first) text.replaceRange(match.second.range, "").trim(' ', '-', '|', '/', '•') else text
    }.filter { it.isNotBlank() }.distinct().joinToString(" · ").ifBlank { null }
    return RadioMetadata(if (am || (valid && value!! >= 520f)) "AM" else "FM", if (valid) number?.replace(',', '.') else null, station)
}
