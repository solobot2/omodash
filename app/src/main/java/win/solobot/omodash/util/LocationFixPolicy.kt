package win.solobot.omodash.util

/** Foreground telemetry, not a background tracking or weather polling interval. */
internal class LocationFixPolicy {
    companion object {
        const val GPS_INTERVAL_MS = 1_000L
        const val NETWORK_INTERVAL_MS = 5_000L
        // Let network positioning take over after one network reporting period without GPS.
        const val GPS_FRESHNESS_MS = NETWORK_INTERVAL_MS
    }

    private var selectedAtMs: Long? = null
    private var gpsAtMs: Long? = null

    fun hasFreshGps(nowMs: Long): Boolean = gpsAtMs?.let {
        nowMs - it in 0 until GPS_FRESHNESS_MS
    } == true

    fun accept(gps: Boolean, fixAtMs: Long, nowMs: Long): Boolean {
        if (selectedAtMs?.let { fixAtMs < it } == true) return false
        if (!gps && hasFreshGps(nowMs)) return false
        selectedAtMs = fixAtMs
        if (gps) gpsAtMs = fixAtMs
        return true
    }

    fun reset() { selectedAtMs = null; gpsAtMs = null }
}
