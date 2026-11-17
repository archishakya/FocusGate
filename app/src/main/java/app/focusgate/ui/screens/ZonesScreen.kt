package app.focusgate.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.focusgate.core.Zone
import app.focusgate.core.ZoneRules
import app.focusgate.ui.components.Muted
import app.focusgate.ui.components.SectionCard
import app.focusgate.ui.components.SubScreen

@Composable
fun ZonesScreen(
    zones: List<Zone>,
    armed: Boolean,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<Zone?>(null) }
    SubScreen("Zones", onBack) {
        Muted("When an app's time is up, it closes inside these places and stays open everywhere else.")
        if (!armed) Muted("Turn FocusGate on to use location.")
        if (zones.isEmpty()) {
            SectionCard {
                Text("No zones yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Muted("Without a zone, apps stay open after their time is up.")
            }
        }
        zones.forEach { z ->
            SectionCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column2(Modifier.weight(1f), z)
                    IconButton(onClick = { pendingDelete = z }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete zone ${z.name}")
                    }
                }
            }
        }
        Button(
            onClick = onAdd,
            enabled = ZoneRules.canAdd(zones),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (ZoneRules.canAdd(zones)) "Add a zone" else "Zone limit reached")
        }
    }
    pendingDelete?.let { z ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${z.name}?") },
            text = { Text("Apps will stay open here once their time is up.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(z.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Column2(modifier: Modifier, z: Zone) {
    androidx.compose.foundation.layout.Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(z.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Muted("${z.radiusM} m radius")
        if (app.focusgate.core.ZoneRules.radiusWarning(z.radiusM)) Muted(RADIUS_WARNING_COPY)
    }
}
