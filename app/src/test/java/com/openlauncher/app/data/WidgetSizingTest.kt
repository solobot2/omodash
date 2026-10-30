package com.openlauncher.app.data

import org.junit.Assert.*
import org.junit.Test

class WidgetSizingTest {
    private fun emptySettings() = AppSettings(showClock = false, showWeather = false,
        showTelemetry = false, showNowPlaying = false, widgetLayout = emptyList())

    private fun assertValid(settings: AppSettings) {
        val occupied = HashSet<Pair<Int, Int>>()
        for (w in settings.widgetLayout.filter { it.enabled && it.id in settings.activeWidgetIds() }) {
            assertTrue("${w.id} has illegal size", WidgetSize(w.spanX, w.spanY) in settings.widgetSizeRules(w.id).sizes)
            assertTrue(w.gridX >= 0 && w.gridY >= 0 && w.gridX + w.spanX <= GRID_COLS && w.gridY + w.spanY <= GRID_ROWS)
            for (y in w.gridY until w.gridY + w.spanY) for (x in w.gridX until w.gridX + w.spanX)
                assertTrue("Widgets overlap at $x,$y", occupied.add(x to y))
        }
    }

    @Test fun instrumentsAllowOnlySquarePairsAndAltimeterIsFixed() {
        val settings = emptySettings()
        assertEquals(listOf(WidgetSize(1, 1), WidgetSize(2, 2)), settings.widgetSizeRules("TELEMETRY").sizes)
        assertEquals(settings.widgetSizeRules("TELEMETRY"), settings.widgetSizeRules("SPEEDOMETER"))
        assertFalse(settings.widgetSizeRules("ALTIMETER").isResizable)
        assertEquals(listOf(WidgetSize(1, 1)), settings.widgetSizeRules("ALTIMETER").sizes)
        assertEquals(2, settings.widgetSizeRules("NOW_PLAYING").minWidth)
        assertEquals(2, settings.widgetSizeRules("TRIP_TRACKER").minWidth)
    }

    @Test fun resizeRejectsForbiddenPairsAndFixedWidgetChanges() {
        val compass = emptySettings().copy(showTelemetry = true, widgetLayout = listOf(WidgetConfig("TELEMETRY", 0, 0)))
        assertNull(compass.withResizedWidget("TELEMETRY", 2, 1))
        val altimeter = emptySettings().copy(showAltimeter = true, widgetLayout = listOf(WidgetConfig("ALTIMETER", 0, 0)))
        assertNull(altimeter.withResizedWidget("ALTIMETER", 2, 2))
        val media = emptySettings().copy(showNowPlaying = true, widgetLayout = listOf(WidgetConfig("NOW_PLAYING", 0, 0, 2, 1)))
        assertNull(media.withResizedWidget("NOW_PLAYING", 1, 1))
        assertNull(media.withResizedWidget("NOW_PLAYING", 7, 2))
    }

    @Test fun resizingAtBottomRightMovesAnchorInsideGridAndPreservesNeighbors() {
        val settings = emptySettings().copy(showTelemetry = true, showClock = true,
            widgetLayout = listOf(WidgetConfig("CLOCK", 4, 0, 2, 1), WidgetConfig("TELEMETRY", 5, 1)))
        val resized = checkNotNull(settings.withResizedWidget("TELEMETRY", 2, 2))
        assertValid(resized)
        val compass = resized.widgetLayout.first { it.id == "TELEMETRY" }
        assertEquals(4, compass.gridX)
        assertEquals(0, compass.gridY)
        assertEquals(WidgetSize(2, 1), resized.widgetLayout.first { it.id == "CLOCK" }.let { WidgetSize(it.spanX, it.spanY) })
    }

    @Test fun resizeWithoutEnoughRoomIsRejectedWithoutChangingOriginal() {
        val settings = AppSettings()
        val before = settings.widgetLayout.toList()
        assertNull(settings.withResizedWidget("NOW_PLAYING", 6, 2))
        assertEquals(before, settings.widgetLayout)
        assertValid(settings)
    }

