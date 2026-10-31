package win.solobot.omodash.model

/** VDValueMedia.MediaType from this vehicle's installed OEM client. */
object OemMediaSource {
    const val BLUETOOTH = 13
    const val CARPLAY = 18
    const val ANDROID_AUTO = 20
    const val DAB = 24
    fun isRadio(type: Int) = type in 8..12 || type == DAB
    fun canSeek(type: Int) = type in 2..4
    fun packageName(type: Int): String? = when (type) {
        BLUETOOTH -> "com.android.bluetooth"
        CARPLAY -> "com.desaysv.vehicle.carplayapp"
        ANDROID_AUTO -> "com.google.android.projection.sink"
        in 2..4, in 8..12, DAB -> "com.desaysv.svaudioapp"
        else -> null
    }
    fun label(type: Int): String = when (type) {
        BLUETOOTH -> "Bluetooth"
        CARPLAY -> "CarPlay"
        ANDROID_AUTO -> "Android Auto"
        DAB -> "DAB"
        in 8..12 -> "Radio"
        in 2..4 -> "Music"
        else -> "Media"
    }
}
