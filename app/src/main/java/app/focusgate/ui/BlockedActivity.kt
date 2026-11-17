package app.focusgate.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.focusgate.arm.ArmController
import app.focusgate.core.ArmState
import app.focusgate.core.MonitoredApps
import app.focusgate.core.TestBlockState
import app.focusgate.core.ZoneState
import app.focusgate.gate.DataGate
import app.focusgate.service.FocusService
import app.focusgate.system.InstalledApps
import app.focusgate.core.releases
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** FocusGate's own opaque full-screen block screen. Never drawn over another app's window. */
class BlockedActivity : ComponentActivity() {
    private var appId by mutableIntStateOf(0)
    private var testMode by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readIntent(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                ArmController.state.collect { if (it is ArmState.Disarmed) finish() } // finding 9
            }
        }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val snap by FocusService.snapshots.collectAsStateWithLifecycle()
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (testMode) {
                        val waited = (snap.test as? TestBlockState.Passed)?.waitedS
                        TestContent(waited) { goHome() }
                    } else {
                        val label = MonitoredApps.all.getOrNull(appId)?.label ?: "This app"
                        val released = snap.releases(appId)
                        // Engine allows the app again (confirmed outside, or time back): brief note, then close
                        // so the user lands back in the app. Re-entering a zone blocks via the normal launch path.
                        LaunchedEffect(released, appId) {
                            if (released) {
                                delay(RELEASE_ACK_MS)
                                if (!testMode && FocusService.snapshots.value.releases(appId)) finish()
                            }
                        }
                        BlockedContent(
                            label = label,
                            outside = released || snap.zone == ZoneState.OUTSIDE,
                            onHome = { goHome() },
                            onBackToApp = { backToApp() },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(i: Intent?) {
        testMode = i?.getBooleanExtra(EXTRA_TEST, false) ?: false
        appId = (i?.getIntExtra(EXTRA_APP_ID, 0) ?: 0).coerceIn(0, MonitoredApps.all.size - 1)
    }

    override fun onResume() {
        super.onResume()
        if (!testMode && !DataGate.isOpen()) {
            finish()
            return
        }
        BlockedActivity.isResumed = true
        UiPresence.blockedResumed = true
        UiPresence.lastOwnActivityEventElapsed = SystemClock.elapsedRealtime()
        FocusService.engine?.markOwnUiShown(System.currentTimeMillis())
    }

    override fun onPause() {
        BlockedActivity.isResumed = false
        UiPresence.blockedResumed = false
        UiPresence.lastOwnActivityEventElapsed = SystemClock.elapsedRealtime()
        super.onPause()
    }

    private fun goHome() {
        FocusService.engine?.markOwnUiShown(System.currentTimeMillis())
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // No launcher; just close.
        }
        finish()
    }

    private fun backToApp() {
        val pkg = MonitoredApps.all.getOrNull(appId)?.pkg ?: return goHome()
        val launch = InstalledApps.launchIntent(this, pkg)
        if (launch == null) {
            goHome()
            return
        }
        try {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // Fall through to finish.
        }
        finish()
    }

    companion object {
        const val EXTRA_APP_ID = "app.focusgate.extra.APP_ID"
        const val EXTRA_TEST = "app.focusgate.extra.TEST"
        const val RELEASE_ACK_MS = 1_000L

        @Volatile var isResumed: Boolean = false
            private set
    }
}

@Composable
private fun BlockedContent(label: String, outside: Boolean, onHome: () -> Unit, onBackToApp: () -> Unit) {
    ScreenColumn {
        if (outside) {
            Title("You're outside your zone, enjoy $label")
            Spacer(Modifier.height(32.dp))
            PrimaryButton("Back to $label", onBackToApp)
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Go home", onHome)
        } else {
            Title("$label's done for today here")
            Spacer(Modifier.height(12.dp))
            Body("0 min left · You're inside a zone. Step outside or come back tomorrow.")
            Spacer(Modifier.height(32.dp))
            PrimaryButton("Go home", onHome)
        }
    }
}

@Composable
private fun TestContent(waitedS: Int?, onDone: () -> Unit) {
    ScreenColumn {
        Title("Test worked")
        Spacer(Modifier.height(12.dp))
        Body(
            if (waitedS != null) "FocusGate opened this screen from the background. Waited $waitedS s."
            else "FocusGate opened this screen from the background.",
        )
        Spacer(Modifier.height(32.dp))
        PrimaryButton("Done", onDone)
    }
}

@Composable
private fun ScreenColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Shield,
            contentDescription = "FocusGate",
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(24.dp))
        content()
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(text) }
}
