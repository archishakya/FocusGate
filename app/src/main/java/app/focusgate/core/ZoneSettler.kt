package app.focusgate.core

import kotlin.math.ceil

/**
 * "Use my current location" settle rule (DESIGN §6). Fixes live only in this object's memory;
 * the caller discards it on Cancel and stores only the final centre on Save.
 */
class ZoneSettler(private val startTs: Long) {
    private data class Fix(val lat: Double, val lon: Double, val acc: Double, val ts: Long)

    data class Center(val lat: Double, val lon: Double, val meanAccuracyM: Double)

    private val last = ArrayDeque<Fix>()
    private var firstFixTs: Long? = null

    /** Best mean accuracy over any [WINDOW]-fix window seen so far (null until [WINDOW] fixes). */
    var bestMeanAccuracyM: Double? = null
        private set

    var settled: Center? = null
        private set

    /** Number of trailing fixes (max [WINDOW]) with accuracy ≤ [GOOD_ACC_M], for the "steady k/[WINDOW]" counter. */
    val steadyCount: Int
        get() {
            var k = 0
            var i = last.size - 1
            while (i >= 0 && last[i].acc <= GOOD_ACC_M) {
                k++
                i--
            }
            return k
        }

    fun onFix(lat: Double, lon: Double, accuracyM: Double, now: Long): Center? {
        if (!ZoneRules.isValidCoords(lat, lon) || !(accuracyM > 0) || !accuracyM.isFinite()) return settled
        if (firstFixTs == null) firstFixTs = now
        last.addLast(Fix(lat, lon, accuracyM, now))
        while (last.size > WINDOW) last.removeFirst()
        if (last.size == WINDOW) {
            val mean = last.sumOf { it.acc } / WINDOW
            bestMeanAccuracyM = bestMeanAccuracyM?.let { minOf(it, mean) } ?: mean
            if (settled == null && now - firstFixTs!! >= MIN_DURATION_MS && last.all { it.acc <= GOOD_ACC_M }) {
                val c = weightedMean()
                val spread = last.maxOf { Geo.distanceM(it.lat, it.lon, c.lat, c.lon) }
                if (spread <= maxOf(3.0, mean)) settled = c
            }
        }
        return settled
    }

    fun timedOut(now: Long): Boolean = settled == null && now - startTs >= TIMEOUT_MS

    fun canSaveAnyway(): Boolean = (bestMeanAccuracyM ?: Double.MAX_VALUE) <= SAVE_ANYWAY_MAX_ACC_M

    /** Radius pre-set for "Save anyway": max(radius, ceil(bestAccuracy)), clamped to the valid range. */
    fun suggestedRadius(radiusM: Int): Int =
        ZoneRules.clampRadius(maxOf(radiusM, ceil(bestMeanAccuracyM ?: 0.0).toInt()))

    /** Centre for "Save anyway": weighted mean of the current window. */
    fun currentCenter(): Center? = if (last.isEmpty()) null else weightedMean()

    private fun weightedMean(): Center {
        var w = 0.0
        var lat = 0.0
        var lon = 0.0
        for (f in last) {
            val wi = 1.0 / (f.acc * f.acc)
            w += wi
            lat += f.lat * wi
            lon += f.lon * wi
        }
        return Center(lat / w, lon / w, last.sumOf { it.acc } / last.size)
    }

    companion object {
        const val WINDOW = 3
        const val GOOD_ACC_M = 20.0
        const val MIN_DURATION_MS = 5_000L
        const val TIMEOUT_MS = 90_000L
        const val SAVE_ANYWAY_MAX_ACC_M = 50.0
    }
}
