package com.openlauncher.app.ui.components

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

@Composable
fun MediaAccessErrorDialog(message: String, onDismiss: () -> Unit, onReport: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Media access unavailable") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onReport) { Text("Create diagnostic report") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
