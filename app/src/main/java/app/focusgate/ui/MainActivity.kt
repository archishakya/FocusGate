package app.focusgate.ui

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focusgate.arm.ArmController
import app.focusgate.core.ArmState
import app.focusgate.core.DisarmReason
import app.focusgate.service.FocusService
import app.focusgate.system.Permissions
import app.focusgate.ui.theme.FocusGateTheme
import app.focusgate.ui.theme.isDarkFor

class MainActivity : ComponentActivity() {
    private lateinit var vm: AppViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm = ViewModelProvider(this)[AppViewModel::class.java]
        setContent {
            val theme by vm.theme.collectAsStateWithLifecycle()
            FocusGateTheme(theme) {
                // Keep system-bar icon contrast in sync with the chosen theme (not only the system one).
                val dark = isDarkFor(theme)
                DisposableEffect(dark) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                        navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    )
                    onDispose { }
                }
                AppShell(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        UiPresence.mainResumed = true
        UiPresence.lastOwnActivityEventElapsed = SystemClock.elapsedRealtime()
        FocusService.engine?.markOwnUiShown(System.currentTimeMillis())
        if (ArmController.state.value is ArmState.Armed) {
            if (!Permissions.usageAccessAllowed(this)) {
                // AppOps check is a permission check, not a data read.
                ArmController.disarm(this, DisarmReason.PERMISSION_LOST)
            } else {
                promoteIfNeeded(this) // finding 3
            }
        }
        vm.refresh()
    }

    override fun onPause() {
        UiPresence.mainResumed = false
        UiPresence.lastOwnActivityEventElapsed = SystemClock.elapsedRealtime()
        super.onPause()
    }

    override fun onStop() {
        FocusService.engine?.onOwnUiStopped()
        super.onStop()
    }
}
