package win.solobot.omodash.data

import org.junit.Assert.*
import org.junit.Test

class VehiclePresetTest {
    @Test fun settingsResolveTheCurrentPresetInsteadOfAHardcodedVehicle() {
        val previous = VehiclePresets.current().id
        try {
            VehiclePresets.select(VehiclePresets.DEFAULT_ID)
            assertEquals("", AppSettings().carPlayPackage)
            assertEquals(VehiclePresets.DEFAULT_ID, AppSettings().vehiclePresetId)
            VehiclePresets.select(VehiclePresets.OMODA_9_ID)
            assertEquals("com.desaysv.vehicle.carplayapp", AppSettings().carPlayPackage)
            assertEquals("com.desaysv.svaudioapp", AppSettings().radioPackage)
        } finally { VehiclePresets.select(previous) }
    }
    @Test fun twoPresetsHaveIndependentMappingsAndLaunchActions() {
        assertEquals(listOf("OMODA 9", "Default"), VehiclePresets.all.map { it.name })
        val preset = VehiclePresets.OMODA_9
        assertEquals("com.desaysv.svaudioapp", preset.binding(VehicleApp.RADIO).packageName)
        assertEquals(preset.binding(VehicleApp.RADIO).packageName, preset.binding(VehicleApp.MUSIC).packageName)
        assertNotEquals(preset.binding(VehicleApp.RADIO).launch, preset.binding(VehicleApp.MUSIC).launch)
        assertEquals("action.start.carplay", preset.binding(VehicleApp.CARPLAY).intentAction)
        assertTrue(preset.binding(VehicleApp.CARPLAY).mediaButtonEvents)
        assertTrue(preset.binding(VehicleApp.ANDROID_AUTO).mediaButtonEvents)
        assertTrue(VehiclePresets.DEFAULT.apps.isEmpty())
    }
    @Test fun oldEmptyOrWrongVehicleAssignmentsAreReplacedWithoutTouchingAppearance() {
        val before = AppSettings(vehicleName = "Owner's car", radioPackage = "wrong.radio", carPlayPackage = "",
            uiScale = 1.5f, shortcuts = listOf(ShortcutConfig("wrong.phone", "Phone", true, DefaultShortcutIcon.PHONE),
                ShortcutConfig("com.spotify.music", "Spotify")))
        val after = before.withVehiclePreset(VehiclePresets.OMODA_9_ID)
        assertEquals("com.desaysv.svaudioapp", after.radioPackage)
        assertEquals("com.desaysv.bluetooth.phone", after.shortcuts[0].packageName)
        assertEquals(before.shortcuts[1], after.shortcuts[1])
        assertEquals(before.widgetLayout, after.widgetLayout)
        assertEquals(before.vehicleName, after.vehicleName)
        assertEquals(before.uiScale, after.uiScale)
    }
    @Test fun changingToDefaultClearsVehicleBindingsAndCanBeReversed() {
        val before = AppSettings().withVehiclePreset(VehiclePresets.OMODA_9_ID)
        val generic = before.withVehiclePreset(VehiclePresets.DEFAULT_ID)
        assertEquals(VehiclePresets.DEFAULT_ID, generic.vehiclePresetId)
        assertTrue(generic.radioPackage.isEmpty() && generic.carPlayPackage.isEmpty() && generic.androidAutoPackage.isEmpty())
        assertTrue(generic.shortcuts.all { it.packageName.isEmpty() })
        assertEquals(before, generic.withVehiclePreset(VehiclePresets.OMODA_9_ID))
    }
    @Test fun preinstalledTypesAlwaysUseThemeIconsEvenWithNativeAppsAndOverrides() {
        for (shortcut in AppSettings().shortcuts) {
            assertEquals(shortcut.defaultIcon, shortcut.themeIcon)
            assertEquals(shortcut.defaultIcon, shortcut.copy(customIconOverride = DefaultShortcutIcon.STAR).themeIcon)
        }
        assertNull(ShortcutConfig("com.spotify.music").themeIcon)
    }
}
