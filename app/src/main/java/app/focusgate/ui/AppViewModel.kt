package app.focusgate.ui

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import app.focusgate.arm.ArmController
import app.focusgate.arm.ArmResult
import app.focusgate.core.AppState
import app.focusgate.core.ArmState
import app.focusgate.core.DisarmReason
import app.focusgate.core.EngineSnapshot
import app.focusgate.core.MonitoredApps
import app.focusgate.core.ServiceTypes
import app.focusgate.core.Zone
import app.focusgate.core.ZoneRules
import app.focusgate.data.Store
import app.focusgate.service.FocusService
import app.focusgate.system.InstalledApps
import app.focusgate.system.Permissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.ZoneId

data class PermState(
    val usage: Boolean = false,
    val notif: Boolean = false,
    val precise: Boolean = false,
    val coarseOnly: Boolean = false,
    val overlay: Boolean = false,
    val locationOn: Boolean = true,
    val battery: Boolean = false,
)

/** Display copy of Store-backed settings, refreshed on resume and after every action. */
data class Cfg(
    val allowances: List<Int> = emptyList(),
    val usedMs: List<Long> = emptyList(),
    val installed: List<Boolean> = emptyList(),
    val zones: List<Zone> = emptyList(),
    val perms: PermState = PermState(),
    val crashBanner: Boolean = false,
    val zonesCorrupt: Boolean = false,
    val onboardingDone: Boolean = false,
)

data class AppRow(
    val appId: Int,
    val label: String,
    val pkg: String,
    val installed: Boolean,
    val allowanceMin: Int,
    val usedMs: Long,
    val remainingMs: Long,
    /** Null when FocusGate is off or the engine has not published yet. */
    val state: AppState?,
) {
    val progress: Float
        get() = if (allowanceMin <= 0) 1f else (usedMs.toFloat() / (allowanceMin * 60_000f)).coerceIn(0f, 1f)
    val minutesLeft: Int
        get() = if (remainingMs <= 0) 0 else ((remainingMs + 59_999L) / 60_000L).toInt()
}

fun buildRows(cfg: Cfg, snap: EngineSnapshot, armed: Boolean): List<AppRow> = MonitoredApps.all.map { a ->
    val allowance = cfg.allowances.getOrElse(a.appId) { Store.DEFAULT_ALLOWANCE_MIN }
    val s = if (armed && snap.running) snap.apps.firstOrNull { it.appId == a.appId } else null
    val used = s?.usedMs ?: cfg.usedMs.getOrElse(a.appId) { 0L }
    val remaining = s?.remainingMs ?: (allowance * 60_000L - used)
    AppRow(a.appId, a.label, a.pkg, cfg.installed.getOrElse(a.appId) { true }, allowance, used, remaining, s?.state)
}

/** Plain one-line status for an app (finding 10 copy for TIMES_UP_NO_ZONES). */
fun rowStatus(r: AppRow): String {
    if (!r.installed) return "Not installed"
    return when (r.state) {
        AppState.HAS_TIME -> "${r.minutesLeft} min left"
        AppState.TIMES_UP_OUTSIDE -> "Time's up · open here (outside zones)"
        AppState.TIMES_UP_UNKNOWN -> "Time's up · location unclear, staying open"
        AppState.TIMES_UP_NO_ZONES -> "Time's up · no zones set, staying open"
        AppState.BLOCKED -> "Time's up · blocked in zones"
        null -> if (r.remainingMs > 0) "${r.minutesLeft} min left" else "Time's up"
    }
}

/**
 * Finding 3: when armed, re-send an explicit ACTION_START if the service is not running, is degraded,
 * or its foreground types differ from what the current permissions and zones call for.
 */
