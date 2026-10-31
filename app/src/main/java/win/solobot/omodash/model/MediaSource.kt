package win.solobot.omodash.model

/** An active music player wins over an OEM tuner's retained station data in automatic mode. */
fun showRadioInNowPlaying(selection: String, mediaPlaying: Boolean, mediaPackage: String?,
    radioAvailable: Boolean, radioPackages: Set<String>, oemSource: Int? = null): Boolean = when {
    selection == "FM/AM Radio" -> true
    oemSource != null -> OemMediaSource.isRadio(oemSource) && radioAvailable
    else -> radioAvailable && !(mediaPlaying && mediaPackage !in radioPackages)
}
