package win.solobot.omodash.diagnostics

import android.media.session.MediaSession
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import win.solobot.omodash.service.ProjectionMediaKeys
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ProjectionMediaKeysIntegrationTest {
    @Test fun aSessionWithOnlyMediaButtonCallbackReceivesPressAndRelease() {
        val session = MediaSession(InstrumentationRegistry.getInstrumentation().targetContext, "OEM callback fixture")
        val received = CopyOnWriteArrayList<Pair<Int, Int>>()
        val latch = CountDownLatch(2)
        session.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(intent: android.content.Intent): Boolean {
                @Suppress("DEPRECATION")
                val key = intent.getParcelableExtra<KeyEvent>(android.content.Intent.EXTRA_KEY_EVENT) ?: return false
                received.add(key.keyCode to key.action); latch.countDown(); return true
            }
        }, Handler(Looper.getMainLooper()))
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS)
        session.isActive = true
        try {
            assertTrue(ProjectionMediaKeys.sendPair(session.controller, KeyEvent.KEYCODE_MEDIA_NEXT))
            assertTrue("Both edges must reach OEM-style callback", latch.await(3, TimeUnit.SECONDS))
            assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_NEXT to KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_NEXT to KeyEvent.ACTION_UP), received.toList())
        } finally { session.release() }
    }
}
