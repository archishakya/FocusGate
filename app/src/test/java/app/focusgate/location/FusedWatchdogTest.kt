package app.focusgate.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FusedWatchdogTest {
    private val t0 = 1_000_000L

    @Test fun fusedRegisteredButNoFixes_fallsBackAfter20s() {
        val w = FusedWatchdog(t0)
        assertFalse(w.shouldFallBack(t0 + 19_999))
        assertTrue(w.shouldFallBack(t0 + 20_000))
    }

    @Test fun freshFixesKeepFused_gapOf30sFallsBack() {
        val w = FusedWatchdog(t0)
        w.onFreshFix(t0 + 5_000)
        assertFalse(w.shouldFallBack(t0 + 25_000)) // first-fix timeout no longer applies
        assertFalse(w.shouldFallBack(t0 + 34_999))
        assertTrue(w.shouldFallBack(t0 + 35_000))
        w.onFreshFix(t0 + 35_000)
        assertFalse(w.shouldFallBack(t0 + 40_000))
    }
}
