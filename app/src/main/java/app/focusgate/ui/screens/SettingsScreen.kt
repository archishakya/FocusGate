package app.focusgate.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import app.focusgate.ui.components.Muted
import app.focusgate.ui.components.SectionCard
import app.focusgate.ui.components.SegmentedToggle
import app.focusgate.ui.components.SubScreen

private val THEMES = listOf("SYSTEM", "LIGHT", "DARK")

@Composable
fun SettingsScreen(
    theme: String,
    onTheme: (String) -> Unit,
    insights: Boolean,
    onMode: (Boolean) -> Unit,
    onPermissions: () -> Unit,
    onPrivacy: () -> Unit,
    onBack: () -> Unit,
) {
    SubScreen("Settings", onBack) {
        SectionCard {
            Text("Theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            SegmentedToggle(
                options = listOf("System", "Light", "Dark"),
                selected = THEMES.indexOf(theme).coerceAtLeast(0),
                onSelect = { onTheme(THEMES[it]) },
            )
        }
        SectionCard {
            Text("Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            SegmentedToggle(
                options = listOf("Essentials", "Insights"),
                selected = if (insights) 1 else 0,
                onSelect = { onMode(it == 1) },
            )
            Muted("Essentials keeps it simple. Insights adds the decision feed, checks and self-tests.")
        }
        NavTile(Icons.Filled.Shield, "Permissions & setup", "Check what FocusGate needs", onPermissions)
        NavTile(Icons.Filled.PrivacyTip, "Privacy", "What is stored and why", onPrivacy)
    }
}
