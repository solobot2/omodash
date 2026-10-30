package com.openlauncher.app.update

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
    val message: String = "Check for a newer version on GitHub",
    val error: String? = null
) {
    val installAvailable: Boolean
        get() = candidate != null && !checking && !downloading && !installing
}
