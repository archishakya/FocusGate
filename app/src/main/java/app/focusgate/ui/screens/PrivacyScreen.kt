package app.focusgate.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import app.focusgate.ui.components.Muted
import app.focusgate.ui.components.SectionCard
import app.focusgate.ui.components.SubScreen

private val STORED = listOf(
    "Your daily limit for each app.",
    "Minutes used today for each app (one number per app) and which day it belongs to.",
    "Your zones: a name, a centre point and a radius. These are the only coordinates FocusGate ever stores.",
    "Whether FocusGate is on or off, plus a few timestamps used to notice restarts and crashes.",
    "Your choices: theme, Essentials or Insights, the feed switch, and setup flags.",
)

private val NOT_STORED = listOf(
    "No usage history, sessions or per-hour data.",
    "No location readings and no accuracy history.",
    "No record of which app you opened and when.",
    "The Decision Feed lives in memory only (last 50 lines, no coordinates) and is cleared when FocusGate turns off.",
    "Nothing is backed up or copied to another device.",
)

private val PERMISSIONS = listOf(
    "Usage Access" to "Sees which of Instagram, Chrome or YouTube is open so it can count minutes. Only read while FocusGate is on, and only those three apps are looked at.",
    "Notifications" to "Shows the always-on 'FocusGate is on' notice with a Disable now button, and rare alerts.",
    "Foreground service" to "Keeps the monitor running while FocusGate is on. A special-use type covers usage monitoring.",
    "Foreground service (location)" to "Lets the monitor read location while the screen is on, only for zone checks.",
    "Display over other apps" to "Only so Android lets FocusGate open its block screen from the background. FocusGate never draws on top of other apps.",
    "Precise location" to "Checks if you are inside a zone. Zones can be as small as 5 m, so approximate location is not enough. " +
        "Location comes from Google Play Services' location service when available (the same one Google Maps uses; it may use Wi-Fi, mobile signals and Google's servers), " +
        "otherwise from GPS. It's only requested while FocusGate is on, the screen is on, you have zones and an app has 2 minutes or less left, " +
        "or while zone setup or the GPS test is open.",
    "Approximate location" to "Android asks for it together with precise location. It isn't useful alone.",
)

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    SubScreen("Privacy", onBack) {
        SectionCard(container = MaterialTheme.colorScheme.primaryContainer) {
            Text(
                "FocusGate has no internet permission and never sends your data anywhere.",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "To find your location it asks Google Play Services' location service (the same one Google Maps uses). " +
                    "That service may use GPS, Wi-Fi and mobile signals, and Google's servers, to work out your position. " +
                    "FocusGate only receives the result, and only when it needs it.",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Block("What FocusGate stores", STORED)
        Block("What it doesn't store", NOT_STORED)
        SectionCard {
            Text("Permissions and why", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            PERMISSIONS.forEach { (name, why) ->
                Column {
                    Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Muted(why)
                }
            }
        }
        SectionCard {
            Text("GPS honesty", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Muted(ZONE_HONESTY_COPY)
        }
        Block(
            "Good to know",
            listOf(
                "Indoors, GPS can be too fuzzy. When FocusGate can't tell where you are, apps stay open.",
                "Location starts 2 minutes before an app's time is up, so a fix is usually ready. Without one, blocking can take 30 s or more.",
                "A blocked app can be visible for a second or two before the block screen covers it.",
                "Force-stopping FocusGate, removing Usage Access or uninstalling it turns the limits off. That's by design.",
                "Swiping away the notification can hide its Disable button. The Disable now button in the app is always there.",
                "Your phone may stop FocusGate in the background unless you allow background power and autostart.",
            ),
        )
    }
}

@Composable
private fun Block(title: String, lines: List<String>) {
    SectionCard {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        lines.forEach { Row { Text("•  "); Text(it, Modifier.weight(1f).padding(bottom = 2.dp)) } }
    }
}
