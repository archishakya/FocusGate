package app.focusgate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneEvaluatorTest {
    private val r5 = listOf(5)
    private val r10 = listOf(10)
    private val t0 = 1_000_000L

    private fun ev(start: Long = t0) = ZoneEvaluator().also { it.start(start) }

    @Test fun singleInsideReading_commitsInside() {
        val e = ev()
        assertEquals(ZoneState.INSIDE, e.onReading(r10, listOf(4.0), 8.0, t0 + 1000))
    }

    @Test fun insideCountsUpTo30mAccuracy() {
        assertEquals(ZoneEvaluator.ReadingClass.INSIDE, ZoneEvaluator.classify(r10, listOf(9.0), 25.0))
        assertEquals(ZoneEvaluator.ReadingClass.INSIDE, ZoneEvaluator.classify(r5, listOf(2.0), 30.0))
        assertEquals(ZoneEvaluator.ReadingClass.UNUSABLE, ZoneEvaluator.classify(r10, listOf(9.0), 31.0))
    }

    @Test fun twoOutsideReadings_unblock_oneDoesNot() {
        val e = ev()
        e.onReading(r10, listOf(2.0), 5.0, t0 + 1000)
        assertEquals(ZoneState.INSIDE, e.onReading(r10, listOf(30.0), 5.0, t0 + 2000))
        assertEquals(ZoneState.OUTSIDE, e.onReading(r10, listOf(30.0), 5.0, t0 + 3000))
    }

    @Test fun ambiguousDoesNotResetCommitted_butBreaksOutsideStreak() {
        val e = ev()
        e.onReading(r10, listOf(2.0), 5.0, t0 + 1000)
        // r=10: exit margin 3 -> 10 < d <= 13 is ambiguous
        assertEquals(ZoneState.INSIDE, e.onReading(r10, listOf(12.0), 5.0, t0 + 2000))
        assertEquals(0, e.streak)
        e.onReading(r10, listOf(30.0), 5.0, t0 + 3000)
        assertEquals(ZoneState.INSIDE, e.onReading(r10, listOf(12.0), 5.0, t0 + 4000))
        assertEquals(ZoneState.INSIDE, e.onReading(r10, listOf(30.0), 5.0, t0 + 5000))
        assertEquals(ZoneState.OUTSIDE, e.onReading(r10, listOf(30.0), 5.0, t0 + 6000))
        // ...and does not reset a committed OUTSIDE either.
        assertEquals(ZoneState.OUTSIDE, e.onReading(r10, listOf(12.0), 5.0, t0 + 7000))
    }

    @Test fun unusableReadingsIgnored() {
        val e = ev()
        e.onReading(r5, listOf(100.0), 4.0, t0 + 1000)
        e.onReading(r5, listOf(2.0), 80.0, t0 + 1500) // too inaccurate, not confidently outside
        assertEquals(1, e.streak)
        assertEquals(ZoneState.OUTSIDE, e.onReading(r5, listOf(100.0), 4.0, t0 + 2000))
    }

    @Test fun holdsLastStateUpToWindow_thenUnknown() {
        val e = ev()
        e.onReading(r5, listOf(1.0), 3.0, t0 + 1000)
        assertFalse(e.isStale(t0 + 1000 + ZoneEvaluator.STALE_MS))
        assertTrue(e.isStale(t0 + 1001 + ZoneEvaluator.STALE_MS))
        e.onReading(r5, listOf(2.0), 80.0, t0 + 120_000) // unusable: does not refresh the hold
        assertEquals(ZoneState.INSIDE, e.state(t0 + 1000 + ZoneEvaluator.HOLD_MS))
        assertEquals(ZoneState.UNKNOWN, e.state(t0 + 1001 + ZoneEvaluator.HOLD_MS))
    }

    @Test fun noUsableReading_staysUnknown() {
        val e = ev()
        assertEquals(ZoneState.UNKNOWN, e.state(t0 + 21_000))
        assertEquals(ZoneState.UNKNOWN, e.onReading(r5, listOf(2.0), 80.0, t0 + 22_000))
    }

    @Test fun markUnavailable_failsOpenImmediately() {
        val e = ev()
        e.onReading(r5, listOf(1.0), 3.0, t0 + 1000)
        e.markUnavailable()
        assertEquals(ZoneState.UNKNOWN, e.state(t0 + 1001))
    }

    @Test fun confidentOutsideWithPoorFix() {
        assertEquals(ZoneEvaluator.ReadingClass.OUTSIDE, ZoneEvaluator.classify(r5, listOf(200.0), 50.0))
        assertEquals(ZoneEvaluator.ReadingClass.UNUSABLE, ZoneEvaluator.classify(r5, listOf(40.0), 50.0))
        val e = ev()
        for (k in 1..2) e.onReading(r5, listOf(200.0), 50.0, t0 + k * 1000L)
        assertEquals(ZoneState.OUTSIDE, e.state(t0 + 2000))
    }

    @Test fun thresholds() {
        assertEquals(20.0, ZoneRules.maxAccuracyM(5), 0.0)
        assertEquals(20.0, ZoneRules.maxAccuracyM(10), 0.0)
        assertEquals(40.0, ZoneRules.maxAccuracyM(20), 0.0)
        assertEquals(100.0, ZoneRules.maxAccuracyM(200), 0.0)
        assertEquals(30.0, ZoneRules.insideMaxAccuracyM(10), 0.0)
        assertEquals(3.0, ZoneRules.exitMargin(5), 0.0)
        assertEquals(3.0, ZoneRules.exitMargin(10), 0.0)
        assertEquals(15.0, ZoneRules.exitMargin(50), 0.0)
    }

    @Test fun multipleZones_anyInside() {
        val radii = listOf(5, 50)
        assertEquals(ZoneEvaluator.ReadingClass.INSIDE, ZoneEvaluator.classify(radii, listOf(500.0, 30.0), 8.0))
        assertEquals(ZoneEvaluator.ReadingClass.OUTSIDE, ZoneEvaluator.classify(radii, listOf(500.0, 80.0), 8.0))
        assertEquals(ZoneEvaluator.ReadingClass.AMBIGUOUS, ZoneEvaluator.classify(radii, listOf(500.0, 60.0), 8.0))
    }

    @Test fun carryOver_keepsInsideThroughHold_thenUnknown_andFreshReadingsFlip() {
        val e = ev()
        e.onReading(r5, listOf(1.0), 3.0, t0 + 1000)
        e.stop(t0 + 4000)
        val restart = t0 + 64_000
        e.start(restart)
        assertEquals(ZoneState.INSIDE, e.state(restart))
        assertEquals(ZoneState.INSIDE, e.state(restart + ZoneEvaluator.HOLD_MS))
        assertEquals(ZoneState.UNKNOWN, e.state(restart + ZoneEvaluator.HOLD_MS + 1))

        val e2 = ev()
        e2.onReading(r5, listOf(1.0), 3.0, t0 + 1000)
        e2.stop(t0 + 4000)
        e2.start(restart)
        e2.onReading(r5, listOf(100.0), 3.0, restart + 1000)
        assertEquals(ZoneState.INSIDE, e2.state(restart + 1000))
        assertEquals(ZoneState.OUTSIDE, e2.onReading(r5, listOf(100.0), 3.0, restart + 2000))
    }

    @Test fun carryOver_nineMinutes_kept_elevenMinutes_startsUnknown() {
        val e = ev()
        e.onReading(r5, listOf(1.0), 3.0, t0 + 1000)
        e.stop(t0 + 4000)
        e.start(t0 + 4000 + 9 * 60_000)
        assertEquals(ZoneState.INSIDE, e.state(t0 + 4000 + 9 * 60_000))

        val e2 = ev()
        e2.onReading(r5, listOf(1.0), 3.0, t0 + 1000)
        e2.stop(t0 + 4000)
        e2.start(t0 + 4000 + 11 * 60_000)
        assertEquals(ZoneState.UNKNOWN, e2.state(t0 + 4000 + 11 * 60_000))
    }

    @Test fun clearCarryOver_startsUnknown() {
        val e = ev()
        e.onReading(r5, listOf(1.0), 3.0, t0 + 1000)
        e.stop(t0 + 4000)
        e.clearCarryOver()
        e.start(t0 + 10_000)
        assertEquals(ZoneState.UNKNOWN, e.state(t0 + 10_000))
    }

    @Test fun endToEnd_row5_zeroHome_blocked_row6_zeroOutside_allowed() {
        val e = ev()
        e.onReading(r5, listOf(2.0), 4.0, t0 + 1000)
        assertEquals(Verdict(Decision.BLOCKED, Reason.LIMIT_INSIDE_ZONE), decide(0, e.state(t0 + 1000)))
        for (k in 2..3) e.onReading(r5, listOf(50.0), 4.0, t0 + k * 1000L)
        assertEquals(Verdict(Decision.ALLOWED, Reason.LIMIT_OUTSIDE_ZONE), decide(0, e.state(t0 + 3000)))
    }
}
