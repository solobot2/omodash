package win.solobot.omodash.design

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import win.solobot.omodash.MainActivity
import win.solobot.omodash.R
import win.solobot.omodash.data.*
import win.solobot.omodash.model.ClimateState
import win.solobot.omodash.model.NavDestination
import win.solobot.omodash.ui.components.*
import win.solobot.omodash.ui.theme.*

/** Debug-only visual workbench, deliberately absent from the release manifest. */
class ThemePreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val theme = intent.getStringExtra("theme") ?: "night"
        val mode = if (theme == "day") DayNightMode.LIGHT else if (theme == "auto") DayNightMode.AUTO else DayNightMode.DARK
        val font = runCatching { AppFont.valueOf(intent.getStringExtra("font") ?: "SYSTEM") }.getOrDefault(AppFont.SYSTEM)
        val uiScale = intent.getFloatExtra("uiScale", 1f)
        val textScale = intent.getFloatExtra("textScale", 1f)
        val sidebar = runCatching { SidebarPosition.valueOf(intent.getStringExtra("sidebar") ?: "LEFT") }.getOrDefault(SidebarPosition.LEFT)
        if (intent.getStringExtra("view") == "launcher") {
            lifecycleScope.launch {
                SettingsRepository(this@ThemePreviewActivity).updateSettings {
                    it.copy(onboardingCompleted = true, dayNightMode = mode, appFont = font, textScale = textScale,
                        uiScale = uiScale, wallpaperUri = "", sidebarPosition = sidebar).withVehiclePreset(VehiclePresets.OMODA_9_ID)
                }
                startActivity(Intent(this@ThemePreviewActivity, MainActivity::class.java))
                finish()
            }
            return
        }
        setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density * uiScale, base.fontScale * textScale)) {
                OmodashTheme(appFont = font, isDayMode = when (mode) { DayNightMode.LIGHT -> true; DayNightMode.DARK -> false; DayNightMode.AUTO -> !isSystemInDarkTheme() }) {
                    Workbench(intent.getStringExtra("view") == "states")
                }
            }
        }
    }
}

@Composable
private fun Workbench(states: Boolean) {
    val p = LocalDashboardPalette.current
    var actions by remember { mutableIntStateOf(0) }
    var destination by remember { mutableStateOf(NavDestination.HOME) }
    val settings = remember { AppSettings(vehiclePresetId = VehiclePresets.DEFAULT_ID,
        vehicleAppPackages = mapOf(VehicleApp.NAVIGATION to "com.android.car.settings",
            VehicleApp.CAR_SETTINGS to "com.android.car.settings", VehicleApp.LOCAL_SETTINGS to "com.android.car.settings")) }
    val context = LocalContext.current
    val climateRepo = remember(context) { ClimateRepository(context) }
    val climate by climateRepo.state.collectAsStateWithLifecycle(initialValue = ClimateState())
    val scope = rememberCoroutineScope()
    ProvidePanelDimensions {
        Column(Modifier.fillMaxSize().dashboardBackground()) {
            Row(Modifier.weight(1f)) {
                Sidebar(destination, settings, true, { destination = it }, { actions++ })
                Column(Modifier.weight(1f).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("OMODASH", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.width(28.dp))
                        Text(if (states) "CONTROLS · actions $actions" else "SURFACES", color = p.muted, style = MaterialTheme.typography.labelLarge)
                    }
                    BoxWithConstraints(Modifier.weight(1f)) {
                        val minimumHeight = 140.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
                        val cardHeight = maxOf(minimumHeight, (maxHeight - 12.dp) / 2)
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.height(cardHeight), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                PreviewCard(if (states) "NavButton" else "Lorem ipsum", Modifier.weight(1.6f)) {
                                    if (states) ControlStates(false) { actions++ } else Lorem()
                                }
                                PreviewCard(if (states) "HvacButton" else "Dolor sit amet", Modifier.weight(1f)) {
                                    if (states) ControlStates(true) { actions++ } else Lorem()
                                }
                            }
                            Row(Modifier.height(cardHeight), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                PreviewCard("Consectetur adipiscing", Modifier.weight(1.6f)) {
                                    Lorem()
                                    if (states) Text("Hold a button to inspect pressed. Disabled controls leave the action count unchanged.",
                                        style = MaterialTheme.typography.bodySmall, color = p.muted)
                                }
                                PreviewCard("Sed do eiusmod", Modifier.weight(1f)) { Lorem() }
                            }
                        }
                    }
                }
            }
            ClimateBar(climate, { action -> scope.launch { climateRepo.dispatch(action) } })
        }
    }
}

@Composable
private fun RowScope.PreviewCard(title: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxHeight().dashboardSurface().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        content()
    }
}

@Composable
private fun Lorem() {
    Text("Dolor sit amet, consectetur adipiscing elit.\nSed do eiusmod tempor incididunt.",
        style = MaterialTheme.typography.bodyLarge, color = LocalDashboardPalette.current.muted)
}

@Composable
private fun ControlStates(climate: Boolean, onClick: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf("Active", "Inactive", "Disabled").forEachIndexed { index, label ->
            var active by remember { mutableStateOf(index == 0) }
            val click = { active = !active; onClick() }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (climate) HvacButton("Preview HVAC $label", R.drawable.symbol_power_settings_new,
                    active, index != 2, click, Modifier.size(LocalPanelDimensions.current.buttonSize))
                else NavButton("Preview nav $label", R.drawable.symbol_home_app_logo,
                    active, index != 2, click, Modifier.size(LocalPanelDimensions.current.buttonSize))
                Text(label, fontSize = 11.sp, color = LocalDashboardPalette.current.muted)
            }
        }
    }
}
