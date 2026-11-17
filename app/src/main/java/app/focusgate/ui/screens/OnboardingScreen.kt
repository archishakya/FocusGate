package app.focusgate.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.focusgate.system.OemSettings
import app.focusgate.system.Permissions
import app.focusgate.ui.PermState
import app.focusgate.ui.components.Muted
import app.focusgate.ui.components.SectionCard
import app.focusgate.ui.components.SubScreen
import app.focusgate.ui.components.rememberLocationRequester
import app.focusgate.ui.components.rememberNotificationRequester
import app.focusgate.ui.components.safeStart
import app.focusgate.ui.theme.GoGreen

/** Shown only when Enable is tapped with missing preconditions, or from Settings. Nothing is requested before a tap. */
@Composable
fun OnboardingScreen(
    perms: PermState,
    armed: Boolean,
    onRefresh: () -> Unit,
    onEnable: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var popupsConfirmed by rememberSaveable { mutableStateOf(false) }
    var powerConfirmed by rememberSaveable { mutableStateOf(false) }
    var autostartConfirmed by rememberSaveable { mutableStateOf(false) }
    val askNotifications = rememberNotificationRequester(onRefresh)
    val askLocation = rememberLocationRequester(onRefresh)

    LaunchedEffect(Unit) { onRefresh() }

    SubScreen("Set up FocusGate", onBack) {
        Muted("Two steps are needed to turn FocusGate on. The rest are recommended.")

        StepCard(
            n = 1, title = "Usage Access", tag = "Needed", done = perms.usage,
            body = "Android calls this a system-wide permission. FocusGate only reads Instagram, Chrome and YouTube, " +
                "only while it's on, and has no internet, so the data can't leave your phone.",
            button = "Open Usage Access",
            onClick = { ctx.safeStart(Permissions.usageAccessIntent()) },
        )
        StepCard(
            n = 2, title = "Notifications", tag = "Needed", done = perms.notif,
            body = "Needed for the always-on 'FocusGate is on' notice with a Disable button.",
            button = "Allow notifications",
            onClick = { askNotifications() },
        )
        StepCard(
            n = 3, title = "Display over other apps", tag = "Recommended", done = perms.overlay,
            body = "Lets FocusGate open its block screen when a timed-out app opens. It never draws over other apps. " +
                "Find FocusGate in the list and turn it on. Blocking may not open without this.",
            button = "Open settings",
            onClick = { ctx.safeStart(Permissions.overlayIntent(ctx)) },
        )
        StepCard(
            n = 4, title = "Background pop-ups", tag = "Optional", done = popupsConfirmed,
            body = "Only if your phone has it: App info → Permissions → Display pop-up windows while running in background → Allow. " +
                "Many phones don't have this setting. If 'Test blocking' works, you can skip it.",
            button = "Open settings",
            onClick = { OemSettings.openBackgroundPopups(ctx) },
            onConfirm = { popupsConfirmed = true },
        )
        StepCard(
            n = 5, title = "Background power (iQOO)", tag = "Recommended", done = powerConfirmed || perms.battery,
            body = "Battery → Background power consumption management → FocusGate → Allow high background power consumption. " +
                "Without it the phone may stop FocusGate in the background.",
            button = "Open settings",
            onClick = { OemSettings.openBackgroundPower(ctx) },
            onConfirm = { powerConfirmed = true },
        )
        StepCard(
            n = 6, title = "Autostart (iQOO)", tag = "Recommended", done = autostartConfirmed,
            body = "Settings > search Autostart (may not exist on all phones) and allow FocusGate. " +
                "Also lock it in Recents so it isn't cleared.",
            button = "Open settings",
            onClick = { OemSettings.openAutostart(ctx) },
            onConfirm = { autostartConfirmed = true },
        )
        StepCard(
            n = 7, title = "Location", tag = "Only for zones", done = perms.precise,
            body = "Precise location tells FocusGate if you're inside a zone. Only used when an app has 2 minutes or less left and your screen is on, or while you set up a zone or run the GPS test.",
            button = "Allow precise location",
            onClick = { askLocation() },
        )
        if (perms.coarseOnly) Text("Precise location needed for zones", color = MaterialTheme.colorScheme.error)
        SectionCard {
            Text("Tip", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Muted(
                "App info → FocusGate → turn off 'Pause app activity if unused' so Android doesn't remove " +
                    "FocusGate's permissions after a while.",
            )
        }

        if (armed) {
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Done") }
        } else {
            Button(
                onClick = onEnable,
                enabled = perms.usage && perms.notif,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("Turn on FocusGate") }
            if (!(perms.usage && perms.notif)) Muted("Finish steps 1 and 2 first.")
        }
    }
}

@Composable
private fun StepCard(
    n: Int,
    title: String,
    tag: String,
    done: Boolean,
    body: String,
    button: String,
    onClick: () -> Unit,
    onConfirm: (() -> Unit)? = null,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (done) {
                Icon(Icons.Filled.CheckCircle, contentDescription = "Done", tint = GoGreen)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                "$n. $title",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(tag, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Muted(body)
        if (!done) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(button) }
                if (onConfirm != null) {
                    TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) { Text("I've done this") }
                }
            }
        }
    }
}
