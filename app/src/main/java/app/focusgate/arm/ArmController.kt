package app.focusgate.arm

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.focusgate.core.ArmRestore
import app.focusgate.core.ArmState
import app.focusgate.core.BootId
import app.focusgate.core.DisarmReason
import app.focusgate.core.RestoreResult
import app.focusgate.core.ServiceStart
import app.focusgate.data.Store
import app.focusgate.gate.DataGate
import app.focusgate.service.FocusService
import app.focusgate.service.Notifications
import app.focusgate.system.Permissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Precondition { USAGE_ACCESS, NOTIFICATIONS }

sealed class ArmResult {
    /** [overlayMissing]: armed, but blocking may not open without "Display over other apps". */
    data class Ok(val overlayMissing: Boolean) : ArmResult()
    data class Missing(val missing: List<Precondition>) : ArmResult()
}

/** Owns the arm state and the only writers of DataGate. All functions run on the main thread. */
object ArmController {
    private val _state = MutableStateFlow<ArmState>(ArmState.Disarmed(DisarmReason.NEVER_ARMED))
    val state: StateFlow<ArmState> = _state.asStateFlow()

    private var restored = false

    /** Process-start path into the gate (DESIGN §10). Idempotent within a process. */
    fun restoreOnProcessStart(ctx: Context) {
        if (restored) return
        restored = true
        Store.init(ctx)
        val stored = Store.armState()
        when (val r = ArmRestore.restore(stored, Store.bootId(), readBootId(ctx))) {
            is RestoreResult.OpenGate -> {
                DataGate.open(r.armedAtTs)
                _state.value = stored
            }
            RestoreResult.PersistReboot -> {
                Store.commitDisarmed(DisarmReason.REBOOT)
                _state.value = ArmState.Disarmed(DisarmReason.REBOOT)
            }
            RestoreResult.StayDisarmed -> _state.value = stored
        }
    }

    fun arm(ctx: Context): ArmResult {
        val missing = buildList {
            if (!Permissions.usageAccessAllowed(ctx)) add(Precondition.USAGE_ACCESS)
            if (!Permissions.notificationsAllowed(ctx)) add(Precondition.NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) return ArmResult.Missing(missing)
        val now = System.currentTimeMillis()
        val boot = readBootId(ctx)
        if (!Store.commitArmed(boot, now)) return ArmResult.Missing(emptyList())
        Store.setCrashBanner(false)
        DataGate.open(now)
        _state.value = ArmState.Armed(boot.bootCount, boot.bootEpochMs, now)
        startService(ctx)
        return ArmResult.Ok(overlayMissing = !Permissions.canDrawOverlays(ctx))
    }

    /** Explicit ACTION_START from a visible activity. */
    fun startService(ctx: Context) {
        try {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, FocusService::class.java).setAction(ServiceStart.ACTION_START),
            )
        } catch (e: Exception) {
            // Not allowed right now; MainActivity.onResume retries.
        }
    }

    fun disarm(ctx: Context, reason: DisarmReason) {
        DataGate.close() // FIRST
        Store.commitDisarmed(reason)
        _state.value = ArmState.Disarmed(reason)
        if (reason == DisarmReason.CRASH_LOOP) {
            Store.setCrashBanner(true)
            Notifications.postCrashLoop(ctx)
        }
        try {
            ctx.stopService(Intent(ctx, FocusService::class.java))
        } catch (e: Exception) {
            // Service not running.
        }
    }

    /** The only BOOT_COUNT read. */
    fun readBootId(ctx: Context): BootId {
        val count: Int? = try {
            Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT)
        } catch (e: Settings.SettingNotFoundException) {
            null
        } catch (e: SecurityException) {
            null
        }
        val elapsed = SystemClock.elapsedRealtime()
        return BootId(count, System.currentTimeMillis() - elapsed, elapsed)
    }
}
