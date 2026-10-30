package com.openlauncher.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openlauncher.app.data.VehiclePresets

@Composable
fun VehiclePresetSelector(selectedId: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VehiclePresets.all.forEach { preset ->
            OutlinedCard(Modifier.fillMaxWidth().clickable { onSelect(preset.id) }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selectedId == preset.id, onClick = { onSelect(preset.id) })
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(preset.name, style = MaterialTheme.typography.titleMedium)
                        Text(preset.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
