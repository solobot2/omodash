package com.openlauncher.app.data

import org.junit.Assert.*
import org.junit.Test

class LauncherSettingsTest {
    @Test fun upgradePreservesPhysicalPositionsAndSizes() {
        val old = listOf(WidgetConfig("CLOCK", 1, 0), WidgetConfig("NOW_PLAYING", 0, 1, 2, 1))
        val migrated = migrateWidgetLayout(old, 1)
        assertEquals(WidgetConfig("CLOCK", 2, 0, 2, 1), migrated[0])
        assertEquals(WidgetConfig("NOW_PLAYING", 0, 1, 4, 1), migrated[1])
        assertEquals(migrated, migrateWidgetLayout(migrated, 2))
    }
    @Test fun customSparseLayoutIsPreservedAndNewColumnsAreReachable() {
        val migrated = migrateWidgetLayout(listOf(WidgetConfig("CLOCK", 0, 0)), 1)
        assertEquals(1, migrated.size)
        val moved = computeWidgetMove(migrated.map { it.copy(spanX = 1) }, "CLOCK", 5, 1).single()
        assertEquals(5, moved.gridX)
        assertEquals(1, moved.spanX)
    }
    @Test fun firmwareAssignmentsFillOnlyDefaultPlaceholders() {
        val placeholder = ShortcutConfig(isDefault = true, defaultIcon = DefaultShortcutIcon.PHONE)
        assertEquals(OmodaDefaults.PHONE, OmodaDefaults.shortcut(placeholder).packageName)
        val custom = placeholder.copy(packageName = "my.phone")
        assertEquals(custom, OmodaDefaults.shortcut(custom))
        val blank = ShortcutConfig()
        assertEquals(blank, OmodaDefaults.shortcut(blank))
    }
    @Test fun scalesStayBetween100And200IncludingOldInvalidValues() {
        assertEquals(1f, launcherScale(0.7f))
        assertEquals(2f, launcherScale(3f))
        assertEquals(1.75f, launcherScale(1.75f))
        assertEquals(1f, launcherScale(Float.NaN))
    }
}