    @Test fun addingToFullGridShrinksOnlyToLegalSizes() {
        val settings = checkNotNull(AppSettings().withAddedWidget("ALTIMETER"))
        assertTrue(settings.showAltimeter)
        assertValid(settings)
        assertEquals(WidgetSize(1, 1), settings.widgetLayout.first { it.id == "ALTIMETER" }.let { WidgetSize(it.spanX, it.spanY) })
        assertTrue(settings.widgetLayout.first { it.id == "NOW_PLAYING" }.spanX >= 2)
    }

    @Test fun allBuiltInWidgetsCanBeAddedWithoutBreakingMinimumsOrSquares() {
        var settings = AppSettings()
        for (id in listOf("ALTIMETER", "SPEEDOMETER", "VITALS", "TRIP_TRACKER", "SOUNDBOARD")) {
            settings = checkNotNull(settings.withAddedWidget(id)) { "Cannot add $id" }
            assertValid(settings)
        }
        assertEquals(9, settings.activeWidgetIds().size)
        assertEquals(9, settings.widgetLayout.size)
        assertEquals(settings, settings.withValidWidgetLayout())
    }

    @Test fun invalidStoredSizesAndStyleChangesAreNormalizedIdempotently() {
        val old = AppSettings(showAltimeter = true, clockStyle = ClockStyle.ANALOG, widgetLayout = listOf(
            WidgetConfig("CLOCK", 0, 0, 4, 1), WidgetConfig("WEATHER", 4, 0, 2, 1),
            WidgetConfig("TELEMETRY", 5, 1, 1, 2), WidgetConfig("NOW_PLAYING", 0, 1, 1, 1),
            WidgetConfig("ALTIMETER", 2, 0, 3, 2)))
        val fixed = old.withValidWidgetLayout()
        assertValid(fixed)
        assertEquals(fixed, fixed.withValidWidgetLayout())
        assertTrue(fixed.widgetLayout.first { it.id == "CLOCK" }.let { it.spanX == it.spanY })
    }

    @Test fun digitalSpeedometerHasItsOwnBoundsAndCannotBeTwoRowsHigh() {
        val settings = emptySettings().copy(speedometerDigitalOnly = true, showSpeedometer = true,
            widgetLayout = listOf(WidgetConfig("SPEEDOMETER", 0, 0, 2, 2))).withValidWidgetLayout()
        assertValid(settings)
        assertEquals(1, settings.widgetSizeRules("SPEEDOMETER").maxHeight)
        assertNull(settings.withResizedWidget("SPEEDOMETER", 2, 2))
    }

    @Test fun movingAnImpossibleLayoutRollsBackInsteadOfOverlapping() {
        val full = listOf(WidgetConfig("wide", 0, 0, 4, 2), WidgetConfig("small", 4, 0, 2, 2))
        assertEquals(full, computeWidgetMove(full, "wide", 1, 0))
    }
    @Test fun validSavedLayoutIsNotChangedAndEveryGridMoveKeepsItValid() {
        val settings = AppSettings()
        assertEquals(settings, settings.withValidWidgetLayout())
        for (widget in settings.widgetLayout) for (y in 0 until GRID_ROWS) for (x in 0 until GRID_COLS) {
            val moved = computeWidgetMove(settings.widgetLayout, widget.id, x, y)
            assertValid(settings.copy(widgetLayout = moved))
            assertEquals(settings.widgetLayout.map { it.id to WidgetSize(it.spanX, it.spanY) },
                moved.map { it.id to WidgetSize(it.spanX, it.spanY) })
        }
    }

    @Test fun widgetOrderDoesNotPreventAddingAllLegalMinimumSizes() {
        val ids = listOf("CLOCK", "WEATHER", "TELEMETRY", "NOW_PLAYING", "ALTIMETER", "SPEEDOMETER", "VITALS", "TRIP_TRACKER", "SOUNDBOARD")
        val random = kotlin.random.Random(20)
        repeat(30) {
            var settings = emptySettings()
            for (id in ids.shuffled(random)) settings = checkNotNull(settings.withAddedWidget(id))
            assertValid(settings)
            assertEquals(9, settings.activeWidgetIds().size)
        }
    }

}
