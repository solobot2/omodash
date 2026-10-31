package win.solobot.omodash.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import win.solobot.omodash.R
import win.solobot.omodash.model.ClimateAction
import win.solobot.omodash.model.ClimateControl
import win.solobot.omodash.model.ClimateState
import win.solobot.omodash.model.ClimateZone

/** Local stateful controls only. No HVAC/CAN/VDB command is sent by this panel. */
@Composable
fun ClimateBar(state: ClimateState, onAction: (ClimateAction) -> Unit, modifier: Modifier = Modifier) {
    val dimensions = LocalPanelDimensions.current
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val controlScale = dimensions.controlScale
    val buttonSize = dimensions.buttonSize
    val toggle: (ClimateControl) -> Unit = { onAction(ClimateAction.Toggle(it)) }
    BoxWithConstraints(modifier.fillMaxWidth().height(dimensions.thickness).dashboardSurface(DashboardSurface.CLIMATE)) {
        val width = with(LocalDensity.current) { constraints.maxWidth.toDp() }
        val compact = width < 950.dp
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp * controlScale)
            .semantics { contentDescription = "Climate panel" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp * controlScale)) {
            TemperatureZone(ClimateZone.DRIVER, state, onAction, compact)
            DashboardVerticalDivider()
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                val availableWidth = with(LocalDensity.current) { constraints.maxWidth.toDp() }
                val syncWidth = 70.dp * fontScale * controlScale
                val autoWidth = 76.dp * fontScale * controlScale
                val acWidth = 62.dp * fontScale * controlScale
                val fanWidth = 86.dp * fontScale * controlScale
                val gap = 8.dp * controlScale
                val dividerWidth = dimensions.dividerThickness + 8.dp * controlScale
                val required = buttonSize * 6 + syncWidth + acWidth + autoWidth + fanWidth + dividerWidth + gap * 10
                Row(Modifier.horizontalScroll(rememberScrollState()).width(maxOf(availableWidth, required)).fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    HvacButton("SYNC", active = state.isOn(ClimateControl.SYNC), onClick = { toggle(ClimateControl.SYNC) },
                        modifier = Modifier.width(syncWidth).height(buttonSize)) { FittedClimateText("SYNC") }
                    DashboardVerticalDivider(Modifier.padding(horizontal = 4.dp * controlScale))
                    HvacButton("Climate power", R.drawable.symbol_power_settings_new, active = state.isOn(ClimateControl.POWER),
                        onClick = { toggle(ClimateControl.POWER) }, modifier = Modifier.size(buttonSize))
                    HvacButton("A/C", active = state.isOn(ClimateControl.AC), onClick = { toggle(ClimateControl.AC) },
                        modifier = Modifier.width(acWidth).height(buttonSize)) { FittedClimateText("A/C", 18f) }
                    HvacButton("Airflow direction", R.drawable.symbol_car_fan_low_mid_left, active = state.isOn(ClimateControl.AIRFLOW),
                        onClick = { toggle(ClimateControl.AIRFLOW) }, modifier = Modifier.size(buttonSize))
                    HvacButton("AUTO", active = state.isOn(ClimateControl.AUTO), onClick = { toggle(ClimateControl.AUTO) },
                        modifier = Modifier.width(autoWidth).height(buttonSize)) { FittedClimateText("AUTO") }
                    val fanOn = state.isOn(ClimateControl.FAN)
                    HvacButton(if (fanOn) "Fan speed ${state.fanSpeed}" else "Fan off", active = fanOn,
                        onClick = { toggle(ClimateControl.FAN) }, modifier = Modifier.width(fanWidth).height(buttonSize)) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp * controlScale),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp * controlScale, Alignment.CenterHorizontally)) {
                            RoundedSymbol(R.drawable.symbol_mode_fan, Modifier.size(dimensions.iconSize))
                            FittedClimateText(if (fanOn) state.fanSpeed.toString() else "0", 23f, Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                    HvacButton("Windshield airflow", R.drawable.symbol_windshield_heat_front,
                        active = state.isOn(ClimateControl.WINDSHIELD_AIRFLOW),
                        onClick = { toggle(ClimateControl.WINDSHIELD_AIRFLOW) }, modifier = Modifier.size(buttonSize))
                    HvacButton("Windshield defrost", R.drawable.symbol_windshield_defrost_front,
                        active = state.isOn(ClimateControl.FRONT_DEFROST),
                        onClick = { toggle(ClimateControl.FRONT_DEFROST) }, modifier = Modifier.size(buttonSize))
                    HvacButton("Rear window defrost", R.drawable.symbol_windshield_defrost_rear,
                        active = state.isOn(ClimateControl.REAR_DEFROST),
                        onClick = { toggle(ClimateControl.REAR_DEFROST) }, modifier = Modifier.size(buttonSize))
                    HvacButton("Recirculation", R.drawable.symbol_car_fan_recirculate,
                        active = state.isOn(ClimateControl.RECIRCULATION),
                        onClick = { toggle(ClimateControl.RECIRCULATION) }, modifier = Modifier.size(buttonSize))
                }
            }
            DashboardVerticalDivider()
            TemperatureZone(ClimateZone.PASSENGER, state, onAction, compact)
        }
    }
}

