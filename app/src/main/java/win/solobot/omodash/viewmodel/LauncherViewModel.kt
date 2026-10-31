package win.solobot.omodash.viewmodel

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
import win.solobot.omodash.data.AppSettings
import win.solobot.omodash.data.ClimateRepository
import win.solobot.omodash.model.ClimateAction
import win.solobot.omodash.model.ClimateState
import win.solobot.omodash.data.DayNightMode
import win.solobot.omodash.data.DefaultShortcutIcon
import win.solobot.omodash.data.SettingsRepository
import win.solobot.omodash.data.ShortcutConfig
import win.solobot.omodash.data.SoundPadConfig
import win.solobot.omodash.data.WeatherApi
import win.solobot.omodash.data.activeWidgetIds
import win.solobot.omodash.data.computeWidgetMove
import win.solobot.omodash.data.withResizedWidget
import win.solobot.omodash.data.withAddedWidget
import win.solobot.omodash.data.withWidgetEnabled
import win.solobot.omodash.data.defaultShortcuts
import win.solobot.omodash.data.vehicleBinding
import win.solobot.omodash.model.AppInfo
import win.solobot.omodash.model.NavDestination
import win.solobot.omodash.model.NowPlayingState
import win.solobot.omodash.model.PlaybackAction
import win.solobot.omodash.model.playbackActionFor
import win.solobot.omodash.model.WeatherState
import win.solobot.omodash.service.MediaListenerService
import win.solobot.omodash.util.LocationCompassManager
import win.solobot.omodash.util.LocationData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SettingsRepository(application)
    private val climateRepo = ClimateRepository(application)
    private val locationMgr  = LocationCompassManager(application)

    val climate: StateFlow<ClimateState> = climateRepo.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, ClimateState())

    fun onClimateAction(action: ClimateAction) {
        viewModelScope.launch { climateRepo.dispatch(action) }
    }

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
                val previous = list[slot]
                list[slot] = if (previous.isDefault) previous.copy(packageName = app.packageName)
                    else ShortcutConfig(packageName = app.packageName, label = app.appName)
            })
        }
        _shortcutPickerSlot.value = null
        _nav.value = NavDestination.HOME
    }

    fun removeShortcut(slot: Int) {
        updateSettings {
            copy(shortcuts = shortcuts.toMutableList().also { list ->
                if (slot in list.indices) {
                    list[slot] = win.solobot.omodash.data.VehiclePresets.find(vehiclePresetId).shortcuts().getOrNull(slot) ?: ShortcutConfig()
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
    enum class AppPickerTarget { CARPLAY, ANDROID_AUTO, RADIO, NAVIGATION, CAR_SETTINGS, LOCAL_SETTINGS }

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
            AppPickerTarget.NAVIGATION, AppPickerTarget.CAR_SETTINGS, AppPickerTarget.LOCAL_SETTINGS -> {
                val target = win.solobot.omodash.data.VehicleApp.valueOf(_appPickerTarget.value!!.name)
                updateSettings { copy(vehicleAppPackages = vehicleAppPackages + (target to app.packageName)) }
            }
            null -> {}
        }
        _appPickerTarget.value = null
        _nav.value = NavDestination.HOME
    }

    fun startVehicleAppPicker(app: win.solobot.omodash.data.VehicleApp) {
        _appPickerTarget.value = AppPickerTarget.valueOf(app.name)
        _nav.value = NavDestination.APP_LIBRARY
    }

    fun launchVehicleApp(app: win.solobot.omodash.data.VehicleApp) {
        val binding = settings.value.vehicleBinding(app)
        if (binding.launch == win.solobot.omodash.data.VehicleLaunch.CURRENT_MEDIA) {
            openCurrentMediaApp()
            return
        }
        val context = getApplication<Application>()
        runCatching {
            val intent = checkNotNull(win.solobot.omodash.util.AppLaunchIntent.resolveBinding(context, binding)) { "App is not available" }
            context.startActivity(intent)
        }.onFailure {
            win.solobot.omodash.diagnostics.DiagnosticLog.event("Launch", "Cannot open preset app $app", it)
            android.widget.Toast.makeText(context, "${app.name.replace('_', ' ')} is not available", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun clearRadioApp()        { updateSettings { copy(radioPackage = "") } }

    fun assignCurrentMediaAsRadio() {
        val pkg = nowPlaying.value?.controller?.packageName ?: return
        updateSettings { copy(radioPackage = pkg) }
        win.solobot.omodash.diagnostics.DiagnosticLog.event("Radio", "User assigned current media session $pkg as radio")
        _appPickerTarget.value = null
        _nav.value = NavDestination.HOME
    }

    fun updateWidgetConfig(id: String, spanX: Int, spanY: Int) {
        updateSettings { withResizedWidget(id, spanX, spanY) ?: this }
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
        updateSettings { withAddedWidget(id) ?: this }
    }

    fun removeWidget(id: String) {
        updateSettings { withWidgetEnabled(id, false) }
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
            val intent = win.solobot.omodash.util.AppLaunchIntent.resolve(app, packageName)
            checkNotNull(intent) { "This package has no launchable screen: $packageName" }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            win.solobot.omodash.diagnostics.DiagnosticLog.event("Launch", "Opening $packageName, component=${intent.component}, action=${intent.action}")
            app.startActivity(intent)
        }.onFailure {
            win.solobot.omodash.diagnostics.DiagnosticLog.event("Launch", "Could not open $packageName", it)
            android.widget.Toast.makeText(app, "Cannot open $packageName: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // ── Now Playing ───────────────────────────────────────────────────────────
    private fun openSessionScreen(controller: android.media.session.MediaController?): Boolean {
        val pending = controller?.sessionActivity ?: return false
        return runCatching {
            pending.send()
            win.solobot.omodash.diagnostics.DiagnosticLog.event("Launch", "Opened session screen for ${controller.packageName}")
            true
        }.getOrElse {
            win.solobot.omodash.diagnostics.DiagnosticLog.event("Launch", "Could not open session screen for ${controller.packageName}", it)
            false
        }
    }

    fun openCurrentMediaApp() {
        val media = nowPlaying.value
        if (media?.oemSource != null && media.oemSource > 0) {
            val view = settings.value.vehicleBinding(win.solobot.omodash.data.VehicleApp.NOW_PLAYING).mediaView
            viewModelScope.launch(Dispatchers.IO) { desayRadio.openAudioSource(media.oemSource, view, useCurrentSource = true) }
            return
        }
        val controller = media?.controller
        if (controller == null) {
            if (media?.packageName != null) launchApp(media.packageName!!)
            else launchFactoryAudio(radio = false)
            return
        }
        if (!openSessionScreen(controller)) launchApp(controller.packageName)
    }

    fun playPause() {
        val media = nowPlaying.value
        if (media?.oemSource != null) {
            val action = when (media.playbackAction) {
                win.solobot.omodash.model.PlaybackAction.PLAY -> 1
                win.solobot.omodash.model.PlaybackAction.PAUSE -> 2
                win.solobot.omodash.model.PlaybackAction.STOP -> 3
                else -> return
            }
            viewModelScope.launch(Dispatchers.IO) { desayRadio.mediaCommand(media.oemSource, action) }
            return
        }
        nowPlaying.value?.controller?.also { ctrl ->
        val state = ctrl.playbackState
        // Radio sources often support Stop but not Pause. Use the live session
        // capabilities so the visible control can actually silence the source.
        val action = playbackActionFor(state?.state, state?.actions ?: 0L)
        win.solobot.omodash.diagnostics.DiagnosticLog.event("MediaCommand", "Requested $action for ${ctrl.packageName}; state=${state?.state}, actions=${state?.actions}")
        runCatching {
            val key = when (action) {
                PlaybackAction.PLAY -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                PlaybackAction.PAUSE -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                else -> null
            }
            if (key != null && win.solobot.omodash.service.ProjectionMediaKeys.dispatch(ctrl, key)) return@runCatching
            when (action) {
            PlaybackAction.PLAY -> ctrl.transportControls.play()
            PlaybackAction.PAUSE -> ctrl.transportControls.pause()
            PlaybackAction.STOP -> ctrl.transportControls.stop()
            PlaybackAction.DISABLED -> Unit
        } }.onFailure { win.solobot.omodash.diagnostics.DiagnosticLog.event("MediaCommand", "Playback command failed", it) }
    }}

    private fun skip(controller: android.media.session.MediaController?, next: Boolean) {
        controller ?: return
        val action = if (next) android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT else android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS
        val actions = controller.playbackState?.actions ?: 0L
        val supported = (actions and action) != 0L
        win.solobot.omodash.diagnostics.DiagnosticLog.event("MediaCommand", "Requested ${if (next) "NEXT" else "PREVIOUS"} for ${controller.packageName}; actions=$actions, advertised=$supported")
        if (!supported) return
        runCatching {
            val key = if (next) android.view.KeyEvent.KEYCODE_MEDIA_NEXT else android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            if (!win.solobot.omodash.service.ProjectionMediaKeys.dispatch(controller, key)) {
                if (next) controller.transportControls.skipToNext() else controller.transportControls.skipToPrevious()
            }
        }
            .onFailure { win.solobot.omodash.diagnostics.DiagnosticLog.event("MediaCommand", "Skip command failed", it) }
    }

    private fun skipCurrent(next: Boolean) {
        val media = nowPlaying.value
        if (media?.oemSource != null) viewModelScope.launch(Dispatchers.IO) { desayRadio.mediaCommand(media.oemSource, if (next) 5 else 4) }
        else skip(media?.controller, next)
    }
    fun skipNext() = skipCurrent(true)
    fun skipPrev() = skipCurrent(false)

    fun seekTo(positionMs: Long) {
        val media = nowPlaying.value ?: return
        if (media.actions and android.media.session.PlaybackState.ACTION_SEEK_TO == 0L) return
        val target = positionMs.coerceIn(0L, media.durationMs.coerceAtLeast(0L))
        if (media.oemSource != null) viewModelScope.launch(Dispatchers.IO) { desayRadio.mediaCommand(media.oemSource, 9, target) }
        else runCatching { media.controller?.transportControls?.seekTo(target) }
            .onFailure { win.solobot.omodash.diagnostics.DiagnosticLog.event("MediaCommand", "Seek failed", it) }
    }

    // ── Weather ───────────────────────────────────────────────────────────────
    private val _weather = MutableStateFlow<WeatherState?>(null)
    val weather: StateFlow<WeatherState?> = _weather

    private val _weatherError = MutableStateFlow<String?>(null)
    val weatherError: StateFlow<String?> = _weatherError
    private val _weatherLoading = MutableStateFlow(false)
    val weatherLoading: StateFlow<Boolean> = _weatherLoading

    private var weatherJob: Job? = null

    fun fetchWeather(lat: Double, lon: Double, metric: Boolean) {
        weatherJob?.cancel()
        weatherJob = viewModelScope.launch {
            _weatherLoading.value = true
            _weatherError.value = null
            try {
                // Always request celsius — the state stores celsius and the widget
                // converts for display, so requesting fahrenheit just round-tripped
                // the value through two lossy conversions
                val resp = WeatherApi.service.getForecast(lat, lon, temperatureUnit = "celsius")
                checkNotNull(resp.currentWeather) { "Weather response has no current conditions" }.let { cw ->
                    _weather.value = WeatherState(
                        temperatureCelsius = cw.temperature,
                        weatherCode       = cw.weathercode,
                        windspeedKmh      = cw.windspeed,
                        isDay             = cw.isDay == 1
                    )
                }
                _weatherError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _weatherError.value = e.message
                win.solobot.omodash.diagnostics.DiagnosticLog.event("Weather", "Weather request failed", e)
            } finally {
                if (currentCoroutineContext().isActive) _weatherLoading.value = false
            }
        }
    }

    fun retryWeather() {
        val loc = locationMgr.location.value
        if (loc == null) startLocationUpdates()
        else fetchWeather(loc.latitude, loc.longitude, settings.value.unitSystem.name == "METRIC")
    }

    // ── Location & Compass ────────────────────────────────────────────────────
    val location: StateFlow<LocationData?> = locationMgr.location
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val compassBearing: StateFlow<Float> = locationMgr.bearing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0f)

    // Re-evaluated every minute: a parked car produces no location updates
    // (minDistance filters), so AUTO mode must also flip on time alone.
    private val minuteTicker = flow { while (true) { emit(Unit); delay(60_000L) } }

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

    private fun isRadioSessionPackage(pkg: String): Boolean =
        settings.value.radioPackage.isNotBlank() && pkg == settings.value.radioPackage

    private val desayRadio = win.solobot.omodash.service.DesayRadio(application)
    private val desayMediaState = flow {
        try {
            while (currentCoroutineContext().isActive) {
                val binding = win.solobot.omodash.data.VehiclePresets.current().binding(win.solobot.omodash.data.VehicleApp.RADIO)
                emit(if (binding.launch == win.solobot.omodash.data.VehicleLaunch.DESAY_RADIO) desayRadio.readMedia()
                    else { desayRadio.close(); null })
                delay(1_000)
            }
        } finally { desayRadio.close() }
    }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val nowPlaying: StateFlow<NowPlayingState?> =
        combine(MediaListenerService.nowPlaying, desayMediaState, settings) { session, native, configuration ->
            val binding = win.solobot.omodash.data.VehiclePresets.find(configuration.vehiclePresetId).binding(win.solobot.omodash.data.VehicleApp.RADIO)
            if (binding.launch != win.solobot.omodash.data.VehicleLaunch.DESAY_RADIO) session
            else if (native == null) NowPlayingState("Waiting for media source", "", null, isPlaying = false, controller = null)
            else {
                val track = native.track
                // Only the explicitly selected source may supplement OEM data.
                // An inactive Android Auto session can be absent from getActiveSessions.
                val matching = session?.takeIf { it.packageName == track.packageName && it.title == track.title &&
                    (track.artist.isBlank() || it.artist == track.artist) }
                track.copy(controller = matching?.controller,
                    albumArt = track.albumArt ?: matching?.albumArt,
                    artUri = track.artUri ?: matching?.artUri,
                    durationMs = track.durationMs.takeIf { it > 0 } ?: matching?.durationMs ?: 0L)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun desayCommand(action: Int) {
        viewModelScope.launch(Dispatchers.IO) { desayRadio.command(action) }
    }

    val hardwareRadio: StateFlow<HardwareRadioState?> =
        combine(_mcuRadio, desayMediaState, MediaListenerService.nowPlaying, settings) { mcu, native, session, configuration ->
            val binding = win.solobot.omodash.data.VehiclePresets.find(configuration.vehiclePresetId).binding(win.solobot.omodash.data.VehicleApp.RADIO)
            mcu ?: if (binding.launch == win.solobot.omodash.data.VehicleLaunch.DESAY_RADIO) native?.radio
            else session?.takeIf { it.packageName == configuration.radioPackage && configuration.radioPackage.isNotBlank() }?.let {
                HardwareRadioState("RADIO", "", stationName = it.title, isPlaying = it.isPlaying,
                    canSeekUp = it.actions and android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
                    canSeekDown = it.actions and android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
                    canStop = it.playbackAction != win.solobot.omodash.model.PlaybackAction.DISABLED)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun radioSessionController() = nowPlaying.value?.controller?.takeIf { isRadioSessionPackage(it.packageName) }

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
            win.solobot.omodash.diagnostics.DiagnosticLog.event("MediaCommand", "Requested radio stop for ${controller.packageName}; actions=$actions")
            runCatching {
                when {
                    (actions and android.media.session.PlaybackState.ACTION_STOP) != 0L -> controller.transportControls.stop()
                    (actions and (android.media.session.PlaybackState.ACTION_PAUSE or android.media.session.PlaybackState.ACTION_PLAY_PAUSE)) != 0L -> controller.transportControls.pause()
                    else -> Unit
                }
            }.onFailure { win.solobot.omodash.diagnostics.DiagnosticLog.event("MediaCommand", "Radio stop failed", it) }
        }
    }

    fun launchFactoryAudio(radio: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!desayRadio.openAudioSource(if (radio) 8 else 2)) withContext(Dispatchers.Main) {
                launchApp(win.solobot.omodash.data.VehiclePresets.current().binding(
                    if (radio) win.solobot.omodash.data.VehicleApp.RADIO else win.solobot.omodash.data.VehicleApp.MUSIC).packageName)
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
        if (win.solobot.omodash.data.VehiclePresets.current().binding(win.solobot.omodash.data.VehicleApp.RADIO).launch ==
            win.solobot.omodash.data.VehicleLaunch.DESAY_RADIO) {
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
                val refreshPeriod = if (_weatherError.value != null) 60_000L else 30 * 60 * 1_000L
                if (settings.value.showWeather && now - lastFetchMs >= refreshPeriod) {
                    lastFetchMs = now
                    fetchWeather(loc.latitude, loc.longitude, settings.value.unitSystem.name == "METRIC")
                }
            }
        }
    }
}
