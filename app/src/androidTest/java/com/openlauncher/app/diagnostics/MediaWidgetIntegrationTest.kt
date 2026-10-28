package com.openlauncher.app.diagnostics

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openlauncher.app.MainActivity
import com.openlauncher.app.model.NowPlayingState
import com.openlauncher.app.ui.screen.AppLibraryScreen
import com.openlauncher.app.ui.widget.NowPlayingWidget
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MediaWidgetIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun find(root: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        root ?: return null
        if (root.contentDescription?.toString() == label || root.text?.toString()?.contains(label) == true) return root
        for (i in 0 until root.childCount) find(root.getChild(i), label)?.let { return it }
        return null
    }

    private fun node(label: String): AccessibilityNodeInfo {
        val end = android.os.SystemClock.elapsedRealtime() + 5_000
        while (android.os.SystemClock.elapsedRealtime() < end) {
            find(instrumentation.uiAutomation.rootInActiveWindow, label)?.let { return it }
            Thread.sleep(50)
        }
        error("Missing visible item: $label")
    }

    private fun effectivelyEnabled(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (!current.isEnabled) return false
            current = current.parent
        }
        return true
    }

    private fun click(label: String) {
        var target = node(label)
        while (!target.isClickable && target.parent != null) target = target.parent
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test fun carPlayShortcutRemainsAvailableWhileRadioMetadataIsPresent() {
        val opened = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    NowPlayingWidget(
                        state = NowPlayingState("Factory radio station", "FM 101.1", null, isPlaying = true, controller = null),
                        accent = Color.Cyan, carPlayPackage = "com.desaysv.vehicle.carplayapp", androidAutoPackage = "",
                        onPlayPause = {}, onNext = {}, onPrev = {}, onLaunchCarPlay = { opened.incrementAndGet() },
                        onLaunchAndroidAuto = {}, onTapToOpenApp = {}, onOpenContextMenu = {}, modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                    )
                }
            } }
            assertFalse(effectivelyEnabled(node("Next")))
            assertFalse(effectivelyEnabled(node("Prev")))
            click("Source Selector")
            click("Open CarPlay")
            instrumentation.waitForIdleSync()
            assertEquals(1, opened.get())
        }
    }

    @Test fun radioPickerCanUseTheCurrentSessionWithoutGuessingAnApplication() {
        val selected = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    AppLibraryScreen(apps = emptyList(), isLoading = false, isPickerMode = false, pickerSlot = null,
                        isCarPlayPickerMode = true, carPlayPickerLabel = "CHOOSE RADIO APP", accent = Color.Cyan,
                        onAppClick = {}, onPickerSelect = { _, _ -> }, onCarPlaySelect = {},
                        currentRadioSource = "factory.media.session", onUseCurrentRadioSource = { selected.incrementAndGet() })
                }
            } }
            var button = node("Use the currently playing source as radio")
            while (!button.isClickable && button.parent != null) button = button.parent
            assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertEquals(1, selected.get())
        }
    }
}
