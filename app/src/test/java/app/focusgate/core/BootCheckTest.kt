package app.focusgate.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootCheckTest {
    @Test fun sameCount_sameBoot() {
        assertTrue(BootCheck.isSameBoot(BootId(7, 1_000, 500), BootId(7, 999_999, 100)))
    }

    @Test fun differentCount_rebooted() {
        assertFalse(BootCheck.isSameBoot(BootId(7, 1_000, 500), BootId(8, 1_000, 900)))
    }

    @Test fun nullCounts_elapsedDecreased_rebooted() {
        assertFalse(BootCheck.isSameBoot(BootId(null, 1_000, 500_000), BootId(null, 1_000, 400_000)))
    }

    @Test fun nullCounts_epochDrift() {
        assertTrue(BootCheck.isSameBoot(BootId(null, 1_000_000, 5_000), BootId(null, 1_120_000, 9_000)))
        assertFalse(BootCheck.isSameBoot(BootId(null, 1_000_000, 5_000), BootId(null, 1_120_001, 9_000)))
    }

    @Test fun missingStored_rebooted() {
        assertFalse(BootCheck.isSameBoot(null, BootId(7, 1_000, 500)))
    }
}
