package app.focusgate.core

import app.focusgate.core.Decision.ALLOWED
import app.focusgate.core.Decision.BLOCKED

fun decide(remainingMs: Long, zone: ZoneState): Verdict = when {
    remainingMs > 0 -> Verdict(ALLOWED, Reason.TIME_LEFT)
    zone == ZoneState.INSIDE -> Verdict(BLOCKED, Reason.LIMIT_INSIDE_ZONE)
    zone == ZoneState.OUTSIDE -> Verdict(ALLOWED, Reason.LIMIT_OUTSIDE_ZONE)
    else -> Verdict(ALLOWED, Reason.LIMIT_LOCATION_UNKNOWN) // fail open
}

/** Fail-open wrapper used by the engine. Any Throwable from computing inputs or deciding returns ALLOWED. */
inline fun safeDecide(inputs: () -> Pair<Long, ZoneState>): Verdict =
    try {
        val (r, z) = inputs()
        decide(r, z)
    } catch (t: Throwable) {
        Verdict(ALLOWED, Reason.ERROR_FAIL_OPEN)
    }

/**
 * True when the Blocked screen for [appId] should dismiss itself: the engine now allows the app because
 * the zone is confirmed OUTSIDE or the allowance is no longer exhausted. UNKNOWN does not dismiss.
 */
fun EngineSnapshot.releases(appId: Int): Boolean {
    val st = apps.getOrNull(appId)?.state ?: return false
    return st == AppState.HAS_TIME || st == AppState.TIMES_UP_OUTSIDE
}

/** Per-app UI state. [decide] itself stays unchanged (finding 10). */
fun appState(remainingMs: Long, zone: ZoneState, hasZones: Boolean, isForegroundBlocked: Boolean): AppState = when {
    remainingMs > 0 -> AppState.HAS_TIME
    !hasZones -> AppState.TIMES_UP_NO_ZONES
    isForegroundBlocked || zone == ZoneState.INSIDE -> AppState.BLOCKED
    zone == ZoneState.OUTSIDE -> AppState.TIMES_UP_OUTSIDE
    else -> AppState.TIMES_UP_UNKNOWN
}
