package com.openlauncher.app.diagnostics

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun crashSurvivesRestartAndKeepsCrashingVersion() {
        val directory = temp.newFolder()
        DiagnosticFiles(directory).crash("0.0.7 (8)\nActivityNotFoundException: media settings")
        val report = DiagnosticFiles(directory).snapshot()
        assertEquals(1, report.size)
        assertTrue(report.values.single().contains("0.0.7 (8)"))
        assertTrue(report.values.single().contains("ActivityNotFoundException"))
    }

    @Test fun journalIsBoundedAndNewestCrashIsRetained() {
        val directory = temp.newFolder()
        val store = DiagnosticFiles(directory, 128)
        repeat(100) { store.record("$it " + "a".repeat(60)) }
        repeat(5) { store.crash("crash $it") }
        store.crash("last crash")
        val result = store.snapshot()
        assertEquals(3, result.keys.count { it.startsWith("crash-") })
        assertTrue(result.values.any { it == "last crash" })
        assertTrue(result.getValue("events.log").contains("99 "))
        assertTrue(result.getValue("events.log").length <= 128)
        assertTrue(result.getValue("events-previous.log").length <= 128)
    }

    @Test fun credentialsAreRedactedAndStackFramesAreKept() {
        val text = "https://x.test/?access_token=abc123&password=xyz\nAuthorization: Bearer secret456\n{\"api_key\":\"key123\"}\nat ConfirmDialog.kt:42"
        val cleaned = DiagnosticFiles.sanitize(text)
        listOf("abc123", "xyz", "secret456", "key123").forEach { assertFalse(cleaned.contains(it)) }
        assertTrue(cleaned.contains("ConfirmDialog.kt:42"))
    }
}
