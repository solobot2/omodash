package com.openlauncher.app.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openlauncher.app.util.DevicePermissionSetup

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun UpdateSettingsPanel(vm: UpdateViewModel = viewModel()) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val state by vm.state.collectAsStateWithLifecycle()
    var setupMessage by remember { mutableStateOf<String?>(null) }
    var showReport by remember { mutableStateOf(false) }
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
            OutlinedButton(colors = com.openlauncher.app.ui.theme.outlinedControlColors(), onClick = vm::check, enabled = !state.checking && !state.downloading && !state.installing) {
                Text(if (state.checking) "Checking…" else "Check updates")
            }
            if (state.candidate != null && !state.downloading && !state.installing) {
                Button(onClick = if (state.ready) vm::install else vm::download, enabled = !state.ready || state.canInstall,
                    colors = com.openlauncher.app.ui.theme.filledControlColors()) {
                    Text(if (state.ready) "Install update" else if (state.canInstall) "Download and install" else "Download update")
                }
            }
            if (state.downloading) OutlinedButton(colors = com.openlauncher.app.ui.theme.outlinedControlColors(), onClick = vm::cancelDownload) { Text("Cancel download") }
        }
        if (!state.canInstall) {
            Text("One-time setup: allow this launcher to install updates.", fontSize = 12.sp)
            OutlinedButton(colors = com.openlauncher.app.ui.theme.outlinedControlColors(), onClick = {
                vm.permissionSetupStarted()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                if (!DevicePermissionSetup.openSystemScreen(context, intent, "UpdateSetup")) {
                    setupMessage = "This firmware has no working app installation permission screen. " + DevicePermissionSetup.INSTRUCTIONS
                }
            }) { Text("Allow app installations") }
            OutlinedButton(colors = com.openlauncher.app.ui.theme.outlinedControlColors(), onClick = vm::recheckPermissions) { Text("Recheck permissions") }
            if (state.ready) Text("The APK is downloaded and verified. Installation is waiting for permission.", fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Automatically confirm this launcher's updates", fontSize = 13.sp)
                Text("Only our verified APK; other installation dialogs are left alone.", fontSize = 11.sp)
            }
            Switch(checked = state.autoConfirm, onCheckedChange = vm::setAutoConfirm)
        }
        if (state.autoConfirm && !state.accessibilityEnabled) {
            Text(if (android.os.Build.VERSION.SDK_INT >= 31)
                "Android can install self-updates automatically. If your device still asks for confirmation, enable the update helper or confirm manually."
                else "Enable Open Launcher Update helper in Accessibility for automatic confirmation. You can also confirm installation manually.", fontSize = 12.sp)
            OutlinedButton(colors = com.openlauncher.app.ui.theme.outlinedControlColors(), onClick = {
                vm.permissionSetupStarted()
                if (DevicePermissionSetup.openSystemScreen(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "UpdateSetup")) return@OutlinedButton
                if (DevicePermissionSetup.enableUpdateHelper(context)) vm.recheckPermissions()
                else setupMessage = "This firmware has no working Accessibility screen. " + DevicePermissionSetup.INSTRUCTIONS
            }) {
                Text("Enable update helper")
            }
        }
        if (state.installing) Text("Complete installation in the system window. If needed, press Home to return to the updated launcher.", fontSize = 12.sp)
    }
    setupMessage?.let { message ->
        com.openlauncher.app.ui.components.MediaAccessErrorDialog(message,
            onDismiss = { setupMessage = null },
            onReport = { setupMessage = null; showReport = true }, title = "Update permissions")
    }
    if (showReport) com.openlauncher.app.diagnostics.DiagnosticReportDialog(onDismiss = { showReport = false })
}
