package win.solobot.omodash.ui.screen

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import win.solobot.omodash.data.AppFont
import win.solobot.omodash.data.AppSettings
import win.solobot.omodash.data.DayNightMode
import win.solobot.omodash.data.VehiclePresets
import win.solobot.omodash.data.withVehiclePreset
import win.solobot.omodash.data.vehicleBinding
import win.solobot.omodash.data.SidebarPosition
import win.solobot.omodash.data.ShortcutConfig
import win.solobot.omodash.data.UnitSystem
import win.solobot.omodash.ui.theme.LocalDayMode
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.delay
import win.solobot.omodash.ui.components.ConfirmDialog

// Resolved at call site via LocalDayMode — see SettingsDivider / SettingsSection

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    accent: Color,
    onUpdate: (AppSettings.() -> AppSettings) -> Unit,
    onReset: () -> Unit,
    onAssignCarPlay: () -> Unit,
    onAssignAndroidAuto: () -> Unit,
    onAssignVehicleApp: (win.solobot.omodash.data.VehicleApp) -> Unit = {},
    focusUpdates: Boolean = false,
    onUpdatesFocused: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showResetDialog       by remember { mutableStateOf(false) }
    var showDiagnosticReport by remember { mutableStateOf(false) }

    // OpenDocument (not GetContent): only SAF document URIs carry a persistable
    // grant, so this is what actually keeps the wallpaper readable after reboot
    val wallpaperPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            onUpdate { copy(wallpaperUri = it.toString()) }
        }
    }

    val scrollState = rememberScrollState()
    val updateRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(focusUpdates) {
        if (focusUpdates) {
            snapshotFlow { scrollState.maxValue }.first { it > 0 && it != Int.MAX_VALUE }
            updateRequester.bringIntoView()
            onUpdatesFocused()
        }
    }
    val isDayMode = LocalDayMode.current
    val screenBg  = MaterialTheme.colorScheme.background

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(screenBg)
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── Title ────────────────────────────────────────────────────────────
        Text(
            text          = "SETTINGS",
            style         = MaterialTheme.typography.titleLarge,
            color         = if (isDayMode) Color(0xFF111111) else accent,
            letterSpacing = 3.sp,
            fontSize      = 14.sp
        )

        Spacer(Modifier.height(4.dp))

        SettingsSection("Diagnostics") {
            win.solobot.omodash.diagnostics.DiagnosticRecordingPanel()
            SettingsDivider()
            SettingsButton(
                label = "Send / save diagnostic ZIP",
                sublabel = "Include recorded logs, crashes and device information",
                icon = Icons.Rounded.BugReport,
                accent = accent,
                onClick = { showDiagnosticReport = true }
            )
        }

        // ── Permissions ──────────────────────────────────────────────────────
        SettingsSection("Permissions") {
            val isMediaConnected by win.solobot.omodash.service.MediaListenerService.isConnected.collectAsState()

            // Bumped on ON_RESUME so statuses refresh when the user returns from
            // system settings (recomposition alone doesn't re-run these checks)
            var permissionRefresh by remember { mutableIntStateOf(0) }
            val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) permissionRefresh++
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            val hasLocation = remember(permissionRefresh) {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            val isDefaultLauncher = remember(permissionRefresh) {
                val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                context.packageManager.resolveActivity(
                    home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
                )?.activityInfo?.packageName == context.packageName
            }

            val homeRoleLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { permissionRefresh++ }

            val locationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { permissionRefresh++ }

            SettingsButton(
                label    = "Set as Default Launcher",
                sublabel = if (isDefaultLauncher) "Active — Omodash is the home app"
                           else "Required so the head unit boots into Omodash",
                icon     = Icons.Rounded.Home,
                accent   = if (isDefaultLauncher) accent else Color(0xFF993333),
                onClick  = {
                    // Preferred: the system home-role dialog (API 29+). Vendor ROMs
                    // sometimes ship without it, so fall through to the home-settings
                    // screen, then the default-apps screen.
                    var launched = false
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        val rm = context.getSystemService(android.app.role.RoleManager::class.java)
                        if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) &&
                            !rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)
                        ) {
                            launched = runCatching {
                                homeRoleLauncher.launch(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME))
                            }.isSuccess
                        }
                    }
                    if (!launched) {
                        launched = runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.isSuccess
                    }
                    if (!launched) {
                        runCatching {
                            context.startActivity(
                                Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }
            )
            SettingsDivider()
            val mediaAllowed = remember(permissionRefresh) { win.solobot.omodash.util.MediaAccess.isGranted(context) }
            Text(
                text = if (isMediaConnected) "Media access: connected"
                    else if (mediaAllowed) "Media access: allowed, waiting for connection"
                    else "Media access: not configured. Run the one-time USB setup on your laptop.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            var mediaError by remember { mutableStateOf<String?>(null) }
            SettingsButton(
                label = if (mediaAllowed) "Reconnect media" else "Enable media access",
                sublabel = "Restore Now Playing, station information and album art",
                icon = Icons.Rounded.MusicNote, accent = accent,
                onClick = {
                    if (mediaAllowed) {
                        win.solobot.omodash.util.MediaAccess.ensureConnected(context)
                        mediaError = null
                    } else mediaError = win.solobot.omodash.util.MediaAccess.openSettings(context)
                    permissionRefresh++
                })
            mediaError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            SettingsDivider()
            SettingsButton(
                label    = "Location Access",
                sublabel = if (hasLocation) "Granted — GPS, compass & weather active" else "Required for compass, speed & weather",
                icon     = if (hasLocation) Icons.Rounded.LocationOn else Icons.Rounded.LocationOff,
                accent   = if (hasLocation) accent else Color(0xFF993333),
                onClick  = {
                    if (!hasLocation) {
                        // Ask in-app first — previously the only grant path was the
                        // onboarding flow; skipping it left GPS features dead forever
                        locationPermissionLauncher.launch(arrayOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                        ))
                    } else {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }
            )
        }

        SettingsSection("Vehicle preset") {
            win.solobot.omodash.ui.components.VehiclePresetSelector(settings.vehiclePresetId) { id ->
                onUpdate { withVehiclePreset(id) }
            }
        }

        if (VehiclePresets.find(settings.vehiclePresetId).allowsAppAssignment) {
        SettingsSection("Phone integration") {
            val carPlayLabel = remember(settings.carPlayPackage) {
                selectedAppLabel(context, settings.carPlayPackage)
            }
            val androidAutoLabel = remember(settings.androidAutoPackage) {
                selectedAppLabel(context, settings.androidAutoPackage)
            }
            SettingsButton(
                label = "CarPlay app",
                sublabel = "$carPlayLabel · Tap to choose or change",
                icon = Icons.Rounded.PhoneAndroid,
                accent = accent,
                onClick = onAssignCarPlay
            )

            SettingsDivider()
            SettingsButton(
                label = "Android Auto app",
                sublabel = "$androidAutoLabel · Tap to choose or change",
                icon = Icons.Rounded.DirectionsCar,
                accent = accent,
                onClick = onAssignAndroidAuto
            )

        }

        }

        // ── Vehicle Name ─────────────────────────────────────────────────────
        SettingsSection("Vehicle") {
            var nameInput by remember(settings.vehicleName) { mutableStateOf(settings.vehicleName) }
            SettingsRow(
                label    = "Vehicle Name",
                sublabel = "",
                icon     = Icons.Rounded.DirectionsCar
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value         = nameInput,
                        onValueChange = { nameInput = it },
                        placeholder   = { Text("MY CAR", color = if (isDayMode) Color(0xFF999999) else Color(0xFF444444), fontSize = 12.sp) },
                        singleLine    = true,
                        textStyle     = LocalTextStyle.current.copy(fontSize = 12.sp, color = if (isDayMode) Color(0xFF111111) else Color.White),
                        colors        = outlinedFieldColors(accent),
                        modifier      = Modifier.width(140.dp)
                    )
                    if (nameInput != settings.vehicleName) {
                        IconButton(onClick = { onUpdate { copy(vehicleName = nameInput) } }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.Check, "Save", tint = accent, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            SettingsDivider()

            SettingsRow(
                label    = "Sidebar Position",
                sublabel = when (settings.sidebarPosition) {
                    SidebarPosition.LEFT   -> "Left side"
                    SidebarPosition.RIGHT  -> "Right side"
                },
                icon     = Icons.Rounded.SwapHoriz
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SidebarPosition.entries.forEach { pos ->
                        FilterChip(
                            selected = settings.sidebarPosition == pos,
                            onClick  = { onUpdate { copy(sidebarPosition = pos) } },
                            label    = {
                                Text(
                                    when (pos) {
                                        SidebarPosition.LEFT   -> "Left"
                                        SidebarPosition.RIGHT  -> "Right"
                                    },
                                    fontSize = 9.sp,
                                    letterSpacing = 0.5.sp
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accent,
                                selectedLabelColor     = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }

            SettingsDivider()

            SettingsRow(label = "Unit System", sublabel = if (settings.unitSystem == UnitSystem.METRIC) "Metric (°C, km)" else "Imperial (°F, mi)", icon = Icons.Rounded.Straighten) {
                Row {
                    FilterChip(
                        selected = settings.unitSystem == UnitSystem.METRIC,
                        onClick  = { onUpdate { copy(unitSystem = UnitSystem.METRIC) } },
                        label    = { Text("Metric", fontSize = 11.sp) },
                        colors   = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent,
                            selectedLabelColor     = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = settings.unitSystem == UnitSystem.IMPERIAL,
                        onClick  = { onUpdate { copy(unitSystem = UnitSystem.IMPERIAL) } },
                        label    = { Text("Imperial", fontSize = 11.sp) },
                        colors   = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent,
                            selectedLabelColor     = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }
        }

        SettingsSection("Sidebar routes") {
            listOf(
                win.solobot.omodash.data.VehicleApp.NAVIGATION to "Navigation",
                win.solobot.omodash.data.VehicleApp.CAR_SETTINGS to "Car Settings",
                win.solobot.omodash.data.VehicleApp.LOCAL_SETTINGS to "Local Settings"
            ).forEach { (app, label) ->
                val binding = settings.vehicleBinding(app)
                if (VehiclePresets.find(settings.vehiclePresetId).allowsAppAssignment) {
                    val assignment = remember(context, binding.packageName, binding.intentAction) {
                        if (binding.packageName.isBlank() && binding.intentAction != null) "System default"
                        else selectedAppLabel(context, binding.packageName)
                    }
                    SettingsButton(label = label, sublabel = assignment,
                        icon = Icons.Rounded.OpenInNew, accent = accent,
                        onClick = { onAssignVehicleApp(app) })
                } else SettingsRow(label = label, sublabel = "Factory preset", icon = Icons.Rounded.OpenInNew) {}
                SettingsDivider()
            }
            Text("Now Playing opens the currently selected audio source.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp))
        }

        // ── Appearance ───────────────────────────────────────────────────────
        SettingsSection("Appearance") {
            // Display Mode
            SettingsRow(
                label    = "Display Mode",
                sublabel = when (settings.dayNightMode) {
                    DayNightMode.DARK   -> "Always dark"
                    DayNightMode.LIGHT  -> "Always light"
                    DayNightMode.AUTO   -> "Follows the factory day/night mode"
                },
                icon = when (settings.dayNightMode) {
                    DayNightMode.DARK   -> Icons.Rounded.NightlightRound
                    DayNightMode.LIGHT  -> Icons.Rounded.LightMode
                    DayNightMode.AUTO   -> Icons.Rounded.Brightness4
                }
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DayNightMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.dayNightMode == mode,
                            onClick  = { onUpdate { copy(dayNightMode = mode) } },
                            label    = {
                                Text(
                                    text      = when (mode) {
                                        DayNightMode.DARK   -> "Dark"
                                        DayNightMode.LIGHT  -> "Light"
                                        DayNightMode.AUTO   -> "Morning / evening"
                                    },
                                    fontSize  = 9.sp,
                                    letterSpacing = 0.5.sp
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accent,
                                selectedLabelColor     = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }

            SettingsDivider()

            // Wallpaper
            SettingsButton(
                label    = "Set Wallpaper",
                sublabel = if (settings.wallpaperUri.isNotEmpty()) "Custom wallpaper active" else "Choose image from gallery",
                icon     = Icons.Rounded.Wallpaper,
                accent   = accent,
                onClick  = { wallpaperPicker.launch(arrayOf("image/*")) }
            )
            if (settings.wallpaperUri.isNotEmpty()) {
                Column {
                    SettingsRow(
                        label    = "Wallpaper Dim",
                        sublabel = "${"%.0f".format(settings.wallpaperDim * 100)}%",
                        icon     = Icons.Rounded.BrightnessLow
                    ) {}
                    Slider(
                        value         = settings.wallpaperDim,
                        onValueChange = { onUpdate { copy(wallpaperDim = it) } },
                        valueRange    = 0f..0.95f,
                        steps         = 18,
                        colors        = sliderColors(accent),
                        modifier      = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick  = { onUpdate { copy(wallpaperUri = "") } },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("REMOVE WALLPAPER", color = Color(0xFF993333), fontSize = 9.sp, letterSpacing = 1.sp)
                    }
                }
            }
        }

        // ── Typography ───────────────────────────────────────────────────────
        SettingsSection("Typography") {
            SettingsRow(label = "Font", sublabel = fontDisplayName(settings.appFont), icon = Icons.Rounded.FontDownload) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    win.solobot.omodash.data.AppFont.entries.forEach { font ->
                        FilterChip(
                            selected = settings.appFont == font,
                            onClick  = { onUpdate { copy(appFont = font) } },
                            label    = { Text(fontDisplayName(font), fontSize = 9.sp, letterSpacing = 0.5.sp) },
                            colors   = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accent,
                                selectedLabelColor     = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }

            SettingsDivider()

            SettingsRow(label = "Bold Font", sublabel = "Heavier weight across all text", icon = Icons.Rounded.FormatBold) {
                Switch(
                    checked         = settings.fontBold,
                    onCheckedChange = { onUpdate { copy(fontBold = it) } },
                    colors          = switchColors(accent)
                )
            }

            SettingsDivider()

            Column {
                SettingsRow(
                    label    = "Text Scale",
                    sublabel = "${"%.0f".format(settings.textScale * 100)}%",
                    icon     = Icons.Rounded.TextFields
                ) {}
                Slider(
                    value         = settings.textScale,
                    onValueChange = { onUpdate { copy(textScale = it) } },
                    valueRange    = 1f..2f,
                    steps         = 19,
                    colors        = sliderColors(accent),
                    modifier      = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
            }

            SettingsDivider()

            Column {
                SettingsRow(
                    label    = "UI Scale",
                    sublabel = "${"%.0f".format(settings.uiScale * 100)}%  — scales all elements",
                    icon     = Icons.Rounded.ZoomIn
                ) {}
                Slider(
                    value         = settings.uiScale,
                    onValueChange = { onUpdate { copy(uiScale = it) } },
                    valueRange    = 1f..2f,
                    steps         = 19,
                    colors        = sliderColors(accent),
                    modifier      = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
            }
        }

        // ── GPS & Calibration ───────────────────────────────────────────────
        SettingsSection("GPS & Calibration") {
            var calibrationStatus by remember { mutableStateOf<String?>(null) }
            val coroutineScope = rememberCoroutineScope()
            var isCalibratingCompass by remember { mutableStateOf(false) }
            var compassCountdown by remember { mutableIntStateOf(0) }

            // 1. Reset A-GPS Button
            SettingsButton(
                label    = "Reset A-GPS Assistance Data",
                sublabel = calibrationStatus ?: "Forces cold start to download fresh satellite orbits entirely offline",
                icon     = Icons.Rounded.MyLocation,
                accent   = accent,
                onClick  = {
                    calibrationStatus = "Clearing A-GPS cache..."
                    val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                    var success = false
                    try {
                        // "delete_aiding_data" is the command AOSP's GPS provider
                        // actually recognizes (requires ACCESS_LOCATION_EXTRA_COMMANDS)
                        success = lm.sendExtraCommand(android.location.LocationManager.GPS_PROVIDER, "delete_aiding_data", android.os.Bundle())
                        lm.sendExtraCommand(android.location.LocationManager.GPS_PROVIDER, "force_xtra_injection", null)
                        lm.sendExtraCommand(android.location.LocationManager.GPS_PROVIDER, "force_time_injection", null)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    calibrationStatus = if (success) {
                        "Cold start forced — go outdoors for a fresh satellite lock (2–3 min)"
                    } else {
                        "Not supported by this device's GPS driver — no data was cleared"
                    }
                }
            )
            
            SettingsDivider()
            
            // 2. Drive-in-circles magnetometer sweep. Android's sensor stack
            // self-calibrates the magnetometer continuously — the circles feed it
            // diverse readings. The timer guides the sweep; it does not (and
            // cannot) apply offsets itself, so the message must not claim it did.
            SettingsButton(
                label    = "Magnetometer Sweep (Parking Lot)",
                sublabel = if (isCalibratingCompass) {
                    "Sweep active: Drive slowly in two 360° circles... (${compassCountdown}s remaining)"
                } else {
                    "Guided sweep — Android self-calibrates the compass while you circle"
                },
                icon     = Icons.Rounded.Navigation,
                accent   = if (isCalibratingCompass) Color.Green else accent,
                onClick  = {
                    if (!isCalibratingCompass) {
                        isCalibratingCompass = true
                        compassCountdown = 30
                        coroutineScope.launch {
                            while (compassCountdown > 0) {
                                delay(1000)
                                compassCountdown--
                            }
                            isCalibratingCompass = false
                            calibrationStatus = "Sweep complete — check the compass widget; if heading is still off, use the manual offset below"
                        }
                    }
                }
            )

            SettingsDivider()

            // 4. Manual Compass Heading Offset Slider
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                SettingsRow(
                    label    = "Compass Heading Offset",
                    sublabel = "Manual Alignment: ${if (settings.compassOffset >= 0) "+" else ""}${settings.compassOffset.toInt()}°  — aligns compass with vehicle front",
                    icon     = Icons.Rounded.Explore
                ) {}
                Slider(
                    value         = settings.compassOffset,
                    onValueChange = { onUpdate { copy(compassOffset = it) } },
                    valueRange    = -180f..180f,
                    steps         = 71, // 5 degree steps: 360 / 5 - 1 = 71 steps
                    colors        = sliderColors(accent),
                    modifier      = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
            }
        }

        // ── Updates ──────────────────────────────────────────────────────────
        SettingsSection("Updates") {
            Box(Modifier.bringIntoViewRequester(updateRequester)) {
                win.solobot.omodash.update.UpdateSettingsPanel()
            }
        }

        // ── Maintenance ──────────────────────────────────────────────────────
        SettingsSection("Maintenance") {
            Button(
                onClick  = { showResetDialog = true },
                shape    = RoundedCornerShape(4.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A0000)),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                Icon(Icons.Rounded.RestartAlt, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Reset to Defaults", color = MaterialTheme.colorScheme.error, fontSize = 13.sp, letterSpacing = 1.sp)
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text          = "v${win.solobot.omodash.BuildConfig.VERSION_NAME}  ·  Made by SolobotWin  ·  2026",
            color         = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize      = 10.sp,
            letterSpacing = 1.sp,
            modifier      = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 16.dp)
        )
    }
    } // end Box

    // ── Dialogs ──────────────────────────────────────────────────────────────
    if (showDiagnosticReport) win.solobot.omodash.diagnostics.DiagnosticReportDialog(onDismiss = { showDiagnosticReport = false })

    if (showResetDialog) {
        ConfirmDialog(
            title        = "Reset Settings",
            message      = "Are you sure you want to reset all settings to default? This cannot be undone.",
            confirmLabel = "Reset",
            onConfirm    = { onReset(); showResetDialog = false },
            onDismiss    = { showResetDialog = false }
        )
    }

}

// ── Helpers ─────────────────────────────────────────────────────────────────

private fun selectedAppLabel(context: android.content.Context, packageName: String): String {
    if (packageName.isEmpty()) return "Not assigned"
    return runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrElse { "$packageName (not installed)" }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDayMode     = LocalDayMode.current
    val sectionColor  = MaterialTheme.colorScheme.onSurfaceVariant
    val dividerColor  = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF1E1E1E)
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(
            text          = title.uppercase(),
            style         = MaterialTheme.typography.labelSmall,
            color         = sectionColor,
            letterSpacing = 2.sp,
            modifier      = Modifier.padding(top = 16.dp, bottom = 6.dp)
        )
        HorizontalDivider(color = dividerColor)
        Column(modifier = Modifier.fillMaxWidth(), content = content)
        HorizontalDivider(color = dividerColor)
    }
}

@Composable
private fun SettingsRow(
    label: String,
    sublabel: String = "",
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable RowScope.() -> Unit
) {
    val isDayMode   = LocalDayMode.current
    val labelColor  = if (isDayMode) Color(0xFF111111) else Color(0xFFDDDDDD)
    val subColor    = MaterialTheme.colorScheme.onSurfaceVariant
    val iconTint    = if (isDayMode) Color(0xFF777777) else MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = labelColor, fontSize = 13.sp)
            if (sublabel.isNotEmpty())
                Text(sublabel, style = MaterialTheme.typography.labelSmall, color = subColor, fontSize = 11.sp)
        }
        content()
    }
}

@Composable
private fun ColumnScope.SettingsButton(
    label: String,
    sublabel: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    onClick: () -> Unit
) {
    val isDayMode  = LocalDayMode.current
    val labelColor = if (isDayMode) Color(0xFF111111) else Color(0xFFDDDDDD)
    val subColor   = MaterialTheme.colorScheme.onSurfaceVariant
    val chevronC   = if (isDayMode) Color(0xFFBBBBBB) else Color(0xFF2A2A2A)
    val iconTint   = if (isDayMode) Color(0xFF777777) else MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 0.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = labelColor, fontSize = 13.sp)
            if (sublabel.isNotEmpty())
                Text(sublabel, style = MaterialTheme.typography.labelSmall, color = subColor, fontSize = 11.sp)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = chevronC, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ColumnScope.SettingsDivider() {
    val isDayMode = LocalDayMode.current
    HorizontalDivider(color = if (isDayMode) Color(0xFFDDDDDD) else Color(0xFF141414))
}

@Composable
private fun outlinedFieldColors(accent: Color): androidx.compose.material3.TextFieldColors {
    val isDayMode = LocalDayMode.current
    val textColor = if (isDayMode) Color(0xFF111111) else Color.White
    val borderU   = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF2A2A2A)
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor   = accent,
        unfocusedBorderColor = borderU,
        focusedTextColor     = textColor,
        unfocusedTextColor   = textColor,
        cursorColor          = accent,
        focusedLabelColor    = accent,
        unfocusedLabelColor  = if (isDayMode) Color(0xFF888888) else Color(0xFF666666)
    )
}

@Composable
private fun switchColors(accent: Color): androidx.compose.material3.SwitchColors {
    val isDayMode = LocalDayMode.current
    return SwitchDefaults.colors(
        checkedThumbColor    = if (isDayMode) Color.White else Color.Black,
        checkedTrackColor    = accent,
        uncheckedThumbColor  = if (isDayMode) Color(0xFFBBBBBB) else Color(0xFF888888),
        uncheckedTrackColor  = if (isDayMode) Color(0xFFDDDDDD) else Color(0xFF1E1E1E),
        uncheckedBorderColor = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF3A3A3A)
    )
}

@Composable
private fun sliderColors(accent: Color): androidx.compose.material3.SliderColors {
    val isDayMode = LocalDayMode.current
    return SliderDefaults.colors(
        thumbColor         = accent,
        activeTrackColor   = accent,
        inactiveTrackColor = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF2A2A2A)
    )
}


private fun fontDisplayName(font: AppFont): String = when (font) {
    AppFont.SYSTEM          -> "System"
    AppFont.JETBRAINS_MONO  -> "JetBrains Mono"
    AppFont.SOURCE_CODE_PRO -> "Source Code Pro"
}
