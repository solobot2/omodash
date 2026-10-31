package win.solobot.omodash.model

import android.graphics.Bitmap

/** Parcelled copies of the same cover must not restart the image request. */
fun stableArtwork(previous: Bitmap?, incoming: Bitmap?, sameTrack: Boolean): Bitmap? {
    if (!sameTrack || previous == null) return incoming
    if (incoming == null) return previous
    return if (previous === incoming || runCatching { previous.sameAs(incoming) }.getOrDefault(false)) previous else incoming
}

fun playbackPosition(positionMs: Long, durationMs: Long, updatedAtMs: Long, nowMs: Long,
    playing: Boolean, speed: Float): Long {
    val elapsed = if (playing && updatedAtMs > 0 && speed > 0) ((nowMs - updatedAtMs).coerceAtLeast(0) * speed).toLong() else 0L
    val position = (positionMs.coerceAtLeast(0) + elapsed).coerceAtLeast(0)
    return if (durationMs > 0) position.coerceAtMost(durationMs) else position
}
