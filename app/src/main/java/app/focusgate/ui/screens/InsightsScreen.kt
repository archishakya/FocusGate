package app.focusgate.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.focusgate.core.ArmState
import app.focusgate.core.DisarmReason
import app.focusgate.core.EngineSnapshot
import app.focusgate.core.FeedEntry
import app.focusgate.core.MonitoredApps
import app.focusgate.core.TestBlockState
import app.focusgate.service.FocusService
import app.focusgate.system.OemSettings
import app.focusgate.system.Permissions
import app.focusgate.ui.AppRow
import app.focusgate.ui.Cfg
import app.focusgate.ui.components.Muted
import app.focusgate.ui.components.PermissionRow
import app.focusgate.ui.components.SectionCard
import app.focusgate.ui.components.SubScreen
import app.focusgate.ui.components.rememberLocationRequester
import app.focusgate.ui.components.rememberNotificationRequester
import app.focusgate.ui.components.safeStart
import app.focusgate.ui.rowStatus
import app.focusgate.ui.theme.GoGreen
import app.focusgate.ui.theme.StopRed
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun feedLine(e: FeedEntry): String {
    val hh = (e.minuteOfDay / 60).toString().padStart(2, '0')
    val mm = (e.minuteOfDay % 60).toString().padStart(2, '0')
    val app = e.appId?.let { id -> MonitoredApps.all.getOrNull(id)?.label }
    return if (app != null) "$hh:$mm · $app · ${e.message}" else "$hh:$mm · ${e.message}"
}

private fun testText(t: TestBlockState): String? = when (t) {
    TestBlockState.Idle -> null
    TestBlockState.Pending, TestBlockState.Waiting ->
        "Switch to another app now (not a payment app). The test runs a few seconds after you leave."
    TestBlockState.Fired -> "Launching the test screen…"
    is TestBlockState.Passed ->
        "Test worked. Pop-ups from background are allowed. Waited ${t.waitedS} s after you left FocusGate."
    TestBlockState.Failed ->
        "Test failed: enable 'Display pop-up windows while running in background' and 'Display over other apps'"
    TestBlockState.Inconclusive -> "Inconclusive: switch to another app and wait"
}

@Composable
private fun StatusLine(label: String, value: String, good: Boolean?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        when (good) {
            true -> Icon(Icons.Filled.CheckCircle, contentDescription = "OK", tint = GoGreen)
            false -> Icon(Icons.Filled.Warning, contentDescription = "Needs attention", tint = StopRed)
            null -> Spacer(Modifier.width(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun InsightsScreen(
    cfg: Cfg,
    rows: List<AppRow>,
    snap: EngineSnapshot,
    arm: ArmState,
    feedVisible: Boolean,
    onFeedVisible: (Boolean) -> Unit,
    onTestBlocking: () -> Unit,
    onPermsChanged: () -> Unit,
    onGpsTest: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val armed = arm is ArmState.Armed
    val askNotifications = rememberNotificationRequester(onPermsChanged)
    val askLocation = rememberLocationRequester(onPermsChanged)
    val p = cfg.perms

    SubScreen("Insights", onBack) {
        SectionCard {
            Text("Live status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (armed) StatusLine("Zone", zoneLabel(snap.zone), null)
            else Muted("FocusGate is off, so nothing is being watched.")
            rows.forEach { StatusLine(it.label, rowStatus(it), null) }
        }

        SectionCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Decision feed",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = { onFeedVisible(!feedVisible) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (feedVisible) "Hide" else "Show")
                }
            }
            if (!feedVisible) {
                Muted("Hidden. It lives in memory only and never shows coordinates.")
            } else if (snap.feed.isEmpty()) {
                Muted("Nothing yet.")
            } else {
                snap.feed.asReversed().take(50).forEach { Text(feedLine(it), style = MaterialTheme.typography.bodySmall) }
            }
        }

        SectionCard {
            Text("Diagnostics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            PermissionRow("Usage Access", "Needed to count minutes", p.usage, "Fix") {
                ctx.safeStart(Permissions.usageAccessIntent())
            }
            PermissionRow("Notifications", "Needed for the always-on notice", p.notif, "Fix") { askNotifications() }
            PermissionRow(
                "Precise location",
                if (p.coarseOnly) "Precise location needed for zones" else "Needed for zones",
                p.precise, "Fix",
            ) { askLocation() }
            PermissionRow("Location switch", "Phone location must be on for zones", p.locationOn, "Turn on") {
                ctx.safeStart(Permissions.locationSourceIntent())
            }
            PermissionRow("Display over other apps", "Lets the block screen open", p.overlay, "Fix") {
                ctx.safeStart(Permissions.overlayIntent(ctx))
            }
            PermissionRow("Battery (unrestricted)", "Keeps FocusGate alive", p.battery, "Fix") {
                ctx.safeStart(Permissions.batteryListIntent())
            }
            PermissionRow("Autostart", "Can't check, tap to open", null, "Open") { OemSettings.openAutostart(ctx) }
            PermissionRow("Background pop-ups", "Can't check, tap to open", null, "Open") {
                OemSettings.openBackgroundPopups(ctx)
            }
            PermissionRow("High background power", "Can't check, tap to open", null, "Open") {
                OemSettings.openBackgroundPower(ctx)
            }
        }

        SectionCard {
            Text("Service", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val running = FocusService.isRunning
            StatusLine("Service running", if (running) "Yes" else "No", if (armed) running else null)
            val since = (arm as? ArmState.Armed)?.armedAtTs?.takeIf { it > 0 }?.let {
                DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
            }
            StatusLine("Armed since", since ?: "—", null)
            StatusLine(
                "Restart status",
                when {
                    armed -> "Same boot"
                    (arm as? ArmState.Disarmed)?.reason == DisarmReason.REBOOT -> "Disarmed after restart"
                    else -> "—"
                },
                null,
            )
            if (armed) {
                StatusLine(
                    "Block launch",
                    if (snap.blockLaunchHealthy) "Healthy" else "May not open",
                    snap.blockLaunchHealthy,
                )
                val degraded = snap.locationDegraded || FocusService.locationDegraded
                StatusLine("Location checks", if (degraded) "Paused: open FocusGate" else "Normal", !degraded)
                StatusLine("Location errors", snap.locationErrors.toString(), null)
            }
        }

        SectionCard {
            Text("Self-tests", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Button(
                onClick = onTestBlocking,
                enabled = armed,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Test blocking")
            }
            if (!armed) Muted("Turn FocusGate on to run this.")
            val msg = if (armed) testText(snap.test) else null
            if (msg != null) {
                Text(msg, style = MaterialTheme.typography.bodyMedium)
                if (snap.test == TestBlockState.Failed) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { OemSettings.openBackgroundPopups(ctx) }) { Text("Pop-up settings") }
                        FilledTonalButton(onClick = { ctx.safeStart(Permissions.overlayIntent(ctx)) }) { Text("Overlay") }
                    }
                }
            }
            OutlinedButton(
                onClick = onGpsTest,
                enabled = armed,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.GpsFixed, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("GPS accuracy test")
            }
            Muted("The definitive check: turn FocusGate on, swipe it from Recents, then open a timed-out app.")
        }
    }
}
