package com.openlauncher.app.model

import android.media.session.PlaybackState

enum class PlaybackAction { DISABLED, PLAY, PAUSE, STOP }

fun playbackActionFor(state: Int?, actions: Long): PlaybackAction {
    fun supports(action: Long) = actions and action != 0L

    return when (state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> when {
            supports(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE) -> PlaybackAction.PAUSE
            supports(PlaybackState.ACTION_STOP) -> PlaybackAction.STOP
            else -> PlaybackAction.DISABLED
        }
        PlaybackState.STATE_NONE,
        PlaybackState.STATE_STOPPED,
        PlaybackState.STATE_PAUSED,
        PlaybackState.STATE_ERROR -> if (supports(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE)) {
            PlaybackAction.PLAY
        } else PlaybackAction.DISABLED
        else -> PlaybackAction.DISABLED
    }
}
