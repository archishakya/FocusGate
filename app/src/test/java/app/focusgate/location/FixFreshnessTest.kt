package app.focusgate.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixFreshnessTest {
    private val s = 1_000_000_000L

    @Test fun ageComputedFromFixTime() {
        assertEquals(3_000L, FixFreshness.ageMs(fixElapsedNs = 100 * s, nowElapsedNs = 103 * s))
        assertEquals(0L, FixFreshness.ageMs(fixElapsedNs = 105 * s, nowElapsedNs = 103 * s))
    }

    @Test fun exactlyTenSecondsAccepted_oneMsMoreRejected() {
        assertTrue(FixFreshness().accept(100 * s, 110 * s, 1.0, 2.0))
        assertFalse(FixFreshness().accept(100 * s, 110 * s + 1_000_000L, 1.0, 2.0))
    }

    @Test fun zeroOrNegativeTimestampRejected() {
        assertFalse(FixFreshness().accept(0L, 5 * s, 1.0, 2.0))
        assertFalse(FixFreshness().accept(0L, 0L, 1.0, 2.0))
        assertFalse(FixFreshness().accept(-1L, 5 * s, 1.0, 2.0))
    }

    @Test fun futureTimestampCountsAsFresh() {
        assertTrue(FixFreshness().accept(105 * s, 103 * s, 1.0, 2.0))
    }

    @Test fun staleFixDropped() {
        val f = FixFreshness()
        assertFalse(f.accept(100 * s, 111 * s, 1.0, 2.0))
    }

    @Test fun freshFixKept() {
        val f = FixFreshness()
        assertTrue(f.accept(100 * s, 102 * s, 1.0, 2.0))
        assertTrue(f.accept(101 * s, 102 * s, 1.0, 2.0)) // same place, newer time: real fix
    }

    @Test fun duplicateWithOldTimestampDropped() {
        val f = FixFreshness()
        assertTrue(f.accept(100 * s, 101 * s, 1.0, 2.0))
        assertFalse(f.accept(100 * s, 102 * s, 1.0, 2.0))
        assertTrue(f.accept(100 * s, 102 * s, 1.0001, 2.0)) // different position
    }
}
