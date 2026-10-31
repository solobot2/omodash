package win.solobot.omodash.ui.screen

import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import win.solobot.omodash.data.AppSettings
import win.solobot.omodash.data.ClockStyle
import win.solobot.omodash.data.computeWidgetMove
import win.solobot.omodash.data.GRID_COLS
import win.solobot.omodash.data.GRID_ROWS
import win.solobot.omodash.data.WidgetConfig
import win.solobot.omodash.data.WidgetSizeRules
import win.solobot.omodash.data.widgetSizeRules
import win.solobot.omodash.data.withResizedWidget
import win.solobot.omodash.data.withAddedWidget
import win.solobot.omodash.model.NowPlayingState
import win.solobot.omodash.model.WeatherState
import win.solobot.omodash.ui.theme.LocalDayMode
import win.solobot.omodash.ui.theme.LocalDashboardPalette
import win.solobot.omodash.ui.components.dashboardSurface
import win.solobot.omodash.ui.components.RoundedSymbol
import win.solobot.omodash.ui.widget.*
import java.util.Calendar
import win.solobot.omodash.util.LocationData

private val WIDGET_RADIUS = RoundedCornerShape(14.dp)

private data class WidgetTypeInfo(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val description: String
)

private val ALL_WIDGET_TYPES = listOf(
    WidgetTypeInfo("CLOCK",       "CLOCK",       Icons.Rounded.AccessTime,  "Time & date"),
    WidgetTypeInfo("WEATHER",     "WEATHER",     Icons.Rounded.Cloud,       "Current conditions"),
    WidgetTypeInfo("NOW_PLAYING", "NOW PLAYING", Icons.Rounded.MusicNote,   "Media controls"),
    WidgetTypeInfo("TELEMETRY",   "COMPASS",     Icons.Rounded.Explore,     "Speed & heading"),
    WidgetTypeInfo("ALTIMETER",   "ALTIMETER",   Icons.Rounded.FlightTakeoff, "Roll, pitch & altitude"),
    WidgetTypeInfo("SPEEDOMETER", "SPEED",       Icons.Rounded.Speed,         "GPS speed"),
    WidgetTypeInfo("VITALS",      "VITALS",      Icons.Rounded.Dns,           "Head Unit Health / Vitals"),
    WidgetTypeInfo("TRIP_TRACKER", "TRIP TRACKER", Icons.Rounded.Map,          "Trip logs & stats"),
    WidgetTypeInfo("SOUNDBOARD",  "SOUNDBOARD",  Icons.Rounded.Piano,         "Custom sound pads")
)

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun HomeScreen(
    settings: AppSettings,
    weather: WeatherState?,
    nowPlaying: NowPlayingState?,
    location: LocationData?,
    bearing: Float,
    isWifi: Boolean,
    isData: Boolean,
    isDayMode: Boolean = false,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onLaunchCarPlay: () -> Unit,
    onLaunchAndroidAuto: () -> Unit,
    onTapNowPlaying: () -> Unit,
    onUpdateWidget: (id: String, spanX: Int, spanY: Int) -> Unit,
    onMoveWidget: (id: String, gridX: Int, gridY: Int) -> Unit,
    onAddWidget: (id: String) -> Unit,
    onRemoveWidget: (id: String) -> Unit,
    onSetClockStyle: (ClockStyle) -> Unit,
    onSetVitalsAsBars: (Boolean) -> Unit = {},
    onSetSpeedometerDigitalOnly: (Boolean) -> Unit = {},
    onUpdateSoundPad: (index: Int, pad: win.solobot.omodash.data.SoundPadConfig) -> Unit = { _, _ -> },
    hardwareRadio: win.solobot.omodash.viewmodel.LauncherViewModel.HardwareRadioState? = null,
    onLaunchHardwareRadio: () -> Unit = {},
    onStartHardwareRadio: () -> Unit = onLaunchHardwareRadio,
    onStopHardwareRadio: () -> Unit = {},
    onRadioSeekUp: () -> Unit = {},
    onRadioSeekDown: () -> Unit = {},
    onRadioCycleFm: () -> Unit = {},
    onRadioSwitchAm: () -> Unit = {},
    onRadioTune: (band: String, freq: Float) -> Unit = { _, _ -> },
    onAssignRadio: () -> Unit = {},
    projectionContent: (@Composable () -> Unit)? = null,
    onDismissProjection: () -> Unit = {},
    weatherMessage: String = "Waiting for weather data",
    onRetryWeather: () -> Unit = {},
    onSeekTo: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val palette = LocalDashboardPalette.current
    val accent = palette.navAccent
    val gap = 12.dp
    val headerTextColor = palette.text
    val statusIconColor = palette.muted
    val controlIconColor = palette.muted

    var resizingId    by remember { mutableStateOf<String?>(null) }
    var contextMenuId by remember { mutableStateOf<String?>(null) }
    var selectedMediaSource by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("Automatic") }

    val configuration    = LocalConfiguration.current
    val isLandscape      = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var editMode         by remember { mutableStateOf(false) }
    var widgetLibraryOpen by remember { mutableStateOf(false) }

    // WEATHER stays in the set even with no data: the commit path
    // (LauncherViewModel.moveWidgetConfig) computes against settings flags
    // only, so dropping it here would make the drop ghost and the committed
    // layout disagree. Weather keeps its card and displays its loading/error state.
    val visibleIds = buildSet {
        if (settings.showClock) add("CLOCK")
        if (settings.showWeather) add("WEATHER")
        if (settings.showNowPlaying) add("NOW_PLAYING")
        if (settings.showTelemetry) add("TELEMETRY")
        if (settings.showAltimeter) add("ALTIMETER")
        if (settings.showSpeedometer) add("SPEEDOMETER")
        if (settings.showVitals) add("VITALS")
        if (settings.showTripTracker) add("TRIP_TRACKER")
        if (settings.showSoundboard) add("SOUNDBOARD")
    }

    // Keep only visible widgets exactly as configured in settings, allowing explicit resizing to dictate layout
    val visible = settings.widgetLayout.filter { it.enabled && it.id in visibleIds }
    val rendered = visible

    var homeOrigin by remember { mutableStateOf(IntOffset.Zero) }
    val widgetBounds = remember { mutableStateMapOf<String, IntRect>() }
    Box(modifier.fillMaxSize().onGloballyPositioned { homeOrigin = it.positionInRoot().round() }) {
        Column(Modifier.fillMaxSize()) {

            // ── Header ──────────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text          = settings.vehicleName.uppercase(),
                    style         = MaterialTheme.typography.titleLarge,
                    color         = headerTextColor,
                    letterSpacing = 3.sp,
                    fontSize      = 14.sp
                )
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(visible = isWifi, enter = fadeIn(), exit = fadeOut()) {
                    Icon(Icons.Rounded.Wifi, "WiFi", tint = statusIconColor, modifier = Modifier.size(16.dp))
                }
                if (isWifi) Spacer(Modifier.width(6.dp))
                AnimatedVisibility(visible = isData, enter = fadeIn(), exit = fadeOut()) {
                    Icon(Icons.Rounded.SignalCellularAlt, "Data", tint = statusIconColor, modifier = Modifier.size(16.dp))
                }
                if (projectionContent != null) {
                    IconButton(onClick = onDismissProjection, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Rounded.Close, "Close projection", tint = headerTextColor, modifier = Modifier.size(20.dp))
                    }
                }
                if (isLandscape) {
                    Spacer(Modifier.width(8.dp))
                    if (editMode) {
                        IconButton(
                            onClick  = { widgetLibraryOpen = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector        = Icons.Rounded.Dashboard,
                                contentDescription = "Widget library",
                                tint               = controlIconColor,
                                modifier           = Modifier.size(15.dp)
                            )
                        }
                        Spacer(Modifier.width(2.dp))
                    }
                    IconButton(
                        onClick  = { onDismissProjection(); editMode = !editMode },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector        = Icons.Rounded.Edit,
                            contentDescription = "Edit widgets",
                            tint               = if (editMode) accent else controlIconColor,
                            modifier           = Modifier.size(15.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF141414))

            // ── Widget Grid ─────────────────────────────────────────────────────
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(gap)
            ) {
                val density = LocalDensity.current
                val availableWidth = with(density) { constraints.maxWidth.toDp() }
                val availableHeight = with(density) { constraints.maxHeight.toDp() }
                val cellW = (availableWidth - gap * (GRID_COLS - 1)) / GRID_COLS
                val cellH = (availableHeight - gap * (GRID_ROWS - 1)) / GRID_ROWS
                val cellStepXPx = with(density) { (cellW + gap).toPx() }
                val cellStepYPx = with(density) { (cellH + gap).toPx() }

                // ── Drag state ───────────────────────────────────────────────────
                var draggingId   by remember { mutableStateOf<String?>(null) }
                var dragOffsetPx by remember { mutableStateOf(Offset.Zero) }

                // Compute snap target for the widget being dragged (uses original spanX)
                val draggingOriginal = if (draggingId != null) visible.find { it.id == draggingId } else null
                val targetGridX = draggingOriginal?.let {
                    (it.gridX + (dragOffsetPx.x / cellStepXPx).roundToInt()).coerceIn(0, GRID_COLS - it.spanX)
                }
                val targetGridY = draggingOriginal?.let {
                    (it.gridY + (dragOffsetPx.y / cellStepYPx).roundToInt()).coerceIn(0, GRID_ROWS - it.spanY)
                }

                // Compute proposed layout (push preview) while dragging
                val proposedLayout = remember(visible, draggingId, targetGridX, targetGridY) {
                    if (draggingOriginal != null && targetGridX != null && targetGridY != null)
                        computeWidgetMove(visible, draggingOriginal.id, targetGridX, targetGridY)
                    else null
                }

                // Drop ghost — rendered before widgets so it appears beneath them
                if (draggingOriginal != null && targetGridX != null && targetGridY != null) {
                    val placed = proposedLayout?.find { it.id == draggingOriginal.id } ?: draggingOriginal
                    val gX = (cellW + gap) * placed.gridX
                    val gY = (cellH + gap) * placed.gridY
                    val gW = cellW * draggingOriginal.spanX + gap * (draggingOriginal.spanX - 1)
                    val gH = cellH * draggingOriginal.spanY + gap * (draggingOriginal.spanY - 1)
                    Box(
                        modifier = Modifier
                            .absoluteOffset(x = gX, y = gY)
                            .size(gW, gH)
                            .background(accent.copy(alpha = 0.08f))
                            .border(1.dp, accent.copy(alpha = 0.5f), WIDGET_RADIUS)
                    )
                }

                // Displacement ghosts — show where pushed widgets will land
                if (proposedLayout != null && draggingOriginal != null) {
                    proposedLayout
                        .filter { it.id != draggingOriginal.id }
                        .forEach { proposed ->
                            val original = visible.find { it.id == proposed.id } ?: return@forEach
                            if (proposed.gridX != original.gridX || proposed.gridY != original.gridY) {
                                val dX = (cellW + gap) * proposed.gridX
                                val dY = (cellH + gap) * proposed.gridY
                                val dW = cellW * proposed.spanX + gap * (proposed.spanX - 1)
                                val dH = cellH * proposed.spanY + gap * (proposed.spanY - 1)
                                Box(
                                    modifier = Modifier
                                        .absoluteOffset(x = dX, y = dY)
                                        .size(dW, dH)
                                        .border(1.dp, Color.White.copy(alpha = 0.25f), WIDGET_RADIUS)
                                )
                            }
                        }
                }

                rendered.forEach { w ->
                    val xOff   = (cellW + gap) * w.gridX
                    val yOff   = (cellH + gap) * w.gridY
                    val width  = cellW * w.spanX + gap * (w.spanX - 1)
                    val height = cellH * w.spanY + gap * (w.spanY - 1)

                    val label = when (w.id) {
                        "CLOCK"       -> clockTimeLabel(Calendar.getInstance())
                        "WEATHER"     -> "WEATHER"
                        "NOW_PLAYING" -> "NOW PLAYING"
                        "TELEMETRY"   -> "COMPASS"
                        "ALTIMETER"   -> "ALTIMETER"
                        "SPEEDOMETER" -> "SPEED"
                        "TRIP_TRACKER" -> "TRIP"
                        "SOUNDBOARD"  -> "SOUND"
                        else          -> w.id
                    }

                    // Original (pre-auto-expand) spanX needed for drag boundary clamping
                    val origSpanX  = visible.find { it.id == w.id }?.spanX ?: 1
                    val isDragging = draggingId == w.id
                    val dragDpX    = if (isDragging) with(density) { dragOffsetPx.x.toDp() } else 0.dp
                    val dragDpY    = if (isDragging) with(density) { dragOffsetPx.y.toDp() } else 0.dp

                    @OptIn(ExperimentalFoundationApi::class)
                    Box(
                        modifier = Modifier
                            .absoluteOffset(x = xOff + dragDpX, y = yOff + dragDpY)
                            .size(width, height)
                            .zIndex(if (isDragging) 1f else 0f)
                            .onGloballyPositioned { coordinates ->
                                widgetBounds[w.id] = IntRect(coordinates.positionInRoot().round(), coordinates.size)
                            }
                            .dashboardSurface(highlighted = editMode)
                            .combinedClickable(
                                indication        = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick           = { if (editMode) contextMenuId = w.id },
                                onLongClick       = { if (!editMode) contextMenuId = w.id }
                            )
                            .then(
                                if (editMode) Modifier.pointerInput(editMode, w.id, w.gridX, w.gridY) {
                                    var hasSignificantDrag = false
                                    // Touch-slop gate: without it, sub-pixel jitter during a
                                    // long-press counts as a drag and the context menu never opens
                                    val slop = viewConfiguration.touchSlop
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { _ ->
                                            draggingId         = w.id
                                            dragOffsetPx       = Offset.Zero
                                            hasSignificantDrag = false
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffsetPx      += dragAmount
                                            if (!hasSignificantDrag && dragOffsetPx.getDistance() > slop) {
                                                hasSignificantDrag = true
                                            }
                                        },
                                        onDragEnd = {
                                            if (hasSignificantDrag) {
                                                val newX = (w.gridX + (dragOffsetPx.x / cellStepXPx).roundToInt())
                                                    .coerceIn(0, GRID_COLS - origSpanX)
                                                val newY = (w.gridY + (dragOffsetPx.y / cellStepYPx).roundToInt())
                                                    .coerceIn(0, GRID_ROWS - w.spanY)
                                                onMoveWidget(w.id, newX, newY)
                                            } else {
                                                contextMenuId = w.id
                                            }
                                            draggingId   = null
                                            dragOffsetPx = Offset.Zero
                                        },
                                        onDragCancel = {
                                            draggingId   = null
                                            dragOffsetPx = Offset.Zero
                                        }
                                    )
                                } else Modifier
                            )
                    ) {
                        when (w.id) {
                            "CLOCK" -> ClockWidget(
                                style      = settings.clockStyle,
                                accent     = accent,
                                isDayMode  = isDayMode,
                                modifier   = Modifier.fillMaxSize()
                            )
                            "WEATHER" -> WeatherWidget(
                                state      = weather,
                                accent     = accent,
                                metric     = settings.unitSystem.name == "METRIC",
                                isDayMode  = isDayMode,
                                message    = weatherMessage,
                                onRetry    = onRetryWeather,
                                modifier   = Modifier.fillMaxSize()
                            )
                            "NOW_PLAYING" -> NowPlayingWidget(
                                state               = nowPlaying,
                                selectedSource      = selectedMediaSource,
                                accent              = accent,
                                onPlayPause         = onPlayPause,
                                onSeekTo            = onSeekTo,
                                onNext              = onNext,
                                onPrev              = onPrev,
                                onOpenContextMenu   = { contextMenuId = w.id },
                                onTapToOpenApp      = onTapNowPlaying,
                                modifier            = Modifier.fillMaxSize(),
                                isEditing           = editMode,
                                isDayMode           = isDayMode,
                                hardwareRadio         = hardwareRadio,
                                onLaunchHardwareRadio = onLaunchHardwareRadio,
                                onStartHardwareRadio  = onStartHardwareRadio,
                                onStopHardwareRadio   = onStopHardwareRadio,
                                onRadioSeekUp         = onRadioSeekUp,
                                onRadioSeekDown       = onRadioSeekDown,
                                onRadioCycleFm        = onRadioCycleFm,
                                onRadioSwitchAm       = onRadioSwitchAm,
                                onRadioTune           = onRadioTune,
                                onAssignRadio         = onAssignRadio,
                                radioPackage          = settings.radioPackage,
                                allowRadioAssignment = win.solobot.omodash.data.VehiclePresets.find(settings.vehiclePresetId).allowsAppAssignment,
                                radioSessionPackages = win.solobot.omodash.data.VehiclePresets.find(settings.vehiclePresetId).radioSessionPackages
                            )
                            "TELEMETRY" -> TelemetryWidget(
                                location  = location,
                                bearing   = (bearing + settings.compassOffset + 360f) % 360f,
                                accent    = accent,
                                isDayMode = isDayMode,
                                modifier  = Modifier.fillMaxSize()
                            )
                            "ALTIMETER" -> AltimeterWidget(
                                location  = location,
                                isMetric  = settings.unitSystem == win.solobot.omodash.data.UnitSystem.METRIC,
                                accent    = accent,
                                isDayMode = isDayMode,
                                modifier  = Modifier.fillMaxSize()
                            )
                            "SPEEDOMETER" -> SpeedometerWidget(
                                location  = location,
                                isMetric  = settings.unitSystem == win.solobot.omodash.data.UnitSystem.METRIC,
                                accent    = accent,
                                isDayMode = isDayMode,
                                digitalOnly = settings.speedometerDigitalOnly,
                                modifier  = Modifier.fillMaxSize()
                            )
                            "VITALS" -> VitalsWidget(
                                accent    = accent,
                                isDayMode = isDayMode,
                                asBars    = settings.vitalsAsBars,
                                modifier  = Modifier.fillMaxSize()
                            )
                            "TRIP_TRACKER" -> TripTrackerWidget(
                                location  = location,
                                isMetric  = settings.unitSystem == win.solobot.omodash.data.UnitSystem.METRIC,
                                accent    = accent,
                                isDayMode = isDayMode,
                                modifier  = Modifier.fillMaxSize()
                            )
                            "SOUNDBOARD" -> SoundboardWidget(
                                pads      = settings.soundboardPads,
                                accent    = accent,
                                isDayMode = isDayMode,
                                isEditing = editMode,
                                onUpdatePad = onUpdateSoundPad,
                                modifier  = Modifier.fillMaxSize()
                            )
                        }

                        if (w.id != "NOW_PLAYING") IconButton(
                            onClick = { contextMenuId = w.id },
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(40.dp)) {
                            RoundedSymbol(win.solobot.omodash.R.drawable.symbol_more_vert, Modifier.size(23.dp),
                                "$label menu", palette.muted)
                        }

                        // Label — hide when album art fills the widget background
                        val labelColor = when {
                            w.id == "NOW_PLAYING" && nowPlaying?.albumArt != null && nowPlaying.title.isNotEmpty() -> Color.Transparent
                            else -> palette.muted.copy(alpha = 0.65f)
                        }
                        Text(
                            text          = label,
                            style         = MaterialTheme.typography.labelSmall,
                            color         = labelColor,
                            letterSpacing = 2.sp,
                            fontSize      = 8.sp,
                            modifier      = Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 10.dp, top = 7.dp)
                        )
                    }
                }
                // Covers only the tile area: header and sidebar stay outside this container.
                if (!editMode && projectionContent != null) Box(Modifier.fillMaxSize().zIndex(10f)) { projectionContent() }
            }
        }

        // This host includes the header and grid padding, so the scrim reaches
        // the navigation/climate boundaries without leaving undimmed strips.
        val selectedId = resizingId ?: contextMenuId
        val selectedWidget = visible.firstOrNull { it.id == selectedId }
        val bounds = selectedWidget?.let { widgetBounds[it.id] }
        if (bounds != null) {
            val anchor = IntRect(bounds.topLeft - homeOrigin, bounds.size)
            win.solobot.omodash.ui.components.WidgetOverlay(anchor, { contextMenuId = null; resizingId = null }) {
                // ── Widget context menu (long-press any cell) ────────────────────────────
                contextMenuId?.let { id ->
                    WidgetContextMenu(
                        widgetId            = id,
                        sizeRules           = settings.widgetSizeRules(id),
                        accent              = accent,
                        clockStyle          = settings.clockStyle,
                        vitalsAsBars        = settings.vitalsAsBars,
                        speedometerDigitalOnly = settings.speedometerDigitalOnly,
                        isDayMode           = isDayMode,
                        onResize            = { contextMenuId = null; resizingId = id },
                        onSetClockStyle     = { onSetClockStyle(it) },
                        onSetVitalsAsBars   = { onSetVitalsAsBars(it) },
                        onSetSpeedometerDigitalOnly = { onSetSpeedometerDigitalOnly(it) },
                        onDismiss           = { contextMenuId = null },
                        mediaItems = {
                            if (id == "NOW_PLAYING") {
                                listOf("Automatic", "All Media", "FM/AM Radio").forEach { source ->
                                    ContextRow(source, if (source == "FM/AM Radio") Icons.Rounded.Radio else Icons.Rounded.MusicNote,
                                        if (selectedMediaSource == source) accent else palette.muted,
                                        { selectedMediaSource = source; contextMenuId = null }, isDayMode)
                                }
                                if (settings.carPlayPackage.isNotBlank()) ContextRow("Open CarPlay", Icons.Rounded.PhoneAndroid,
                                    palette.text, { contextMenuId = null; onLaunchCarPlay() }, isDayMode)
                                if (settings.androidAutoPackage.isNotBlank()) ContextRow("Open Android Auto", Icons.Rounded.DirectionsCar,
                                    palette.text, { contextMenuId = null; onLaunchAndroidAuto() }, isDayMode)
                                if (win.solobot.omodash.data.VehiclePresets.find(settings.vehiclePresetId).allowsAppAssignment)
                                    ContextRow("Change radio app", Icons.Rounded.Radio, palette.text,
                                        { contextMenuId = null; onAssignRadio() }, isDayMode)
                            }
                        }
                    )
                }

                // ── Resize dialog ────────────────────────────────────────────────────────
                resizingId?.let { id ->
                    val config = settings.widgetLayout.find { it.id == id }
                    if (config != null) {
                        WidgetResizeDialog(
                            config    = config,
                            settings  = settings,
                            accent    = accent,
                            isDayMode = isDayMode,
                            onDismiss = { resizingId = null },
                            onConfirm = { sx, sy ->
                                onUpdateWidget(id, sx, sy)
                                resizingId = null
                            }
                        )
                    }
                }
            }
        }
    }

    // ── Widget library ────────────────────────────────────────────────────────
    if (widgetLibraryOpen) {
        WidgetLibraryDialog(
            settings  = settings,
            accent    = accent,
            isDayMode = isDayMode,
            onAdd     = { id -> onAddWidget(id) },
            onRemove  = { id -> onRemoveWidget(id) },
            onDismiss = { widgetLibraryOpen = false }
        )
    }
}

