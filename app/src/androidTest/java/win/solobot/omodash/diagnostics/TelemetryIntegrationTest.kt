package win.solobot.omodash.diagnostics

import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import win.solobot.omodash.MainActivity
import win.solobot.omodash.viewmodel.LauncherViewModel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

/** Real LocationManager delivery through the foreground launcher's normal subscriptions. */
@RunWith(AndroidJUnit4::class)
class TelemetryIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private lateinit var manager: LocationManager
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var vm: LauncherViewModel
    private var locationWasEnabled: Boolean? = null

    private fun shell(command: String): String = automation.executeShellCommand(command).use {
        FileInputStream(it.fileDescriptor).bufferedReader().readText()
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < end) {
            if (predicate()) return
            Thread.sleep(20)
        }
        fail(message)
    }

    @Suppress("DEPRECATION")
    @Before fun setUp() {
        val user = Process.myUid() / 100_000
        locationWasEnabled = shell("cmd location is-location-enabled --user $user").trim() == "true"
        shell("cmd location set-location-enabled true --user $user")
        shell("pm grant --user $user ${context.packageName} android.permission.ACCESS_FINE_LOCATION")
        shell("pm grant --user $user ${context.packageName} android.permission.ACCESS_COARSE_LOCATION")
        shell("appops set ${context.packageName} android:mock_location allow")
        manager = context.getSystemService(LocationManager::class.java)
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            manager.addTestProvider(provider, false, provider == LocationManager.GPS_PROVIDER,
                false, false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
            manager.setTestProviderEnabled(provider, true)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { activity ->
            vm = ViewModelProvider(activity)[LauncherViewModel::class.java]
            vm.updateSettings { copy(onboardingCompleted = true, showWeather = false) }
            vm.startLocationUpdates()
        }
        await("Launcher settings not ready") { vm.settings.value.onboardingCompleted }
        instrumentation.waitForIdleSync()
    }

    @After fun tearDown() {
        scenario?.close()
        if (::manager.isInitialized) for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            runCatching { manager.removeTestProvider(provider) }
        }
        shell("appops set ${context.packageName} android:mock_location default")
        locationWasEnabled?.let { shell("cmd location set-location-enabled $it --user ${Process.myUid() / 100_000}") }
    }

    private fun fix(provider: String = LocationManager.GPS_PROVIDER, speed: Float? = null, bearing: Float? = null) {
        manager.setTestProviderLocation(provider, Location(provider).apply {
            // Keep coordinates identical: a five-metre gate must not hide stopping/turning.
            latitude = 51.0; longitude = 19.0; accuracy = 1f; altitude = 100.0
            time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            speed?.let { this.speed = it }
            bearing?.let { this.bearing = it }
        })
    }

    @Test fun stoppingAndDueNorthArriveWithoutMovingFiveMetres() {
        fix(speed = 10f, bearing = 90f)
        await("First GPS fix missing") { vm.location.value?.speedMps == 10f && vm.compassBearing.value == 90f }
        Thread.sleep(1_100)
        fix(speed = 0f, bearing = 0f)
        await("Stop and north were filtered out") { vm.location.value?.speedMps == 0f && vm.compassBearing.value == 0f }
    }

    @Test fun networkFixDoesNotZeroFreshGpsSpeedOrCourse() {
        fix(speed = 7f, bearing = 110f)
        await("GPS fix missing") { vm.location.value?.speedMps == 7f }
        fix(provider = LocationManager.NETWORK_PROVIDER)
        await("Network callback missing") { TelemetryDiagnostics.snapshot().getLong("networkCallbacks") > 0 }
        assertEquals(7f, vm.location.value!!.speedMps, 0f)
        assertEquals(110f, vm.compassBearing.value, 0f)
    }

    @Test fun repeatedStartsStayBoundedAndBackgroundRemovesSubscriptions() {
        val registrations = TelemetryDiagnostics.snapshot().getInt("providerRegistrations")
        assertEquals(2, registrations)
        scenario!!.onActivity { repeat(20) { vm.startLocationUpdates() } }
        assertEquals(registrations, TelemetryDiagnostics.snapshot().getInt("providerRegistrations"))
        fix(speed = 3f, bearing = 90f)
        await("GPS fix missing") { vm.location.value?.speedMps == 3f }
        scenario!!.moveToState(Lifecycle.State.CREATED)
        assertFalse(TelemetryDiagnostics.snapshot().getBoolean("active"))
        val callbacks = TelemetryDiagnostics.snapshot().getLong("gpsCallbacks")
        Thread.sleep(1_100)
        fix(speed = 20f, bearing = 180f)
        Thread.sleep(1_100)
        assertEquals(callbacks, TelemetryDiagnostics.snapshot().getLong("gpsCallbacks"))
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        assertTrue(TelemetryDiagnostics.snapshot().getBoolean("active"))
        assertEquals(2, TelemetryDiagnostics.snapshot().getInt("providerRegistrations"))
    }

    @Test fun fiveHzProviderIsDeliveredAtOneHzWithMeasuredCpuCost() {
        val wall = SystemClock.elapsedRealtime()
        val cpu = Process.getElapsedCpuTime()
        repeat(50) { index ->
            fix(speed = 3f + index % 5, bearing = (index * 7 % 360).toFloat())
            Thread.sleep(200)
        }
        instrumentation.waitForIdleSync()
        val elapsed = SystemClock.elapsedRealtime() - wall
        val cpuTime = Process.getElapsedCpuTime() - cpu
        val stats = TelemetryDiagnostics.snapshot()
        val report = JSONObject().put("elapsedMs", elapsed).put("processCpuMs", cpuTime)
            .put("processCpuPercentOneCore", cpuTime * 100.0 / elapsed).put("telemetry", stats)
        File(context.filesDir, "telemetry-emulator-validation.json").writeText(report.toString(2))
        val callbacks = stats.getLong("gpsCallbacks")
        assertTrue("Unexpected GPS event count: $stats", callbacks in 7..13)
        assertTrue("Delivery was not rate limited: $stats", stats.getLong("meanGpsCallbackIntervalMs") >= 800)
        assertEquals(2, stats.getInt("providerRegistrations"))
        assertNotNull(vm.location.value)
    }
}
