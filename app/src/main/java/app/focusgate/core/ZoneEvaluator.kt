package app.focusgate.core

/**
 * Per-reading classification + asymmetric hysteresis + hold-last-state staleness + carry-over.
 *
 * - One INSIDE reading commits INSIDE (block fast).
 * - [N_OUTSIDE] consecutive OUTSIDE readings commit OUTSIDE.
 * - AMBIGUOUS / UNUSABLE readings never change the committed state.
 * - With no usable reading, the committed state is held for [HOLD_MS]; after that it falls to UNKNOWN.
 *
 * Keeps only (candidate, streak, lastUsableTs, committed, committedAt) plus the carry-over pair.
 * Readings (distances, accuracy) are discarded immediately.
 */
class ZoneEvaluator {
    enum class ReadingClass { INSIDE, OUTSIDE, AMBIGUOUS, UNUSABLE }

    var committed: ZoneState = ZoneState.UNKNOWN
        private set
    var committedAt: Long = 0L
        private set
    private var candidate: ZoneState? = null
    /** Consecutive readings of the same class (INSIDE or OUTSIDE). Not capped; UIs cap it. */
    var streak: Int = 0
        private set
    private var lastUsableTs: Long = 0L

    private var carried: ZoneState = ZoneState.UNKNOWN
    private var stoppedAt: Long = Long.MIN_VALUE

    /** Starts a sampling session at [restartTs], applying the carry-over rule. */
    fun start(restartTs: Long) {
        val keep = carried != ZoneState.UNKNOWN && stoppedAt != Long.MIN_VALUE && restartTs - stoppedAt <= CARRY_OVER_MS
        committed = if (keep) carried else ZoneState.UNKNOWN
        candidate = if (keep) carried else null
        streak = 0
        lastUsableTs = restartTs
        committedAt = restartTs
    }

    /** Stops the session and remembers the committed state for carry-over. */
    fun stop(now: Long) {
        carried = state(now)
        stoppedAt = now
    }

    fun clearCarryOver() {
        carried = ZoneState.UNKNOWN
        stoppedAt = Long.MIN_VALUE
    }

    /** Provider off / permission lost / exception: UNKNOWN immediately. */
    fun markUnavailable() {
        committed = ZoneState.UNKNOWN
        candidate = null
        streak = 0
    }

    fun reset() {
        markUnavailable()
        clearCarryOver()
        lastUsableTs = 0L
        committedAt = 0L
    }

    /** True when no usable reading arrived for [STALE_MS] (the state is being held). */
    fun isStale(now: Long): Boolean = now - lastUsableTs > STALE_MS

    /** Committed state; held for [HOLD_MS] without a usable reading, then UNKNOWN. */
    fun state(now: Long): ZoneState {
        if (committed != ZoneState.UNKNOWN && now - lastUsableTs > HOLD_MS) markUnavailable()
        return committed
    }

    fun onReading(radiiM: List<Int>, distancesM: List<Double>, accuracyM: Double, now: Long): ZoneState {
        // Apply the hold window before this reading refreshes lastUsableTs.
        state(now)
        when (val c = classify(radiiM, distancesM, accuracyM)) {
            ReadingClass.UNUSABLE -> Unit
            ReadingClass.AMBIGUOUS -> {
                // Near the edge: keep the committed state, only break a pending streak.
                lastUsableTs = now
                candidate = null
                streak = 0
            }
            ReadingClass.INSIDE, ReadingClass.OUTSIDE -> {
                lastUsableTs = now
                val s = if (c == ReadingClass.INSIDE) ZoneState.INSIDE else ZoneState.OUTSIDE
                if (candidate == s) streak++ else {
                    candidate = s
                    streak = 1
                }
                val need = if (s == ZoneState.INSIDE) N_INSIDE else N_OUTSIDE
                if (streak >= need && committed != s) {
                    committed = s
                    committedAt = now
                }
            }
        }
        return state(now)
    }

    companion object {
        const val N_INSIDE = 1
        const val N_OUTSIDE = 2
        const val STALE_MS = 60_000L
        const val HOLD_MS = 300_000L
        const val CARRY_OVER_MS = 600_000L

        fun classify(radiiM: List<Int>, distancesM: List<Double>, accuracyM: Double): ReadingClass {
            if (radiiM.isEmpty() || radiiM.size != distancesM.size) return ReadingClass.UNUSABLE
            if (accuracyM.isNaN()) return ReadingClass.UNUSABLE
            var anyUsable = false
            var inside = false
            var allOutside = true
            for (i in radiiM.indices) {
                val r = radiiM[i]
                val d = distancesM[i]
                val usable = accuracyM > 0 && accuracyM <= ZoneRules.maxAccuracyM(r)
                val exit = r + ZoneRules.exitMargin(r)
                if (accuracyM > 0 && accuracyM <= ZoneRules.insideMaxAccuracyM(r) && d <= r) inside = true
                if (usable) {
                    anyUsable = true
                    if (d <= exit) allOutside = false
                } else {
                    if (!(accuracyM > 0 && d - accuracyM > exit)) allOutside = false
                }
            }
            return when {
                inside -> ReadingClass.INSIDE
                allOutside -> ReadingClass.OUTSIDE
                anyUsable -> ReadingClass.AMBIGUOUS
                else -> ReadingClass.UNUSABLE
            }
        }
    }
}
