package win.solobot.omodash.diagnostics

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class RecordingIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext

    @Test fun boundedSystemRecordingRequiresReportConsentAndKeepsRecordedEvents() {
        assumeTrue("Grant READ_LOGS via USB for this integration test", DiagnosticReport.hasSystemLogs(app))
        val directory = File(app.filesDir, "diagnostic-recording")
        val reports = mutableListOf<File>()
        try {
            DiagnosticRecording.start(app, true)
            assertTrue(DiagnosticRecording.state.value.active)
            // A different UID writes the marker. An app-only logcat cannot pass this check.
            Thread.sleep(500)
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "log -t OmodashUsbTest USB_EXTERNAL_RECORDING_MARKER token=private-recording-token"
            )).use { it.readBytes() }
            val deadline = SystemClock.elapsedRealtime() + 8_000
            while (SystemClock.elapsedRealtime() < deadline &&
                DiagnosticRecording.snapshot(app, true).values.none { it.contains("USB_EXTERNAL_RECORDING_MARKER") }) Thread.sleep(100)
            assertTrue(DiagnosticRecording.snapshot(app, true).values.any { it.contains("USB_EXTERNAL_RECORDING_MARKER") })

            // The recorder must end by itself; closing a form is not needed to enforce its limit.
            val stopDeadline = SystemClock.elapsedRealtime() + 123_000
            while (DiagnosticRecording.state.value.active && SystemClock.elapsedRealtime() < stopDeadline) Thread.sleep(500)
            assertFalse("The 2-minute logcat process must stop", DiagnosticRecording.state.value.active)
            assertTrue(DiagnosticRecording.snapshot(app, true).values.any { it.contains("USB_EXTERNAL_RECORDING_MARKER") })
            val without = DiagnosticReport.create(app, "without system consent", false).also { reports.add(it) }
            ZipFile(without).use { zip ->
                assertNotNull(zip.getEntry("recorded-logcat-omitted.txt"))
                assertFalse(zip.entries().asSequence().any { it.name.startsWith("recorded-events") })
                assertNull(zip.getEntry("logcat-system.txt"))
            }
            val with = DiagnosticReport.create(app, "with system consent", true).also { reports.add(it) }
            ZipFile(with).use { zip ->
                val logs = zip.entries().asSequence().filter { it.name.startsWith("recorded-events") }
                    .joinToString("\n") { zip.getInputStream(it).bufferedReader().readText() }
                assertTrue(logs.contains("USB_EXTERNAL_RECORDING_MARKER"))
                assertFalse(logs.contains("private-recording-token"))
            }
        } finally {
            DiagnosticRecording.stop()
            directory.deleteRecursively()
            reports.forEach { it.delete() }
        }
    }
}
