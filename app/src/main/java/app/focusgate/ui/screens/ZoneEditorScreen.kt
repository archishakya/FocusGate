package app.focusgate.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focusgate.core.Zone
import app.focusgate.core.ZoneRules
import app.focusgate.core.ZoneSettler
import app.focusgate.location.LocationSession
import app.focusgate.system.Permissions
import app.focusgate.ui.PermState
import app.focusgate.ui.components.Muted
import app.focusgate.ui.components.SectionCard
import app.focusgate.ui.components.SubScreen
import app.focusgate.ui.components.rememberLocationRequester
import kotlinx.coroutines.delay
import java.util.UUID
import kotlin.math.roundToInt

const val ZONE_HONESTY_COPY =
    "Phone location is usually 3–10 m outdoors and worse indoors. 10 m works well for a room or desk. " +
        "If accuracy often shows more than ~15 m, use 15–20 m."

const val RADIUS_WARNING_COPY = "Below 10 m, GPS indoors may be unreliable"

@Composable
fun ZoneEditorScreen(
    armed: Boolean,
    perms: PermState,
    onSave: (Zone) -> Boolean,
    onPermsChanged: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val session = remember { LocationSession(ctx) }
    val ls by session.state.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var radius by rememberSaveable { mutableIntStateOf(ZoneRules.RADIUS_DEFAULT) }
    var radiusText by rememberSaveable { mutableStateOf(ZoneRules.RADIUS_DEFAULT.toString()) }
    var center by remember { mutableStateOf<ZoneSettler.Center?>(null) }
    var startFailed by remember { mutableStateOf(false) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) session.stop() }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            session.stop()
        }
    }
    // A settled spot is kept in memory only; sampling then stops.
    LaunchedEffect(ls.settled) {
        ls.settled?.let {
            center = it
            session.stop()
        }
    }
    // Ticks the 90 s timeout even when no fix arrives.
    LaunchedEffect(ls.running) {
        while (session.state.value.running) {
            delay(1000)
            session.refresh()
        }
    }

    fun startSampling() {
        center = null
        startFailed = !session.start(LocationSession.Mode.ZoneSetup)
    }

    val askLocation = rememberLocationRequester {
        onPermsChanged()
        if (armed && Permissions.preciseLocationGranted(ctx)) startSampling()
    }

    fun setRadius(r: Int) {
        radius = r
        radiusText = r.toString()
    }

    SubScreen("New zone", onBack, Modifier.imePadding()) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(ZoneRules.NAME_MAX) },
            label = { Text("Zone name (e.g. Home)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionCard {
            Text("Spot", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val c = center
            if (c != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = "Locked")
                    Spacer(Modifier.width(8.dp))
                    Text("Spot locked (±${c.meanAccuracyM.roundToInt()} m)")
                }
            }
            if (ls.running) {
                Text(
                    ls.accuracyM?.let { "±${it.roundToInt()} m" } ?: "Waiting for GPS…",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text("steady ${ls.steady}/${ZoneSettler.WINDOW}")
                Muted("Stand still, ideally near a window.")
                if (ls.timedOut) {
                    Text("This is taking a while. GPS isn't settling here.")
                    if (ls.canSaveAnyway) {
                        OutlinedButton(
                            onClick = {
                                session.currentCenter()?.let {
                                    setRadius(session.suggestedRadius(radius))
                                    center = it
                                    session.stop()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("Save anyway") }
                    } else {
                        Muted("GPS is too fuzzy here (over 50 m). Try outside or near a window.")
                    }
                }
                TextButton(onClick = { session.stop() }) { Text("Stop") }
            } else {
                Button(
                    onClick = { if (perms.precise) startSampling() else askLocation() },
                    enabled = armed,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.MyLocation, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (c == null) "Use my current location" else "Re-measure")
                }
                if (!armed) Muted("Turn FocusGate on to use location")
                if (armed && perms.coarseOnly) Text("Precise location needed for zones")
                if (startFailed || ls.unavailable) {
                    Muted("Couldn't get location. Check that Location is on.")
                }
            }
        }

        SectionCard {
            Text("Size", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Slider(
                value = radius.toFloat(),
                onValueChange = { setRadius(it.roundToInt().coerceIn(ZoneRules.RADIUS_MIN, ZoneRules.RADIUS_MAX)) },
                valueRange = ZoneRules.RADIUS_MIN.toFloat()..ZoneRules.RADIUS_MAX.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = radiusText,
                onValueChange = { t ->
                    val digits = t.filter { it.isDigit() }.take(3)
                    radiusText = digits
                    digits.toIntOrNull()?.takeIf { ZoneRules.isValidRadius(it) }?.let { radius = it }
                },
                label = { Text("Radius (m)") },
                singleLine = true,
                isError = radiusText.toIntOrNull()?.let { !ZoneRules.isValidRadius(it) } ?: true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            if (ZoneRules.radiusWarning(radius)) {
                Text(RADIUS_WARNING_COPY, color = MaterialTheme.colorScheme.error)
            }
            Muted(ZONE_HONESTY_COPY)
        }

        val c = center
        val canSave = ZoneRules.validName(name) != null && c != null && ZoneRules.isValidRadius(radius)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Cancel") }
            Button(
                enabled = canSave,
                onClick = {
                    val n = ZoneRules.validName(name)
                    if (n != null && c != null) {
                        onSave(Zone(UUID.randomUUID().toString(), n, c.lat, c.lon, radius))
                    }
                },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text("Save") }
        }
        if (!canSave) Muted("Add a name and lock a spot to save.")
    }
}
