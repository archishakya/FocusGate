package app.focusgate.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.focusgate.core.DisarmReason
import app.focusgate.core.EngineSnapshot
import app.focusgate.core.ZoneState
import app.focusgate.system.Permissions
import app.focusgate.ui.AppRow
import app.focusgate.ui.Cfg
import app.focusgate.ui.components.AppCard
import app.focusgate.ui.components.Banner
import app.focusgate.ui.components.Muted
import app.focusgate.ui.components.SectionCard
import app.focusgate.ui.components.SegmentedToggle
import app.focusgate.ui.components.StatusCard
import app.focusgate.ui.components.rememberLocationRequester
import app.focusgate.ui.components.safeStart
import app.focusgate.ui.rowStatus

fun zoneLabel(z: ZoneState): String = when (z) {
    ZoneState.INSIDE -> "Inside a zone"
    ZoneState.OUTSIDE -> "Outside your zones"
    ZoneState.UNKNOWN -> "Unclear"
}

@Composable
internal fun NavTile(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SectionCard(modifier.clip(MaterialTheme.shapes.large).clickable(role = Role.Button, onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Muted(subtitle)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
fun HomeScreen(
    armed: Boolean,
    reason: DisarmReason?,
    rows: List<AppRow>,
    cfg: Cfg,
    snap: EngineSnapshot,
    insights: Boolean,
    onMode: (Boolean) -> Unit,
    onSettings: () -> Unit,
    onAllowances: () -> Unit,
    onZones: () -> Unit,
    onInsights: () -> Unit,
    onDismissCrash: () -> Unit,
    onPermsChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    val askLocation = rememberLocationRequester(onPermsChanged)
    val installed = rows.filter { it.installed }
    val line = when {
        !armed -> "Tap Enable to start your limits"
        installed.isEmpty() -> "None of your apps are installed"
        else -> installed.minBy { it.remainingMs }.let { "${it.label}: ${rowStatus(it)}" }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "FocusGate",
                Modifier.weight(1f).padding(start = 8.dp),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
            )
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
        }

        if (reason == DisarmReason.CRASH_LOOP || cfg.crashBanner) {
            Banner("FocusGate turned itself off after repeated errors", actionLabel = "Got it", onAction = onDismissCrash)
        }
        if (reason == DisarmReason.PERMISSION_LOST) {
            Banner(
                "Usage Access was turned off",
                actionLabel = "Fix",
                onAction = { ctx.safeStart(Permissions.usageAccessIntent()) },
            )
        }
        if (reason == DisarmReason.REBOOT) Banner("FocusGate is off after restart. Tap Enable.")
        if (cfg.zonesCorrupt) Banner("Some saved zones couldn't be read. Please add them again.")
        if (armed && cfg.zones.isNotEmpty() && !cfg.perms.locationOn) {
            Banner(
                "Location is off. Zones can't be checked, so apps stay open.",
                actionLabel = "Turn on",
                onAction = { ctx.safeStart(Permissions.locationSourceIntent()) },
            )
        }
        if (armed && cfg.zones.isNotEmpty() && !cfg.perms.precise) {
            Banner("Precise location needed for zones", actionLabel = "Allow", onAction = askLocation)
        }
        if (armed && !cfg.perms.overlay) {
            Banner(
                "Blocking may not open without 'Display over other apps'",
                actionLabel = "Fix",
                onAction = { ctx.safeStart(Permissions.overlayIntent(ctx)) },
            )
        }

        if (!armed && reason == DisarmReason.NEVER_ARMED && !cfg.onboardingDone) {
            SectionCard(container = MaterialTheme.colorScheme.tertiaryContainer) {
                Text("Welcome", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Give Instagram, Chrome and YouTube a daily limit. When time's up they close at the places you choose. " +
                        "Tap Enable below to set up.",
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }

        StatusCard(on = armed, line = line)

        SegmentedToggle(
            options = listOf("Essentials", "Insights"),
            selected = if (insights) 1 else 0,
            onSelect = { onMode(it == 1) },
        )

        rows.forEach { AppCard(it) }

        if (insights && armed) {
            SectionCard {
                Text("Live status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Zone: ${zoneLabel(snap.zone)}")
            }
        }

        NavTile(Icons.Filled.Tune, "Daily limits", "Minutes per app", onAllowances)
        NavTile(
            Icons.Filled.Place,
            "Zones",
            if (cfg.zones.isEmpty()) "No zones yet" else "${cfg.zones.size} saved",
            onZones,
        )
        if (insights) NavTile(Icons.Filled.Insights, "Insights & tests", "Feed, diagnostics, self-tests", onInsights)
        Spacer(Modifier.padding(4.dp))
    }
}
