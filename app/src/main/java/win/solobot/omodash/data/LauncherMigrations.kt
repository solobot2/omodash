package win.solobot.omodash.data

/** Version 2 doubles horizontal resolution while retaining the physical layout. */
fun migrateWidgetLayout(layout: List<WidgetConfig>, version: Int): List<WidgetConfig> =
    if (version >= 2) layout else layout.map { it.copy(gridX = it.gridX * 2, spanX = it.spanX * 2) }

fun launcherScale(value: Float): Float = if (value.isFinite()) value.coerceIn(1f, 2f) else 1f

/** The bottom edge is reserved for the launcher's climate panel. */
fun migrateSidebarPosition(value: String?, legacyRightHandDrive: Boolean): SidebarPosition = when (value) {
    "LEFT", "BOTTOM" -> SidebarPosition.LEFT
    "RIGHT" -> SidebarPosition.RIGHT
    else -> if (legacyRightHandDrive) SidebarPosition.RIGHT else SidebarPosition.LEFT
}
