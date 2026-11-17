package app.focusgate.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DecisionTest {
    private val min = 60_000L

    private fun check(remaining: Long, zone: ZoneState, d: Decision, r: Reason) =
        assertEquals(Verdict(d, r), decide(remaining, zone))

    @Test fun thirtyLeftHome_allowed() = check(30 * min, ZoneState.INSIDE, Decision.ALLOWED, Reason.TIME_LEFT)
    @Test fun thirtyLeftOutside_allowed() = check(30 * min, ZoneState.OUTSIDE, Decision.ALLOWED, Reason.TIME_LEFT)
    @Test fun zeroHome_blocked() = check(0, ZoneState.INSIDE, Decision.BLOCKED, Reason.LIMIT_INSIDE_ZONE)
    @Test fun zeroOutside_allowed() = check(0, ZoneState.OUTSIDE, Decision.ALLOWED, Reason.LIMIT_OUTSIDE_ZONE)

    @Test fun zeroLeaveHome_unblocked() {
        assertEquals(Decision.BLOCKED, decide(0, ZoneState.INSIDE).decision)
        assertEquals(Decision.ALLOWED, decide(0, ZoneState.OUTSIDE).decision)
    }

    @Test fun zeroReturnHome_blocked() {
        assertEquals(Decision.ALLOWED, decide(0, ZoneState.OUTSIDE).decision)
        assertEquals(Decision.BLOCKED, decide(0, ZoneState.INSIDE).decision)
    }

    @Test fun zeroUnknown_allowedFailOpen() =
        check(0, ZoneState.UNKNOWN, Decision.ALLOWED, Reason.LIMIT_LOCATION_UNKNOWN)

    @Test fun negativeOverrunInside_blocked() =
        check(-5 * min, ZoneState.INSIDE, Decision.BLOCKED, Reason.LIMIT_INSIDE_ZONE)

    @Test fun oneMsInside_allowed() = check(1, ZoneState.INSIDE, Decision.ALLOWED, Reason.TIME_LEFT)

    @Test fun throwingInputs_failOpen() {
        val v = safeDecide { throw IllegalStateException("boom") }
        assertEquals(Verdict(Decision.ALLOWED, Reason.ERROR_FAIL_OPEN), v)
    }

    @Test fun safeDecide_passesThroughNormalResult() {
        assertEquals(Decision.BLOCKED, safeDecide { 0L to ZoneState.INSIDE }.decision)
    }

    @Test fun appState_noZonesAtZero_isNoZones() {
        assertEquals(AppState.TIMES_UP_NO_ZONES, appState(0, ZoneState.UNKNOWN, hasZones = false, isForegroundBlocked = false))
    }

    @Test fun appState_otherMappings() {
        assertEquals(AppState.HAS_TIME, appState(1, ZoneState.UNKNOWN, hasZones = false, isForegroundBlocked = false))
        assertEquals(AppState.BLOCKED, appState(0, ZoneState.INSIDE, hasZones = true, isForegroundBlocked = true))
        assertEquals(AppState.TIMES_UP_OUTSIDE, appState(0, ZoneState.OUTSIDE, hasZones = true, isForegroundBlocked = false))
        assertEquals(AppState.TIMES_UP_UNKNOWN, appState(0, ZoneState.UNKNOWN, hasZones = true, isForegroundBlocked = false))
    }
}
