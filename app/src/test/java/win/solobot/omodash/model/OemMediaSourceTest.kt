package win.solobot.omodash.model

import org.junit.Assert.*
import org.junit.Test

class OemMediaSourceTest {
    private val radioPackages = setOf("com.desaysv.svaudioapp")

    @Test fun explicitSourceWinsOverRetainedRadioMetadataAndSessionFlags() {
        for (source in listOf(2, 3, 4, 13, 18, 20)) {
            assertFalse(showRadioInNowPlaying("All Media", true, "com.desaysv.svaudioapp", true, radioPackages, source))
        }
        for (source in listOf(8, 9, 10, 11, 12, 24)) {
            assertTrue(showRadioInNowPlaying("All Media", false, null, true, radioPackages, source))
        }
        assertFalse(showRadioInNowPlaying("Automatic", false, null, true, radioPackages, 1))
    }

    @Test fun onlyTheSupportedFactorySourcesAllowAbsoluteSeeking() {
        for (source in listOf(2, 3, 4)) assertTrue(OemMediaSource.canSeek(source))
        for (source in listOf(9, 13, 17, 18, 19, 20, 24)) assertFalse(OemMediaSource.canSeek(source))
        assertEquals("com.google.android.projection.sink", OemMediaSource.packageName(20))
        assertEquals("com.android.bluetooth", OemMediaSource.packageName(13))
    }

    @Test fun timelineAdvancesOnlyWhilePlayingAndStaysWithinTheTrack() {
        assertEquals(2500L, playbackPosition(1000, 10_000, 100, 1100, true, 1.5f))
        assertEquals(1000L, playbackPosition(1000, 10_000, 100, 1100, false, 1.5f))
        assertEquals(1500L, playbackPosition(1000, 1500, 100, 1100, true, 1f))
        assertEquals(1000L, playbackPosition(1000, 10_000, 2000, 1100, true, 1f))
        assertEquals(0L, playbackPosition(-1, 0, 0, 1100, false, 0f))
    }
}
