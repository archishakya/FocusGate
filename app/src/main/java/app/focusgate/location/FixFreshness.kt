package app.focusgate.location

/**
 * Pure (JVM-testable) freshness filter. Fix age comes from the fix's own timestamp
 * (Location.elapsedRealtimeNanos) against the current elapsedRealtimeNanos, never from receive time,
 * so stale fixes re-delivered by a provider are not mistaken for fresh ones.
 * Holds only the previous fix's primitives, in memory, for duplicate detection.
 */
class FixFreshness(private val maxAgeMs: Long = MAX_AGE_MS) {
    private var prevLat = Double.NaN
    private var prevLon = Double.NaN
    private var prevFixNs = Long.MIN_VALUE

    /** True if the fix should be used. */
    fun accept(fixElapsedNs: Long, nowElapsedNs: Long, lat: Double, lon: Double): Boolean {
        if (fixElapsedNs <= 0L) return false // no real fix timestamp: age unknown
        val age = ageMs(fixElapsedNs, nowElapsedNs)
        if (age > maxAgeMs) return false
        // Same position re-delivered with the same or an older timestamp: not a new fix.
        if (lat == prevLat && lon == prevLon && fixElapsedNs <= prevFixNs) return false
        prevLat = lat
        prevLon = lon
        prevFixNs = fixElapsedNs
        return true
    }

    fun reset() {
        prevLat = Double.NaN
        prevLon = Double.NaN
        prevFixNs = Long.MIN_VALUE
    }

    companion object {
        const val MAX_AGE_MS = 10_000L

        /** Fix age in ms; a fix stamped slightly in the future counts as 0. */
        fun ageMs(fixElapsedNs: Long, nowElapsedNs: Long): Long =
            ((nowElapsedNs - fixElapsedNs) / 1_000_000L).coerceAtLeast(0L)
    }
}
