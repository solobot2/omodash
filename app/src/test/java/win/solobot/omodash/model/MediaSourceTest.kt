package win.solobot.omodash.model

import org.junit.Assert.*
import org.junit.Test

class MediaSourceTest {
    private val radios = setOf("factory.audio", "factory.tuner")
    @Test fun spotifyPlaybackWinsOverRetainedRadioState() {
        assertFalse(showRadioInNowPlaying("Automatic", true, "com.spotify.music", true, radios))
    }
    @Test fun activeFactoryRadioAndIdleRadioUseTheRadioDeck() {
        assertTrue(showRadioInNowPlaying("Automatic", true, "factory.tuner", true, radios))
        assertTrue(showRadioInNowPlaying("Automatic", false, "com.spotify.music", true, radios))
        assertFalse(showRadioInNowPlaying("Automatic", false, null, false, radios))
    }
    @Test fun explicitSourceChoiceRemainsInEffectAcrossSessionChanges() {
        assertTrue(showRadioInNowPlaying("FM/AM Radio", true, "com.spotify.music", false, radios))
        assertTrue(showRadioInNowPlaying("Any Player", true, "factory.audio", true, radios))
    }
    @Test fun allMediaUsesTheSameRadioDeckButKeepsOtherPlayers() {
        assertTrue(showRadioInNowPlaying("All Media", true, "factory.audio", true, radios))
        assertFalse(showRadioInNowPlaying("All Media", true, "com.spotify.music", true, radios))
        assertFalse(showRadioInNowPlaying("All Media", true, "factory.audio", false, radios))
    }
}
