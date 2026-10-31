package win.solobot.omodash.model

import org.junit.Assert.*
import org.junit.Test

class RadioMetadataTest {
    @Test fun stationNamesWithDigitsRemainStationNames() {
        for (name in listOf("Radio 357", "Radio ZET", "RMF FM", "TOP 2026", "Rock 101")) {
            val parsed = radioMetadata(name, "")
            assertEquals(name, parsed.station)
            assertNull(parsed.frequency)
        }
    }
    @Test fun frequencyFromSubtitlePreservesStationTitle() {
        assertEquals(RadioMetadata("FM", "99.9", "Radio 357"), radioMetadata("Radio 357", "FM 99,9"))
        assertEquals(RadioMetadata("FM", "96.0", "RMF FM"), radioMetadata("RMF FM", "96.0 MHz"))
    }
    @Test fun frequenciesMustBeInTheTunerRange() {
        assertEquals(RadioMetadata("FM", "101.1", "WXYZ"), radioMetadata("FM 101.1 WXYZ", ""))
        assertEquals(RadioMetadata("AM", "1040", null), radioMetadata("1040 kHz", ""))
        assertEquals(RadioMetadata("AM", "1040", null), radioMetadata("1040", ""))
        assertEquals(RadioMetadata("FM", null, "FM 2026 · News"), radioMetadata("FM 2026", "News"))
    }
}
