package com.openlauncher.app.ui.components

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VehiclePermissionViewModel(app: Application) : AndroidViewModel(app) {
    private val context = app.applicationContext
    private val mutable = MutableStateFlow(Pair(false,
        if (context.getSharedPreferences("vehicle_permission_setup", Application.MODE_PRIVATE).getBoolean("attempted", false))
            "A local setup was requested. Media access: ${if (com.openlauncher.app.util.MediaAccess.isGranted(context)) "granted" else "not granted"}. " +
                "Use Recheck permissions in Updates to check installation access."
        else ""))
    val state = mutable.asStateFlow()
    fun configure() {
        if (mutable.value.first) return
        mutable.value = true to "Preparing setup…"
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    com.openlauncher.app.util.LocalAdbPermissions.configure(context) { mutable.value = true to it }
                }
                mutable.value = false to result
            } catch (error: Exception) { mutable.value = false to (error.message ?: "Vehicle setup failed") }
        }
    }
}

@Composable
fun VehiclePermissionSetupPanel(vm: VehiclePermissionViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("For head units without Android permission screens. Connects only to this device's local ADB to configure media, updates and diagnostic access for Open Launcher.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text("Android may ask you to allow debugging and may restart the launcher when installation access changes.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = vm::configure, enabled = !state.first,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
            Text("Configure vehicle permissions")
        }
        if (state.first) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.second.isNotBlank()) Text(state.second, color = MaterialTheme.colorScheme.onSurface)
    }
}
