package com.openlauncher.app.update

import org.junit.Assert.*
import org.junit.Test

class UpdateUiStateTest {
    private val update = UpdateInfo("com.openlauncher.app", 17, "0.0.16", 21,
        "https://github.com/solobot2/omodash/releases/download/v0.0.16/omodash.apk", "a".repeat(64), 100, "b".repeat(64))

    @Test fun downloadedApkWithoutPermissionHasAnActionInsteadOfDisabledInstall() {
        val state = UpdateUiState(candidate = update, ready = true, canInstall = false)
        assertEquals(UpdatePrimaryAction.ALLOW_INSTALLATION, state.primaryAction)
        assertEquals("Allow installation", state.primaryActionLabel)
    }
    @Test fun grantingPermissionMakesTheExistingDownloadInstallable() {
        val downloaded = UpdateUiState(candidate = update, ready = true)
        val allowed = downloaded.copy(canInstall = true)
        assertEquals(UpdatePrimaryAction.INSTALL, allowed.primaryAction)
        assertEquals("Install update", allowed.primaryActionLabel)
        assertTrue(allowed.ready)
        assertEquals(downloaded.candidate, allowed.candidate)
    }
    @Test fun downloadRemainsAvailableBeforePermissionSetup() {
        val state = UpdateUiState(candidate = update)
        assertEquals(UpdatePrimaryAction.DOWNLOAD, state.primaryAction)
        assertEquals("Download update", state.primaryActionLabel)
        assertEquals("Download and install", state.copy(canInstall = true).primaryActionLabel)
    }
    @Test fun busyOrMissingCandidateNeverOffersDuplicateOperations() {
        assertNull(UpdateUiState().primaryAction)
        val state = UpdateUiState(candidate = update, ready = true)
        assertNull(state.copy(checking = true).primaryAction)
        assertNull(state.copy(downloading = true).primaryAction)
        assertNull(state.copy(installing = true).primaryAction)
    }
    @Test fun failedInstallationCanBeRetriedWithoutDownloadingAgain() {
        val state = UpdateUiState(candidate = update, ready = true, canInstall = true, error = "Installer cancelled")
        assertEquals(UpdatePrimaryAction.INSTALL, state.primaryAction)
    }
    @Test fun missingHeadUnitScreenDoesNotRemovePermissionRecoveryAction() {
        val state = UpdateUiState(candidate = update, ready = true, installationSetupNeeded = true,
            error = "USB setup is not complete")
        assertEquals(UpdatePrimaryAction.ALLOW_INSTALLATION, state.primaryAction)
    }
}
