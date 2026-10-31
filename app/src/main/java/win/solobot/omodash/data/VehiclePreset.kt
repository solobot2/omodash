package win.solobot.omodash.data

enum class VehicleApp { NAVIGATION, CAR_SETTINGS, LOCAL_SETTINGS, NOW_PLAYING, RADIO, MUSIC, CAMERA, PHONE, CARPLAY, ANDROID_AUTO }
enum class VehicleLaunch { APP, CURRENT_MEDIA, DESAY_RADIO, DESAY_MUSIC, DESAY_CAMERA }

data class VehicleAppBinding(
    val packageName: String,
    val launch: VehicleLaunch = VehicleLaunch.APP,
    val intentAction: String? = null,
    val mediaButtonEvents: Boolean = false,
    val activityName: String? = null,
    val intentCategory: String? = null,
    val intExtras: Map<String, Int> = emptyMap(),
    val mediaView: Int = 1,
    val fallbackPackages: List<String> = emptyList()
)

data class VehiclePreset(
    val id: String,
    val name: String,
    val description: String,
    val apps: Map<VehicleApp, VehicleAppBinding>,
    val radioSessionPackages: Set<String> = emptySet()
) {
    fun binding(app: VehicleApp) = apps[app] ?: VehicleAppBinding("")
    val allowsAppAssignment get() = id == VehiclePresets.DEFAULT_ID

    fun shortcuts() = listOf(
        Triple(VehicleApp.NAVIGATION, "Navigation", DefaultShortcutIcon.NAVIGATION),
        Triple(VehicleApp.CAR_SETTINGS, "Car Settings", DefaultShortcutIcon.CAR),
        Triple(VehicleApp.LOCAL_SETTINGS, "Local Settings", DefaultShortcutIcon.SETTINGS),
        Triple(VehicleApp.NOW_PLAYING, "Now Playing", DefaultShortcutIcon.MUSIC)
    ).map { (app, label, icon) -> ShortcutConfig(binding(app).packageName, label, true, icon) }
}

/** Vehicle assignments and launch protocols belong here, independently of UI/settings defaults. */
object VehiclePresets {
    const val OMODA_9_ID = "omoda_9_hw110"
    const val DEFAULT_ID = "default"
    val OMODA_9 = VehiclePreset(OMODA_9_ID, "OMODA 9", "Factory apps for OMODA 9 HW110",
        mapOf(
            VehicleApp.NAVIGATION to VehicleAppBinding("com.astrob.turbodog",
                intentAction = "android.intent.action.MAIN", activityName = "com.astrob.turbodog.WelcomeActivity",
                intentCategory = "android.intent.category.APP_MAPS", fallbackPackages = listOf("com.astrob.turbodog.online")),
            VehicleApp.CAR_SETTINGS to VehicleAppBinding("com.desaysv.vehiclesetting",
                intentAction = "com.desaysv.vehiclesetting.ACTION_QUICK_CONTROL_SETTING",
                activityName = "com.desaysv.vehiclesetting.ui.activity.SettingActivity",
                intExtras = mapOf("com.vehiclesetting.section" to 8)),
            VehicleApp.LOCAL_SETTINGS to VehicleAppBinding("com.desaysv.setting",
                intentAction = "com.desaysv.setting.ACTION_DEVICE_SETTING",
                activityName = "com.desaysv.setting.view.activity.SettingActivity"),
            VehicleApp.NOW_PLAYING to VehicleAppBinding("", VehicleLaunch.CURRENT_MEDIA, mediaView = 3),
            VehicleApp.RADIO to VehicleAppBinding("com.desaysv.svaudioapp", VehicleLaunch.DESAY_RADIO),
            VehicleApp.MUSIC to VehicleAppBinding("com.desaysv.svaudioapp", VehicleLaunch.DESAY_MUSIC),
            VehicleApp.CAMERA to VehicleAppBinding("com.desaysv.ivi.vds.rvc", VehicleLaunch.DESAY_CAMERA),
            VehicleApp.PHONE to VehicleAppBinding("com.desaysv.bluetooth.phone"),
            VehicleApp.CARPLAY to VehicleAppBinding("com.desaysv.vehicle.carplayapp",
                intentAction = "action.start.carplay", mediaButtonEvents = true),
            VehicleApp.ANDROID_AUTO to VehicleAppBinding("com.google.android.projection.sink", mediaButtonEvents = true)
        ), radioSessionPackages = setOf("com.desaysv.svaudioapp", "com.desaysv.ivi.vds.tuner"))
    val DEFAULT = VehiclePreset(DEFAULT_ID, "Default", "Assign apps manually for another head unit", mapOf(
        VehicleApp.NAVIGATION to VehicleAppBinding("", intentAction = "android.intent.action.MAIN", intentCategory = "android.intent.category.APP_MAPS"),
        VehicleApp.LOCAL_SETTINGS to VehicleAppBinding("", intentAction = "android.settings.SETTINGS"),
        VehicleApp.NOW_PLAYING to VehicleAppBinding("", VehicleLaunch.CURRENT_MEDIA, mediaView = 3)))
    val all = listOf(OMODA_9, DEFAULT)
    @Volatile private var selectedId = DEFAULT_ID
    fun current(): VehiclePreset = find(selectedId)
    fun select(id: String): VehiclePreset = find(id).also { selectedId = it.id }
    fun find(id: String) = all.firstOrNull { it.id == id } ?: DEFAULT
}

