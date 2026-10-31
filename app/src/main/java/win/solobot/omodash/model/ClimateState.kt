package win.solobot.omodash.model

enum class ClimateControl {
    POWER, AC, AUTO, FAN, SYNC, DRIVER_SEAT, PASSENGER_SEAT, AIRFLOW,
    WINDSHIELD_AIRFLOW, FRONT_DEFROST, REAR_DEFROST, RECIRCULATION
}

enum class ClimateZone { DRIVER, PASSENGER }

sealed interface ClimateAction {
    data class Toggle(val control: ClimateControl) : ClimateAction
    data class AdjustTemperature(val zone: ClimateZone, val degrees: Int) : ClimateAction
}

/** Local panel state until vehicle HVAC snapshots and commands are connected. */
data class ClimateState(
    val enabled: Set<ClimateControl> = setOf(ClimateControl.POWER, ClimateControl.AUTO, ClimateControl.FAN),
    val fanSpeed: Int = 3,
    val driverTemperature: Int = 22,
    val passengerTemperature: Int = 22
) {
    fun isOn(control: ClimateControl): Boolean = control in enabled

    fun apply(action: ClimateAction): ClimateState = when (action) {
        is ClimateAction.Toggle -> {
            val turningOn = !isOn(action.control)
            copy(
                enabled = if (turningOn) enabled + action.control else enabled - action.control,
                passengerTemperature = if (action.control == ClimateControl.SYNC && turningOn)
                    driverTemperature else passengerTemperature
            )
            // FAN only changes its enabled flag; the last fanSpeed is retained.
        }
        is ClimateAction.AdjustTemperature -> {
            val current = if (action.zone == ClimateZone.DRIVER) driverTemperature else passengerTemperature
            // OMODA temperature controls use whole degrees.
            val next = (current + action.degrees).coerceIn(16, 30)
            if (isOn(ClimateControl.SYNC)) copy(driverTemperature = next, passengerTemperature = next)
            else if (action.zone == ClimateZone.DRIVER) copy(driverTemperature = next)
            else copy(passengerTemperature = next)
        }
    }
}
