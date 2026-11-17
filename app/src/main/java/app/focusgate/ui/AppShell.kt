package app.focusgate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focusgate.arm.ArmResult
import app.focusgate.core.ArmState
import app.focusgate.ui.components.PowerPill
import app.focusgate.ui.screens.AllowancesScreen
import app.focusgate.ui.screens.GpsTestScreen
import app.focusgate.ui.screens.HomeScreen
import app.focusgate.ui.screens.InsightsScreen
import app.focusgate.ui.screens.OnboardingScreen
import app.focusgate.ui.screens.PrivacyScreen
import app.focusgate.ui.screens.SettingsScreen
import app.focusgate.ui.screens.ZoneEditorScreen
import app.focusgate.ui.screens.ZonesScreen
import kotlinx.coroutines.launch

/** Routes are kept as one comma-joined string so the back stack survives rotation and process death. */
@Composable
fun AppShell(vm: AppViewModel) {
    val cfg by vm.cfg.collectAsStateWithLifecycle()
    val arm by vm.armState.collectAsStateWithLifecycle()
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val mode by vm.uiMode.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
    val feedVisible by vm.feedVisible.collectAsStateWithLifecycle()

    var stack by rememberSaveable { mutableStateOf("home") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val armed = arm is ArmState.Armed
    val reason = (arm as? ArmState.Disarmed)?.reason
    val insights = mode == "INSIGHTS"
    val rows = buildRows(cfg, snap, armed)

    val routes = stack.split(",")
    val current = routes.last()

    fun go(route: String) {
        stack = "$stack,$route"
    }

    fun back() {
        stack = if (routes.size > 1) routes.dropLast(1).joinToString(",") else "home"
    }

    fun say(msg: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }

    fun enable() {
        when (val r = vm.enable()) {
            is ArmResult.Ok -> {
                vm.completeOnboarding()
                if (current == "onboarding") back()
                if (r.overlayMissing) say("Blocking may not open without 'Display over other apps'")
            }
            is ArmResult.Missing -> {
                if (r.missing.isEmpty()) say("Couldn't turn on. Try again.")
                else if (current != "onboarding") go("onboarding")
            }
        }
    }

    BackHandler(enabled = routes.size > 1) { back() }

    val showPill = current != "onboarding" && current != "zone_new"

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (showPill) {
                Box(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
                        .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    PowerPill(
                        armed = armed,
                        onEnable = { enable() },
                        onDisable = {
                            vm.disableNow()
                            say("FocusGate is off")
                        },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (current) {
                "allow" -> AllowancesScreen(rows, onSet = vm::setAllowance, onBack = { back() })
                "settings" -> SettingsScreen(
                    theme = theme,
                    onTheme = vm::setTheme,
                    insights = insights,
                    onMode = vm::setUiMode,
                    onPermissions = { go("onboarding") },
                    onPrivacy = { go("privacy") },
                    onBack = { back() },
                )
                "zones" -> ZonesScreen(
                    zones = cfg.zones,
                    armed = armed,
                    onAdd = { go("zone_new") },
                    onDelete = vm::deleteZone,
                    onBack = { back() },
                )
                "zone_new" -> ZoneEditorScreen(
                    armed = armed,
                    perms = cfg.perms,
                    onSave = { z ->
                        val ok = vm.addZone(z)
                        if (ok) back() else say("Couldn't save the zone")
                        ok
                    },
                    onPermsChanged = vm::afterZonesChanged,
                    onBack = { back() },
                )
                "onboarding" -> OnboardingScreen(
                    perms = cfg.perms,
                    armed = armed,
                    onRefresh = vm::refresh,
                    onEnable = { enable() },
                    onBack = { back() },
                )
                "privacy" -> PrivacyScreen(onBack = { back() })
                "insights" -> InsightsScreen(
                    cfg = cfg,
                    rows = rows,
                    snap = snap,
                    arm = arm,
                    feedVisible = feedVisible,
                    onFeedVisible = vm::setFeedVisible,
                    onTestBlocking = vm::requestTestBlock,
                    onPermsChanged = vm::afterZonesChanged,
                    onGpsTest = { go("gpstest") },
                    onBack = { back() },
                )
                "gpstest" -> GpsTestScreen(
                    armed = armed,
                    perms = cfg.perms,
                    zones = cfg.zones,
                    onPermsChanged = vm::afterZonesChanged,
                    onBack = { back() },
                )
                else -> HomeScreen(
                    armed = armed,
                    reason = reason,
                    rows = rows,
                    cfg = cfg,
                    snap = snap,
                    insights = insights,
                    onMode = vm::setUiMode,
                    onSettings = { go("settings") },
                    onAllowances = { go("allow") },
                    onZones = { go("zones") },
                    onInsights = { go("insights") },
                    onDismissCrash = vm::dismissCrashBanner,
                    onPermsChanged = vm::afterZonesChanged,
                )
            }
        }
    }
}
