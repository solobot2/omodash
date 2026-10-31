package win.solobot.omodash.projection

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import win.solobot.omodash.MainActivity
import win.solobot.omodash.data.AppSettings
import win.solobot.omodash.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ScalingIntegrationTest {
    @Test fun bothScaleEndpointsPersistAndRenderOnUltrawideDisplay() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repo = SettingsRepository(context)
        val previous = repo.settingsFlow.first()
        try {
            for (scale in listOf(1f, 2f)) {
                repo.saveSettings(AppSettings(onboardingCompleted = true, uiScale = scale, textScale = scale))
                assertEquals(scale, repo.settingsFlow.first().uiScale)
                assertEquals(scale, repo.settingsFlow.first().textScale)
                ActivityScenario.launch(MainActivity::class.java).use {
                    instrumentation.waitForIdleSync()
                    Thread.sleep(1000)
                    val bitmap = instrumentation.uiAutomation.takeScreenshot()
                    assertNotNull(bitmap)
                    File(context.cacheDir, "scale-${(scale * 100).toInt()}.png").outputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    bitmap.recycle()
                }
            }
        } finally { repo.saveSettings(previous) }
    }
}
