package com.openlauncher.app.diagnostics

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openlauncher.app.MainActivity
import com.openlauncher.app.data.*
import com.openlauncher.app.service.MediaListenerService
import com.openlauncher.app.ui.components.Sidebar
import com.openlauncher.app.ui.screen.OnboardingScreen
import com.openlauncher.app.ui.screen.SettingsScreen
import com.openlauncher.app.ui.theme.OpenLauncherTheme
import com.openlauncher.app.model.NavDestination
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class VehicleMediaIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun find(root: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        root ?: return null
        if (root.text?.toString() == label || root.contentDescription?.toString() == label) return root
        for (i in 0 until root.childCount) find(root.getChild(i), label)?.let { return it }
        return null
    }
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue("Condition did not become true", condition())
    }
    private fun node(label: String): AccessibilityNodeInfo {
        awaitCondition { find(automation.rootInActiveWindow, label) != null }
        return checkNotNull(find(automation.rootInActiveWindow, label))
    }
    private fun click(label: String) {
        var item = node(label)
        while (!item.isClickable && item.parent != null) item = item.parent
        assertTrue(item.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    @Test fun onboardingOffersBothPresetsAndUsesTheSelectedOne() {
        var selected = VehiclePresets.OMODA_9_ID
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                var preset by remember { mutableStateOf(selected) }
                OpenLauncherTheme {
                    OnboardingScreen(Color.White, preset, { preset = it; selected = it }, {})
                }
            } }
            click("GET STARTED")
            node("OMODA 9")
            click("Default")
            assertEquals(VehiclePresets.DEFAULT_ID, selected)
            click("CONTINUE")
            node("TELEMETRY & WEATHER")
        }
    }

    @Test fun settingsPresetSelectionIsPersistedAndRestoresFactoryRadio() {
        val context = instrumentation.targetContext
        val repo = SettingsRepository(context)
        val original = runBlocking { repo.settingsFlow.first() }
        try {
            runBlocking { repo.saveSettings(AppSettings().withVehiclePreset(VehiclePresets.DEFAULT_ID)) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    var settings by remember { mutableStateOf(AppSettings().withVehiclePreset(VehiclePresets.DEFAULT_ID)) }
                    OpenLauncherTheme {
                        SettingsScreen(settings, Color.White, { transform ->
                            settings = transform(settings)
                            runBlocking { repo.saveSettings(settings) }
                        }, {}, {}, {})
                    }
                } }
                val deadline = SystemClock.elapsedRealtime() + 8_000
                while (find(automation.rootInActiveWindow, "OMODA 9") == null && SystemClock.elapsedRealtime() < deadline) {
                    automation.performGlobalActionForTestScroll()
                }
                click("OMODA 9")
                val saved = runBlocking { repo.settingsFlow.first() }
                assertEquals(VehiclePresets.OMODA_9_ID, saved.vehiclePresetId)
                assertEquals("com.desaysv.svaudioapp", saved.radioPackage)
            }
        } finally { runBlocking { repo.saveSettings(original) } }
    }

    private fun android.app.UiAutomation.performGlobalActionForTestScroll() {
        val display = instrumentation.targetContext.resources.displayMetrics
        val x = display.widthPixels * 3 / 4
        executeShellCommand("input swipe $x ${display.heightPixels * 4 / 5} $x ${display.heightPixels / 4} 150").close()
        Thread.sleep(250)
    }

    @Test fun presetSidebarDoesNotLoadNativeApplicationIcons() {
        val nativeIcons = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { OpenLauncherTheme {
                Sidebar(NavDestination.HOME, AppSettings(), {
                    nativeIcons.incrementAndGet()
                    android.graphics.drawable.ColorDrawable(AndroidColor.RED)
                }, {}, {}, {}, {}, { _, _ -> }, { _, _ -> })
            } } }
            node("Phone")
            assertEquals(0, nativeIcons.get())
        }
    }

    @Test fun omodaRadioDoesNotOfferAnApplicationPicker() {
        val opened = AtomicInteger()
        val preset = VehiclePresets.find(VehiclePresets.OMODA_9_ID)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { OpenLauncherTheme {
                com.openlauncher.app.ui.widget.NowPlayingWidget(null, Color.White, "", "", {}, {}, {}, {}, {}, {}, {},
                    radioPackage = preset.binding(VehicleApp.RADIO).packageName, allowRadioAssignment = false,
                    onLaunchHardwareRadio = { opened.incrementAndGet() })
            } } }
            click("Source Selector")
            assertNull(find(automation.rootInActiveWindow, "Change radio app"))
            click("FM/AM Radio")
            click("OPEN RADIO")
            assertEquals(1, opened.get())
            assertNull(find(automation.rootInActiveWindow, "ASSIGN RADIO APP"))
        }
    }

    @Test fun sessionsWithoutNotificationsSwitchAndRefreshArtwork() {
        val context = instrumentation.targetContext
        val user = Process.myUid() / 100_000
        automation.executeShellCommand("cmd notification allow_listener ${context.packageName}/com.openlauncher.app.service.MediaListenerService $user").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitCondition { MediaListenerService.isConnected.value }
            val first = MediaSession(context, "Omodash first player")
            val second = MediaSession(context, "Omodash second player")
            fun metadata(title: String, color: Int) = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Fixture artist")
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }).build()
            fun state(value: Int) = PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                .setState(value, 0, 1f).build()
            try {
                first.setMetadata(metadata("First track", AndroidColor.RED))
                first.setPlaybackState(state(PlaybackState.STATE_PLAYING))
                first.isActive = true
                awaitCondition { MediaListenerService.nowPlaying.value?.title == "First track" }
                second.setMetadata(metadata("Spotify-style track", AndroidColor.GREEN))
                second.setPlaybackState(state(PlaybackState.STATE_PAUSED))
                second.isActive = true
                first.setPlaybackState(state(PlaybackState.STATE_PAUSED))
                second.setPlaybackState(state(PlaybackState.STATE_PLAYING))
                awaitCondition { MediaListenerService.nowPlaying.value?.title == "Spotify-style track" }
                second.setMetadata(metadata("Spotify-style track", AndroidColor.BLUE))
                awaitCondition { MediaListenerService.nowPlaying.value?.albumArt?.getPixel(0, 0) == AndroidColor.BLUE }
                second.release()
                awaitCondition { MediaListenerService.nowPlaying.value?.title == "First track" }
            } finally { first.release(); second.release() }
        }
    }
}