@Composable
internal fun WidgetContextMenu(
    widgetId: String,
    sizeRules: WidgetSizeRules,
    accent: Color,
    clockStyle: ClockStyle,
    vitalsAsBars: Boolean,
    speedometerDigitalOnly: Boolean,
    isDayMode: Boolean,
    onResize: () -> Unit,
    onSetClockStyle: (ClockStyle) -> Unit,
    onSetVitalsAsBars: (Boolean) -> Unit,
    onSetSpeedometerDigitalOnly: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    mediaItems: @Composable ColumnScope.() -> Unit = {}
) {
    val menuBg    = if (isDayMode) Color(0xFFFFFFFF) else Color(0xFF111111)
    val menuBorder = if (isDayMode) Color(0xFFDDE1E5) else Color(0xFF1E1E1E)
    val menuDivider = if (isDayMode) Color(0xFFF1F3F5) else Color(0xFF1A1A1A)
    Box {
        Column(
            modifier = Modifier
                .width(260.dp)
                .dashboardSurface()
                .padding(vertical = 6.dp)
                .verticalScroll(rememberScrollState())
        ) {
            val inactiveMenuTint = LocalDashboardPalette.current.muted
            mediaItems()
            if (sizeRules.isResizable) {
                ContextRow("RESIZE", Icons.Rounded.OpenWith, accent, onResize, isDayMode = isDayMode)
            } else {
                Text("FIXED SIZE ${sizeRules.sizes.single()}", color = inactiveMenuTint,
                    fontSize = 10.sp, modifier = Modifier.padding(16.dp))
            }
            if (widgetId == "CLOCK") {
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = "DIGITAL",
                    icon    = Icons.Rounded.Schedule,
                    tint    = if (clockStyle == ClockStyle.DIGITAL) accent else inactiveMenuTint,
                    onClick = { onSetClockStyle(ClockStyle.DIGITAL); onDismiss() },
                    isDayMode = isDayMode
                )
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = "ANALOG",
                    icon    = Icons.Rounded.Watch,
                    tint    = if (clockStyle == ClockStyle.ANALOG) accent else inactiveMenuTint,
                    onClick = { onSetClockStyle(ClockStyle.ANALOG); onDismiss() },
                    isDayMode = isDayMode
                )
            }
            if (widgetId == "VITALS") {
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = "DIAL GAUGES",
                    icon    = Icons.Rounded.Adjust,
                    tint    = if (!vitalsAsBars) accent else inactiveMenuTint,
                    onClick = { onSetVitalsAsBars(false); onDismiss() },
                    isDayMode = isDayMode
                )
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = "BARS VIEW",
                    icon    = Icons.Rounded.FormatAlignLeft,
                    tint    = if (vitalsAsBars) accent else inactiveMenuTint,
                    onClick = { onSetVitalsAsBars(true); onDismiss() },
                    isDayMode = isDayMode
                )
            }
            if (widgetId == "SPEEDOMETER") {
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = "DIAL TRACK",
                    icon    = Icons.Rounded.Speed,
                    tint    = if (!speedometerDigitalOnly) accent else inactiveMenuTint,
                    onClick = { onSetSpeedometerDigitalOnly(false); onDismiss() },
                    isDayMode = isDayMode
                )
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = "DIGITAL ONLY",
                    icon    = Icons.Rounded.Dialpad,
                    tint    = if (speedometerDigitalOnly) accent else inactiveMenuTint,
                    onClick = { onSetSpeedometerDigitalOnly(true); onDismiss() },
                    isDayMode = isDayMode
                )
            }


        }
    }
}

