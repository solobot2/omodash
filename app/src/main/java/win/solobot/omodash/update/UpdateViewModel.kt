package win.solobot.omodash.update

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import win.solobot.omodash.BuildConfig
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
                ready && !canInstall -> "The update is downloaded and verified. Press Install update to continue."
                ready && store.legacyInstaller -> "Confirm installation in the Android system window. If it was closed, press Install update to retry."
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
                autoConfirm = store.autoConfirm, message = status, error = store.error
            )
        }
        if (checkPermissions) {
            win.solobot.omodash.diagnostics.DiagnosticLog.event("UpdateSetup", "Permission check: install=${snapshot.canInstall}, helper=${snapshot.accessibilityEnabled}")
        }
        _state.value = snapshot
        if (!foreground || !windowFocused) return
        if (store.confirmationIntent != null) {
            runCatching { UpdateInstaller.launchConfirmation(app) }.onFailure {
                UpdateInstaller.abandon(app)
                store.error = it.message ?: "Cannot open the system installer"
            }
        } else if (snapshot.ready && store.installRequested && store.sessionId < 0) {
            when {
                snapshot.canInstall -> stageInstallation()
                !store.permissionSetupAttempted -> prepareInstallation()
                else -> {
                    store.installRequested = false
                    store.error = "Installation was not allowed. Press Install update to retry."
                    _state.value = _state.value.copy(error = store.error)
                }
            }
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

    /** The only installation entry point: download, access setup, verification and installation. */
    fun install() {
        val candidate = _state.value.candidate ?: return
        if (busy || !_state.value.installAvailable) return
        store.installRequested = true
        store.permissionSetupAttempted = false
        store.error = null
        busy = true
        viewModelScope.launch {
            try {
                if (!_state.value.ready) {
                    withContext(Dispatchers.IO) { UpdateDownloads.start(app, candidate, installWhenReady = true) }
                    message = "Downloading ${candidate.versionName}"
                }
            } catch (error: Exception) {
                store.installRequested = false
                store.error = error.message ?: "Cannot start the update"
            } finally {
                busy = false
                refresh(checkPermissions = true)
            }
        }
    }

    private fun stageInstallation() {
        if (busy || !_state.value.ready || _state.value.installing) return
        busy = true
        _state.value = _state.value.copy(installing = true, error = null)
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

    /** One attempt per Install update request; returning without access never opens a popup loop. */
    private fun prepareInstallation() {
        if (busy) return
        busy = true
        store.permissionSetupAttempted = true
        _state.value = _state.value.copy(installing = true, error = null, message = "Preparing update installation…")
        viewModelScope.launch {
            val allowed = withContext(Dispatchers.IO) {
                runCatching { LocalUpdateAccess.allow(app) }.onFailure {
                    win.solobot.omodash.diagnostics.DiagnosticLog.event("UpdateSetup", "Local ADB setup unavailable", it)
                }
                UpdateInstaller.canInstall(app)
            }
            busy = false
            if (allowed) {
                refresh(checkPermissions = true)
            } else {
                val opened = Build.VERSION.SDK_INT >= 26 &&
                    win.solobot.omodash.util.DevicePermissionSetup.openSystemScreen(app,
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")), "UpdateSetup")
                _state.value = _state.value.copy(installing = false)
                if (opened) {
                    permissionsLoaded = false
                    message = "Confirm installation access in the Android system window. The update will continue automatically."
                    _state.value = _state.value.copy(message = message)
                } else {
                    store.installRequested = false
                    store.error = "Installation setup is unavailable. Authorize local TCP ADB or run tools/vehicle_usb.py setup from a USB-connected laptop, then press Install update again."
                    refresh()
                }
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
