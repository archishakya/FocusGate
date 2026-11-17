package app.focusgate.location

/**
 * Pure (JVM-testable) liveness rule for AUTO mode. Google fused may accept a request and then never
 * deliver a fix; when that happens GpsSource switches to platform GPS for the rest of the session.
 * Times are elapsedRealtime ms. Holds no location data.
 */
class FusedWatchdog(
    private val startedAtMs: Long,
    private val firstFixTimeoutMs: Long = FIRST_FIX_TIMEOUT_MS,
    private val fixGapTimeoutMs: Long = FIX_GAP_TIMEOUT_MS,
) {
    private var lastFreshFixMs: Long? = null

    /** A fresh (freshness-accepted) fix was delivered. */
    fun onFreshFix(nowMs: Long) {
        lastFreshFixMs = nowMs
    }

    /** True when fused should be torn down and platform GPS started. */
    fun shouldFallBack(nowMs: Long): Boolean {
        val last = lastFreshFixMs
        return if (last == null) nowMs - startedAtMs >= firstFixTimeoutMs else nowMs - last >= fixGapTimeoutMs
    }

    companion object {
        const val FIRST_FIX_TIMEOUT_MS = 20_000L
        const val FIX_GAP_TIMEOUT_MS = 30_000L
        const val CHECK_INTERVAL_MS = 5_000L
    }
}
