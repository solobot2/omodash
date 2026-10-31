package win.solobot.omodash.projection

import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import win.solobot.omodash.MainActivity
import win.solobot.omodash.model.AppInfo
import win.solobot.omodash.ui.screen.AppLibraryScreen
import win.solobot.omodash.ui.theme.OmodashTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppTileIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private fun find(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        root ?: return null
        if (root.text?.toString() == text) return root
        for (i in 0 until root.childCount) find(root.getChild(i), text)?.let { return it }
        return null
    }
    @Test fun iconFillsTheTileAndTheWholeTileStillSelectsTheApplication() {
        val app = AppInfo("fixture.green", "Green App", ColorDrawable(android.graphics.Color.GREEN), false)
        var selected: String? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { OmodashTheme(accent = Color.White, isDayMode = false) {
                AppLibraryScreen(listOf(app), false, true, 0, false, accent = Color.White,
                    onAppClick = {}, onPickerSelect = { _, picked -> selected = picked.packageName }, onCarPlaySelect = {})
            } } }
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
            var label: AccessibilityNodeInfo? = null
            while (label == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                label = find(automation.rootInActiveWindow, "GREEN APP")
                if (label == null) Thread.sleep(50)
            }
            var tile = checkNotNull(label)
            while (!tile.isClickable && tile.parent != null) tile = tile.parent
            val bounds = Rect().also(tile::getBoundsInScreen)
            assertTrue(bounds.width() > 100 && bounds.height() > 100)
            instrumentation.waitForIdleSync()
            Thread.sleep(250)
            val bitmap = automation.takeScreenshot()
            assertEquals(0x00ff00, bitmap.getPixel(bounds.left + 8, bounds.top + 8) and 0xffffff)
            assertEquals(0x00ff00, bitmap.getPixel(bounds.centerX(), bounds.top + bounds.height() / 4) and 0xffffff)
            java.io.File(instrumentation.targetContext.cacheDir, "app-tile-full-icon.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            assertTrue(tile.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertEquals(app.packageName, selected)
        }
    }
}
