package app.focusgate.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.focusgate.core.Geo
import app.focusgate.core.Zone
import app.focusgate.gate.DataGate
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

/** Receives a reading reduced to distances (one per zone) and accuracy; or a raw fix for zone setup. */
interface GpsSink {
    /** [zones] is the exact zone list the distances were computed for (same order). */
    fun onReading(distancesM: List<Double>, accuracyM: Double, lat: Double, lon: Double, zones: List<Zone>)
    fun onUnavailable()
}

/**
 * The only location request call site.
 * AUTO: Google Play Services fused location (same service Google Maps uses; it may use GPS, Wi-Fi, mobile
 * signals and Google's servers), falling back to the platform GPS provider if Play Services is missing,
 * fails, or delivers no fresh fix in time ([FusedWatchdog]). Other modes exist for the GPS test screen.
 * Only fresh fixes (≤ 10 s by the fix's own timestamp) are delivered. Keeps no Location past the callback.
 */
class GpsSource(private val ctx: Context) {
    enum class Mode { AUTO, GOOGLE_FUSED, GPS_ONLY, PHONE_FUSED }

    private val lock = Any()
    private var session: Any? = null
    private var fusedClient: FusedLocationProviderClient? = null
    private var fusedCallback: LocationCallback? = null
    private var cts: CancellationTokenSource? = null
    private var listener: LocationListener? = null
    private val freshness = FixFreshness()
    private var watchdog: FusedWatchdog? = null
    private var watchdogHandler: Handler? = null
    private var watchdogRunnable: Runnable? = null

    /** Source of the current session (label only), e.g. "Google fused". */
    @Volatile var providersLabel: String? = null
        private set

    /** Source of the most recent fix, transient. */
    @Volatile var lastFixProvider: String? = null
        private set

    /** Real age of the most recent delivered fix at receive time, and the elapsedRealtime (ms) of the fix itself. */
    @Volatile var lastFixTimeElapsedMs: Long? = null
        private set

    /** Fixes delivered this session, per source label. Memory only. */
    @Volatile var fixCounts: Map<String, Int> = emptyMap()
        private set

    @SuppressLint("MissingPermission") // FINE is checked just below
    fun start(looper: Looper, zones: List<Zone>, sink: GpsSink, mode: Mode = Mode.AUTO): Boolean {
        if (!DataGate.isOpen()) return false
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        stop()
        synchronized(lock) {
            val token = Any()
            session = token
            freshness.reset()
            fixCounts = emptyMap()
            val ok = when (mode) {
                Mode.AUTO -> (playServicesOk() && startFused(token, looper, zones, sink, fallback = true)) ||
                    startPlatform(token, looper, zones, sink, listOf(LocationManager.GPS_PROVIDER))
                Mode.GOOGLE_FUSED -> playServicesOk() && startFused(token, looper, zones, sink, fallback = false)
                Mode.GPS_ONLY -> startPlatform(token, looper, zones, sink, listOf(LocationManager.GPS_PROVIDER))
                Mode.PHONE_FUSED -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    startPlatform(token, looper, zones, sink, listOf(LocationManager.FUSED_PROVIDER))
            }
            if (!ok) {
                session = null
                teardownLocked()
            }
            return ok
        }
    }

    private fun playServicesOk(): Boolean = try {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS
    } catch (e: Exception) {
        false
    }

