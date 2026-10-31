package win.solobot.omodash.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import win.solobot.omodash.R
import win.solobot.omodash.data.AppSettings
import win.solobot.omodash.data.VehicleApp
import win.solobot.omodash.data.vehicleBinding
import win.solobot.omodash.model.NavDestination
import win.solobot.omodash.util.AppLaunchIntent

private data class PresetNavItem(val app: VehicleApp, val icon: Int, val label: String, val available: Boolean)

/** Preset routes above; three fixed launcher destinations pinned to the bottom. */
@Composable
fun Sidebar(
    currentDest: NavDestination,
    settings: AppSettings,
    nowPlayingAvailable: Boolean,
    onNavigate: (NavDestination) -> Unit,
    onVehicleApp: (VehicleApp) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val routes = remember(settings.vehiclePresetId, settings.vehicleAppPackages) {
        listOf(
            Triple(VehicleApp.NAVIGATION, R.drawable.symbol_navigation, "Navigation"),
            Triple(VehicleApp.CAR_SETTINGS, R.drawable.symbol_car_gear, "Car Settings"),
            Triple(VehicleApp.LOCAL_SETTINGS, R.drawable.symbol_settings_b_roll, "Local Settings"),
            Triple(VehicleApp.NOW_PLAYING, R.drawable.symbol_music_note, "Now Playing")
        ).map { (app, icon, label) ->
            PresetNavItem(app, icon, label, app == VehicleApp.NOW_PLAYING ||
                AppLaunchIntent.resolveBinding(context, settings.vehicleBinding(app)) != null)
        }
    }
    val dimensions = LocalPanelDimensions.current
    Box(modifier.width(dimensions.thickness).fillMaxHeight().dashboardSurface(DashboardSurface.NAVIGATION)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = dimensions.padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(dimensions.gap)) {
            routes.forEach { (app, icon, label, available) ->
                NavButton(label, icon, enabled = available && (app != VehicleApp.NOW_PLAYING || nowPlayingAvailable),
                    onClick = { onVehicleApp(app) }, modifier = Modifier.size(dimensions.buttonSize))
            }
            Spacer(Modifier.weight(1f))
            DashboardHorizontalDivider(Modifier.padding(horizontal = 4.dp, vertical = dimensions.dividerPadding))
            listOf(
                Triple(NavDestination.APP_LIBRARY, R.drawable.symbol_dashboard, "Applications"),
                Triple(NavDestination.SETTINGS, R.drawable.symbol_settings_heart, "Launcher settings"),
                Triple(NavDestination.HOME, R.drawable.symbol_home_app_logo, "Home")
            ).forEach { (destination, icon, label) ->
                NavButton(label, icon, active = currentDest == destination, onClick = { onNavigate(destination) },
                    modifier = Modifier.size(dimensions.buttonSize))
            }
        }
    }
}
