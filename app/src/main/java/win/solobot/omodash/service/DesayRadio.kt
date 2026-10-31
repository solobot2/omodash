package win.solobot.omodash.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.session.PlaybackState
import win.solobot.omodash.model.NowPlayingState
import win.solobot.omodash.model.OemMediaSource
import win.solobot.omodash.model.playbackActionFor
import win.solobot.omodash.model.stableArtwork
import android.os.Handler
import android.os.HandlerThread
import win.solobot.omodash.diagnostics.DiagnosticLog
import win.solobot.omodash.viewmodel.LauncherViewModel.HardwareRadioState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Uses the installed OEM's VDB client. No guessed broadcasts or copied vendor binaries. */
class DesayRadio(private val context: Context) {
    companion object {
        // The OEM singleton keeps Handlers across release/init cycles, so its
        // Looper must live for the process rather than for one visible widget.
        private val clientDispatcher by lazy {
            Handler(HandlerThread("Omodash-Desay").apply { start() }.looper).asCoroutineDispatcher()
        }
    }
    private var bus: Any? = null
    private var loader: ClassLoader? = null
    private var attempted = false
    private var retryAt = 0L
    data class MediaSnapshot(val sourceType: Int, val radio: HardwareRadioState?, val track: NowPlayingState)
    private var lastSource: Int? = null
    private var lastTrack: NowPlayingState? = null
    private var artTrack: String? = null
    private var artBytes: ByteArray? = null
    private var artBitmap: Bitmap? = null
    private var artUri: String? = null
    private val commandMutex = Mutex()
    private fun type(name: String) = checkNotNull(loader).loadClass("com.desaysv.ivi.vdb.$name")
    private fun initialize(): Boolean {
        if (bus != null) return true
        if (attempted && android.os.SystemClock.elapsedRealtime() < retryAt) return false
        attempted = true
        retryAt = android.os.SystemClock.elapsedRealtime() + 15_000
        if (runCatching { context.packageManager.getPackageInfo("com.desaysv.ivi.vds.tuner", 0) }.isFailure) return false
        return runCatching {
            loader = context.createPackageContext("com.desaysv.launcher", Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY).classLoader
            val cls = type("client.VDBus")
            val instance = cls.getMethod("getDefault").invoke(null)
            bus = instance
            cls.getMethod("init", Context::class.java).invoke(instance, context)
            val serviceType = type("client.bind.VDServiceDef\$ServiceType")
            for (name in listOf("TUNER", "MEDIA", "RVC", "VEHICLE_HAL")) {
                val value = serviceType.enumConstants!!.first { (it as Enum<*>).name == name }
                cls.getMethod("bindService", serviceType).invoke(instance, value)
            }
            retryAt = 0L
            DiagnosticLog.event("Radio", "Initialized installed Desay VDB client")
            true
        }.getOrElse {
            bus?.let { instance -> runCatching { instance.javaClass.getMethod("release").invoke(instance) } }
            bus = null
            DiagnosticLog.event("Radio", "Desay VDB client unavailable; using media session", it)
            false
        }
    }

    private fun value(event: Int, bean: String): Any? {
        val instance = bus ?: return null
        val eventValue = instance.javaClass.getMethod("getOnce", Int::class.javaPrimitiveType).invoke(instance, event) ?: return null
        return type("event.id.$bean").getMethod("getValue", type("event.VDEvent")).invoke(null, eventValue)
    }

    private fun currentSourceType(): Int? = value(393217, "media.bean.VDMediaType")?.let {
        it.javaClass.getMethod("getMediaType").invoke(it) as Int
    }

    private fun field(bean: Any?, name: String): Any? = bean?.javaClass?.getMethod(name)?.invoke(bean)

    private fun sourceValue(event: Int, beanName: String, source: Int): Any? {
        val bean = type("event.id.$beanName")
        val request = bean.getConstructor().newInstance()
        bean.getMethod("putMediaType", Int::class.javaPrimitiveType).invoke(request, source)
        val eventValue = bean.getMethod("createEvent", Int::class.javaPrimitiveType, bean).invoke(null, event, request)
        val response = bus!!.javaClass.getMethod("getOnce", type("event.VDEvent")).invoke(bus, eventValue) ?: return null
        return bean.getMethod("getValue", type("event.VDEvent")).invoke(null, response)
    }

