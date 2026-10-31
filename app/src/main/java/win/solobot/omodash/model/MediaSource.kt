package win.solobot.omodash.model

/** An active music player wins over an OEM tuner's retained station data in automatic mode. */
fun showRadioInNowPlaying(selection: String, mediaPlaying: Boolean, mediaPackage: String?,
    radioAvailable: Boolean, radioPackages: Set<String>): Boolean = when (selection) {
    "FM/AM Radio" -> true
    "Any Player" -> false
    else -> radioAvailable && !(mediaPlaying && mediaPackage !in radioPackages)
}
