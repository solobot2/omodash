package win.solobot.omodash.service

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import win.solobot.omodash.model.NowPlayingState
import win.solobot.omodash.model.playbackActionFor
import win.solobot.omodash.diagnostics.DiagnosticLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MediaListenerService : NotificationListenerService() {

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val controllers = linkedMapOf<android.media.session.MediaSession.Token, MediaController>()
    private var observingSessions = false
    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { refreshNowPlaying() }


    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refreshNowPlaying()
        override fun onMetadataChanged(metadata: MediaMetadata?)   = refreshNowPlaying(forceMetadata = true)
        override fun onSessionDestroyed()                          = refreshNowPlaying()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        isConnected.value = true
        DiagnosticLog.event("Media", "Notification listener connected")
        val manager = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager
        runCatching {
            manager?.addOnActiveSessionsChangedListener(sessionListener,
                ComponentName(this, MediaListenerService::class.java), handler)
            observingSessions = manager != null
        }.onFailure { DiagnosticLog.event("Media", "Cannot observe media session changes", it) }
        refreshNowPlaying()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        isConnected.value = false
        DiagnosticLog.event("Media", "Notification listener disconnected")
        clearControllers()
        _nowPlaying.value = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?)  { refreshNowPlaying() }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { refreshNowPlaying() }

    override fun onDestroy() {
        instance = null
        isConnected.value = false
        clearControllers()
        // Clear the static flow so the UI doesn't keep showing a dead session
        // (and pinning its album-art bitmap) after the service is killed
        _nowPlaying.value = null
        super.onDestroy()
    }

    private fun clearControllers() {
        if (observingSessions) {
            (getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager)
                ?.removeOnActiveSessionsChangedListener(sessionListener)
            observingSessions = false
        }
        controllers.values.forEach { runCatching { it.unregisterCallback(controllerCallback) } }
        controllers.clear()
    }

    private fun refreshNowPlaying(forceMetadata: Boolean = false) {
        val msm = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        val sessions = try {
            msm.getActiveSessions(ComponentName(this, MediaListenerService::class.java))
        } catch (error: SecurityException) {
            DiagnosticLog.event("Media", "Cannot query active media sessions", error)
            emptyList()
        }
        val tokens = sessions.map { it.sessionToken }.toSet()
        controllers.keys.filter { it !in tokens }.forEach { token ->
            controllers.remove(token)?.unregisterCallback(controllerCallback)
        }
        sessions.forEach { controller ->
            if (controller.sessionToken !in controllers) {
                controllers[controller.sessionToken] = controller
                controller.registerCallback(controllerCallback, handler)
            }
        }
        val active = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { it.playbackState?.state in setOf(PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING) }
            ?: sessions.firstOrNull()
        val controller = active?.let { controllers[it.sessionToken] }
        if (controller?.sessionToken != _nowPlaying.value?.controller?.sessionToken) {
            DiagnosticLog.event("Media", "Selected session ${controller?.packageName ?: "none"}")
        }
        updateFromController(controller, forceMetadata)
    }

    private fun updateFromController(controller: MediaController?, forceMetadata: Boolean) {
        if (controller == null) { _nowPlaying.value = null; return }
        val meta = controller.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: "Unknown"
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        // Prefer the largest bitmap the source provides; many apps put a
        // downscaled image in ALBUM_ART and the full one in ART (or vice versa)
        val art = try {
            listOfNotNull(
                meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART),
                meta?.getBitmap(MediaMetadata.METADATA_KEY_ART),
                meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ).maxByOrNull { it.width * it.height }
        } catch (_: Exception) { null }
        val artUri = meta?.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        val playbackState = controller.playbackState
        val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
        val playbackAction = playbackActionFor(playbackState?.state, playbackState?.actions ?: 0L)
        val actions = playbackState?.actions ?: 0L

        // Skip redundant emissions: metadata bitmaps parcel into fresh instances on
        // every read, so a plain data-class compare would never match and every
        // notification would force a recomposition + art redraw.
        val prev = _nowPlaying.value
        if (!forceMetadata && prev != null &&
            prev.controller?.sessionToken == controller.sessionToken &&
            prev.title == title && prev.artist == artist &&
            prev.isPlaying == isPlaying && prev.playbackAction == playbackAction && prev.actions == actions && prev.artUri == artUri &&
            (prev.albumArt != null) == (art != null)
        ) return

        _nowPlaying.value = NowPlayingState(
            title      = title,
            artist     = artist,
            albumArt   = art,
            artUri     = artUri,
            isPlaying  = isPlaying,
            controller = controller,
            playbackAction = playbackAction,
            actions = actions
        )
    }

    companion object {
        private val _nowPlaying = MutableStateFlow<NowPlayingState?>(null)
        val nowPlaying: StateFlow<NowPlayingState?> = _nowPlaying
        val isConnected = MutableStateFlow(false)

        @Volatile private var instance: MediaListenerService? = null
        fun requestRefresh() { instance?.refreshNowPlaying() }
    }
}
