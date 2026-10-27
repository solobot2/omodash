package com.openlauncher.app.diagnostics

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun DiagnosticReportDialog(onDismiss: () -> Unit, vm: DiagnosticViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    var description by rememberSaveable { mutableStateOf("") }
    var includeSystem by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf(vm.recipient) }
    var recording by remember { mutableStateOf(false) }
    val canReadSystem = remember { DiagnosticReport.hasSystemLogs(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report a problem") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Describe what you pressed and what happened. A diagnostic ZIP will be attached to your email.")
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(8000) },
                    label = { Text("What happened?") },
                    minLines = 2, maxLines = 4,
                    modifier = Modifier.fillMaxWidth(), enabled = !state.busy
                )
                OutlinedTextField(value = email, onValueChange = { email = it.take(254) },
                    label = { Text("Send to email") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                if (canReadSystem) {
                    Row {
                        Checkbox(checked = includeSystem, onCheckedChange = { includeSystem = it }, enabled = !state.busy)
                        Text("Also include system logs (may contain data from other apps)")
                    }
                } else Text("Includes launcher logs, crashes and device information. Full system logs are unavailable without special access.")
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.message?.let { Text(it) }
                state.report?.let { file ->
                    Text(file.name, style = MaterialTheme.typography.bodySmall)
                }
                if (Build.VERSION.SDK_INT >= 29) OutlinedButton(colors = com.openlauncher.app.ui.theme.outlinedControlColors(), onClick = { vm.save(description, includeSystem) }, enabled = !state.busy) { Text("Save ZIP to Downloads") }
                OutlinedButton(colors = com.openlauncher.app.ui.theme.outlinedControlColors(), onClick = { PerformanceTrace.start(context); recording = true }, enabled = !state.busy) { Text("Record performance for 2 minutes") }
                if (recording) Text("Recording continues with this dialog closed. Reproduce the issue, then send a new ZIP.")
            }
        },
        confirmButton = {
            Button(onClick = { vm.send(description, includeSystem, email) }, enabled = !state.busy,
                colors = com.openlauncher.app.ui.theme.filledControlColors()) { Text("Send report / Gmail") }
        },
        dismissButton = { TextButton(colors = com.openlauncher.app.ui.theme.textControlColors(), onClick = onDismiss) { Text("Close") } }
    )
}
