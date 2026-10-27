package com.openlauncher.app.ui.components

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier

@Composable
fun MediaAccessErrorDialog(message: String, onDismiss: () -> Unit, onReport: () -> Unit, title: String = "Media access setup") {
    val context = LocalContext.current
    var launchError by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(launchError ?: message)
                VehiclePermissionSetupPanel()
            }
        },
        confirmButton = { TextButton(colors = com.openlauncher.app.ui.theme.textControlColors(), onClick = { launchError = com.openlauncher.app.util.DevicePermissionSetup.openJCarTools(context) }) { Text("Open JCarTools") } },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                TextButton(colors = com.openlauncher.app.ui.theme.textControlColors(), onClick = onReport) { Text("Diagnostic ZIP") }
                TextButton(colors = com.openlauncher.app.ui.theme.textControlColors(), onClick = onDismiss) { Text("Close") }
            }
        }
    )
}