fun DefaultShortcutIcon.vehicleApp(): VehicleApp? = when (this) {
    DefaultShortcutIcon.NAVIGATION -> VehicleApp.NAVIGATION
    DefaultShortcutIcon.CAR -> VehicleApp.CAR_SETTINGS
    DefaultShortcutIcon.SETTINGS -> VehicleApp.LOCAL_SETTINGS
    DefaultShortcutIcon.RADIO -> VehicleApp.RADIO
    DefaultShortcutIcon.MUSIC -> VehicleApp.NOW_PLAYING
    DefaultShortcutIcon.CAMERA -> VehicleApp.CAMERA
    DefaultShortcutIcon.PHONE -> VehicleApp.PHONE
    else -> null
}

fun AppSettings.vehicleBinding(app: VehicleApp): VehicleAppBinding {
    val preset = VehiclePresets.find(vehiclePresetId)
    val assigned = when (app) {
        VehicleApp.CARPLAY -> carPlayPackage.takeIf { it.isNotBlank() }
        VehicleApp.ANDROID_AUTO -> androidAutoPackage.takeIf { it.isNotBlank() }
        VehicleApp.RADIO -> radioPackage.takeIf { it.isNotBlank() }
        else -> vehicleAppPackages[app]
    }
    return if (preset.allowsAppAssignment && assigned != null && app != VehicleApp.NOW_PLAYING)
        VehicleAppBinding(assigned) else preset.binding(app)
}

/** Preinstalled types retain the theme icon even after an app has been assigned. */
val ShortcutConfig.themeIcon: DefaultShortcutIcon?
    get() = if (isDefault && defaultIcon != DefaultShortcutIcon.NONE) defaultIcon
        else customIconOverride?.takeUnless { it == DefaultShortcutIcon.NONE }

fun AppSettings.withVehicleBindings(): AppSettings {
    val preset = VehiclePresets.find(vehiclePresetId)
    if (preset.allowsAppAssignment) return copy(vehiclePresetId = preset.id)
    return copy(vehiclePresetId = preset.id,
        radioPackage = preset.binding(VehicleApp.RADIO).packageName,
        carPlayPackage = preset.binding(VehicleApp.CARPLAY).packageName,
        androidAutoPackage = preset.binding(VehicleApp.ANDROID_AUTO).packageName,
        shortcuts = shortcuts.map { shortcut ->
            val app = shortcut.defaultIcon.vehicleApp()
            if (shortcut.isDefault && app != null) shortcut.copy(packageName = preset.binding(app).packageName,
                customIconOverride = null) else shortcut
        })
}

/** Changing a preset replaces vehicle assignments, retaining appearance, layout and custom shortcuts. */
fun AppSettings.withVehiclePreset(id: String): AppSettings {
    val preset = VehiclePresets.select(id)
    return copy(vehiclePresetId = preset.id,
        radioPackage = preset.binding(VehicleApp.RADIO).packageName,
        carPlayPackage = preset.binding(VehicleApp.CARPLAY).packageName,
        androidAutoPackage = preset.binding(VehicleApp.ANDROID_AUTO).packageName,
        shortcuts = shortcuts.map { shortcut ->
            val app = shortcut.defaultIcon.vehicleApp()
            if (shortcut.isDefault && app != null) shortcut.copy(packageName = preset.binding(app).packageName,
                customIconOverride = null) else shortcut
        }).withVehicleBindings()
}