    suspend fun readMedia(): MediaSnapshot? = withContext(clientDispatcher) {
        if (!initialize()) return@withContext null
        runCatching {
            val source = currentSourceType() ?: return@withContext null
            if (source != lastSource) {
                DiagnosticLog.event("MediaSource", "OEM current source=$source")
                lastSource = source
                lastTrack = null
                artTrack = null
                artBytes = null
                artBitmap = null
                artUri = null
            }
            val radio = if (OemMediaSource.isRadio(source)) runCatching { readRadio(source) }.getOrElse {
                DiagnosticLog.event("MediaSource", "Station information temporarily unavailable; source=$source", it)
                HardwareRadioState(if (source == OemMediaSource.DAB) "DAB" else if (source in setOf(10, 12)) "AM" else "FM", "", isDesay = true)
            } else null
            val track = if (radio != null) {
                val frequency = radio.freq.takeIf { it.isNotBlank() }?.let {
                    "${radio.band} $it ${if (radio.isAm) "kHz" else "MHz"}"
                }.orEmpty()
                val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                NowPlayingState(radio.stationName ?: frequency.ifBlank { radio.band }, frequency,
                    null, isPlaying = radio.isPlaying, controller = null,
                    playbackAction = playbackActionFor(if (radio.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, actions),
                    actions = actions, sourcePackage = OemMediaSource.packageName(source), oemSource = source)
            } else runCatching { readTrack(source) }.getOrElse {
                DiagnosticLog.event("MediaSource", "Track information temporarily unavailable; source=$source", it)
                NowPlayingState(OemMediaSource.label(source), "Track information unavailable", null,
                    isPlaying = false, controller = null, sourcePackage = OemMediaSource.packageName(source), oemSource = source)
            }
            MediaSnapshot(source, radio, track)
        }.getOrElse {
            retryAt = android.os.SystemClock.elapsedRealtime() + 5_000
            DiagnosticLog.event("MediaSource", "OEM media read failed", it)
            null
        }
    }

    private fun readRadio(source: Int): HardwareRadioState {
        if (source == OemMediaSource.DAB) {
            val info = value(1048777, "tuner.bean.dab.VDDABInfo")
            val play = value(1048778, "tuner.bean.dab.VDDABPlay")
            val status = field(field(play, "getPlayStatus"), "getPlayStatus") as? Int
            return HardwareRadioState("DAB", "", stationName = (field(info, "getProgramStationName") as? String)?.trim()?.takeIf { it.isNotEmpty() },
                isPlaying = status == null || status == 1, isDesay = true)
        }
        val info = value(1048577, "tuner.bean.VDFmAmInfo")
        val band = field(info, "getBand") as? Int
        val am = (band != null && band in 5..7) || source == 10 || source == 12
        val freq = field(info, "getFrequency") as? Int ?: 0
        val station = field(field(info, "getRadioText"), "getProgramStationName") as? String
        val play = value(1048579, "tuner.bean.VDFmAmPlay")
        val status = field(play, "getPlayStatus") as? Int
        // Missing station data is not evidence that an explicitly selected radio stopped.
        return HardwareRadioState(if (am) "AM" else "FM", if (freq <= 0) "" else if (am) freq.toString() else (freq / 1000f).toString(),
            stationName = station?.trim { it.isWhitespace() || it == 0.toChar() }?.takeIf { it.isNotEmpty() }, isPlaying = status == null || status == 1, isDesay = true)
    }

    private fun readTrack(source: Int): NowPlayingState {
        val item = sourceValue(393218, "media.bean.VDMediaItem", source)
        val info = field(item, "getMediaInfo")
        val play = sourceValue(393220, "media.bean.VDMediaPlay", source)
        val time = sourceValue(393221, "media.bean.VDMediaPlayTime", source)
        val sameSource = lastTrack?.oemSource == source
        val title = (field(info, "getTitle") as? String)?.takeIf { it.isNotBlank() }
            ?: lastTrack?.takeIf { sameSource }?.title ?: OemMediaSource.label(source)
        val artist = (field(info, "getArtist") as? String)?.takeIf { it.isNotBlank() }
            ?: lastTrack?.takeIf { sameSource && it.title == title }?.artist.orEmpty()
        val mediaId = (field(info, "getFileUrl") as? String)?.takeIf { it.isNotBlank() }
        val key = "$source|${mediaId ?: "$title|$artist"}"
        val sameTrack = key == artTrack
        if (!sameTrack) { artTrack = key; artBytes = null; artBitmap = null; artUri = null }
        val bytes = field(info, "getAlbumBuffer") as? ByteArray
        if (bytes != null && bytes.isNotEmpty() && (artBytes == null || !bytes.contentEquals(artBytes))) {
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            artBitmap = stableArtwork(artBitmap, decoded, sameTrack = true)
            artBytes = bytes
        }
        (field(info, "getAlbumImgUrl") as? String)?.takeIf { it.isNotBlank() }?.let { artUri = it }
        val status = field(play, "getPlayStatus") as? Int
        val playing = status == 1
        var actions = if (source in 2..4 || source in setOf(13, 17, 18, 19, 20, 29, 32))
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS else 0L
        if (OemMediaSource.canSeek(source)) actions = actions or PlaybackState.ACTION_SEEK_TO
        val position = (field(time, "getPosition") as? Number)?.toLong()?.coerceAtLeast(0)
            ?: lastTrack?.takeIf { sameTrack }?.let {
                win.solobot.omodash.model.playbackPosition(it.positionMs, it.durationMs, it.positionUpdatedAtMs,
                    android.os.SystemClock.elapsedRealtime(), it.isPlaying, it.playbackSpeed)
            } ?: 0L
        val duration = (field(time, "getDuration") as? Number)?.toLong()?.coerceAtLeast(0)
            ?: lastTrack?.takeIf { sameTrack }?.durationMs ?: 0L
        if (!sameTrack) DiagnosticLog.event("MediaSource", "OEM track ready source=$source, metadata=${info != null}, artwork=${artBitmap != null || artUri != null}, duration=$duration")
        val sampleTime = if (sameTrack && lastTrack?.positionMs == position && lastTrack?.isPlaying == playing)
            lastTrack!!.positionUpdatedAtMs else android.os.SystemClock.elapsedRealtime()
        return NowPlayingState(title, artist, artBitmap, artUri, playing, null,
            playbackActionFor(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, actions), actions,
            sourcePackage = OemMediaSource.packageName(source), oemSource = source, durationMs = duration, positionMs = position,
            positionUpdatedAtMs = sampleTime, playbackSpeed = if (playing) 1f else 0f, mediaId = mediaId)
            .also { lastTrack = it }
    }

    private fun mediaEvent(source: Int, action: Int, reason: Int? = null, positionMs: Long? = null): Any {
        val bean = type("event.id.media.bean.VDMediaPlay")
        val payload = bean.getConstructor().newInstance()
        bean.getMethod("putMediaType", Int::class.javaPrimitiveType).invoke(payload, source)
        bean.getMethod("putPlayAction", Int::class.javaPrimitiveType).invoke(payload, action)
        if (reason != null || positionMs != null) {
            val info = type("event.id.media.bean.VDMediaPlayActionInfo")
            val actionInfo = info.getConstructor().newInstance()
            if (reason != null) info.getMethod("putReason", Int::class.javaPrimitiveType).invoke(actionInfo, reason)
            if (positionMs != null) info.getMethod("putSeekPosition", Int::class.javaPrimitiveType)
                .invoke(actionInfo, positionMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            bean.getMethod("putMediaPlayActionInfo", info).invoke(payload, actionInfo)
        }
        return checkNotNull(bean.getMethod("createEvent", Int::class.javaPrimitiveType, bean).invoke(null, 393220, payload))
    }

    private fun sendMediaEvent(event: Any) {
        bus!!.javaClass.getMethod("set", type("event.VDEvent")).invoke(bus, event)
    }

    /** Same source-specific media events as the factory launcher; never targets a stale source. */
    suspend fun mediaCommand(source: Int, action: Int, positionMs: Long? = null): Boolean = withContext(clientDispatcher) { commandMutex.withLock { runCatching {
        check(action in setOf(1, 2, 3, 4, 5, 9))
        check(initialize() && currentSourceType() == source) { "The media source changed" }
        if (action == 9) check(OemMediaSource.canSeek(source) && positionMs != null)
        if (source in setOf(OemMediaSource.ANDROID_AUTO, OemMediaSource.CARPLAY)) {
            // Native projection callbacks require both key-down and key-up reasons.
            try {
                sendMediaEvent(mediaEvent(source, action, reason = 10))
                delay(50)
            } finally {
                withContext(NonCancellable) { sendMediaEvent(mediaEvent(source, action, reason = 11)) }
            }
        } else sendMediaEvent(mediaEvent(source, action, positionMs = positionMs))
        DiagnosticLog.event("MediaCommand", "Requested OEM media source=$source action=$action")
        true
    }.getOrElse { DiagnosticLog.event("MediaCommand", "OEM media command failed", it); false } } }

    /** Constants verified in the installed launcher's VDValueTuner.FmAmPlayAction. */
    suspend fun command(action: Int): Boolean = withContext(clientDispatcher) { runCatching {
        check(action in setOf(2, 3, 6, 7))
        check(initialize())
        val source = currentSourceType()
        check(source != null && OemMediaSource.isRadio(source)) { "Radio is no longer the active source" }
        if (source == OemMediaSource.DAB) {
            val actionType = type("event.id.tuner.bean.dab.VDDABPlayAction")
            val dabAction = actionType.getConstructor().newInstance()
            actionType.getMethod("putPlayAction", Int::class.javaPrimitiveType).invoke(dabAction, action)
            val bean = type("event.id.tuner.bean.dab.VDDABPlay")
            val payload = bean.getConstructor().newInstance()
            bean.getMethod("putPlayAction", actionType).invoke(payload, dabAction)
            val event = bean.getMethod("createEvent", Int::class.javaPrimitiveType, bean).invoke(null, 1048778, payload)
            bus!!.javaClass.getMethod("set", type("event.VDEvent")).invoke(bus, event)
            return@runCatching true
        }
        val bean = type("event.id.tuner.bean.VDFmAmPlay")
        val payload = bean.getConstructor().newInstance()
        bean.getMethod("putPlayAction", Int::class.javaPrimitiveType).invoke(payload, action)
        val event = bean.getMethod("createEvent", Int::class.javaPrimitiveType, bean).invoke(null, 1048579, payload)
        bus!!.javaClass.getMethod("set", type("event.VDEvent")).invoke(bus, event)
        DiagnosticLog.event("MediaCommand", "Requested Desay FM/AM action=$action")
        true
    }.getOrElse { DiagnosticLog.event("MediaCommand", "Desay radio command failed", it); false } }

    /** Factory media OPEN_SOURCE; radio and music share the same visible audio APK. */
    suspend fun openAudioSource(mediaType: Int, view: Int = 1, useCurrentSource: Boolean = false): Boolean = withContext(clientDispatcher) { runCatching {
        require(mediaType > 0)
        require(view in 1..3)
        check(initialize()) { "Factory audio controls are unavailable" }
        val selected = currentSourceType()
        val requestedType = if (useCurrentSource) checkNotNull(selected) { "The media source is unavailable" }
            else if (mediaType == 8 && selected != null && OemMediaSource.isRadio(selected)) selected else mediaType
        require(requestedType > 0)
        val bean = type("event.id.media.bean.VDMediaPlay")
        val payload = bean.getConstructor().newInstance()
        bean.getMethod("putMediaType", Int::class.javaPrimitiveType).invoke(payload, requestedType)
        bean.getMethod("putPlayAction", Int::class.javaPrimitiveType).invoke(payload, 6)
        val info = type("event.id.media.bean.VDMediaPlayActionInfo")
        val actionInfo = info.getConstructor().newInstance()
        info.getMethod("putReason", Int::class.javaPrimitiveType).invoke(actionInfo, 8)
        info.getMethod("putDisPlayView", Int::class.javaPrimitiveType).invoke(actionInfo, 1)
        info.getMethod("putIntent", Int::class.javaPrimitiveType).invoke(actionInfo, view)
        bean.getMethod("putMediaPlayActionInfo", info).invoke(payload, actionInfo)
        val event = bean.getMethod("createEvent", Int::class.javaPrimitiveType, bean).invoke(null, 393220, payload)
        bus!!.javaClass.getMethod("set", type("event.VDEvent")).invoke(bus, event)
        DiagnosticLog.event("Launch", "Requested factory audio source $requestedType view=$view")
        true
    }.getOrElse { DiagnosticLog.event("Launch", "Factory audio source request failed", it); false } }

    /** Same AVM_ICON_PRESS event as the factory launcher's camera shortcut. */
    suspend fun openCamera(): Boolean = withContext(clientDispatcher) { runCatching {
        check(initialize()) { "Factory vehicle controls are unavailable" }
        val speedEvent = bus!!.javaClass.getMethod("getOnce", Int::class.javaPrimitiveType).invoke(bus, 519)
        val speedPayload = speedEvent?.javaClass?.getMethod("getPayload")?.invoke(speedEvent) as? android.os.Bundle
        val speedKey = type("event.id.vehicle.VDKeyVehicleHal").getField("DOUBLE_VECTOR").get(null) as String
        val speed = speedPayload?.getDoubleArray(speedKey)?.firstOrNull()
        check(speed != null && speed.isFinite() && kotlin.math.abs(speed * 3.6) < 30) { "Factory camera requires vehicle speed below 30 km/h" }
        val eventType = type("event.VDEvent")
        val event = eventType.getConstructor(Int::class.javaPrimitiveType, android.os.Bundle::class.java)
            .newInstance(786834, android.os.Bundle())
        bus!!.javaClass.getMethod("set", eventType).invoke(bus, event)
        DiagnosticLog.event("Camera", "Requested factory AVM camera")
        true
    }.getOrElse { DiagnosticLog.event("Camera", "Factory camera request failed", it); false } }

    suspend fun close() = withContext(NonCancellable + clientDispatcher) { commandMutex.withLock {
        bus?.let { runCatching { it.javaClass.getMethod("release").invoke(it) } }
        bus = null
        attempted = false
        retryAt = 0L
        lastSource = null
        lastTrack = null
        artTrack = null
        artBytes = null
        artBitmap = null
        artUri = null
    }
    }
}
