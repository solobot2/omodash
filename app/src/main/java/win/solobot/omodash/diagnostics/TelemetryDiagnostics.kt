package win.solobot.omodash.diagnostics

import android.os.SystemClock
import org.json.JSONObject

/** Counters only: no per-fix logging, coordinates, timers or extra sensor requests. */
object TelemetryDiagnostics {
    private var active = false
    private var gpsIntervalMs = 0L
    private var magneticCompass = false
    private var registrations = 0
    private var gpsCallbacks = 0L
    private var networkCallbacks = 0L
    private var acceptedFixes = 0L
    private var sensorCallbacks = 0L
    private var firstGpsAtMs = 0L
    private var lastGpsAtMs = 0L
    private var maxGpsGapMs = 0L
    private var maxDeliveryDelayMs = 0L

    @Synchronized fun start(intervalMs: Long, hasMagneticCompass: Boolean) {
        active = true
        gpsIntervalMs = intervalMs
        magneticCompass = hasMagneticCompass
        registrations = 0
        gpsCallbacks = 0; networkCallbacks = 0; acceptedFixes = 0; sensorCallbacks = 0
        firstGpsAtMs = 0; lastGpsAtMs = 0; maxGpsGapMs = 0; maxDeliveryDelayMs = 0
    }

    @Synchronized fun registered() { registrations++ }
    @Synchronized fun sensor() { sensorCallbacks++ }
    @Synchronized fun location(gps: Boolean, fixAtMs: Long, nowMs: Long, accepted: Boolean) {
        if (gps) {
            if (gpsCallbacks++ == 0L) firstGpsAtMs = nowMs
            else maxGpsGapMs = maxOf(maxGpsGapMs, nowMs - lastGpsAtMs)
            lastGpsAtMs = nowMs
            maxDeliveryDelayMs = maxOf(maxDeliveryDelayMs, (nowMs - fixAtMs).coerceAtLeast(0))
        } else networkCallbacks++
        if (accepted) acceptedFixes++
    }

    @Synchronized fun stop() { active = false }

    @Synchronized fun snapshot(): JSONObject = JSONObject()
        .put("active", active).put("gpsRequestedIntervalMs", gpsIntervalMs)
        .put("magneticCompassAvailable", magneticCompass).put("providerRegistrations", registrations)
        .put("gpsCallbacks", gpsCallbacks).put("networkCallbacks", networkCallbacks)
        .put("acceptedFixes", acceptedFixes).put("sensorCallbacks", sensorCallbacks)
        .put("meanGpsCallbackIntervalMs", if (gpsCallbacks > 1) (lastGpsAtMs - firstGpsAtMs) / (gpsCallbacks - 1) else JSONObject.NULL)
        .put("maxGpsCallbackGapMs", maxGpsGapMs)
        .put("lastGpsCallbackAgeMs", if (gpsCallbacks > 0) SystemClock.elapsedRealtime() - lastGpsAtMs else JSONObject.NULL)
        .put("maxGpsDeliveryDelayMs", maxDeliveryDelayMs)
}
