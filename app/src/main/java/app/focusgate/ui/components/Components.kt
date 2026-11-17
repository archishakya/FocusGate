package app.focusgate.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import app.focusgate.system.Permissions
import app.focusgate.ui.AppRow
import app.focusgate.ui.rowStatus
import app.focusgate.ui.theme.GoGreen
import app.focusgate.ui.theme.StopRed

// ---- context helpers ----

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Starts an activity; false when no handler exists or it is refused. */
fun Context.safeStart(intent: Intent): Boolean = try {
    if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
    true
} catch (e: Exception) {
    false
}

// ---- runtime permission requesters ----

/** POST_NOTIFICATIONS runtime request; falls back to the app notification settings when permanently denied. */
@Composable
fun rememberNotificationRequester(onDone: () -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            val a = ctx.findActivity()
            val canAskAgain = a != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(a, Manifest.permission.POST_NOTIFICATIONS)
            if (!canAskAgain) ctx.safeStart(Permissions.appNotificationSettingsIntent(ctx))
        }
        onDone()
    }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onDone()
        }
    }
}

/** FINE + COARSE requested together; app details when permanently denied. */
@Composable
fun rememberLocationRequester(onDone: () -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (!fine && !coarse) {
            val a = ctx.findActivity()
            val canAskAgain = a != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(a, Manifest.permission.ACCESS_FINE_LOCATION)
            if (!canAskAgain) ctx.safeStart(Permissions.appDetailsIntent(ctx))
        }
        onDone()
    }
    return {
        launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
}

// ---- building blocks ----

fun appIcon(appId: Int): ImageVector = when (appId) {
    0 -> Icons.Filled.CameraAlt
    1 -> Icons.Filled.Public
    else -> Icons.Filled.PlayCircle
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Ring that shows the remaining fraction (1 = full allowance left). */
@Composable
fun ProgressRing(
    remainingFraction: Float,
    modifier: Modifier = Modifier,
    ringSize: Dp = 64.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit,
) {
    val track = MaterialTheme.colorScheme.surface
    Box(modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 8.dp.toPx()
            val topLeft = Offset(stroke / 2, stroke / 2)
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 0f, 360f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
            val sweep = 360f * remainingFraction.coerceIn(0f, 1f)
            if (sweep > 0f) {
                drawArc(color, -90f, sweep, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        content()
    }
}

@Composable
fun AppCard(row: AppRow, modifier: Modifier = Modifier) {
    val timesUp = row.installed && row.remainingMs <= 0
    SectionCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(
                remainingFraction = if (row.installed) 1f - row.progress else 0f,
                color = if (timesUp) StopRed else MaterialTheme.colorScheme.primary,
            ) {
                Icon(appIcon(row.appId), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(row.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    rowStatus(row),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (timesUp) {
                Icon(Icons.Filled.Warning, contentDescription = "Time's up", tint = StopRed)
            }
        }
    }
}

@Composable
fun StatusCard(on: Boolean, line: String, modifier: Modifier = Modifier) {
    SectionCard(
        modifier,
        container = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp).clip(CircleShape)
                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Shield,
                    contentDescription = if (on) "Protection on" else "Protection off",
                    modifier = Modifier.size(36.dp),
                    tint = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    if (on) "FocusGate is on" else "FocusGate is off",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Big always-visible pill: red "Disable now" when armed, green "Enable" otherwise. 56 dp tall. */
@Composable
fun PowerPill(armed: Boolean, onEnable: () -> Unit, onDisable: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = if (armed) onDisable else onEnable,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = if (armed) StopRed else GoGreen, contentColor = Color.White),
    ) {
        Icon(Icons.Filled.PowerSettingsNew, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(if (armed) "Disable now" else "Enable", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Banner(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    SectionCard(modifier, container = MaterialTheme.colorScheme.errorContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = "Heads up", tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                text,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(actionLabel) }
            }
        }
    }
}

/** ok = true granted, false missing, null = cannot be checked. */
@Composable
fun PermissionRow(
    title: String,
    detail: String,
    ok: Boolean?,
    actionLabel: String,
    modifier: Modifier = Modifier,
    onFix: () -> Unit,
) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        when (ok) {
            true -> Icon(Icons.Filled.CheckCircle, contentDescription = "OK", tint = GoGreen)
            false -> Icon(Icons.Filled.Warning, contentDescription = "Needs attention", tint = StopRed)
            null -> Icon(Icons.Filled.Info, contentDescription = "Can't check", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (ok != true) {
            FilledTonalButton(onClick = onFix, modifier = Modifier.heightIn(min = 48.dp)) { Text(actionLabel) }
        }
    }
}

/** Simple pill-shaped segmented control. */
@Composable
fun SegmentedToggle(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(CircleShape)
                    .background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .selectable(selected = sel, role = Role.RadioButton, onClick = { onSelect(i) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Sub-screen frame: back arrow + title + scrolling body. */
@Composable
fun SubScreen(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
            Spacer(Modifier.height(16.dp))
        }
    }
}
