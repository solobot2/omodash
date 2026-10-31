package win.solobot.omodash.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import win.solobot.omodash.diagnostics.TelemetryDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,
    val speedMps: Float = 0f
)

class LocationCompassManager(context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager   = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val _location  = MutableStateFlow<LocationData?>(null)
    private val _bearing   = MutableStateFlow(0f)
    val location: StateFlow<LocationData?> = _location
    val bearing: StateFlow<Float> = _bearing

    private val gravity      = FloatArray(3)
    private val geomagnetic  = FloatArray(3)
    private val rotation = FloatArray(9)
    private val inclination = FloatArray(9)
    private val orientation = FloatArray(3)
    private val fixPolicy = LocationFixPolicy()
    private var started = false
    private var sensorsRegistered = false
    private val registeredProviders = mutableSetOf<String>()
    private var gpsBearingAtMs: Long? = null
    private var sensorBearingInitialized = false
    // Circular low-pass filter for smooth bearing (avoids 0°/360° wrap artifacts)
    private var bearingSin   = 0f
    private var bearingCos   = 1f   // initial: pointing north
    private var lastLocationForBearing: Location? = null

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!started) return
            TelemetryDiagnostics.sensor()
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    System.arraycopy(event.values, 0, gravity, 0, 3)
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                }
            }
            if (SensorManager.getRotationMatrix(rotation, inclination, gravity, geomagnetic)) {
                SensorManager.getOrientation(rotation, orientation)
                val azimuthRad = orientation[0].toDouble()
                if (!sensorBearingInitialized) {
                    bearingSin = sin(azimuthRad).toFloat()
                    bearingCos = cos(azimuthRad).toFloat()
                    sensorBearingInitialized = true
                }
                // Circular low-pass filter — correctly handles 0°/360° wrap-around
                val alpha = 0.10f
                bearingSin = alpha * sin(azimuthRad).toFloat() + (1f - alpha) * bearingSin
                bearingCos = alpha * cos(azimuthRad).toFloat() + (1f - alpha) * bearingCos
                // Do not let magnetic samples fight a fresh GPS course on each frame.
                if (gpsBearingAtMs?.let { SystemClock.elapsedRealtime() - it in 0 until LocationFixPolicy.GPS_FRESHNESS_MS } != true) {
                    _bearing.value = ((Math.toDegrees(atan2(bearingSin.toDouble(), bearingCos.toDouble())) + 360) % 360).toFloat()
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = updateLocation(loc)

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        // These only gained default implementations in API 30.
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun updateLocation(loc: Location, live: Boolean = true) {
        if (!started) return
        val gps = loc.provider == LocationManager.GPS_PROVIDER
        val nowMs = SystemClock.elapsedRealtime()
        val fixAtMs = loc.elapsedRealtimeNanos / 1_000_000
        val accepted = fixPolicy.accept(gps, fixAtMs, nowMs)
        if (live) TelemetryDiagnostics.location(gps, fixAtMs, nowMs, accepted)
        if (!accepted) return
        _location.value = LocationData(
            latitude  = loc.latitude,
            longitude = loc.longitude,
            altitude  = loc.altitude,
            accuracy  = loc.accuracy,
            speedMps  = if (loc.hasSpeed()) loc.speed else 0f
        )

        // 1. If GPS has a hardware-computed bearing, use it (works offline)
        if (gps && loc.hasBearing()) {
            _bearing.value = loc.bearing
            gpsBearingAtMs = fixAtMs
            lastLocationForBearing = loc
        } else if (gps) {
            // 2. Math fallback: Calculate bearing between consecutive location points (works offline & sensor-less!)
            val lastLoc = lastLocationForBearing
            if (lastLoc != null) {
                val distance = lastLoc.distanceTo(loc)
                // Ensure the distance is enough to overcome GPS jitter (e.g. 3 meters)
                if (distance > 3f) {
                    val computedBearing = lastLoc.bearingTo(loc)
                    // Normalize bearing to 0-360
                    _bearing.value = (computedBearing + 360f) % 360f
                    gpsBearingAtMs = fixAtMs
                    lastLocationForBearing = loc
                }
            } else {
                lastLocationForBearing = loc
            }
        }
    }

    fun start() {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (!started) {
            started = true
            TelemetryDiagnostics.start(LocationFixPolicy.GPS_INTERVAL_MS, accelerometer != null && magnetometer != null)
        }
        // A lone accelerometer cannot produce a compass; do not subscribe to it needlessly.
        if (!sensorsRegistered && accelerometer != null && magnetometer != null) {
            val acceleration = sensorManager.registerListener(sensorListener, accelerometer, SensorManager.SENSOR_DELAY_UI)
            val magnetic = sensorManager.registerListener(sensorListener, magnetometer, SensorManager.SENSOR_DELAY_UI)
            sensorsRegistered = acceleration && magnetic
            if (!sensorsRegistered) sensorManager.unregisterListener(sensorListener)
        }
        // One callback per second at most. No distance gate: stopping must deliver speed=0,
        // and slow turns must not wait until another five metres have been travelled.
        registerProvider(LocationManager.GPS_PROVIDER, LocationFixPolicy.GPS_INTERVAL_MS, 0f)
        registerProvider(LocationManager.NETWORK_PROVIDER, LocationFixPolicy.NETWORK_INTERVAL_MS, 10f)
    }

    private fun registerProvider(provider: String, intervalMs: Long, distanceM: Float) {
        if (provider in registeredProviders) return
        try {
            if (provider in locationManager.allProviders) {
                locationManager.requestLocationUpdates(provider, intervalMs, distanceM, locationListener, Looper.getMainLooper())
                registeredProviders += provider
                TelemetryDiagnostics.registered()
                locationManager.getLastKnownLocation(provider)?.let { updateLocation(it, live = false) }
            }
        } catch (_: SecurityException) {
            // Location permission may be denied or revoked while the app is open.
        } catch (_: Exception) {}
    }

    fun stop() {
        started = false
        sensorManager.unregisterListener(sensorListener)
        runCatching { locationManager.removeUpdates(locationListener) }
        registeredProviders.clear()
        sensorsRegistered = false
        sensorBearingInitialized = false
        fixPolicy.reset()
        gpsBearingAtMs = null
        lastLocationForBearing = null
        TelemetryDiagnostics.stop()
    }
}