    /** Called with [lock] held. */
    @SuppressLint("MissingPermission")
    private fun startFused(token: Any, looper: Looper, zones: List<Zone>, sink: GpsSink, fallback: Boolean): Boolean {
        return try {
            val client = LocationServices.getFusedLocationProviderClient(ctx)
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, FUSED_INTERVAL_MS)
                .setMinUpdateIntervalMillis(FUSED_MIN_INTERVAL_MS)
                .setMinUpdateDistanceMeters(0f)
                .setGranularity(Granularity.GRANULARITY_FINE)
                .setWaitForAccurateLocation(false)
                .build()
            val cb = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    if (!DataGate.isOpen()) {
                        stop()
                        return
                    }
                    for (loc in result.locations) handle(token, loc, LABEL_GOOGLE, zones, sink)
                }
            }
            fusedClient = client
            fusedCallback = cb
            providersLabel = LABEL_GOOGLE
            client.requestLocationUpdates(req, cb, looper).addOnFailureListener {
                if (!DataGate.isOpen()) {
                    stop()
                    return@addOnFailureListener
                }
                synchronized(lock) {
                    if (session !== token) return@addOnFailureListener
                    teardownLocked()
                    val ok = fallback &&
                        startPlatform(token, looper, zones, sink, listOf(LocationManager.GPS_PROVIDER))
                    if (!ok) session = null
                    if (!ok) sink.onUnavailable()
                }
            }
            // Immediate fresh fix; old lastLocation is never used as a seed.
            val source = CancellationTokenSource()
            cts = source
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, source.token).addOnSuccessListener { loc ->
                if (!DataGate.isOpen()) {
                    stop()
                    return@addOnSuccessListener
                }
                if (loc != null) handle(token, loc, LABEL_GOOGLE, zones, sink)
            }
            if (fallback) armWatchdogLocked(token, looper, zones, sink)
            true
        } catch (e: Exception) {
            teardownLocked()
            false
        }
    }

    /** AUTO only, called with [lock] held: if fused stays silent, switch to platform GPS for the session. */
    private fun armWatchdogLocked(token: Any, looper: Looper, zones: List<Zone>, sink: GpsSink) {
        val h = Handler(looper)
        val w = FusedWatchdog(SystemClock.elapsedRealtime())
        lateinit var r: Runnable
        r = Runnable {
            if (!DataGate.isOpen()) {
                stop()
                return@Runnable
            }
            var unavailable = false
            synchronized(lock) {
                if (session !== token || watchdog !== w) return@Runnable
                if (!w.shouldFallBack(SystemClock.elapsedRealtime())) {
                    h.postDelayed(r, FusedWatchdog.CHECK_INTERVAL_MS)
                    return@Runnable
                }
                teardownLocked()
                freshness.reset()
                val ok = startPlatform(token, looper, zones, sink, listOf(LocationManager.GPS_PROVIDER))
                if (!ok) {
                    session = null
                    unavailable = true
                }
            }
            if (unavailable) sink.onUnavailable() // fail open
        }
        watchdog = w
        watchdogHandler = h
        watchdogRunnable = r
        h.postDelayed(r, FusedWatchdog.CHECK_INTERVAL_MS)
    }

    /** Called with [lock] held. */
    @SuppressLint("MissingPermission")
    private fun startPlatform(
        token: Any,
        looper: Looper,
        zones: List<Zone>,
        sink: GpsSink,
        wanted: List<String>,
    ): Boolean {
        return try {
            val lm = ctx.getSystemService(LocationManager::class.java) ?: return false
            val providers = wanted.filter {
                (it != LocationManager.FUSED_PROVIDER || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) &&
                    lm.hasProvider(it) && lm.isProviderEnabled(it)
            }
            if (providers.isEmpty()) return false
            val l = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (!DataGate.isOpen()) {
                        stop()
                        return
                    }
                    handle(token, location, label(location.provider), zones, sink)
                }

                override fun onProviderDisabled(provider: String) {
                    if (!DataGate.isOpen()) {
                        stop()
                        return
                    }
                    sink.onUnavailable()
                }

                override fun onProviderEnabled(provider: String) = Unit

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            listener = l // set first so a failure below is torn down
            for (p in providers) lm.requestLocationUpdates(p, PLATFORM_INTERVAL_MS, 0f, l, looper)
            providersLabel = providers.joinToString(" + ") { label(it) }
            true
        } catch (e: Exception) {
            teardownLocked()
            false
        }
    }

    /** Freshness-filters a fix and delivers primitives only; the Location is not kept. */
    private fun handle(token: Any, loc: Location, source: String, zones: List<Zone>, sink: GpsSink) {
        if (!DataGate.isOpen()) {
            stop()
            return
        }
        val lat = loc.latitude
        val lon = loc.longitude
        val fixNs = loc.elapsedRealtimeNanos
        val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else Double.NaN
        synchronized(lock) {
            if (session !== token) return
            if (!freshness.accept(fixNs, SystemClock.elapsedRealtimeNanos(), lat, lon)) return
            lastFixProvider = source
            lastFixTimeElapsedMs = fixNs / 1_000_000L
            watchdog?.onFreshFix(SystemClock.elapsedRealtime())
            fixCounts = fixCounts + (source to ((fixCounts[source] ?: 0) + 1))
        }
        val d = zones.map { Geo.distanceM(lat, lon, it.lat, it.lon) }
        sink.onReading(d, acc, lat, lon, zones)
    }

    /** Ungated: must always be able to tear down. */
    fun stop() {
        synchronized(lock) {
            session = null
            teardownLocked()
            freshness.reset()
            lastFixTimeElapsedMs = null
            fixCounts = emptyMap()
        }
    }

    private fun teardownLocked() {
        watchdogRunnable?.let { r -> watchdogHandler?.removeCallbacks(r) }
        watchdogRunnable = null
        watchdogHandler = null
        watchdog = null
        providersLabel = null
        lastFixProvider = null
        cts?.let {
            try {
                it.cancel()
            } catch (e: Exception) {
                // Ignore.
            }
        }
        cts = null
        val cb = fusedCallback
        val client = fusedClient
        fusedCallback = null
        fusedClient = null
        if (cb != null && client != null) {
            try {
                client.removeLocationUpdates(cb)
            } catch (e: Exception) {
                // Already removed.
            }
        }
        val l = listener ?: return
        listener = null
        try {
            ctx.getSystemService(LocationManager::class.java)?.removeUpdates(l)
        } catch (e: Exception) {
            // Already removed.
        }
    }

    companion object {
        const val FUSED_INTERVAL_MS = 2_000L
        const val FUSED_MIN_INTERVAL_MS = 1_000L
        const val PLATFORM_INTERVAL_MS = 1_000L
        const val LABEL_GOOGLE = "Google fused"
        const val LABEL_GPS = "GPS"
        const val LABEL_PHONE_FUSED = "Phone fused"

        fun label(provider: String?): String = when (provider) {
            LocationManager.GPS_PROVIDER -> LABEL_GPS
            "fused" -> LABEL_PHONE_FUSED
            else -> provider?.replaceFirstChar { it.uppercase() } ?: "?"
        }
    }
}
