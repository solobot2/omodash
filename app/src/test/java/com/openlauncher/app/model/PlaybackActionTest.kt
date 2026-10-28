package com.openlauncher.app.model

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackActionTest {
    @Test fun aPlayingRadioWithStopButNoPauseUsesStop() {
        assertEquals(PlaybackAction.STOP, playbackActionFor(PlaybackState.STATE_PLAYING, PlaybackState.ACTION_STOP))
    }
    @Test fun aMetadataOnlySourceDoesNotPretendToSupportPlaybackControl() {
        assertEquals(PlaybackAction.DISABLED, playbackActionFor(PlaybackState.STATE_PLAYING, 0))
        assertEquals(PlaybackAction.DISABLED, playbackActionFor(PlaybackState.STATE_PAUSED, PlaybackState.ACTION_STOP))
    }
    @Test fun aPausedPlayerUsesPlayRatherThanStop() {
        assertEquals(PlaybackAction.PLAY, playbackActionFor(PlaybackState.STATE_PAUSED, PlaybackState.ACTION_PLAY or PlaybackState.ACTION_STOP))
    }
}