@Composable
private fun ContextRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
    isDayMode: Boolean = false
) {
    val finalTint = tint
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, tint = finalTint, modifier = Modifier.size(16.dp))
        Text(label, color = finalTint, fontSize = 10.sp, letterSpacing = 1.sp)
    }
}

@Composable
internal fun WidgetResizeDialog(
    config: WidgetConfig,
    settings: AppSettings,
    accent: Color,
    isDayMode: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (spanX: Int, spanY: Int) -> Unit
) {
    val rules = settings.widgetSizeRules(config.id)
    var selected by remember(config) { mutableStateOf(rules.closest(config.spanX, config.spanY)) }
    val available = remember(settings, config.id) {
        rules.sizes.associateWith { settings.withResizedWidget(config.id, it.width, it.height) != null }
    }
    val dialogBg = if (isDayMode) Color.White else MaterialTheme.colorScheme.background
    val dialogText = if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground
    val selectionColor = if (isDayMode) Color(0xFF111111) else accent
    val selectionText = if (isDayMode) Color.White else Color.Black
    val actionColors = ButtonDefaults.textButtonColors(contentColor = selectionColor)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val title = config.id.replace('_', ' ')
    val titleStyle = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, lineHeight = 18.sp)
    val labelStyle = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp, lineHeight = 16.sp)
    // Rectangular choices form one row per height. Never wrap 4×1 into the 1×2 row.
    val rows = remember(rules) {
        if (rules.isSquare) listOf(rules.sizes)
        else rules.sizes.groupBy { it.height }.toSortedMap().values.map { it.sortedBy { size -> size.width } }
    }
    val chipWidth = with(density) {
        rules.sizes.maxOf { measurer.measure(AnnotatedString(it.toString()), labelStyle).size.width }.toDp()
    } + 32.dp // Material FilterChip: 8 dp outer + 8 dp label padding on each side.
    val choiceWidth = maxOf(56.dp, chipWidth)
    val columns = rows.maxOf { it.size }
    val gridWidth = choiceWidth * columns + 8.dp * (columns - 1)
    val titleWidth = with(density) { measurer.measure(AnnotatedString(title), titleStyle).size.width.toDp() }
    val actionsWidth = listOf("CANCEL", "APPLY").fold(8.dp) { width, label ->
        width + maxOf(64.dp, with(density) {
            measurer.measure(AnnotatedString(label), labelStyle).size.width.toDp()
        } + 24.dp)
    }
    BoxWithConstraints {
        val availableWidth = with(density) { constraints.maxWidth.toDp() }
        val availableHeight = with(density) { constraints.maxHeight.toDp() }
        val width = maxOf(200.dp, gridWidth + 32.dp, titleWidth + 32.dp, actionsWidth + 32.dp)
            .coerceAtMost(availableWidth)
        Surface(
            modifier = Modifier.width(width).heightIn(max = availableHeight),
            shape = RoundedCornerShape(12.dp), color = dialogBg, contentColor = dialogText
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = titleStyle)
                // At combined large UI/text scales, scroll complete rows together.
                // Width choices keep their height grouping at every scale and font.
                Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    rows.forEach { sizes ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            sizes.forEach { size ->
                                FilterChip(
                                    selected = selected == size, enabled = available[size] == true,
                                    onClick = { selected = size }, modifier = Modifier.width(choiceWidth),
                                    label = { Text(size.toString(), style = labelStyle, maxLines = 1, softWrap = false) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = selectionColor, selectedLabelColor = selectionText)
                                )
                            }
                        }
                    }
                }
                if (available.values.any { !it }) Text("Dimmed sizes do not fit the current layout.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp)
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss, colors = actionColors) { Text("CANCEL", style = labelStyle) }
                    TextButton(enabled = available[selected] == true, colors = actionColors,
                        onClick = { onConfirm(selected.width, selected.height) }) { Text("APPLY", style = labelStyle) }
                }
            }
        }
    }
}

