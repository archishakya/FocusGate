package app.focusgate.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.focusgate.arm.ArmController
import app.focusgate.core.ConfigSource
import app.focusgate.core.EngineCore
import app.focusgate.core.FgsType
import app.focusgate.core.LocationReader
import app.focusgate.core.PermissionProbe
import app.focusgate.core.PresenceProbe
import app.focusgate.core.TickOutcome
import app.focusgate.core.UsageRead
import app.focusgate.core.UsageReader
import app.focusgate.core.Zone
import app.focusgate.data.Store
import app.focusgate.data.UsageEventSource
import app.focusgate.gate.DataGate
import app.focusgate.location.GpsSink
import app.focusgate.location.GpsSource
import app.focusgate.system.Permissions
import app.focusgate.ui.UiPresence
import java.time.ZoneId

/** Android wrapper around EngineCore: HandlerThread, 1 s poll while interactive, screen receiver. */
class Engine(private val ctx: Context) {
    private val thread = HandlerThread("fg-engine").also { it.start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val usageSource = UsageEventSource(ctx)
    private val gps = GpsSource(ctx)
    private val launcher = BlockLauncher(ctx)

    private var core: EngineCore? = null
    private var stopping = false
    private var registered = false
    private var lastText: String? = null
    private var lastNotifyAt = 0L

    private val screenRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            // Delivered on the engine handler.
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_SCREEN_ON -> onScreenOn()
            }
        }
    }

    private val tickRunnable = Runnable { runTick() }

    fun start() {
        handler.post {
            if (!DataGate.isOpen()) {
                stopping = true
                return@post
            }
            val interactive = try {
                ctx.getSystemService(PowerManager::class.java)?.isInteractive ?: true
            } catch (e: Exception) {
                true
            }
            core = buildCore(interactive)
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            ContextCompat.registerReceiver(ctx, screenRx, filter, null, handler, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            if (interactive) handler.post(tickRunnable)
        }
    }

    private fun buildCore(interactive: Boolean): EngineCore {
        lateinit var c: EngineCore
        val sink = object : GpsSink {
            override fun onReading(
                distancesM: List<Double>,
                accuracyM: Double,
                lat: Double,
                lon: Double,
                zones: List<Zone>,
            ) {
                // lat/lon are not used or kept here; only distances, accuracy and the zone set reach the core.
                c.onLocationReading(distancesM, accuracyM, zones)
            }

            override fun onUnavailable() = c.onLocationUnavailable()
        }
        c = EngineCore(
            gateOpen = { DataGate.isOpen() },
            armedAtTs = { DataGate.armedAtTs() },
            usage = object : UsageReader {
                override fun read(begin: Long, end: Long): UsageRead = usageSource.read(begin, end)
            },
            location = object : LocationReader {
                override fun start(): Boolean = gps.start(thread.looper, Store.zones(), sink)
                override fun stop() = gps.stop()
            },
            blocker = launcher,
            perms = object : PermissionProbe {
                override fun usageAccessAllowed() = Permissions.usageAccessAllowed(ctx)
                override fun preciseLocationGranted() = Permissions.preciseLocationGranted(ctx)
                override fun fgsHasLocationType() = FgsType.LOCATION in FocusService.currentTypes
            },
            presence = object : PresenceProbe {
                override fun mainResumed() = UiPresence.mainResumed
                override fun blockedResumed() = UiPresence.blockedResumed
                override fun lastOwnActivityEventElapsed() = UiPresence.lastOwnActivityEventElapsed
            },
            config = object : ConfigSource {
                override fun allowanceMin(appId: Int) = Store.allowanceMin(appId)
                override fun zones(): List<Zone> = Store.zones()
            },
            clock = object : app.focusgate.core.Clock {
                override fun now() = System.currentTimeMillis()
                override fun elapsed() = SystemClock.elapsedRealtime()
            },
            zoneId = ZoneId.systemDefault(),
            initial = Store.readUsage(),
            screenOn = interactive,
        )
        return c
    }

    private fun runTick() {
        handler.removeCallbacks(tickRunnable)
        val c = core ?: return
        if (stopping) return
        if (!DataGate.isOpen()) {
            stopping = true
            return
        }
        c.locationDegraded = FocusService.locationDegraded
        handle(c.tick())
        if (!stopping) handler.postDelayed(tickRunnable, POLL_MS)
    }

    private fun handle(out: TickOutcome) {
        val c = core ?: return
        when (out) {
            is TickOutcome.RequestDisarm -> {
                stopping = true
                handler.removeCallbacks(tickRunnable)
                val reason = out.reason
                mainHandler.post { ArmController.disarm(ctx, reason) }
            }
            is TickOutcome.Continue -> {
                if (!DataGate.isOpen()) return
                val s = out.snapshot
                FocusService.snapshots.value = s
                val now = System.currentTimeMillis()
                val text = Notifications.statusText(s, FocusService.locationDegraded)
                if (s.repostNotification || (text != lastText && now - lastNotifyAt >= NOTIFY_MIN_MS)) {
                    lastText = text
                    lastNotifyAt = now
                    Notifications.notifyStatus(ctx, Notifications.buildStatus(ctx, text))
                }
                if (!s.blockLaunchHealthy) Notifications.postBlockLaunchFailed(ctx, now)
                c.persistIfDue()?.let { Store.writeUsage(it, SystemClock.elapsedRealtime()) }
            }
        }
    }

    private fun onScreenOff() {
        handler.removeCallbacks(tickRunnable)
        val c = core ?: return
        if (stopping) return
        handle(c.onScreenOff())
        handler.removeCallbacks(tickRunnable)
        c.persistIfDue()?.let { Store.writeUsage(it, SystemClock.elapsedRealtime()) }
    }

    private fun onScreenOn() {
        val c = core ?: return
        if (stopping || !DataGate.isOpen()) return
        c.onScreenOn(System.currentTimeMillis())
        handler.post(tickRunnable)
    }

    fun requestImmediateTick() {
        handler.post { if (core != null && !stopping) runTick() }
    }

    fun requestTestBlock() {
        handler.post { core?.requestTestBlock() }
    }

    fun flagNotificationRepost() {
        handler.post {
            core?.flagNotificationRepost()
            if (core != null && !stopping) runTick()
        }
    }

    fun onOwnUiStopped() {
        handler.post { core?.onOwnUiStopped() }
    }

    fun markOwnUiShown(ts: Long) {
        handler.post { core?.markOwnUiShown(ts) }
    }

    fun onZonesChanged() {
        handler.post {
            core?.onZonesChanged()
            if (core != null && !stopping) runTick()
        }
    }

    /** Teardown; returns a latch-free join target. Called from FocusService.onDestroy (main thread). */
    fun stop(): Thread {
        handler.post {
            stopping = true
            handler.removeCallbacks(tickRunnable)
            gps.stop()
            if (registered) {
                try {
                    ctx.unregisterReceiver(screenRx)
                } catch (e: Exception) {
                    // Already unregistered.
                }
                registered = false
            }
            core?.let { Store.writeUsageAndClearCursor(it.stop()) } // no final query
            core = null
            thread.quitSafely()
        }
        return thread
    }

    companion object {
        const val POLL_MS = 1_000L
        const val NOTIFY_MIN_MS = 10_000L
    }
}
