package com.openlauncher.app.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

enum class ClockStyle { DIGITAL, ANALOG }
enum class UnitSystem { METRIC, IMPERIAL }
enum class AppFont { SYSTEM, JETBRAINS_MONO, SOURCE_CODE_PRO }
enum class DayNightMode { DARK, LIGHT, AUTO, SYSTEM }
enum class SidebarPosition { LEFT, RIGHT, BOTTOM }
enum class GradientDirection { TOP_TO_BOTTOM, LEFT_TO_RIGHT, DIAGONAL, RADIAL }

enum class DefaultShortcutIcon {
    NONE,
    // Navigation & vehicle
    RADIO, CAMERA, PHONE, MAP, NAVIGATION, CAR, GAS_STATION, DASHBOARD,
    // Audio & media
    MUSIC, SPEAKER, HEADSET, EQUALIZER, VOLUME_UP,
    // Connectivity
    BLUETOOTH, WIFI,
    // Lighting & climate
    LIGHTBULB, BRIGHTNESS, AC, THERMOSTAT,
    // General utility
    TV, VIDEOCAM, STAR, MESSAGE, TIMER, LOCK, SETTINGS, FAVORITE,
    // Web / location
    GLOBE
}

data class SoundPadConfig(
    val label: String,
    val audioUri: String = "",
    val synthType: String = "BEEP"
)

fun defaultSoundboardPads() = listOf(
    SoundPadConfig("mario_jump",   synthType = "mario_jump"),
    SoundPadConfig("mario_coin",   synthType = "mario_coin"),
    SoundPadConfig("boom",         synthType = "boom"),
    SoundPadConfig("loud_fart",    synthType = "loud_fart"),
    SoundPadConfig("+",            synthType = ""),
    SoundPadConfig("+",            synthType = "")
)

data class ShortcutConfig(
    val packageName: String = "",
    val label: String = "",
    val isDefault: Boolean = false,
    val defaultIcon: DefaultShortcutIcon = DefaultShortcutIcon.NONE,
    // null = native app icon; non-null = override with this vector icon
    val customIconOverride: DefaultShortcutIcon? = null
)

const val GRID_COLS = 6
const val GRID_ROWS = 2

data class WidgetConfig(
    val id: String,          // "CLOCK" | "WEATHER" | "TELEMETRY" | "NOW_PLAYING"
    val gridX: Int,          // column 0..(GRID_COLS-1)
    val gridY: Int,          // row    0..(GRID_ROWS-1)
    val spanX: Int = 1,
    val spanY: Int = 1,
    val enabled: Boolean = true
)

data class AppSettings(
    val vehicleName: String = "MY CAR",
    val accentColor: Int = Color.White.toArgb(),
    val backgroundColor: Int = Color.Black.toArgb(),
    val fontColor: Int = Color.White.toArgb(),
    val wallpaperUri: String = "",
    val fontBold: Boolean = false,
    val textScale: Float = 1.2f,
    val uiScale: Float = 1.0f,
    val clockStyle: ClockStyle = ClockStyle.DIGITAL,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val appFont: AppFont = AppFont.JETBRAINS_MONO,
    val showWeather: Boolean = true,
    val showClock: Boolean = true,
    val showTelemetry: Boolean = true,
    val showNowPlaying: Boolean = true,
    val shortcuts: List<ShortcutConfig> = defaultShortcuts(),
    val widgetLayout: List<WidgetConfig> = defaultWidgetLayout(),
    val carPlayPackage: String = OmodaDefaults.CARPLAY,
    val androidAutoPackage: String = OmodaDefaults.ANDROID_AUTO,
    val useGradient: Boolean = false,
    val gradientEndColor: Int = Color.Black.toArgb(),
    val wallpaperDim: Float = 0.55f,
    val sidebarPosition: SidebarPosition = SidebarPosition.LEFT,
    val bottomBarShortcutsRight: Boolean = false,
    val showAltimeter: Boolean = false,
    val showSpeedometer: Boolean = false,
    val dayNightMode: DayNightMode = DayNightMode.DARK,
    // Head unit's radio app — mirrored & controlled via its MediaSession
    val radioPackage: String = OmodaDefaults.AUDIO,
    val onboardingCompleted: Boolean = false,
    val showVitals: Boolean = false,
    val showTripTracker: Boolean = false,
    val compassOffset: Float = 0f,
    val showSoundboard: Boolean = false,
    val soundboardPads: List<SoundPadConfig> = defaultSoundboardPads(),
    val vitalsAsBars: Boolean = false,
    val speedometerDigitalOnly: Boolean = false,
    val gradientDirection: GradientDirection = GradientDirection.DIAGONAL,
    val useCustomBackgroundColor: Boolean = false
)

fun defaultShortcuts() = listOf(
    ShortcutConfig(packageName = OmodaDefaults.AUDIO, label = "Radio", isDefault = true, defaultIcon = DefaultShortcutIcon.RADIO),
    ShortcutConfig(packageName = OmodaDefaults.CAMERA, label = "Camera", isDefault = true, defaultIcon = DefaultShortcutIcon.CAMERA),
    ShortcutConfig(packageName = OmodaDefaults.AUDIO, label = "Music", isDefault = true, defaultIcon = DefaultShortcutIcon.MUSIC),
    ShortcutConfig(packageName = OmodaDefaults.PHONE, label = "Phone", isDefault = true, defaultIcon = DefaultShortcutIcon.PHONE)
)

fun defaultWidgetLayout() = listOf(
    WidgetConfig("CLOCK",       gridX = 0, gridY = 0, spanX = 2, spanY = 1),
    WidgetConfig("WEATHER",     gridX = 2, gridY = 0, spanX = 2, spanY = 1),
    WidgetConfig("TELEMETRY",   gridX = 4, gridY = 0, spanX = 2, spanY = 2),
    WidgetConfig("NOW_PLAYING", gridX = 0, gridY = 1, spanX = 4, spanY = 1)
)

fun AppSettings.activeWidgetIds(): Set<String> = buildSet {
    if (showClock) add("CLOCK")
    if (showWeather) add("WEATHER")
    if (showNowPlaying) add("NOW_PLAYING")
    if (showTelemetry) add("TELEMETRY")
    if (showAltimeter) add("ALTIMETER")
    if (showSpeedometer) add("SPEEDOMETER")
    if (showVitals) add("VITALS")
    if (showTripTracker) add("TRIP_TRACKER")
    if (showSoundboard) add("SOUNDBOARD")
}

/** Moves only when every active widget can still be placed without changing its size. */
fun computeWidgetMove(
    layout: List<WidgetConfig>,
    movingId: String,
    targetX: Int,
    targetY: Int
): List<WidgetConfig> {
    val moving = layout.find { it.id == movingId } ?: return layout
    if (moving.spanX !in 1..GRID_COLS || moving.spanY !in 1..GRID_ROWS) return layout
    val requested = layout.map { if (it.id == movingId) it.copy(
        gridX = targetX.coerceIn(0, GRID_COLS - moving.spanX),
        gridY = targetY.coerceIn(0, GRID_ROWS - moving.spanY)) else it }
    return packWidgetLayout(requested, pinnedId = movingId) ?: layout
}
