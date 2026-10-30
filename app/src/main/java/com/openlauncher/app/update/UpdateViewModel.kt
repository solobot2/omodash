package com.openlauncher.app.update

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openlauncher.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.applicationContext
    private val store = UpdateStore(app)
    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state
    private var foreground = false
    private var windowFocused = false
    private var pollJob: Job? = null
    private var permissionsLoaded = false
    private var installationSetupNeeded = false
    private var busy = false
    private var message = "Check for a newer version on GitHub"

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        updatePolling()
    }

    fun setWindowFocused(value: Boolean) {
        if (windowFocused == value) return
        windowFocused = value
        updatePolling()
    }

    private fun updatePolling() {
        pollJob?.cancel()
        if (!foreground || !windowFocused) return
        pollJob = viewModelScope.launch {
            if (!busy) refresh(checkPermissions = !permissionsLoaded)
            while (isActive) {
                delay(1_000)
                // No PackageManager/AppOps/DownloadManager polling when idle or in the background.
                if (!busy && (_state.value.downloading || _state.value.installing)) refresh()
            }
        }
    }

    private suspend fun refresh(checkPermissions: Boolean = false) {
        // Latch before IPC: an OEM permission popup can steal focus and cancel this job.
        // Regaining focus must not immediately repeat the query and create a popup loop.
        if (checkPermissions) {
            permissionsLoaded = true
        }
        val snapshot = withContext(Dispatchers.IO) {
            store.clearFinishedUpdate()
            // An abandoned or timed-out installer must not leave the UI stuck forever.
            if (store.sessionId >= 0 && app.packageManager.packageInstaller.getSessionInfo(store.sessionId) == null) {
                store.sessionId = -1
                store.disarm()
            }
            val download = UpdateDownloads.refresh(app)
            val installed = installedVersion(app)
            val candidate = store.candidate?.takeIf { it.versionCode > installed.first }
            val ready = candidate != null && store.verified
            val canInstall = if (checkPermissions) UpdateInstaller.canInstall(app) else _state.value.canInstall
            val installing = store.sessionId >= 0
            val status = when {
                download.running -> "Downloading ${candidate?.versionName.orEmpty()}"
                installing -> "Opening the system installer"
                ready && !canInstall -> "The update is downloaded. Allow installation to continue."
                ready -> "The update is ready to install"
                else -> message
            }
            UpdateUiState(
                currentVersion = "${installed.second} (${installed.first})",
                candidate = candidate,
                downloading = download.running, progress = download.percent,
                ready = ready, installing = installing,
                canInstall = canInstall,
                accessibilityEnabled = if (checkPermissions) UpdateAccessibilityService.isEnabled(app) else _state.value.accessibilityEnabled,
                autoConfirm = store.autoConfirm, message = status, error = store.error,
                installationSetupNeeded = installationSetupNeeded && !canInstall
            )
        }
        if (checkPermissions) {
            com.openlauncher.app.diagnostics.DiagnosticLog.event("UpdateSetup", "Permission check: install=${snapshot.canInstall}, helper=${snapshot.accessibilityEnabled}")
        }
        if (snapshot.canInstall) installationSetupNeeded = false
        _state.value = snapshot
        if (!foreground || !windowFocused) return
        if (store.confirmationIntent != null) {
            runCatching { UpdateInstaller.launchConfirmation(app) }.onFailure {
                UpdateInstaller.abandon(app)
                store.error = it.message ?: "Cannot open the system installer"
            }
        } else if (snapshot.ready && snapshot.canInstall && store.installRequested && store.sessionId < 0) {
            install()
        }
    }

    fun check() {
        if (busy || _state.value.downloading || _state.value.installing) return
        if (BuildConfig.DEBUG) {
            message = "Production updates are available in the signed release build"
            _state.value = _state.value.copy(message = message)
            return
        }
        busy = true
        _state.value = _state.value.copy(checking = true, error = null)
        viewModelScope.launch {
            try {
                val update = withContext(Dispatchers.IO) { UpdateClient.latest(app) }
                val installed = installedVersion(app).first
                if (update.versionCode > installed) {
                    val previous = store.candidate
                    if (previous?.versionCode != update.versionCode || previous.sha256 != update.sha256 ||
                        previous.apkUrl != update.apkUrl || previous.size != update.size ||
                        previous.certificateSha256 != update.certificateSha256) {
                        withContext(Dispatchers.IO) { UpdateDownloads.cancel(app) }
                    }
                    store.candidate = update
                    message = "Version ${update.versionName} is available"
                } else {
                    withContext(Dispatchers.IO) { UpdateDownloads.cancel(app) }
                    store.candidate = null
                    message = "You have the latest version"
                }
                store.error = null
            } catch (_: NoReleaseException) {
                message = "No published updates yet"
                store.error = null
            } catch (error: Exception) {
                store.error = error.message ?: "Could not check for updates"
            } finally {
                busy = false
                refresh(checkPermissions = true)
            }
        }
    }

    fun download() {
        val candidate = _state.value.candidate ?: return
        if (busy || _state.value.downloading || _state.value.installing) return
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { UpdateDownloads.start(app, candidate, installWhenReady = _state.value.canInstall) }
                message = "Downloading ${candidate.versionName}"
            } catch (error: Exception) {
                store.error = error.message ?: "Cannot start the download"
            } finally {
                busy = false
                refresh()
            }
        }
    }

    fun install() {
        if (busy || !_state.value.ready || _state.value.installing) return
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { UpdateInstaller.stage(app) }
                message = "Opening the system installer"
            } catch (error: Exception) {
                store.installRequested = false
                store.error = error.message ?: "Cannot install the update"
            } finally {
                busy = false
                refresh()
            }
        }
    }

    /** Explicit continuation of a downloaded update, never an automatic settings popup loop. */
    fun allowInstallation() {
        if (busy || !_state.value.ready || _state.value.installing) return
        if (Build.VERSION.SDK_INT < 26) { install(); return }
        store.error = null
        store.installRequested = true
        // Only the return from this explicit action rechecks permission. Normal focus changes
        // retain the permission latch to avoid repeated OEM permission queries/popups.
        permissionsLoaded = false
        val opened = com.openlauncher.app.util.DevicePermissionSetup.openSystemScreen(app,
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")), "UpdateSetup")
        if (opened) {
            _state.value = _state.value.copy(error = null)
        } else {
            permissionsLoaded = true
            installationSetupNeeded = true
            _state.value = _state.value.copy(installationSetupNeeded = true, error = null)
        }
    }

    fun dismissInstallationSetup() {
        installationSetupNeeded = false
        store.installRequested = false
        _state.value = _state.value.copy(installationSetupNeeded = false)
    }

    fun recheckInstallationSetup() {
        if (busy) return
        store.installRequested = true
        store.error = null
        viewModelScope.launch {
            refresh(checkPermissions = true)
            if (!_state.value.canInstall) {
                store.error = "Installation is still not allowed. Complete the USB setup, then recheck."
                _state.value = _state.value.copy(error = store.error)
            }
        }
    }

    fun cancelDownload() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { UpdateDownloads.cancel(app) }
            message = "Download cancelled"
            refresh()
        }
    }

    fun setAutoConfirm(enabled: Boolean) {
        store.autoConfirm = enabled
        if (!enabled) store.disarm()
        _state.value = _state.value.copy(autoConfirm = enabled)
    }
}
