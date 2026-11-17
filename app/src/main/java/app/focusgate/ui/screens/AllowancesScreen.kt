package app.focusgate.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.focusgate.ui.AppRow
import app.focusgate.ui.components.Muted
import app.focusgate.ui.components.SectionCard
import app.focusgate.ui.components.SubScreen
import app.focusgate.ui.components.appIcon
import kotlin.math.roundToInt

@Composable
fun AllowancesScreen(rows: List<AppRow>, onSet: (appId: Int, min: Int) -> Unit, onBack: () -> Unit) {
    SubScreen("Daily limits", onBack) {
        Muted("How long each app stays open per day. 0 means it is always at its limit.")
        rows.forEach { row -> AllowanceCard(row, onSet) }
    }
}

@Composable
private fun AllowanceCard(row: AppRow, onSet: (Int, Int) -> Unit) {
    var value by remember(row.allowanceMin) { mutableFloatStateOf(row.allowanceMin.toFloat()) }
    val minutes = value.roundToInt().coerceIn(0, 600)
    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(appIcon(row.appId), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(row.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            FilledTonalIconButton(onClick = {
                val v = (minutes - 5).coerceAtLeast(0)
                value = v.toFloat()
                onSet(row.appId, v)
            }) { Icon(Icons.Filled.Remove, contentDescription = "5 minutes less for ${row.label}") }
            Text(
                "$minutes min",
                Modifier.width(88.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
            FilledTonalIconButton(onClick = {
                val v = (minutes + 5).coerceAtMost(600)
                value = v.toFloat()
                onSet(row.appId, v)
            }) { Icon(Icons.Filled.Add, contentDescription = "5 minutes more for ${row.label}") }
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onSet(row.appId, minutes) },
            valueRange = 0f..600f,
            modifier = Modifier.fillMaxWidth(),
        )
        if (minutes == 0) Muted("Always at limit")
    }
}
