package app.focusgate.location

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import app.focusgate.arm.ArmController
import app.focusgate.core.ArmState
import app.focusgate.core.Geo
import app.focusgate.core.Zone
import app.focusgate.core.ZoneEvaluator
import app.focusgate.core.ZoneSettler
import app.focusgate.core.ZoneState
import app.focusgate.gate.DataGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI-scoped location session on the main looper (zone setup / GPS test). Nothing is persisted. */
class LocationSession(ctx: Context) {
    sealed class Mode {
        object ZoneSetup : Mode()
        data class GpsTest(val zones: List<Zone>, val source: GpsSource.Mode = GpsSource.Mode.AUTO) : Mode()
    }

    data class UiState(
        val running: Boolean = false,
        val accuracyM: Double? = null,
        /** ZoneSetup: steady k/5. */
        val steady: Int = 0,
        /** ZoneSetup: settled centre, kept only in memory. */
        val settled: ZoneSettler.Center? = null,
        val timedOut: Boolean = false,
        val canSaveAnyway: Boolean = false,
        /** GpsTest: engine-equivalent inside/outside/unclear across all zones and the consecutive same-class count. */
        val zone: ZoneState = ZoneState.UNKNOWN,
        val consecutive: Int = 0,
        /** GpsTest, memory only (never stored or logged): nearest zone name, its radius, distance to its centre. */
        val zoneName: String? = null,
        val radiusM: Int? = null,
        val distanceM: Double? = null,
        /** Provider of the latest fix ("Fused" / "GPS"). */
        val provider: String? = null,
        /** GpsTest: latest reading was too inaccurate to count. */
        val ignored: Boolean = false,
        /** GpsTest: no usable reading for a while; the last state is being held. */
        val holding: Boolean = false,
        /** elapsedRealtime (ms) of the latest fix's own timestamp, so the age shown is the real fix age. */
        val lastFixElapsed: Long? = null,
        val unavailable: Boolean = false,
        /** GpsTest diagnostics, memory only: fixes per source this session, metres from the session's first fix. */
        val fixCounts: Map<String, Int> = emptyMap(),
        val movedM: Double? = null,
    )

    private val gps = GpsSource(ctx.applicationContext)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var settler: ZoneSettler? = null
    private var evaluator: ZoneEvaluator? = null
    private var testZones: List<Zone> = emptyList()
    private var scope: CoroutineScope? = null
    private var watch: Job? = null
    /** GpsTest: first fix of this session, transient (never stored or logged). */
    private var firstLat = Double.NaN
    private var firstLon = Double.NaN

    /** Returns false (and stays stopped) when the gate is closed or sampling cannot start. */
    fun start(mode: Mode): Boolean {
        if (!DataGate.isOpen()) return false
        stop()
        val now = System.currentTimeMillis()
        val zones: List<Zone>
        when (mode) {
            Mode.ZoneSetup -> {
                settler = ZoneSettler(now)
                zones = emptyList()
            }
            is Mode.GpsTest -> {
                evaluator = ZoneEvaluator().also { it.start(now) }
                zones = mode.zones
                testZones = mode.zones
            }
        }
        val sink = object : GpsSink {
            override fun onReading(
                distancesM: List<Double>,
                accuracyM: Double,
                lat: Double,
                lon: Double,
                zones: List<Zone>,
            ) {
                if (!DataGate.isOpen()) {
                    stop()
                    return
                }
                // Readings computed for another zone set (zones edited mid-session) are dropped.
                if (zones != testZones) return
                onFix(distancesM, accuracyM, lat, lon)
            }

            override fun onUnavailable() {
                evaluator?.markUnavailable()
                _state.value = _state.value.copy(unavailable = true, zone = ZoneState.UNKNOWN, consecutive = 0)
            }
        }
        val source = (mode as? Mode.GpsTest)?.source ?: GpsSource.Mode.AUTO
        if (!gps.start(Looper.getMainLooper(), zones, sink, source)) {
            settler = null
            evaluator = null
            _state.value = UiState(unavailable = true)
            return false
        }
        _state.value = UiState(running = true, provider = gps.providersLabel)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        watch = s.launch {
            ArmController.state.collect { if (it is ArmState.Disarmed) stop() }
        }
        return true
    }

    private fun onFix(distancesM: List<Double>, accuracyM: Double, lat: Double, lon: Double) {
        val now = System.currentTimeMillis()
        val cur = _state.value
        settler?.let { st ->
            st.onFix(lat, lon, accuracyM, now)
            _state.value = cur.copy(
                accuracyM = accuracyM.takeIf { it.isFinite() },
                steady = st.steadyCount,
                settled = st.settled,
                timedOut = st.timedOut(now),
                canSaveAnyway = st.canSaveAnyway(),
                provider = gps.lastFixProvider,
                lastFixElapsed = gps.lastFixTimeElapsedMs ?: SystemClock.elapsedRealtime(),
                unavailable = false,
            )
        }
        evaluator?.let { ev ->
            // Engine-equivalent verdict over all zones; the nearest zone is diagnostic only.
            val near = nearestZone(distancesM)
            val radii = near?.let { testZones.map { it.radiusM } }
            val dists = near?.let { distancesM }
            val z = if (radii == null || dists == null) ZoneState.UNKNOWN
            else ev.onReading(radii, dists, accuracyM, now)
            val ignored = radii != null && dists != null &&
                ZoneEvaluator.classify(radii, dists, accuracyM) == ZoneEvaluator.ReadingClass.UNUSABLE
            _state.value = cur.copy(
                accuracyM = accuracyM.takeIf { it.isFinite() },
                zone = z,
                consecutive = ev.streak,
                zoneName = near?.first?.name,
                radiusM = near?.first?.radiusM,
                distanceM = near?.second,
                provider = gps.lastFixProvider,
                ignored = ignored,
                holding = ev.isStale(now),
                lastFixElapsed = gps.lastFixTimeElapsedMs ?: SystemClock.elapsedRealtime(),
                unavailable = false,
                fixCounts = gps.fixCounts,
                movedM = moved(lat, lon),
            )
        }
    }

    private fun moved(lat: Double, lon: Double): Double {
        if (firstLat.isNaN()) {
            firstLat = lat
            firstLon = lon
        }
        return Geo.distanceM(firstLat, firstLon, lat, lon)
    }

    /** Nearest zone and its distance; the distance is not kept. */
    private fun nearestZone(distancesM: List<Double>): Pair<Zone, Double>? {
        if (distancesM.isEmpty() || testZones.size != distancesM.size) return null
        var best = 0
        for (i in distancesM.indices) if (distancesM[i] < distancesM[best]) best = i
        return testZones[best] to distancesM[best]
    }

    /** "Save anyway" centre (≤ 50 m best accuracy), memory only. */
    fun currentCenter(): ZoneSettler.Center? = settler?.currentCenter()

    fun suggestedRadius(r: Int): Int = settler?.suggestedRadius(r) ?: r

    /** Ticks the timeout / staleness without a fix. */
    fun refresh() {
        val now = System.currentTimeMillis()
        settler?.let { _state.value = _state.value.copy(timedOut = it.timedOut(now)) }
        evaluator?.let {
            _state.value = _state.value.copy(zone = it.state(now), consecutive = it.streak, holding = it.isStale(now))
        }
    }

    fun stop() {
        gps.stop()
        watch = null
        scope?.cancel()
        scope = null
        settler = null
        evaluator = null
        testZones = emptyList()
        firstLat = Double.NaN
        firstLon = Double.NaN
        _state.value = UiState()
    }
}
