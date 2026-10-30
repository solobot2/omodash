package com.openlauncher.app.data

/** Packages from the owner's Omoda 9 HW110 firmware. */
object OmodaDefaults {
    const val AUDIO = "com.desaysv.svaudioapp"
    const val CAMERA = "com.desaysv.ivi.vds.rvc"
    const val PHONE = "com.desaysv.bluetooth.phone"
    const val CARPLAY = "com.desaysv.vehicle.carplayapp"
    const val ANDROID_AUTO = "com.google.android.projection.sink"

    fun shortcut(shortcut: ShortcutConfig): ShortcutConfig {
        if (shortcut.packageName.isNotBlank() || !shortcut.isDefault) return shortcut
        val pkg = when (shortcut.defaultIcon) {
            DefaultShortcutIcon.RADIO, DefaultShortcutIcon.MUSIC -> AUDIO
            DefaultShortcutIcon.CAMERA -> CAMERA
            DefaultShortcutIcon.PHONE -> PHONE
            else -> return shortcut
        }
        return shortcut.copy(packageName = pkg)
    }
}

/** Version 2 doubles horizontal resolution while retaining the physical layout. */
fun migrateWidgetLayout(layout: List<WidgetConfig>, version: Int): List<WidgetConfig> =
    if (version >= 2) layout else layout.map { it.copy(gridX = it.gridX * 2, spanX = it.spanX * 2) }

fun launcherScale(value: Float): Float = if (value.isFinite()) value.coerceIn(1f, 2f) else 1f
