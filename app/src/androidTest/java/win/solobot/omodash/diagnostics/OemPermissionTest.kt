package win.solobot.omodash.diagnostics

import android.app.Application
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import win.solobot.omodash.update.UpdateAccessibilityService
import win.solobot.omodash.update.UpdateViewModel
import win.solobot.omodash.util.DevicePermissionSetup
import win.solobot.omodash.util.MediaAccess
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OemPermissionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    @Test fun automotiveSettingsStubIsNeverLaunched() {
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { fail("Automotive stub must not be opened") }
        }
        val intent = object : Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) {
            override fun resolveActivity(pm: PackageManager): ComponentName = ComponentName(
                "com.android.car.frameworkpackagestubs", "com.android.car.frameworkpackagestubs.Stubs\$SettingsStub"
            )
        }
        assertFalse(DevicePermissionSetup.openSystemScreen(context, intent, "Test"))
    }

    @Test fun secureGrantEnablesOnlyOurServicesAndPreservesExistingEntries() {
        val resolver = app.contentResolver
        val keys = listOf("enabled_notification_listeners", Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, Settings.Secure.ACCESSIBILITY_ENABLED)
        val before = keys.associateWith { Settings.Secure.getString(resolver, it) }
        val alreadyGranted = DevicePermissionSetup.canWriteSecureSettings(app)
        val mediaAlreadyGranted = MediaAccess.isGranted(app)
        val userId = shell("am get-current-user").trim().toInt()
        if (!alreadyGranted) shell("pm grant --user current ${app.packageName} android.permission.WRITE_SECURE_SETTINGS")
        try {
            assertTrue(DevicePermissionSetup.canWriteSecureSettings(app))
            val configured = DevicePermissionSetup.enableNotificationListener(app)
            if (configured) assertTrue(MediaAccess.isGranted(app))
            // Modern Android requires the real NotificationManager approval, not only the legacy setting.
            shell("cmd notification allow_listener ${MediaAccess.component(app).flattenToString()} $userId")
            assertTrue(DevicePermissionSetup.enableUpdateHelper(app))
            val expected = mapOf(
                keys[0] to MediaAccess.component(app),
                keys[1] to ComponentName(app, UpdateAccessibilityService::class.java)
            )
            expected.forEach { (key, own) ->
                val entries = Settings.Secure.getString(resolver, key).split(':')
                before[key].orEmpty().split(':').filter(String::isNotBlank).forEach { assertTrue(entries.contains(it)) }
                assertEquals(1, entries.count { ComponentName.unflattenFromString(it) == own })
            }
            assertTrue(DevicePermissionSetup.enableNotificationListener(app)) // idempotent
            assertTrue(DevicePermissionSetup.enableUpdateHelper(app))
            val deadline = android.os.SystemClock.elapsedRealtime() + 4_000
            while (!MediaAccess.isGranted(app) && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            assertTrue(MediaAccess.isGranted(app))
            assertTrue(UpdateAccessibilityService.isEnabled(app))
        } finally {
            if (!mediaAlreadyGranted) shell("cmd notification disallow_listener ${MediaAccess.component(app).flattenToString()} $userId")
            before.forEach { (key, value) -> Settings.Secure.putString(resolver, key, value) }
            if (!alreadyGranted) shell("pm revoke --user current ${app.packageName} android.permission.WRITE_SECURE_SETTINGS")
        }
    }

    @Test fun idleAndBackgroundUpdaterDoNotRepeatPermissionChecks() {
        fun count(): Int = DiagnosticLog.snapshot().values.sumOf { text ->
            text.lineSequence().count { it.contains("[UpdateSetup] Permission check:") }
        }
        val before = count()
        lateinit var vm: UpdateViewModel
        instrumentation.runOnMainSync {
            vm = UpdateViewModel(app.applicationContext as Application)
            vm.setForeground(true)
            vm.setWindowFocused(true)
        }
        try {
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
            while (count() == before && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            assertEquals(before + 1, count())
            Thread.sleep(2_300)
            assertEquals(before + 1, count())
            instrumentation.runOnMainSync { vm.setForeground(false) }
            Thread.sleep(1_200)
            assertEquals(before + 1, count())
            instrumentation.runOnMainSync { vm.setForeground(true) }
            Thread.sleep(1_200)
            assertEquals(before + 1, count())
        } finally { instrumentation.runOnMainSync { vm.setForeground(false) } }
    }
}
