package com.openlauncher.app.projection

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openlauncher.app.MainActivity
import com.openlauncher.app.ui.theme.OpenLauncherTheme
import com.openlauncher.app.update.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateUiIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val update = UpdateInfo("com.openlauncher.app.debug", 17, "0.0.16", 21,
        "https://github.com/solobot2/omodash/releases/download/v0.0.16/omodash.apk", "a".repeat(64), 100, "b".repeat(64))
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
        error("Missing update action: $text")
    }
    private fun click(text: String) {
        var target = node(text)
        while (!target.isClickable && target.parent != null) target = target.parent
        assertTrue(target.isEnabled)
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun verifySingleInstallAction(ready: Boolean, canInstall: Boolean) {
        var installed = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { OpenLauncherTheme(accent = Color.White) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    UpdateSettingsContent(UpdateUiState(candidate = update, ready = ready, canInstall = canInstall),
                        {}, { installed++ }, {}, {})
                }
            } } }
            click("Install update")
            assertEquals(1, installed)
            assertNull(find(automation.rootInActiveWindow, "Allow installation"))
            assertNull(find(automation.rootInActiveWindow, "Recheck and install"))
            assertNull(find(automation.rootInActiveWindow, "Download update"))
        }
    }
    @Test fun installUpdateStartsTheEntireFlowBeforeDownloading() = verifySingleInstallAction(false, false)
    @Test fun installUpdateAlsoHandlesMissingPermissionForDownloadedApk() = verifySingleInstallAction(true, false)
    @Test fun permittedDownloadedApkUsesTheSameInstallUpdateButton() = verifySingleInstallAction(true, true)
}
