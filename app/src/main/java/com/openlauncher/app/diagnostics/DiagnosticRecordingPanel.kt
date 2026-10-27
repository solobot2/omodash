package com.openlauncher.app.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DiagnosticRecordingPanel() {
    val context = LocalContext.current
    val state by DiagnosticRecording.state.collectAsStateWithLifecycle()
    var showOptions by remember { mutableStateOf(false) }
    var includeSystem by remember { mutableStateOf(false) }
    val systemAvailable = remember { DiagnosticReport.hasSystemLogs(context) }
    val savedRecording = remember {
        java.io.File(context.filesDir, "diagnostic-recording/recording.json").isFile
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { showOptions = true }, enabled = !state.active,
            colors = com.openlauncher.app.ui.theme.outlinedControlColors()) {
            Text(if (state.active) "Recording logs…" else "Record logs for 2 minutes")
        }
        Text(state.message.ifBlank {
            if (savedRecording) "A previous recording is saved. Send / save a ZIP below, or start a new recording to replace it."
            else "Record the problem first, then send / save the diagnostic ZIP below."
        },
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
    if (showOptions) AlertDialog(
        onDismissRequest = { showOptions = false },
        title = { Text("Record logs for 2 minutes") },
        text = {
            Column {
                Text("Records logs and CPU / memory samples while you reproduce the problem. You can close settings. Stopping the launcher also stops recording; saved logs remain available after reopening it.")
                if (systemAvailable) Row {
                    Checkbox(checked = includeSystem, onCheckedChange = { includeSystem = it })
                    Text("Include system logs (may contain data from other apps)")
                } else Text("System logs need the one-time USB setup. Until then, only launcher logs are recorded.")
            }
        },
        confirmButton = { Button(onClick = { DiagnosticRecording.start(context, includeSystem); showOptions = false },
            colors = com.openlauncher.app.ui.theme.filledControlColors()) { Text("Start recording") } },
        dismissButton = { TextButton(onClick = { showOptions = false }, colors = com.openlauncher.app.ui.theme.textControlColors()) { Text("Cancel") } }
    )
}
