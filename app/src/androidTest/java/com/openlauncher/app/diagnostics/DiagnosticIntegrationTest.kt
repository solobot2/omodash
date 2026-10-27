package com.openlauncher.app.diagnostics

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openlauncher.app.util.MediaAccess
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class DiagnosticIntegrationTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun unavailableOemSettingsReturnsExplanationAndLogsFailures() {
        val actions = mutableListOf<String?>()
        val unavailable = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) {
                actions.add(intent.action)
                throw ActivityNotFoundException("OEM test: no notification settings")
            }
        }
        assertNotNull(MediaAccess.openSettings(unavailable))
        assertEquals(if (Build.VERSION.SDK_INT >= 30) 2 else 1, actions.size)
        assertEquals("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS", actions.last())
        assertTrue(DiagnosticLog.snapshot().values.any { it.contains("OEM test: no notification settings") })
    }

    @Test fun missingDetailScreenFallsBackToListenerList() {
        val actions = mutableListOf<String?>()
        val fallback = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) {
                actions.add(intent.action)
                if (intent.action == "android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS") {
                    assertEquals(MediaAccess.component(app).flattenToString(), intent.getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME))
                    throw ActivityNotFoundException("No detail screen")
                }
            }
        }
        assertNull(MediaAccess.openSettings(fallback))
        assertEquals("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS", actions.last())
    }

    @Test fun reportIncludesPermissionEvidenceAndDoesNotDumpPreferences() {
        app.getSharedPreferences("diagnostic-test", 0).edit().putString("secret", "DO_NOT_EXPORT_PREFERENCES_9182").commit()
        val file = DiagnosticReport.create(app, "Media permission button: token=secret9182", false)
        try {
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence().associate { it.name to zip.getInputStream(it).bufferedReader().readText() }
                assertTrue(entries.keys.containsAll(listOf("description.txt", "device.json", "logcat-app.txt", "events.log", "source-errors.txt")))
                assertFalse(entries.containsKey("logcat-system.txt"))
                assertEquals("Media permission button: token=[redacted]", entries["description.txt"])
                val device = JSONObject(entries.getValue("device.json"))
                assertEquals(app.packageName, device.getString("package"))
                assertTrue(device.has("settingsActivities"))
                assertTrue(device.has("notificationAccessGranted"))
                assertFalse(entries.values.any { it.contains("DO_NOT_EXPORT_PREFERENCES_9182") })
            }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.diagnostics", file)
            assertEquals(file.length(), app.contentResolver.openInputStream(uri)!!.use { it.readBytes().size.toLong() })
            try {
                FileProvider.getUriForFile(app, "${app.packageName}.diagnostics", File(app.filesDir, "../shared_prefs/diagnostic-test.xml"))
                fail("The provider must expose reports only")
            } catch (_: IllegalArgumentException) { /* Expected: outside reports directory. */ }
        } finally {
            file.delete()
            app.getSharedPreferences("diagnostic-test", 0).edit().clear().commit()
        }
    }
}
