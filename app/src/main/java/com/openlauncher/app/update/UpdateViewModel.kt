package com.openlauncher.app.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openlauncher.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
)

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.applicationContext
    private val store = UpdateStore(app)
    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state
    private var foreground = false
    private var busy = false
    private var message = "Check for a newer version on GitHub"

    init {
        viewModelScope.launch {
            while (isActive) {
                if (!busy && foreground) refresh()
                delay(750)
            }
        }
    }

    fun setForeground(value: Boolean) { foreground = value }

    private suspend fun refresh() {
        val snapshot = withContext(Dispatchers.IO) {
            store.clearFinishedUpdate()
            // An abandoned or timed-out installer must not leave the UI stuck forever.
            if (store.sessionId >= 0 && app.packageManager.packageInstaller.getSessionInfo(store.sessionId) == null) {
                store.sessionId = -1
                store.disarm()
            }
            val download = UpdateDownloads.refresh(app)
            val installed = installedVersion(app)
            UpdateUiState(
                currentVersion = "${installed.second} (${installed.first})",
                candidate = store.candidate?.takeIf { it.versionCode > installed.first },
                downloading = download.running, progress = download.percent,
                ready = store.verified, installing = store.sessionId >= 0,
                canInstall = UpdateInstaller.canInstall(app),
                accessibilityEnabled = UpdateAccessibilityService.isEnabled(app),
                autoConfirm = store.autoConfirm, message = message, error = store.error
            )
        }
        _state.value = snapshot
        if (!foreground) return
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
                refresh()
            }
        }
    }

    fun download() {
        val candidate = _state.value.candidate ?: return
        if (busy || _state.value.downloading || _state.value.installing) return
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { UpdateDownloads.start(app, candidate) }
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