// ── Widget Library ────────────────────────────────────────────────────────────

@Composable
private fun WidgetLibraryDialog(
    settings: AppSettings,
    accent: Color,
    isDayMode: Boolean,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val dialogBg    = if (isDayMode) Color(0xFFEEEEEE) else Color(0xFF0C0C0C)
    val dialogBorder = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF1E1E1E)
    val titleColor  = if (isDayMode) Color(0xFF495057) else Color(0xFF555555)
    val closeColor  = if (isDayMode) Color(0xFF495057) else Color(0xFF444444)

    val activeIds = buildSet {
        if (settings.showClock) add("CLOCK")
        if (settings.showWeather) add("WEATHER")
        if (settings.showNowPlaying) add("NOW_PLAYING")
        if (settings.showTelemetry) add("TELEMETRY")
        if (settings.showAltimeter) add("ALTIMETER")
        if (settings.showSpeedometer) add("SPEEDOMETER")
        if (settings.showVitals) add("VITALS")
        if (settings.showTripTracker) add("TRIP_TRACKER")
        if (settings.showSoundboard) add("SOUNDBOARD")
    }
    val additions = remember(settings) {
        ALL_WIDGET_TYPES.associate { it.id to (settings.withAddedWidget(it.id) != null) }
    }
    val canAdd = ALL_WIDGET_TYPES.any { it.id !in activeIds && additions[it.id] == true }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(dialogBg)
                .border(1.dp, dialogBorder, RoundedCornerShape(4.dp))
                .padding(16.dp)
                .widthIn(min = 320.dp, max = 520.dp)
        ) {
            Row(
                modifier          = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text          = "WIDGET LIBRARY",
                    color         = titleColor,
                    fontSize      = 9.sp,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Rounded.Close, null, tint = closeColor, modifier = Modifier.size(14.dp))
                }
            }

            LazyVerticalGrid(
                columns               = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement   = Arrangement.spacedBy(6.dp),
                modifier              = Modifier.fillMaxWidth()
            ) {
                items(ALL_WIDGET_TYPES) { info ->
                    val isActive = info.id in activeIds
                    WidgetLibraryCard(
                        info     = info,
                        isActive = isActive,
                        canAdd   = additions[info.id] == true,
                        accent   = accent,
                        isDayMode = isDayMode,
                        onToggle = { if (isActive) onRemove(info.id) else onAdd(info.id) }
                    )
                }
            }

            if (!canAdd) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text          = if (ALL_WIDGET_TYPES.all { it.id in activeIds }) "ALL WIDGETS ARE ACTIVE"
                        else "NO MORE WIDGETS FIT — REMOVE A WIDGET TO ADD ANOTHER",
                    color         = if (isDayMode) Color(0xFFE03131) else Color(0xFF3A3A3A),
                    fontSize      = 8.sp,
                    letterSpacing = 1.sp,
                    modifier      = Modifier.fillMaxWidth(),
                    textAlign     = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun WidgetLibraryCard(
    info: WidgetTypeInfo,
    isActive: Boolean,
    canAdd: Boolean,
    accent: Color,
    isDayMode: Boolean,
    onToggle: () -> Unit
) {
    val enabled    = isActive || canAdd
    val cardBorder = if (isActive) accent else if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF1A1A1A)
    val cardBg     = if (isActive) accent.copy(alpha = 0.15f) else if (isDayMode) Color(0xFFFFFFFF) else Color(0xFF0E0E0E)
    val iconTint   = if (isActive) accent else if (isDayMode) Color(0xFF495057) else Color(0xFF333333)
    val labelColor = if (isActive) accent else if (isDayMode) Color(0xFF212529) else Color(0xFF3A3A3A)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(cardBg)
            .border(1.dp, cardBorder, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(info.icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(5.dp))
        Text(
            text          = info.label,
            color         = labelColor,
            fontSize      = 7.sp,
            letterSpacing = 1.sp,
            textAlign     = TextAlign.Center,
            maxLines      = 2,
            lineHeight    = 9.sp
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text          = when {
                isActive -> "ACTIVE"
                !canAdd  -> "FULL"
                else     -> "ADD"
            },
            color         = when {
                isActive -> accent.copy(alpha = 0.75f)
                !canAdd  -> if (isDayMode) Color(0xFFADB5BD) else Color(0xFF282828)
                else     -> if (isDayMode) Color(0xFF495057) else Color(0xFF3A3A3A)
            },
            fontSize      = 6.sp,
            letterSpacing = 1.sp,
            textAlign     = TextAlign.Center
        )
    }
}
