package com.openlauncher.app.update

import org.junit.Assert.*
import org.junit.Test

class UpdateUiStateTest {
    private val update = UpdateInfo("com.openlauncher.app", 18, "0.0.17", 21,
        "https://github.com/solobot2/omodash/releases/download/v0.0.17/omodash.apk", "a".repeat(64), 100, "b".repeat(64))

    @Test fun installIsAvailableBeforeDownloadWithoutPermission() {
        assertTrue(UpdateUiState(candidate = update).installAvailable)
    }
    @Test fun downloadedApkWithoutPermissionUsesTheSameInstallAction() {
        assertTrue(UpdateUiState(candidate = update, ready = true).installAvailable)
    }
    @Test fun grantedPermissionDoesNotRequireASeparateAction() {
        assertTrue(UpdateUiState(candidate = update, ready = true, canInstall = true).installAvailable)
    }
    @Test fun busyOperationsDoNotOfferDuplicateInstalls() {
        val state = UpdateUiState(candidate = update, ready = true)
        assertFalse(state.copy(checking = true).installAvailable)
        assertFalse(state.copy(downloading = true).installAvailable)
        assertFalse(state.copy(installing = true).installAvailable)
    }
    @Test fun noCandidateMeansNoInstallationAction() {
        assertFalse(UpdateUiState().installAvailable)
    }
    @Test fun cancelledOrFailedSetupCanBeRetriedWithInstallUpdate() {
        assertTrue(UpdateUiState(candidate = update, ready = true, error = "Installation cancelled").installAvailable)
    }
}
