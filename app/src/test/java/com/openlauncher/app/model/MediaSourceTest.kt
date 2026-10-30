package com.openlauncher.app.model

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
        assertFalse(showRadioInNowPlaying("Any Player", true, "factory.audio", true, radios))
    }
}
