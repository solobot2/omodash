package win.solobot.omodash

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import win.solobot.omodash.data.DayNightMode
import win.solobot.omodash.data.VehiclePresets
import win.solobot.omodash.data.VehicleLaunch
import win.solobot.omodash.data.vehicleApp
import win.solobot.omodash.data.withVehiclePreset
import win.solobot.omodash.data.SidebarPosition
import win.solobot.omodash.ui.components.dashboardBackground
import win.solobot.omodash.model.NavDestination
import win.solobot.omodash.ui.components.ClimateBar
import win.solobot.omodash.ui.components.ProvidePanelDimensions
import win.solobot.omodash.ui.components.Sidebar
import win.solobot.omodash.ui.screen.*
import win.solobot.omodash.ui.theme.OmodashTheme
import win.solobot.omodash.viewmodel.LauncherViewModel

class MainActivity : ComponentActivity() {

    private val vm: LauncherViewModel by viewModels()
    private var restoreUpdates = false

    private val locationPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            vm.startLocationUpdates()
        }
    }

    private fun configureSystemBars() {
        // Our climate bar owns the bottom edge while this Activity is in front.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        win.solobot.omodash.diagnostics.DiagnosticLog.event("Window", "focus=$hasFocus")
        if (hasFocus) configureSystemBars()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreUpdates = win.solobot.omodash.update.UpdateInstaller.hasPendingInstallation(this)
        if (restoreUpdates) vm.navigate(NavDestination.SETTINGS)
        win.solobot.omodash.util.LauncherExit.restoreComponents(this)
        configureSystemBars()

        setContent {
            val settingsLoaded by vm.settingsLoaded.collectAsStateWithLifecycle()
            val settings       by vm.settings.collectAsStateWithLifecycle()
            val climate        by vm.climate.collectAsStateWithLifecycle()
            val nav            by vm.nav.collectAsStateWithLifecycle()
            val apps        by vm.apps.collectAsStateWithLifecycle()
            val appsLoading by vm.appsLoading.collectAsStateWithLifecycle()
            val nowPlaying  by vm.nowPlaying.collectAsStateWithLifecycle()
            val weather     by vm.weather.collectAsStateWithLifecycle()
            val weatherError by vm.weatherError.collectAsStateWithLifecycle()
            val weatherLoading by vm.weatherLoading.collectAsStateWithLifecycle()
            val location    by vm.location.collectAsStateWithLifecycle()
            val bearing     by vm.compassBearing.collectAsStateWithLifecycle()
            val isWifi      by vm.isWifi.collectAsStateWithLifecycle()
            val isData      by vm.isData.collectAsStateWithLifecycle()
            val hardwareRadio by vm.hardwareRadio.collectAsStateWithLifecycle()
            val systemIsDark = isSystemInDarkTheme()
            val isDayMode = when (settings.dayNightMode) {
                DayNightMode.LIGHT -> true
                DayNightMode.DARK -> false
                DayNightMode.AUTO -> !systemIsDark
            }
            val pickerSlot      by vm.shortcutPickerSlot.collectAsStateWithLifecycle()
            val appPickerTarget by vm.appPickerTarget.collectAsStateWithLifecycle()

            var projectionPackage by remember { mutableStateOf<String?>(null) }
            val closeProjection: () -> Unit = {
                projectionPackage = null
            }
            val openProjection: (String) -> Unit = { pkg ->
                projectionPackage = pkg.takeIf { it.isNotBlank() }
            }
            LaunchedEffect(nav) { if (nav != NavDestination.HOME) projectionPackage = null }
            DisposableEffect(Unit) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) projectionPackage = null
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }

            val accent = (if (isDayMode) win.solobot.omodash.ui.theme.DayDashboard else win.solobot.omodash.ui.theme.NightDashboard).navAccent

            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density   = baseDensity.density * settings.uiScale,
                    fontScale = baseDensity.fontScale * settings.textScale
                )
            ) {
                if (!settingsLoaded) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                } else OmodashTheme(
                    fontBold   = settings.fontBold,
                    textScale  = 1f,
                    appFont    = settings.appFont,
                    isDayMode  = isDayMode
                ) {
                ProvidePanelDimensions {
                    if (!settings.onboardingCompleted) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                OnboardingScreen(
                                    accent = accent,
                                    presetId = settings.vehiclePresetId,
                                    onSelectPreset = { id -> vm.updateSettings { withVehiclePreset(id) } },
                                    onComplete = {
                                        vm.updateSettings { copy(onboardingCompleted = true) }
                                        // Start location updates immediately upon completion
                                        vm.startLocationUpdates()
                                    }
                                )
                            }
                            ClimateBar(climate, vm::onClimateAction)
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize().dashboardBackground()) {
                            // Optional wallpaper layer
                            if (settings.wallpaperUri.isNotEmpty()) {
                                AsyncImage(
                                    model              = android.net.Uri.parse(settings.wallpaperUri),
                                    contentDescription = null,
                                    contentScale       = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier           = Modifier.fillMaxSize()
                                )
                                Box(modifier = Modifier.fillMaxSize()
                                    .background(Color.Black.copy(alpha = settings.wallpaperDim)))
                            }


                            val sidebarContent: @Composable () -> Unit = {
                                Sidebar(
                                    currentDest   = nav,
                                    settings      = settings,
                                    nowPlayingAvailable = nowPlaying?.packageName != null || (nowPlaying?.oemSource ?: 0) > 0,
                                    onVehicleApp = { app -> closeProjection(); vm.launchVehicleApp(app) },
                                    onNavigate    = { dest ->
                                        closeProjection()
                                        vm.cancelShortcutPicker()
                                        vm.cancelCarPlayPicker()
                                        vm.exitRearrangeMode()
                                        vm.navigate(dest)
                                    },

                                )
                            }

                            val mainPane: @Composable (Modifier) -> Unit = { paneModifier ->
                                // ── Main content pane ─────────────────────────────
                                AnimatedContent(
                                    targetState   = nav,
                                    transitionSpec = {
                                        fadeIn() + slideInHorizontally { it / 10 } togetherWith
                                        fadeOut() + slideOutHorizontally { -it / 10 }
                                    },
                                    modifier = paneModifier,
                                    label    = "pane_transition"
                                ) { destination ->
                                    when (destination) {
                                        NavDestination.HOME -> HomeScreen(
                                            settings            = settings,
                                            weather             = weather,
                                            weatherMessage = when {
                                                weatherError != null -> "Weather unavailable"
                                                location == null -> "Waiting for location"
                                                weatherLoading -> "Loading weather…"
                                                else -> "Weather unavailable"
                                            },
                                            onRetryWeather = vm::retryWeather,
                                            nowPlaying          = nowPlaying,
                                            location            = location,
                                            bearing             = bearing,
                                            isWifi              = isWifi,
                                            isData              = isData,
                                            isDayMode           = isDayMode,
                                            onPlayPause         = vm::playPause,
                                            onSeekTo            = vm::seekTo,
                                            onNext              = vm::skipNext,
                                            onPrev              = vm::skipPrev,
                                            onLaunchCarPlay     = { vm.launchVehicleApp(win.solobot.omodash.data.VehicleApp.CARPLAY) },
                                            onLaunchAndroidAuto = { vm.launchVehicleApp(win.solobot.omodash.data.VehicleApp.ANDROID_AUTO) },
                                            onTapNowPlaying = {
                                                if (nowPlaying?.oemSource != null) {
                                                    vm.openCurrentMediaApp()
                                                } else {
                                                    val pkg = nowPlaying?.controller?.packageName
                                                    if (pkg == settings.androidAutoPackage) openProjection(pkg)
                                                    else vm.openCurrentMediaApp()
                                                }
                                            },
                                            onUpdateWidget      = { id, sx, sy -> vm.updateWidgetConfig(id, sx, sy) },
                                            onMoveWidget        = { id, gx, gy -> vm.moveWidgetConfig(id, gx, gy) },
                                            onAddWidget         = { id -> vm.addWidget(id) },
                                            onRemoveWidget      = { id -> vm.removeWidget(id) },
                                            onSetClockStyle     = { style -> vm.updateSettings { copy(clockStyle = style) } },
                                            onSetVitalsAsBars   = { asBars -> vm.updateSettings { copy(vitalsAsBars = asBars) } },
                                            onSetSpeedometerDigitalOnly = { digital -> vm.updateSettings { copy(speedometerDigitalOnly = digital) } },
                                            onUpdateSoundPad    = { idx, pad -> vm.updateSoundboardPad(idx, pad) },
                                            hardwareRadio         = hardwareRadio,
                                            onLaunchHardwareRadio = { vm.launchHardwareRadioApp() },
                                            onStartHardwareRadio = vm::startHardwareRadioPlayback,
                                            onStopHardwareRadio   = { vm.stopHardwareRadioApp() },
                                            onRadioSeekUp         = { vm.radioSeekUp() },
                                            onRadioSeekDown       = { vm.radioSeekDown() },
                                            onRadioCycleFm        = { vm.radioCycleFm() },
                                            onRadioSwitchAm       = { vm.radioSwitchAm() },
                                            onRadioTune           = { band, freq -> vm.radioTune(band, freq) },
                                            onAssignRadio         = { vm.startRadioPicker() },
                                            onDismissProjection = closeProjection,
                                            projectionContent = projectionPackage?.let { pkg ->
                                                @Composable { key(pkg) {
                                                    win.solobot.omodash.projection.ProjectionWidget(pkg, closeProjection)
                                                } }
                                            }
                                        )

                                        NavDestination.APP_LIBRARY -> AppLibraryScreen(
                                            apps                = apps,
                                            isLoading           = appsLoading,
                                            isPickerMode        = pickerSlot != null,
                                            pickerSlot          = pickerSlot,
                                            isCarPlayPickerMode = appPickerTarget != null,
                                            carPlayPickerLabel  = when (appPickerTarget) {
                                                win.solobot.omodash.viewmodel.LauncherViewModel.AppPickerTarget.ANDROID_AUTO -> "CHOOSE ANDROID AUTO APP"
                                                win.solobot.omodash.viewmodel.LauncherViewModel.AppPickerTarget.RADIO        -> "CHOOSE RADIO APP"
                                                win.solobot.omodash.viewmodel.LauncherViewModel.AppPickerTarget.NAVIGATION -> "CHOOSE NAVIGATION APP"
                                                win.solobot.omodash.viewmodel.LauncherViewModel.AppPickerTarget.CAR_SETTINGS -> "CHOOSE CAR SETTINGS APP"
                                                win.solobot.omodash.viewmodel.LauncherViewModel.AppPickerTarget.LOCAL_SETTINGS -> "CHOOSE LOCAL SETTINGS APP"
                                                else -> "CHOOSE CARPLAY APP"
                                            },
                                            accent              = accent,
                                            onAppClick          = { app -> vm.launchApp(app.packageName) },
                                            onPickerSelect      = { slot, app -> vm.assignShortcut(slot, app) },
                                            onCarPlaySelect     = { app -> vm.assignPickerApp(app) },
                                            currentRadioSource = if (appPickerTarget == win.solobot.omodash.viewmodel.LauncherViewModel.AppPickerTarget.RADIO)
                                                nowPlaying?.controller?.packageName?.let { pkg ->
                                                    val label = apps.firstOrNull { it.packageName == pkg }?.appName ?: pkg
                                                    "$label · ${nowPlaying?.title.orEmpty()}"
                                                } else null,
                                            onUseCurrentRadioSource = { vm.assignCurrentMediaAsRadio() }
                                        )

                                        NavDestination.SETTINGS -> SettingsScreen(
                                            settings = settings,
                                            accent   = accent,
                                            onUpdate = { block -> vm.updateSettings(block) },
                                            onReset  = { vm.resetSettings() },
                                            onAssignCarPlay = { vm.startCarPlayPicker() },
                                            onAssignAndroidAuto = { vm.startAndroidAutoPicker() },
                                            onAssignVehicleApp = vm::startVehicleAppPicker,
                                            focusUpdates = restoreUpdates,
                                            onUpdatesFocused = { restoreUpdates = false }
                                        )
                                    }
                                }
                            }

                            Column(Modifier.fillMaxSize()) {
                                Row(Modifier.weight(1f).fillMaxWidth()) {
                                    if (settings.sidebarPosition == SidebarPosition.LEFT) sidebarContent()
                                    mainPane(Modifier.weight(1f).fillMaxHeight())
                                    if (settings.sidebarPosition == SidebarPosition.RIGHT) sidebarContent()
                                }
                                ClimateBar(climate, vm::onClimateAction)
                            }
                        }
                    }
                } // ProvidePanelDimensions
                }
            } // CompositionLocalProvider
        }
    }

    override fun onResume() {
        super.onResume()
        win.solobot.omodash.diagnostics.DiagnosticLog.event("Activity", "Resumed")
        vm.refreshConnectivity()
        vm.refreshMedia()
        win.solobot.omodash.util.MediaAccess.ensureConnected(this)
    }

    override fun onStop() {
        super.onStop()
        win.solobot.omodash.diagnostics.DiagnosticLog.event("Activity", "Stopped")
        vm.stopLocationUpdates()
        win.solobot.omodash.projection.taskhost.TaskHostState.close(
            win.solobot.omodash.projection.taskhost.TaskHostState.token)
    }

    override fun onStart() {
        super.onStart()
        win.solobot.omodash.diagnostics.DiagnosticLog.event("Activity", "Started")
        vm.startLocationUpdates()
    }
}
