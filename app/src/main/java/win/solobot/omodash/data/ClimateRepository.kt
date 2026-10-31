package win.solobot.omodash.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import win.solobot.omodash.model.ClimateAction
import win.solobot.omodash.model.ClimateControl
import win.solobot.omodash.model.ClimateState
import java.io.IOException

private val Context.climateStore by preferencesDataStore(name = "climate_state")

/** Persists the local simulation separately from launcher appearance settings. */
class ClimateRepository(context: Context) {
    private val store = context.applicationContext.climateStore

    private object Keys {
        val ENABLED = stringSetPreferencesKey("enabled")
        val FAN_SPEED = intPreferencesKey("fan_speed")
        val DRIVER_TEMPERATURE = intPreferencesKey("driver_temperature_celsius")
        val PASSENGER_TEMPERATURE = intPreferencesKey("passenger_temperature_celsius")
    }

    val state = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map(::read)

    suspend fun dispatch(action: ClimateAction) {
        // Atomic read/modify/write also preserves rapid consecutive presses.
        store.edit { prefs -> write(prefs, read(prefs).apply(action)) }
    }

    /** The future vehicle adapter can replace a complete snapshot here. */
    suspend fun replace(state: ClimateState) {
        store.edit { write(it, state) }
    }

    private fun read(prefs: Preferences): ClimateState {
        val defaults = ClimateState()
        return ClimateState(
            enabled = prefs[Keys.ENABLED]?.map { ClimateControl.valueOf(it) }?.toSet() ?: defaults.enabled,
            fanSpeed = prefs[Keys.FAN_SPEED] ?: defaults.fanSpeed,
            driverTemperature = prefs[Keys.DRIVER_TEMPERATURE] ?: defaults.driverTemperature,
            passengerTemperature = prefs[Keys.PASSENGER_TEMPERATURE] ?: defaults.passengerTemperature
        )
    }

    private fun write(prefs: MutablePreferences, state: ClimateState) {
        prefs[Keys.ENABLED] = state.enabled.map { it.name }.toSet()
        prefs[Keys.FAN_SPEED] = state.fanSpeed
        prefs[Keys.DRIVER_TEMPERATURE] = state.driverTemperature
        prefs[Keys.PASSENGER_TEMPERATURE] = state.passengerTemperature
    }
}