fun promoteIfNeeded(ctx: Context) {
    if (ArmController.state.value !is ArmState.Armed) return
    val want = ServiceTypes.compute(
        Build.VERSION.SDK_INT, Permissions.preciseLocationGranted(ctx), Store.zones().isNotEmpty(),
    )
    if (!FocusService.isRunning || FocusService.locationDegraded || want != FocusService.currentTypes) {
        ArmController.startService(ctx)
    }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication<Application>().applicationContext

    private val _cfg = MutableStateFlow(Cfg())
    val cfg: StateFlow<Cfg> = _cfg.asStateFlow()
    private val _uiMode = MutableStateFlow(Store.uiMode())
    val uiMode: StateFlow<String> = _uiMode.asStateFlow()
    private val _theme = MutableStateFlow(Store.theme())
    val theme: StateFlow<String> = _theme.asStateFlow()
    private val _feedVisible = MutableStateFlow(Store.feedVisible())
    val feedVisible: StateFlow<Boolean> = _feedVisible.asStateFlow()

    val armState: StateFlow<ArmState> = ArmController.state
    val snapshot: StateFlow<EngineSnapshot> = FocusService.snapshots

    init {
        refresh()
    }

    fun refresh() {
        rolloverIfNeeded()
        val usage = Store.readUsage()
        val today = today()
        val used = if (usage.dayKey == today) usage.usedMs else usage.usedMs.map { 0L }
        _cfg.value = Cfg(
            allowances = MonitoredApps.all.map { Store.allowanceMin(it.appId) },
            usedMs = used,
            installed = MonitoredApps.all.map { InstalledApps.isInstalled(ctx, it.pkg) },
            zones = Store.zones(),
            perms = PermState(
                usage = Permissions.usageAccessAllowed(ctx),
                notif = Permissions.notificationsAllowed(ctx),
                precise = Permissions.preciseLocationGranted(ctx),
                coarseOnly = Permissions.coarseOnly(ctx),
                overlay = Permissions.canDrawOverlays(ctx),
                locationOn = Permissions.locationEnabled(ctx),
                battery = Permissions.ignoringBatteryOptimizations(ctx),
            ),
            crashBanner = Store.crashBanner(),
            zonesCorrupt = Store.zonesCorrupt,
            onboardingDone = Store.onboardingDone(),
        )
    }

    private fun today(): Long = LocalDate.now(ZoneId.systemDefault()).toEpochDay()

    /** Finding 5: the display rollover is written only while disarmed AND no engine exists. */
    private fun rolloverIfNeeded() {
        if (ArmController.state.value is ArmState.Armed || FocusService.engine != null) return
        val usage = Store.readUsage()
        val today = today()
        if (usage.dayKey != today && usage.usedMs.any { it != 0L }) Store.resetCounters(today)
    }

    fun setUiMode(insights: Boolean) {
        val v = if (insights) "INSIGHTS" else "ESSENTIALS"
        Store.setUiMode(v)
        _uiMode.value = v
    }

    fun setTheme(v: String) {
        Store.setTheme(v)
        _theme.value = v
    }

    fun setFeedVisible(v: Boolean) {
        Store.setFeedVisible(v)
        _feedVisible.value = v
    }

    fun setAllowance(appId: Int, min: Int) {
        Store.setAllowanceMin(appId, min)
        refresh()
        FocusService.engine?.requestImmediateTick()
    }

    fun enable(): ArmResult {
        val r = ArmController.arm(ctx)
        refresh()
        return r
    }

    fun disableNow() {
        ArmController.disarm(ctx, DisarmReason.PANIC)
        refresh()
    }

    fun dismissCrashBanner() {
        Store.setCrashBanner(false)
        refresh()
    }

    fun completeOnboarding() {
        Store.setOnboardingDone(true)
        refresh()
    }

    fun requestTestBlock() {
        FocusService.engine?.requestTestBlock()
    }

    fun addZone(z: Zone): Boolean {
        val cur = Store.zones()
        if (!ZoneRules.canAdd(cur) || !ZoneRules.isValid(z)) return false
        Store.setZones(cur + z)
        afterZonesChanged()
        return true
    }

    fun deleteZone(id: String) {
        Store.setZones(Store.zones().filter { it.id != id })
        afterZonesChanged()
    }

    /** Called after granting location too: lets the service gain its location type. */
    fun afterZonesChanged() {
        refresh()
        FocusService.engine?.onZonesChanged()
        promoteIfNeeded(ctx)
    }
}
