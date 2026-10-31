package win.solobot.omodash.model

import android.graphics.Bitmap
import android.media.session.MediaController

data class NowPlayingState(
    val title: String,
    val artist: String,
    val albumArt: Bitmap?,
    // Full-resolution artwork URI when the source app provides one —
    // preferred over the (often downscaled) metadata bitmap
    val artUri: String? = null,
    val isPlaying: Boolean,
    val controller: MediaController?,
    val playbackAction: PlaybackAction = PlaybackAction.DISABLED,
    val actions: Long = 0L,
    val sourcePackage: String? = null,
    val oemSource: Int? = null,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val positionUpdatedAtMs: Long = 0L,
    val playbackSpeed: Float = 0f,
    val mediaId: String? = null
) {
    val packageName: String? get() = sourcePackage ?: controller?.packageName
}
