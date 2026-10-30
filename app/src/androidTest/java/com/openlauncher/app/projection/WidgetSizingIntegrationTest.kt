package com.openlauncher.app.projection

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import com.openlauncher.app.ui.theme.OpenLauncherTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openlauncher.app.MainActivity
import com.openlauncher.app.data.*
import com.openlauncher.app.ui.screen.WidgetContextMenu
import com.openlauncher.app.ui.screen.WidgetResizeDialog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetSizingIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private fun find(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        root ?: return null
        if (root.text?.toString() == text) return root
        for (i in 0 until root.childCount) find(root.getChild(i), text)?.let { return it }
        return null
    }
    private fun node(text: String): AccessibilityNodeInfo {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            find(automation.rootInActiveWindow, text)?.let { return it }
            Thread.sleep(50)
        }
        error("Missing widget-size control: $text")
    }
    private fun click(text: String) {
        var target = node(text)
        while (!target.isClickable && target.parent != null) target = target.parent
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    @Test fun squareInstrumentOffersOnlyCoupledSizesAndAppliesTheSelectedPair() {
        val widget = WidgetConfig("TELEMETRY", 0, 0)
        val settings = AppSettings(showClock = false, showWeather = false, showNowPlaying = false, widgetLayout = listOf(widget))
        var selected: WidgetSize? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { OpenLauncherTheme(accent = Color.Cyan, isDayMode = false) {
                WidgetResizeDialog(widget, settings, Color.Cyan, false, {}, { w, h -> selected = WidgetSize(w, h) })
            } } }
            node("1 × 1")
            node("2 × 2")
            assertNull(find(automation.rootInActiveWindow, "2 × 1"))
            click("2 × 2")
            click("APPLY")
            assertEquals(WidgetSize(2, 2), selected)
        }
    }
    @Test fun fixedAltimeterHasNoResizeAction() {
        val settings = AppSettings()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { OpenLauncherTheme(accent = Color.Cyan, isDayMode = false) {
                WidgetContextMenu("ALTIMETER", settings.widgetSizeRules("ALTIMETER"), Color.Cyan,
                    ClockStyle.DIGITAL, false, false, false, {}, {}, {}, {}, {})
            } } }
            node("FIXED SIZE 1 × 1")
            assertNull(find(automation.rootInActiveWindow, "RESIZE"))
        }
    }
    @Test fun sizeThatWouldOverlapOtherWidgetsIsDisabled() {
        val settings = AppSettings()
        val widget = settings.widgetLayout.first { it.id == "NOW_PLAYING" }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { OpenLauncherTheme(accent = Color.Cyan, isDayMode = false) {
                WidgetResizeDialog(widget, settings, Color.Cyan, false, {}, { _, _ -> })
            } } }
            var target: AccessibilityNodeInfo? = node("6 × 2")
            var disabled = false
            while (target != null) { if (!target.isEnabled) disabled = true; target = target.parent }
            assertTrue("A size covering the entire occupied grid must be disabled", disabled)
            node("Dimmed sizes do not fit the current layout.")
        }
    }
    @Test fun pickerCanApplyAValidSizeAtMaximumUiAndFontScale() {
        val settings = AppSettings()
        val widget = settings.widgetLayout.first { it.id == "NOW_PLAYING" }
        var selected: WidgetSize? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density * 2f, density.fontScale * 2f)) {
                    OpenLauncherTheme(accent = Color.Cyan, isDayMode = false) {
                        WidgetResizeDialog(widget, settings, Color.Cyan, false, {}, { w, h -> selected = WidgetSize(w, h) })
                    }
                }
            } }
            click("2 × 1")
            val bounds = android.graphics.Rect()
            node("2 × 1").getBoundsInScreen(bounds)
            assertTrue("Dialog text must inherit the launcher scale", bounds.height() >= 36)
            Thread.sleep(250)
            val file = java.io.File(instrumentation.targetContext.cacheDir, "widget-sizes-200.png")
            file.outputStream().use { automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            click("APPLY")
            assertEquals(WidgetSize(2, 1), selected)
        }
    }

}
