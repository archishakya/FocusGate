package app.focusgate.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import app.focusgate.arm.ArmController
import app.focusgate.core.CrashLoopGuard
import app.focusgate.core.DisarmReason
import app.focusgate.core.EngineSnapshot
import app.focusgate.core.FgsType
import app.focusgate.core.RestartRecorder
import app.focusgate.core.ServicePort
import app.focusgate.core.ServiceStart
import app.focusgate.core.ServiceTypes
import app.focusgate.core.StartReturn
import app.focusgate.data.Store
import app.focusgate.gate.DataGate
import app.focusgate.system.Permissions
import kotlinx.coroutines.flow.MutableStateFlow

/** Thin adapter: onStartCommand dispatch lives in the pure ServiceStart. */
class FocusService : Service() {
    private val recorder = RestartRecorder()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        ArmController.restoreOnProcessStart(this) // no-op if the Application already ran it
        isRunning = true
        if (Store.serviceAlive() && DataGate.isOpen()) {
            // Previous instance never reached onDestroy (OEM kill / crash).
            if (port.recordUncleanRestart()) ArmController.disarm(this, DisarmReason.CRASH_LOOP)
        }
        Store.setServiceAlive(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        when (ServiceStart.handle(intent?.action, startId, port)) {
            StartReturn.STICKY -> START_STICKY
            StartReturn.NOT_STICKY -> START_NOT_STICKY
        }

    override fun onDestroy() {
        val e = engine
        engine = null
        if (e != null) {
            val t = e.stop()
            try {
                t.join(STOP_JOIN_MS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        isRunning = false
        locationDegraded = false
        currentTypes = emptySet()
        snapshots.value = EngineSnapshot.Idle
        Store.setServiceAlive(false)
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (ex: Exception) {
            // Not foreground.
        }
        super.onDestroy()
    }

    private fun toBits(types: Set<FgsType>): Int {
        var bits = 0
        if (FgsType.LOCATION in types) bits = bits or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (FgsType.SPECIAL_USE in types && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            bits = bits or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        return bits
    }

    private fun promote(types: Set<FgsType>, n: Notification) {
        val bits = toBits(types)
        if (bits == 0) startForeground(Notifications.NOTIF_ID, n)
        else ServiceCompat.startForeground(this, Notifications.NOTIF_ID, n, bits)
        currentTypes = types
    }

    private val port = object : ServicePort {
        override fun gateOpen() = DataGate.isOpen()
        override fun hasEngine() = engine != null

        override fun fullTypes(): Set<FgsType> = ServiceTypes.compute(
            Build.VERSION.SDK_INT, Permissions.preciseLocationGranted(this@FocusService), Store.zones().isNotEmpty(),
        )

        override fun degradedTypes(): Set<FgsType> = ServiceTypes.degraded(Build.VERSION.SDK_INT)

        override fun startForeground(types: Set<FgsType>) {
            val degraded = FgsType.LOCATION !in types && FgsType.LOCATION in fullTypes()
            val text = Notifications.statusText(snapshots.value, degraded)
            promote(types, Notifications.buildStatus(this@FocusService, text))
        }

        override fun startForegroundMinimal() {
            promote(ServiceTypes.degraded(Build.VERSION.SDK_INT), Notifications.buildMinimal(this@FocusService))
        }

        override fun stopForegroundRemove() {
            ServiceCompat.stopForeground(this@FocusService, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }

        override fun stopSelf() = this@FocusService.stopSelf()
        override fun stopSelf(startId: Int) = this@FocusService.stopSelf(startId)

        override fun createEngine() {
            if (engine != null || !DataGate.isOpen()) return
            engine = Engine(this@FocusService).also { it.start() }
        }

        override fun disarm(reason: DisarmReason) = ArmController.disarm(this@FocusService, reason)

        override fun recordUncleanRestart(): Boolean {
            val now = System.currentTimeMillis()
            val before = Store.restarts()
            val after = recorder.record(before, now)
            if (after !== before) Store.setRestarts(after)
            return CrashLoopGuard.isTripped(after, now)
        }

        override fun setDegraded(degraded: Boolean) {
            locationDegraded = degraded
        }

        override fun refreshNotification() {
            val text = Notifications.statusText(snapshots.value, locationDegraded)
            Notifications.notifyStatus(this@FocusService, Notifications.buildStatus(this@FocusService, text))
        }

        override fun engineTick() {
            engine?.requestImmediateTick()
        }

        override fun engineRequestTestBlock() {
            engine?.requestTestBlock()
        }

        override fun engineFlagNotificationRepost() {
            engine?.flagNotificationRepost()
        }

        override fun postPausedAlert() = Notifications.postPaused(this@FocusService)
    }

    companion object {
        private const val STOP_JOIN_MS = 500L

        @Volatile var engine: Engine? = null
            private set
        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var locationDegraded: Boolean = false
            private set
        @Volatile var currentTypes: Set<FgsType> = emptySet()
            private set
        val snapshots = MutableStateFlow(EngineSnapshot.Idle)
    }
}
