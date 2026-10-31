package win.solobot.omodash.service

import android.content.Context
import win.solobot.omodash.diagnostics.DiagnosticLog
import win.solobot.omodash.viewmodel.LauncherViewModel.HardwareRadioState

/** Uses the installed OEM's VDB client. No guessed broadcasts or copied vendor binaries. */
class DesayRadio(private val context: Context) {
    private var bus: Any? = null
    private var loader: ClassLoader? = null
    private var attempted = false
    private var retryAt = 0L
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

    @Synchronized fun read(): HardwareRadioState? {
        if (android.os.SystemClock.elapsedRealtime() < retryAt && bus != null) return null
        if (!initialize()) return null
        retryAt = 0L
        return runCatching {
            val source = value(393217, "media.bean.VDMediaType") ?: return null
            if ((source.javaClass.getMethod("getMediaType").invoke(source) as Int) !in setOf(8, 9, 10, 11, 12)) return null
            val info = value(1048577, "tuner.bean.VDFmAmInfo") ?: return null
            val band = info.javaClass.getMethod("getBand").invoke(info) as Int
            val freq = info.javaClass.getMethod("getFrequency").invoke(info) as Int
            if (freq <= 0) return null
            val am = band in 5..7
            val rds = info.javaClass.getMethod("getRadioText").invoke(info)
            val station = rds?.javaClass?.getMethod("getProgramStationName")?.invoke(rds) as? String
            val play = value(1048579, "tuner.bean.VDFmAmPlay")
            val playing = play?.javaClass?.getMethod("getPlayStatus")?.invoke(play) == 1
            HardwareRadioState(if (am) "AM" else "FM", if (am) freq.toString() else (freq / 1000f).toString(),
                stationName = station?.takeIf { it.isNotBlank() }, isPlaying = playing, isDesay = true)
        }.getOrElse {
            retryAt = android.os.SystemClock.elapsedRealtime() + 5_000
            DiagnosticLog.event("Radio", "Desay VDB read failed; using media session", it)
            null
        }
    }

    /** Constants verified in the installed launcher's VDValueTuner.FmAmPlayAction. */
    @Synchronized fun command(action: Int): Boolean = runCatching {
        check(action in setOf(2, 3, 6, 7))
        check(read() != null) { "Desay FM/AM is no longer the active source" }
        val bean = type("event.id.tuner.bean.VDFmAmPlay")
        val payload = bean.getConstructor().newInstance()
        bean.getMethod("putPlayAction", Int::class.javaPrimitiveType).invoke(payload, action)
        val event = bean.getMethod("createEvent", Int::class.javaPrimitiveType, bean).invoke(null, 1048579, payload)
        bus!!.javaClass.getMethod("set", type("event.VDEvent")).invoke(bus, event)
        DiagnosticLog.event("MediaCommand", "Requested Desay FM/AM action=$action")
        true
    }.getOrElse { DiagnosticLog.event("MediaCommand", "Desay radio command failed", it); false }

    /** Factory media OPEN_SOURCE; radio and music share the same visible audio APK. */
    @Synchronized fun openAudioSource(mediaType: Int): Boolean = runCatching {
        require(mediaType in setOf(2, 8))
        check(initialize()) { "Factory audio controls are unavailable" }
        val bean = type("event.id.media.bean.VDMediaPlay")
        val payload = bean.getConstructor().newInstance()
        bean.getMethod("putMediaType", Int::class.javaPrimitiveType).invoke(payload, mediaType)
        bean.getMethod("putPlayAction", Int::class.javaPrimitiveType).invoke(payload, 6)
        val info = type("event.id.media.bean.VDMediaPlayActionInfo")
        val actionInfo = info.getConstructor().newInstance()
        info.getMethod("putReason", Int::class.javaPrimitiveType).invoke(actionInfo, 8)
        info.getMethod("putDisPlayView", Int::class.javaPrimitiveType).invoke(actionInfo, 1)
        info.getMethod("putIntent", Int::class.javaPrimitiveType).invoke(actionInfo, 1)
        bean.getMethod("putMediaPlayActionInfo", info).invoke(payload, actionInfo)
        val event = bean.getMethod("createEvent", Int::class.javaPrimitiveType, bean).invoke(null, 393220, payload)
        bus!!.javaClass.getMethod("set", type("event.VDEvent")).invoke(bus, event)
        DiagnosticLog.event("Launch", "Requested factory audio source $mediaType")
        true
    }.getOrElse { DiagnosticLog.event("Launch", "Factory audio source request failed", it); false }

    /** Same AVM_ICON_PRESS event as the factory launcher's camera shortcut. */
    @Synchronized fun openCamera(): Boolean = runCatching {
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
    }.getOrElse { DiagnosticLog.event("Camera", "Factory camera request failed", it); false }

    @Synchronized fun close() { bus?.let { runCatching { it.javaClass.getMethod("release").invoke(it) } }; bus = null; attempted = false; retryAt = 0L }
}
