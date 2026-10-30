package com.openlauncher.app.update

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun UpdateSettingsPanel(vm: UpdateViewModel = viewModel()) {
    val owner = LocalLifecycleOwner.current
    val state by vm.state.collectAsStateWithLifecycle()
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused, vm) { vm.setWindowFocused(focused) }
    DisposableEffect(owner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.setForeground(true)
                Lifecycle.Event.ON_PAUSE -> vm.setForeground(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        vm.setForeground(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { owner.lifecycle.removeObserver(observer); vm.setForeground(false) }
    }

    UpdateSettingsContent(state, vm::check, vm::download, vm::install, vm::allowInstallation,
        vm::cancelDownload, vm::setAutoConfirm, vm::recheckInstallationSetup, vm::dismissInstallationSetup)
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun UpdateSettingsContent(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onAllowInstallation: () -> Unit,
    onCancelDownload: () -> Unit,
    onAutoConfirm: (Boolean) -> Unit,
    onRecheckInstallation: () -> Unit,
    onDismissSetup: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Installed version: ${state.currentVersion}", fontSize = 13.sp)
        Text(state.error ?: state.message, color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
            fontSize = 12.sp)
        if (state.downloading) {
            val progress = state.progress
            if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                Text("Downloading: $progress%", fontSize = 12.sp)
            }
        }
        state.candidate?.let { update ->
            Text("${update.versionName} · ${"%.1f".format(update.size / 1048576.0)} MB", fontSize = 13.sp)
            if (update.notes.isNotBlank()) Text(update.notes, fontSize = 12.sp)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(colors = com.openlauncher.app.ui.theme.outlinedControlColors(), onClick = onCheck, enabled = !state.checking && !state.downloading && !state.installing) {
                Text(if (state.checking) "Checking…" else "Check updates")
            }
            state.primaryAction?.let { action ->
                Button(onClick = when (action) {
                    UpdatePrimaryAction.DOWNLOAD -> onDownload
                    UpdatePrimaryAction.INSTALL -> onInstall
                    UpdatePrimaryAction.ALLOW_INSTALLATION -> onAllowInstallation
                }, colors = com.openlauncher.app.ui.theme.filledControlColors()) {
                    Text(checkNotNull(state.primaryActionLabel))
                }
            }
            if (state.downloading) OutlinedButton(colors = com.openlauncher.app.ui.theme.outlinedControlColors(), onClick = onCancelDownload) { Text("Cancel download") }
        }
        Text(
            if (state.canInstall) "Update installation: allowed"
            else "Update installation: permission required.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp
        )
        if (state.ready && !state.canInstall) Text("The APK is downloaded and verified. Press Allow installation to continue.", fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Automatically confirm this launcher's updates", fontSize = 13.sp)
                Text("Only our verified APK; other installation dialogs are left alone.", fontSize = 11.sp)
            }
            Switch(checked = state.autoConfirm, onCheckedChange = onAutoConfirm)
        }
        if (state.autoConfirm) Text(
            if (state.accessibilityEnabled) "Automatic confirmation helper: enabled"
            else if (android.os.Build.VERSION.SDK_INT >= 31) "Android may install self-updates automatically; otherwise confirm in the system window."
            else "Automatic confirmation helper: not configured. USB setup enables it; manual confirmation is also possible.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp
        )
        if (state.installing) Text("Complete installation in the system window. If needed, press Home to return to the updated launcher.", fontSize = 12.sp)
    }
    if (state.installationSetupNeeded) AlertDialog(
        onDismissRequest = onDismissSetup,
        title = { Text("Set up update installation") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("This head unit has no permission screen for installing apps. Connect your laptop by USB and run:")
            Text("python3 tools/vehicle_usb.py setup", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            Text("The downloaded APK is kept. After setup, press Recheck and install.")
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = onRecheckInstallation,
            colors = com.openlauncher.app.ui.theme.textControlColors()) { Text("Recheck and install") } },
        dismissButton = { TextButton(onClick = onDismissSetup,
            colors = com.openlauncher.app.ui.theme.textControlColors()) { Text("Cancel") } }
    )

}
