package com.openlauncher.app.projection

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openlauncher.app.MainActivity
import com.openlauncher.app.projection.taskhost.TaskContainerView
import com.openlauncher.app.projection.taskhost.TaskHostState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskHostIntegrationTest {
    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 35_000
        while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue("Container state: ${TaskHostState.message.value}", condition())
    }
    @Test fun localAdbStartsWholeActivityAndClosingPaneReleasesHelper() {
        assertEquals(30, android.os.Build.VERSION.SDK_INT)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { MaterialTheme { TaskContainerView("com.android.settings") } } }
            await { TaskHostState.callback != null }
            assertTrue(TaskHostState.surface?.isValid == true)
            assertTrue(TaskHostState.width > 0)
            assertEquals("", TaskHostState.message.value)
            val owner = TaskHostState.token
            scenario.onActivity { it.setContent { Text("Closed") } }
            await { TaskHostState.surface == null && TaskHostState.callback == null }
            assertNotEquals(owner, TaskHostState.token)
            // A second session must reconnect through ADB with the same stored key.
            scenario.onActivity { it.setContent { MaterialTheme { TaskContainerView("com.android.settings") } } }
            await { TaskHostState.callback != null }
        }
        await { TaskHostState.surface == null && TaskHostState.callback == null }
    }
    @Test fun ordinaryAppCannotAcquireShellBridge() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            context.contentResolver.call(android.net.Uri.parse("content://${context.packageName}.taskhost"), "bridge", "0", null)
            fail("App UID received a shell bridge")
        } catch (_: SecurityException) { }
    }
}
