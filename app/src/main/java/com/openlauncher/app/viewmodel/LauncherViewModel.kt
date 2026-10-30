package com.openlauncher.app.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openlauncher.app.data.AppSettings
import com.openlauncher.app.data.DayNightMode
import com.openlauncher.app.data.DefaultShortcutIcon
import com.openlauncher.app.data.GRID_COLS
import com.openlauncher.app.data.GRID_ROWS
import com.openlauncher.app.data.SettingsRepository
import com.openlauncher.app.data.ShortcutConfig
import com.openlauncher.app.data.SoundPadConfig
import com.openlauncher.app.data.WeatherApi
import com.openlauncher.app.data.activeWidgetIds
import com.openlauncher.app.data.computeWidgetMove
import com.openlauncher.app.data.defaultShortcuts
import com.openlauncher.app.util.SunriseSunset
import com.openlauncher.app.model.AppInfo
import com.openlauncher.app.model.NavDestination
import com.openlauncher.app.model.NowPlayingState
import com.openlauncher.app.model.PlaybackAction
import com.openlauncher.app.model.playbackActionFor
import com.openlauncher.app.model.WeatherState
import com.openlauncher.app.service.MediaListenerService
import com.openlauncher.app.util.LocationCompassManager
import com.openlauncher.app.util.LocationData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SettingsRepository(application)
    private val locationMgr  = LocationCompassManager(application)

    // ── Settings ──────────────────────────────────────────────────────────────
    private val _settingsLoaded = MutableStateFlow(false)
    val settingsLoaded: StateFlow<Boolean> = _settingsLoaded

    val settings: StateFlow<AppSettings> = settingsRepo.settingsFlow
        .onEach { _settingsLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun updateSettings(block: AppSettings.() -> AppSettings) {
        viewModelScope.launch { settingsRepo.updateSettings { it.block() } }
    }

    fun resetSettings() {
        viewModelScope.launch { settingsRepo.resetToDefaults() }
    }

    // ── Navigation ────────────────────────────────────────────────────────────
    private val _nav = MutableStateFlow(NavDestination.HOME)
    val nav: StateFlow<NavDestination> = _nav

    fun navigate(dest: NavDestination) { _nav.value = dest }

    // ── Shortcut picker ───────────────────────────────────────────────────────
    private val _shortcutPickerSlot = MutableStateFlow<Int?>(null)
    val shortcutPickerSlot: StateFlow<Int?> = _shortcutPickerSlot

    fun startShortcutPicker(slot: Int) {
        _shortcutPickerSlot.value = slot
        _nav.value = NavDestination.APP_LIBRARY
    }

    fun assignShortcut(slot: Int, app: AppInfo) {
        updateSettings {
            copy(shortcuts = shortcuts.toMutableList().also { list ->
                while (list.size <= slot) list.add(ShortcutConfig())
                list[slot] = ShortcutConfig(packageName = app.packageName, label = app.appName)
            })
        }
        _shortcutPickerSlot.value = null
        _nav.value = NavDestination.HOME
    }

    fun removeShortcut(slot: Int) {
        updateSettings {
            copy(shortcuts = shortcuts.toMutableList().also { list ->
                if (slot in list.indices) {
                    list[slot] = defaultShortcuts().getOrNull(slot) ?: ShortcutConfig()
                }
            })
        }
    }

    fun reorderShortcut(from: Int, to: Int) {
        updateSettings {
            copy(shortcuts = shortcuts.toMutableList().also { list ->
                val item = list.removeAt(from)
                list.add(to.coerceIn(0, list.size), item)
            })
        }
    }

    fun setShortcutIcon(slot: Int, icon: DefaultShortcutIcon?) {
        updateSettings {
            copy(shortcuts = shortcuts.toMutableList().also { list ->
                if (slot in list.indices) list[slot] = list[slot].copy(customIconOverride = icon)
            })
        }
    }

    fun cancelShortcutPicker() {
        _shortcutPickerSlot.value = null
    }

    // ── CarPlay / Android Auto picker ─────────────────────────────────────────
    enum class AppPickerTarget { CARPLAY, ANDROID_AUTO, RADIO }

    private val _appPickerTarget = MutableStateFlow<AppPickerTarget?>(null)
    val appPickerTarget: StateFlow<AppPickerTarget?> = _appPickerTarget

    fun startCarPlayPicker() {
        _appPickerTarget.value = AppPickerTarget.CARPLAY
        _nav.value = NavDestination.APP_LIBRARY
    }

    fun startAndroidAutoPicker() {
        _appPickerTarget.value = AppPickerTarget.ANDROID_AUTO
        _nav.value = NavDestination.APP_LIBRARY
    }

    fun startRadioPicker() {
        _appPickerTarget.value = AppPickerTarget.RADIO
        _nav.value = NavDestination.APP_LIBRARY
    }

    fun assignPickerApp(app: AppInfo) {
        when (_appPickerTarget.value) {
            AppPickerTarget.CARPLAY      -> updateSettings { copy(carPlayPackage = app.packageName) }
            AppPickerTarget.ANDROID_AUTO -> updateSettings { copy(androidAutoPackage = app.packageName) }
            AppPickerTarget.RADIO        -> updateSettings { copy(radioPackage = app.packageName) }
            null -> {}
        }
        _appPickerTarget.value = null
        _nav.value = NavDestination.HOME
    }

    fun clearRadioApp()        { updateSettings { copy(radioPackage = "") } }

    fun assignCurrentMediaAsRadio() {
        val pkg = nowPlaying.value?.controller?.packageName ?: return
        updateSettings { copy(radioPackage = pkg) }
        com.openlauncher.app.diagnostics.DiagnosticLog.event("Radio", "User assigned current media session $pkg as radio")
        _appPickerTarget.value = null
        _nav.value = NavDestination.HOME
    }

    fun updateWidgetConfig(id: String, spanX: Int, spanY: Int) {
        updateSettings {
            val resized = widgetLayout.map { w ->
                if (w.id == id) w.copy(
                    spanX = spanX.coerceIn(1, GRID_COLS - w.gridX),
                    spanY = spanY.coerceIn(1, GRID_ROWS - w.gridY)
                ) else w
            }
            // Re-run collision resolution so enlarging a widget pushes neighbors
            // aside instead of stacking on top of them
            val activeIds = activeWidgetIds()
            val active    = resized.filter { it.enabled && it.id in activeIds }
            val inactive  = resized.filter { !it.enabled || it.id !in activeIds }
            val target    = active.find { it.id == id }
            copy(widgetLayout = if (target != null)
                computeWidgetMove(active, id, target.gridX, target.gridY) + inactive
            else resized)
        }
    }

    fun moveWidgetConfig(id: String, gridX: Int, gridY: Int) {
        updateSettings {
            val activeIds = activeWidgetIds()
            val active   = widgetLayout.filter { it.enabled && it.id in activeIds }
            val inactive = widgetLayout.filter { !it.enabled || it.id !in activeIds }
            copy(widgetLayout = computeWidgetMove(active, id, gridX, gridY) + inactive)
        }
    }

    fun addWidget(id: String) {
        updateSettings {
            val activeIds = activeWidgetIds()
            var layout    = widgetLayout
            var cell      = freeCellIn(layout, activeIds)

            // If grid is full, shrink the largest multi-cell widget by one span to make room
            if (cell == null) {
                val candidate = layout
                    .filter { it.enabled && it.id in activeIds && it.spanX * it.spanY > 1 }
                    .maxByOrNull { it.spanX * it.spanY }
                if (candidate != null) {
                    layout = layout.map { w ->
                        if (w.id == candidate.id)
                            if (w.spanY > 1) w.copy(spanY = w.spanY - 1) else w.copy(spanX = w.spanX - 1)
                        else w
                    }
                    cell = freeCellIn(layout, activeIds)
                }
            }

            val cell_ = cell ?: return@updateSettings this

            val withShow = when (id) {
                "CLOCK"       -> copy(showClock = true)
                "WEATHER"     -> copy(showWeather = true)
                "NOW_PLAYING" -> copy(showNowPlaying = true)
                "TELEMETRY"   -> copy(showTelemetry = true)
                "ALTIMETER"   -> copy(showAltimeter = true)
                "SPEEDOMETER" -> copy(showSpeedometer = true)
                "VITALS"      -> copy(showVitals = true)
                "TRIP_TRACKER" -> copy(showTripTracker = true)
                "SOUNDBOARD"  -> copy(showSoundboard = true)
                else          -> this
            }
            val idx       = layout.indexOfFirst { it.id == id }
            val newLayout = if (idx >= 0) layout.toMutableList().also { list ->
                val w = list[idx]
                // Try to keep the widget's previous span; if no free area fits it,
                // fall back to 1×1 at the free cell so it can't overlap neighbors
                val area = if (w.spanX > 1 || w.spanY > 1) freeAreaIn(layout, activeIds, w.spanX, w.spanY) else null
                list[idx] = if (area != null)
                    w.copy(enabled = true, gridX = area.first, gridY = area.second)
                else
                    w.copy(enabled = true, gridX = cell_.first, gridY = cell_.second, spanX = 1, spanY = 1)
            } else {
                layout + com.openlauncher.app.data.WidgetConfig(id, cell_.first, cell_.second)
            }
            withShow.copy(widgetLayout = newLayout)
        }
    }

    fun removeWidget(id: String) {
        updateSettings {
            when (id) {
                "CLOCK"       -> copy(showClock = false)
                "WEATHER"     -> copy(showWeather = false)
                "NOW_PLAYING" -> copy(showNowPlaying = false)
                "TELEMETRY"   -> copy(showTelemetry = false)
                "ALTIMETER"   -> copy(showAltimeter = false)
                "SPEEDOMETER" -> copy(showSpeedometer = false)
                "VITALS"      -> copy(showVitals = false)
                "TRIP_TRACKER" -> copy(showTripTracker = false)
                "SOUNDBOARD"  -> copy(showSoundboard = false)
                else          -> this
            }
        }
    }

    fun updateSoundboardPad(index: Int, pad: SoundPadConfig) {
        updateSettings {
            // Persisted lists from older versions may be shorter than the 6 pads
            // the widget displays — pad before assigning to avoid IndexOutOfBounds
            val padded = soundboardPads.toMutableList()
            while (padded.size <= index) padded.add(SoundPadConfig("+", synthType = ""))
            padded[index] = pad
            copy(soundboardPads = padded)
        }
    }

    private fun freeCellIn(
        layout: List<com.openlauncher.app.data.WidgetConfig>,
        activeIds: Set<String>
    ): Pair<Int, Int>? = freeAreaIn(layout, activeIds, 1, 1)

    private fun freeAreaIn(
        layout: List<com.openlauncher.app.data.WidgetConfig>,
        activeIds: Set<String>,
        spanX: Int,
        spanY: Int
    ): Pair<Int, Int>? {
        val occupied = buildSet<Pair<Int, Int>> {
            layout.filter { it.enabled && it.id in activeIds }.forEach { w ->
                for (dx in 0 until w.spanX) for (dy in 0 until w.spanY) add(w.gridX + dx to w.gridY + dy)
            }
        }
        for (row in 0 until GRID_ROWS) for (col in 0 until GRID_COLS) {
            if (col + spanX > GRID_COLS || row + spanY > GRID_ROWS) continue
            if ((0 until spanX).all { dx -> (0 until spanY).all { dy -> (col + dx to row + dy) !in occupied } })
                return col to row
        }
        return null
    }

    fun cancelCarPlayPicker() {
        _appPickerTarget.value = null
    }

    // ── Rearrange mode ────────────────────────────────────────────────────────
    private val _rearrangeMode = MutableStateFlow(false)
    val rearrangeMode: StateFlow<Boolean> = _rearrangeMode

    fun toggleRearrangeMode() { _rearrangeMode.value = !_rearrangeMode.value }
    fun exitRearrangeMode()   { _rearrangeMode.value = false }

    // ── Installed apps ────────────────────────────────────────────────────────
    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps

    private val _appsLoading = MutableStateFlow(false)
    val appsLoading: StateFlow<Boolean> = _appsLoading

    fun loadInstalledApps() {
        if (_appsLoading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _appsLoading.value = true
            val pm = getApplication<Application>().packageManager

            // Use getInstalledApplications — same source Android Settings uses,
            // catches apps with no launcher/ACTION_MAIN activity (e.g. CarPlay companions)
            _apps.value = pm.getInstalledApplications(0)
                .mapNotNull { appInfo ->
                    try {
                        val label = pm.getApplicationLabel(appInfo).toString()
                        if (label.isBlank()) return@mapNotNull null
                        AppInfo(
                            packageName = appInfo.packageName,
                            appName     = label,
                            icon        = pm.getApplicationIcon(appInfo),
                            isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                        )
                    } catch (_: Exception) { null }
                }
                .distinctBy { it.packageName }
                .sortedBy { it.appName }
            _appsLoading.value = false
        }
    }

    fun launchApp(packageName: String) {
        val app = getApplication<Application>()
        if (packageName.isBlank()) return
        runCatching {
            val intent = com.openlauncher.app.util.AppLaunchIntent.resolve(app, packageName)
            checkNotNull(intent) { "This package has no launchable screen: $packageName" }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            com.openlauncher.app.diagnostics.DiagnosticLog.event("Launch", "Opening $packageName, component=${intent.component}, action=${intent.action}")
            app.startActivity(intent)
        }.onFailure {
            com.openlauncher.app.diagnostics.DiagnosticLog.event("Launch", "Could not open $packageName", it)
            android.widget.Toast.makeText(app, "Cannot open $packageName: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // ── Now Playing ───────────────────────────────────────────────────────────
    val nowPlaying: StateFlow<NowPlayingState?> = MediaListenerService.nowPlaying
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun openSessionScreen(controller: android.media.session.MediaController?): Boolean {
        val pending = controller?.sessionActivity ?: return false
        return runCatching {
            pending.send()
            com.openlauncher.app.diagnostics.DiagnosticLog.event("Launch", "Opened session screen for ${controller.packageName}")
            true
        }.getOrElse {
            com.openlauncher.app.diagnostics.DiagnosticLog.event("Launch", "Could not open session screen for ${controller.packageName}", it)
            false
        }
    }

    fun openCurrentMediaApp() {
        val controller = nowPlaying.value?.controller
        if (controller == null) { launchFactoryAudio(radio = false); return }
        if (!openSessionScreen(controller)) launchApp(controller.packageName)
    }

    fun playPause() { nowPlaying.value?.controller?.also { ctrl ->
        val state = ctrl.playbackState
        // Radio sources often support Stop but not Pause. Use the live session
        // capabilities so the visible control can actually silence the source.
        val action = playbackActionFor(state?.state, state?.actions ?: 0L)
        com.openlauncher.app.diagnostics.DiagnosticLog.event("MediaCommand", "Requested $action for ${ctrl.packageName}; state=${state?.state}, actions=${state?.actions}")
        runCatching {
            val key = when (action) {
                PlaybackAction.PLAY -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                PlaybackAction.PAUSE -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                else -> null
            }
            if (key != null && com.openlauncher.app.service.ProjectionMediaKeys.dispatch(ctrl, key)) return@runCatching
            when (action) {
            PlaybackAction.PLAY -> ctrl.transportControls.play()
            PlaybackAction.PAUSE -> ctrl.transportControls.pause()
            PlaybackAction.STOP -> ctrl.transportControls.stop()
            PlaybackAction.DISABLED -> Unit
        } }.onFailure { com.openlauncher.app.diagnostics.DiagnosticLog.event("MediaCommand", "Playback command failed", it) }
    }}

    private fun skip(controller: android.media.session.MediaController?, next: Boolean) {
        controller ?: return
        val action = if (next) android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT else android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS
        val actions = controller.playbackState?.actions ?: 0L
        val supported = (actions and action) != 0L
        com.openlauncher.app.diagnostics.DiagnosticLog.event("MediaCommand", "Requested ${if (next) "NEXT" else "PREVIOUS"} for ${controller.packageName}; actions=$actions, advertised=$supported")
        if (!supported) return
        runCatching {
            val key = if (next) android.view.KeyEvent.KEYCODE_MEDIA_NEXT else android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            if (!com.openlauncher.app.service.ProjectionMediaKeys.dispatch(controller, key)) {
                if (next) controller.transportControls.skipToNext() else controller.transportControls.skipToPrevious()
            }
        }
            .onFailure { com.openlauncher.app.diagnostics.DiagnosticLog.event("MediaCommand", "Skip command failed", it) }
    }

    fun skipNext() { skip(nowPlaying.value?.controller, true) }
    fun skipPrev() { skip(nowPlaying.value?.controller, false) }

    // ── Weather ───────────────────────────────────────────────────────────────
    private val _weather = MutableStateFlow<WeatherState?>(null)
    val weather: StateFlow<WeatherState?> = _weather

    private val _weatherError = MutableStateFlow<String?>(null)
    val weatherError: StateFlow<String?> = _weatherError

    private var weatherJob: Job? = null

    fun fetchWeather(lat: Double, lon: Double, metric: Boolean) {
        weatherJob?.cancel()
        weatherJob = viewModelScope.launch {
            try {
                // Always request celsius — the state stores celsius and the widget
                // converts for display, so requesting fahrenheit just round-tripped
                // the value through two lossy conversions
                val resp = WeatherApi.service.getForecast(lat, lon, temperatureUnit = "celsius")
                resp.currentWeather?.let { cw ->
                    _weather.value = WeatherState(
                        temperatureCelsius = cw.temperature,
                        weatherCode       = cw.weathercode,
                        windspeedKmh      = cw.windspeed,
                        isDay             = cw.isDay == 1
                    )
                }
                _weatherError.value = null
            } catch (e: Exception) {
                _weatherError.value = e.message
            }
        }
    }

    // ── Location & Compass ────────────────────────────────────────────────────
    val location: StateFlow<LocationData?> = locationMgr.location
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val compassBearing: StateFlow<Float> = locationMgr.bearing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0f)

    // Re-evaluated every minute: a parked car produces no location updates
    // (minDistance filters), so AUTO mode must also flip on time alone.
    private val minuteTicker = flow { while (true) { emit(Unit); delay(60_000L) } }

    val isDayMode: StateFlow<Boolean> = combine(settings, locationMgr.location, minuteTicker) { s, loc, _ ->
        when (s.dayNightMode) {
            DayNightMode.DARK   -> false
            DayNightMode.LIGHT  -> true
            DayNightMode.AUTO   -> if (loc != null) SunriseSunset.isDay(loc.latitude, loc.longitude) else false
            DayNightMode.SYSTEM -> false // placeholder — overridden in MainActivity via isSystemInDarkTheme()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun startLocationUpdates() = locationMgr.start()
    fun stopLocationUpdates()  = locationMgr.stop()

    // ── Connectivity ──────────────────────────────────────────────────────────
    private val _isWifi = MutableStateFlow(false)
    private val _isData = MutableStateFlow(false)
    val isWifi: StateFlow<Boolean> = _isWifi
    val isData: StateFlow<Boolean> = _isData

    fun refreshMedia() {
        MediaListenerService.requestRefresh()
    }

    // ── Radio ─────────────────────────────────────────────────────────────────
    // Android has no public FM tuner API (the Broadcast Radio HAL is @SystemApi),
    // so the radio widget mirrors the unit's real tuner through two backends:
    //   1. szchoiceway MCU units — Settings.Global JSON + canbus broadcasts.
    //      Full control: seek, band switching, direct frequency tuning.
    //   2. Any other unit — the vendor radio app's MediaSession. Frequency and
    //      station are parsed from session metadata. Controls depend on the actions
    //      advertised and implemented by that session. No direct tuning.
    data class HardwareRadioState(
        val band: String,
        val freq: String,
        val stationName: String? = null,
        // Direct tuning + FM1/FM2/FM3/AM switching — vendor MCU backend only
        val canTune: Boolean = false,
        val canSeekUp: Boolean = true,
        val canSeekDown: Boolean = true,
        val canStop: Boolean = true,
        val isPlaying: Boolean = true,
        val isDesay: Boolean = false
    ) {
        val display get() = "$band  $freq"
        val isAm    get() = band.equals("AM", ignoreCase = true)
    }

    private val _mcuRadio = MutableStateFlow<HardwareRadioState?>(null)

    private fun packageInstalled(pkg: String) = runCatching {
        getApplication<Application>().packageManager.getPackageInfo(pkg, 0)
    }.isSuccess

    private val hasSzchoicewayMcu: Boolean by lazy {
        packageInstalled("com.szchoiceway.radio") ||
        packageInstalled("com.szchoiceway.eventcenter") ||
        runCatching {
            AndroidSettings.Global.getString(
                getApplication<Application>().contentResolver, "SYS_MEDIA_INFO_JSON"
            ) != null
        }.getOrDefault(false)
    }

    private fun parseRadioJson(): HardwareRadioState? {
        return try {
            val json = AndroidSettings.Global.getString(
                getApplication<Application>().contentResolver, "SYS_MEDIA_INFO_JSON"
            ) ?: return null
            val title = org.json.JSONObject(json).optString("mediaTitle", "") // e.g. "FM1 90.10"
            if (title.isEmpty()) return null
            val parts = title.trim().split("\\s+".toRegex())
            if (parts.size < 2) return null
            HardwareRadioState(band = parts[0], freq = parts[1], canTune = true)
        } catch (e: Exception) {
            android.util.Log.e("RadioMcu", "parseRadioJson error", e)
            null
        }
    }

    private var radioObserver: ContentObserver? = null

    private fun startHardwareRadioObserver() {
        if (radioObserver != null) return
        _mcuRadio.value = parseRadioJson()
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                _mcuRadio.value = parseRadioJson()
            }
        }
        getApplication<Application>().contentResolver.registerContentObserver(
            AndroidSettings.Global.getUriFor("SYS_MEDIA_INFO_JSON"),
            false, observer
        )
        radioObserver = observer
    }

    // ── MediaSession radio backend ───────────────────────────────────────────

    private fun looksLikeRadioPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return "radio" in p || "fmradio" in p || "tuner" in p || p.endsWith(".fm") || ".fm." in p
    }

    private fun isRadioSessionPackage(pkg: String): Boolean {
        val assigned = settings.value.radioPackage
        return if (assigned.isNotEmpty()) pkg == assigned else looksLikeRadioPackage(pkg)
    }

    private fun parseSessionRadio(np: NowPlayingState): HardwareRadioState? {
        val pkg = np.controller?.packageName ?: return null
        if (!isRadioSessionPackage(pkg)) return null
        val metadata = com.openlauncher.app.model.radioMetadata(np.title, np.artist)
        if (pkg == com.openlauncher.app.data.OmodaDefaults.AUDIO && metadata.frequency == null) return null
        return HardwareRadioState(band = metadata.band, freq = metadata.frequency.orEmpty(), stationName = metadata.station,
            isPlaying = np.isPlaying,
            canSeekUp = (np.actions and android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT) != 0L,
            canSeekDown = (np.actions and android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L,
            canStop = (np.actions and (android.media.session.PlaybackState.ACTION_STOP or android.media.session.PlaybackState.ACTION_PAUSE or android.media.session.PlaybackState.ACTION_PLAY_PAUSE)) != 0L)
    }

    private val desayRadio = com.openlauncher.app.service.DesayRadio(application)
    private val desayRadioState = flow {
        try {
            while (currentCoroutineContext().isActive) {
                emit(desayRadio.read())
                delay(1_500)
            }
        } finally { desayRadio.close() }
    }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun desayCommand(action: Int) {
        viewModelScope.launch(Dispatchers.IO) { desayRadio.command(action) }
    }

    // MCU backend wins when present; otherwise mirror the radio app's session
    val hardwareRadio: StateFlow<HardwareRadioState?> =
        combine(_mcuRadio, desayRadioState, nowPlaying, settings) { mcu, desay, np, _ ->
            mcu ?: desay?.let { native ->
                native.copy(stationName = native.stationName ?: np?.let { parseSessionRadio(it)?.stationName })
            } ?: np?.let { parseSessionRadio(it) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun radioSessionController() = nowPlaying.value?.controller?.takeIf { c ->
        c.packageName?.let { isRadioSessionPackage(it) } == true
    }

    // Send a radio keyCode to the MCU via the EventService broadcast channel.
    // Permission com.szchoiceway.permission.broadcast (prot=normal) is required and declared.
    // keyCodes: 15=seek_up, 14=seek_down, 30=fm_cycle, 31=am
    private fun sendMcuByteArray(bytes: ByteArray) {
        runCatching {
            val intent = Intent("com.szchoiceway.eventcenter.EventUtils.ACTION_MCU_CMD_EVENT").apply {
                putExtra("EventUtils.MCU_CMD_DATA", bytes)
            }
            getApplication<Application>().sendBroadcast(intent)
        }
    }

    private fun sendMcuBytes(vararg bytes: Byte) = sendMcuByteArray(bytes)

    // Tune to a specific frequency.
    // Canbus frame: 0x5a 0xa5 0x0d [0x91 bandByte bank(2) zeros... freqAscii] checksum
    // Checksum = low byte of sum of all preceding bytes.
    // Wrapped with 0x0d 0x08 sub-command prefix for MCU_CMD_DATA.
    fun radioTune(band: String, freqMhz: Float) {
        if (!hasSzchoicewayMcu) return // direct tuning is MCU-only
        val bandByte  = when (band) { "FM3" -> 0x03.toByte(); "AM" -> 0x04.toByte(); else -> 0x01.toByte() }
        val bankStr   = if (band == "FM2") "02" else "01"
        val freqStr   = if (band == "AM") String.format(java.util.Locale.US, "%.0f", freqMhz) else String.format(java.util.Locale.US, "%.1f", freqMhz)
        val padding   = 9 - freqStr.length
        val header    = byteArrayOf(0x5a.toByte(), 0xa5.toByte(), 0x0d.toByte())
        val data      = byteArrayOf(0x91.toByte(), bandByte,
                            bankStr[0].code.toByte(), bankStr[1].code.toByte()) +
                        ByteArray(padding) +
                        freqStr.map { it.code.toByte() }.toByteArray()
        val frame     = header + data
        val checksum  = (frame.sumOf { it.toInt() and 0xFF } and 0xFF).toByte()
        val finalPayload = byteArrayOf(0x0d.toByte(), 0x08.toByte()) + frame + byteArrayOf(checksum)
        sendMcuByteArray(finalPayload)
    }

    private val mcuActive get() = _mcuRadio.value != null

    // Seek routes to the MCU when that backend is live, otherwise to the radio
    // app's MediaSession, only when it advertises skip next/previous.
    fun radioSeekUp()   { if (mcuActive) sendMcuBytes(0x02, 0x0f) else if (hardwareRadio.value?.isDesay == true) desayCommand(7) else skip(radioSessionController(), true) }
    fun radioSeekDown() { if (mcuActive) sendMcuBytes(0x02, 0x0e) else if (hardwareRadio.value?.isDesay == true) desayCommand(6) else skip(radioSessionController(), false) }
    // Band switching and direct tuning only exist on the MCU backend
    fun radioCycleFm()  { if (mcuActive) sendMcuBytes(0x02, 0x1e) }
    fun radioSwitchAm() { if (mcuActive) sendMcuBytes(0x02, 0x1f) }
    fun radioStart()    { sendMcuBytes(0x01, 0x01) }
    fun radioStop()     { sendMcuBytes(0x01, 0x63) }

    fun startHardwareRadioPlayback() {
        if (mcuActive) radioStart()
        else if (hardwareRadio.value?.isDesay == true) desayCommand(2)
        else {
            val controller = radioSessionController()
            val actions = controller?.playbackState?.actions ?: 0L
            if (controller != null && (actions and (android.media.session.PlaybackState.ACTION_PLAY or android.media.session.PlaybackState.ACTION_PLAY_PAUSE)) != 0L)
                controller.transportControls.play()
            else launchHardwareRadioApp()
        }
    }

    fun stopHardwareRadioApp() {
        if (mcuActive) radioStop()
        else if (hardwareRadio.value?.isDesay == true) desayCommand(3)
        else radioSessionController()?.let { controller ->
            val actions = controller.playbackState?.actions ?: 0L
            com.openlauncher.app.diagnostics.DiagnosticLog.event("MediaCommand", "Requested radio stop for ${controller.packageName}; actions=$actions")
            runCatching {
                when {
                    (actions and android.media.session.PlaybackState.ACTION_STOP) != 0L -> controller.transportControls.stop()
                    (actions and (android.media.session.PlaybackState.ACTION_PAUSE or android.media.session.PlaybackState.ACTION_PLAY_PAUSE)) != 0L -> controller.transportControls.pause()
                    else -> Unit
                }
            }.onFailure { com.openlauncher.app.diagnostics.DiagnosticLog.event("MediaCommand", "Radio stop failed", it) }
        }
    }

    fun launchFactoryAudio(radio: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!desayRadio.openAudioSource(if (radio) 8 else 2)) withContext(Dispatchers.Main) {
                launchApp(com.openlauncher.app.data.OmodaDefaults.AUDIO)
            }
        }
    }

    fun launchCamera() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!desayRadio.openCamera()) withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(getApplication(), "Factory camera is unavailable. Save a diagnostic report from Settings.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun launchHardwareRadioApp() {
        if (settings.value.radioPackage == com.openlauncher.app.data.OmodaDefaults.AUDIO) {
            launchFactoryAudio(radio = true)
            return
        }
        if (hasSzchoicewayMcu) radioStart()
        val controller = radioSessionController()
        if (openSessionScreen(controller)) return
        val pkg = settings.value.radioPackage.ifEmpty {
            when {
                hasSzchoicewayMcu -> "com.szchoiceway.radio"
                else              -> radioSessionController()?.packageName ?: ""
            }
        }
        if (pkg.isNotEmpty()) {
            launchApp(pkg)
        }
        // Resume playback if the session is just paused
        // Opening a radio screen must not silently start playback or take audio focus.
    }

    fun refreshConnectivity() {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            _isWifi.value = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            _isData.value = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        } else {
            // activeNetwork requires API 23 — legacy path for Android 5.x head units
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            val connected = info?.isConnected == true
            @Suppress("DEPRECATION")
            val type = info?.type
            _isWifi.value = connected && type == ConnectivityManager.TYPE_WIFI
            _isData.value = connected && type == ConnectivityManager.TYPE_MOBILE
        }
    }

    override fun onCleared() {
        super.onCleared()
        locationMgr.stop()
        radioObserver?.let { getApplication<Application>().contentResolver.unregisterContentObserver(it) }
        radioObserver = null
    }

    init {
        loadInstalledApps()
        refreshConnectivity()
        if (hasSzchoicewayMcu) startHardwareRadioObserver()
        // Fetch weather on first location fix, then every 30 minutes.
        // The minute ticker covers the parked case where no location updates arrive.
        viewModelScope.launch {
            var lastFetchMs = 0L
            merge(
                locationMgr.location.filterNotNull(),
                minuteTicker.mapNotNull { locationMgr.location.value }
            ).collect { loc ->
                val now = System.currentTimeMillis()
                if (now - lastFetchMs >= 30 * 60 * 1_000L) {
                    lastFetchMs = now
                    fetchWeather(loc.latitude, loc.longitude, settings.value.unitSystem.name == "METRIC")
                }
            }
        }
    }
}
