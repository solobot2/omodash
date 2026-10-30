package com.openlauncher.app.data

/** Version 2 doubles horizontal resolution while retaining the physical layout. */
fun migrateWidgetLayout(layout: List<WidgetConfig>, version: Int): List<WidgetConfig> =
    if (version >= 2) layout else layout.map { it.copy(gridX = it.gridX * 2, spanX = it.spanX * 2) }

fun launcherScale(value: Float): Float = if (value.isFinite()) value.coerceIn(1f, 2f) else 1f
