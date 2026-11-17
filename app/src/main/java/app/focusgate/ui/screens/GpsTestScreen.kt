package app.focusgate.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focusgate.core.Zone
import app.focusgate.core.ZoneEvaluator
import app.focusgate.core.ZoneState
import app.focusgate.location.GpsSource
import app.focusgate.location.LocationSession
import app.focusgate.ui.PermState
import app.focusgate.ui.components.Muted
import app.focusgate.ui.components.SectionCard
import app.focusgate.ui.components.SubScreen
import app.focusgate.ui.components.rememberLocationRequester
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Armed-only GPS check. Sampling follows the screen lifecycle (ON_START / ON_STOP). No coordinates are shown or kept. */
@Composable
fun GpsTestScreen(
    armed: Boolean,
    perms: PermState,
    zones: List<Zone>,
    onPermsChanged: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val session = remember { LocationSession(ctx) }
    val ls by session.state.collectAsStateWithLifecycle()
    var startFailed by remember { mutableStateOf(false) }
    var nowElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val askLocation = rememberLocationRequester(onPermsChanged)

    // Comparison only; the engine always uses AUTO.
    var source by remember { mutableStateOf(GpsSource.Mode.AUTO) }
    val canRun = armed && perms.precise && zones.isNotEmpty()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, canRun, zones, source) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START ->
                    if (canRun) startFailed = !session.start(LocationSession.Mode.GpsTest(zones, source))
                Lifecycle.Event.ON_STOP -> session.stop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            session.stop()
        }
    }
    LaunchedEffect(ls.running) {
        while (session.state.value.running) {
            delay(1000)
            nowElapsed = SystemClock.elapsedRealtime()
            session.refresh()
        }
    }

    SubScreen("GPS accuracy test", onBack) {
        when {
            !armed -> SectionCard { Text("Turn FocusGate on to run this test.") }
            zones.isEmpty() -> SectionCard { Text("Add a zone first, then come back.") }
            !perms.precise -> SectionCard {
                Text(if (perms.coarseOnly) "Precise location needed for zones" else "Allow precise location to run this test.")
                Button(onClick = { askLocation() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Allow precise location") }
            }
            else -> {
                SectionCard {
                    Text(
                        ls.accuracyM?.let { "±${it.roundToInt()} m" } ?: "Waiting for GPS…",
                        Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            when (ls.zone) {
                                ZoneState.INSIDE -> Icons.Filled.Home
                                ZoneState.OUTSIDE -> Icons.Filled.Place
                                ZoneState.UNKNOWN -> Icons.Filled.Info
                            },
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (ls.zone) {
                                ZoneState.INSIDE -> "Inside"
                                ZoneState.OUTSIDE -> "Outside"
                                ZoneState.UNKNOWN -> "Unclear"
                            },
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    // Distance is shown live only; it is never stored or logged, and no coordinates are shown.
                    val zn = ls.zoneName
                    val rm = ls.radiusM
                    Muted("Inside if you're in any zone, outside only when you're out of all of them (same as blocking).")
                    if (zn != null && rm != null) {
                        Text(
                            "Nearest zone (diagnostic): $zn ($rm m radius)",
                            Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }
                    ls.distanceM?.let {
                        Text(
                            "Distance to centre: ${it.roundToInt()} m",
                            Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Text(
                        when {
                            ls.zone != ZoneState.UNKNOWN -> "Confirmed"
                            ls.consecutive > 0 ->
                                "Checking (${ls.consecutive.coerceAtMost(ZoneEvaluator.N_OUTSIDE)}/${ZoneEvaluator.N_OUTSIDE})"
                            else -> "Waiting for a clear reading"
                        },
                        Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                    ls.provider?.let { Text("Source: $it", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                    ls.movedM?.let {
                        Text(
                            "Moved since you opened this screen: ${it.roundToInt()} m",
                            Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (ls.fixCounts.isNotEmpty()) {
                        Muted("Fixes this session: " + ls.fixCounts.entries.joinToString(", ") { "${it.key} ${it.value}" })
                    }
                    if (ls.ignored) Muted("Reading ignored: too inaccurate")
                    if (ls.holding && ls.zone != ZoneState.UNKNOWN) Muted("No fresh reading, keeping the last result")
                    val last = ls.lastFixElapsed
                    Text(
                        if (last == null) "No fix yet" else "Last fix: ${((nowElapsed - last) / 1000).coerceAtLeast(0)} s ago",
                        Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                    if (startFailed || ls.unavailable) Muted("Can't get GPS right now. Check that Location is on.")
                }
                SectionCard {
                    Text("Location source (this test only)")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            GpsSource.Mode.AUTO to "Auto",
                            GpsSource.Mode.GOOGLE_FUSED to "Google fused",
                            GpsSource.Mode.GPS_ONLY to "GPS only",
                        ).forEach { (m, label) ->
                            FilterChip(
                                selected = source == m,
                                onClick = { source = m },
                                label = { Text(label) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                    Muted("Blocking always uses Auto: Google fused location, or GPS if that isn't available.")
                }
                Muted("The big number is accuracy, not distance. Let it settle ~30 s. If it stays above 15 m, use a 15–20 m zone.")
                Muted(ZONE_HONESTY_COPY)
            }
        }
    }
}