@Composable
private fun TemperatureZone(zone: ClimateZone, state: ClimateState, onAction: (ClimateAction) -> Unit, compact: Boolean) {
    val dimensions = LocalPanelDimensions.current
    val scale = dimensions.controlScale
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val driver = zone == ClimateZone.DRIVER
    val name = if (driver) "Driver" else "Passenger"
    val temperature = if (driver) state.driverTemperature else state.passengerTemperature
    val seat = if (driver) ClimateControl.DRIVER_SEAT else ClimateControl.PASSENGER_SEAT
    val stepWidth = (if (compact) 34.dp else 46.dp) * scale
    val temperatureWidth = (if (compact) 72.dp * fontScale.coerceAtMost(1.2f) else 90.dp * fontScale) * scale
    val seatClick = { onAction(ClimateAction.Toggle(seat)) }
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((if (compact) 4.dp else 7.dp) * scale)) {
        if (driver) HvacButton("Driver seat heating", R.drawable.symbol_seat_heat_left, active = state.isOn(seat),
            onClick = seatClick, modifier = Modifier.size(dimensions.buttonSize))
        HvacButton("$name temperature down", R.drawable.symbol_remove,
            onClick = { onAction(ClimateAction.AdjustTemperature(zone, -1)) },
            modifier = Modifier.width(stepWidth).height(dimensions.buttonSize))
        val temperatureText = temperature.toString()
        FittedClimateText(temperatureText, 32f, Modifier.width(temperatureWidth).height(dimensions.buttonSize), FontWeight.SemiBold)
        HvacButton("$name temperature up", R.drawable.symbol_add,
            onClick = { onAction(ClimateAction.AdjustTemperature(zone, 1)) },
            modifier = Modifier.width(stepWidth).height(dimensions.buttonSize))
        if (!driver) HvacButton("Passenger seat heating", R.drawable.symbol_seat_heat_right, active = state.isOn(seat),
            onClick = seatClick, modifier = Modifier.size(dimensions.buttonSize))
    }
}

/** Fits the selected font's real metrics without changing panel or icon geometry. */
@Composable
private fun FittedClimateText(text: String, maximumSp: Float = 17f, modifier: Modifier = Modifier.fillMaxSize(),
    weight: FontWeight = FontWeight.Medium) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.bodyLarge.copy(fontSize = maximumSp.sp, fontWeight = weight)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val fontSize = remember(text, measurer, style, density, constraints) {
            val padding = with(density) { 4.dp.toPx() }
            val width = (constraints.maxWidth - padding).coerceAtLeast(0f)
            val height = (constraints.maxHeight - padding).coerceAtLeast(0f)
            var low = 1f
            var high = maximumSp
            repeat(10) {
                val candidate = (low + high) / 2
                val measured = measurer.measure(AnnotatedString(text), style.copy(fontSize = candidate.sp), softWrap = false)
                if (measured.size.width <= width && measured.size.height <= height) low = candidate else high = candidate
            }
            low.sp
        }
        Text(text, style = style.copy(fontSize = fontSize), maxLines = 1, softWrap = false)
    }
}
