package com.openlauncher.app.update

enum class UpdatePrimaryAction { DOWNLOAD, INSTALL, ALLOW_INSTALLATION }

data class UpdateUiState(
    val currentVersion: String = "",
    val candidate: UpdateInfo? = null,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val progress: Int? = null,
    val ready: Boolean = false,
    val installing: Boolean = false,
    val canInstall: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val autoConfirm: Boolean = true,
    val installationSetupNeeded: Boolean = false,
    val message: String = "Check for a newer version on GitHub",
    val error: String? = null
) {
    val primaryAction: UpdatePrimaryAction?
        get() = when {
            candidate == null || checking || downloading || installing -> null
            !ready -> UpdatePrimaryAction.DOWNLOAD
            !canInstall -> UpdatePrimaryAction.ALLOW_INSTALLATION
            else -> UpdatePrimaryAction.INSTALL
        }

    val primaryActionLabel: String?
        get() = when (primaryAction) {
            UpdatePrimaryAction.DOWNLOAD -> if (canInstall) "Download and install" else "Download update"
            UpdatePrimaryAction.INSTALL -> "Install update"
            UpdatePrimaryAction.ALLOW_INSTALLATION -> "Allow installation"
            null -> null
        }
}
