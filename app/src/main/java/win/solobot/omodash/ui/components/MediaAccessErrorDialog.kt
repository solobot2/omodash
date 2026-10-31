package win.solobot.omodash.ui.components

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier

@Composable
fun MediaAccessErrorDialog(message: String, onDismiss: () -> Unit, onReport: () -> Unit, title: String = "Media access setup") {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(colors = win.solobot.omodash.ui.theme.textControlColors(), onClick = onReport) { Text("Diagnostic ZIP") } },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                TextButton(colors = win.solobot.omodash.ui.theme.textControlColors(), onClick = onDismiss) { Text("Close") }
            }
        }
    )
}
