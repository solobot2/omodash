package win.solobot.omodash.util

import org.junit.Assert.*
import org.junit.Test

class LocationFixPolicyTest {
    @Test fun networkCannotReplaceFreshGpsWithAZeroSpeedFix() {
        val policy = LocationFixPolicy()
        assertTrue(policy.accept(true, 10_000, 10_010))
        assertFalse(policy.accept(false, 11_000, 11_010))
        assertTrue(policy.accept(true, 11_000, 11_020))
    }

    @Test fun networkIsAvailableBeforeGpsAndAfterGpsStopsArriving() {
        val policy = LocationFixPolicy()
        assertTrue(policy.accept(false, 9_000, 9_010))
        assertTrue(policy.accept(true, 10_000, 10_010))
        assertTrue(policy.accept(false, 15_000, 15_010))
        assertFalse(policy.accept(true, 14_000, 15_020))
        assertTrue(policy.accept(true, 16_000, 16_010))
    }

    @Test fun cachedFixesUseMeasurementTimeAndNeverRewindLiveTelemetry() {
        val policy = LocationFixPolicy()
        assertTrue(policy.accept(true, 10_000, 20_000))
        assertFalse(policy.hasFreshGps(20_000))
        assertTrue(policy.accept(false, 19_000, 20_000))
        assertFalse(policy.accept(false, 9_000, 20_000))
        policy.reset()
        assertTrue(policy.accept(false, 9_000, 20_000))
    }
}
