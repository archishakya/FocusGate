package app.focusgate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLoopGuardTest {
    private val min = 60_000L

    @Test fun threeWithinTenMinutes_trips() {
        var l = emptyList<Long>()
        l = CrashLoopGuard.record(l, 0)
        l = CrashLoopGuard.record(l, 4 * min)
        l = CrashLoopGuard.record(l, 9 * min)
        assertTrue(CrashLoopGuard.isTripped(l, 9 * min))
    }

    @Test fun threeOverElevenMinutes_doesNotTrip() {
        var l = emptyList<Long>()
        l = CrashLoopGuard.record(l, 0)
        l = CrashLoopGuard.record(l, 5 * min)
        l = CrashLoopGuard.record(l, 11 * min)
        assertFalse(CrashLoopGuard.isTripped(l, 11 * min))
    }

    @Test fun armClears() {
        assertTrue(CrashLoopGuard.cleared().isEmpty())
        assertFalse(CrashLoopGuard.isTripped(CrashLoopGuard.cleared(), 0))
    }

    @Test fun bothSignalsOnOneInstance_recordOneEntry_twoKillsDontTrip() {
        var l = emptyList<Long>()
        val first = RestartRecorder()
        l = first.record(l, 0) // service_alive still true in onCreate
        l = first.record(l, 1_000) // null intent on the same instance
        assertEquals(1, l.size)
        val second = RestartRecorder()
        l = second.record(l, 2 * min)
        l = second.record(l, 2 * min + 1_000)
        assertEquals(2, l.size)
        assertFalse(CrashLoopGuard.isTripped(l, 2 * min + 1_000))
    }

    @Test fun keepsAtMostFive_andEncodesRoundTrip() {
        var l = emptyList<Long>()
        for (k in 1..8) l = CrashLoopGuard.record(l, k.toLong())
        assertEquals(listOf(4L, 5L, 6L, 7L, 8L), l)
        assertEquals(l, CrashLoopGuard.decode(CrashLoopGuard.encode(l)))
        assertEquals(emptyList<Long>(), CrashLoopGuard.decode("garbage"))
        assertEquals(emptyList<Long>(), CrashLoopGuard.decode(null))
    }
}
