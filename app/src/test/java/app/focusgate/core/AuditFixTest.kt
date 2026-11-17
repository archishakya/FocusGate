package app.focusgate.core

import app.focusgate.core.EngineRig.Companion.IG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit fix round: zone changes while sampling, auto-release, multi-zone, fail-open on provider loss. */
class AuditFixTest {
    private fun snap(o: TickOutcome): EngineSnapshot = (o as TickOutcome.Continue).snapshot

    private val zA = Zone("a", "Home", 48.0, 11.0, 5)
    private val zB = Zone("b", "Office", 48.1, 11.1, 5)

    private fun samplingRig(zones: List<Zone>): EngineRig {
        val r = EngineRig(igAllowanceMin = 0)
        r.config.zoneList = zones
        r.resumed(IG, r.t0 + 500)
        r.step()
        assertTrue(r.core.isSampling)
        return r
    }

    @Test fun zoneChangeWhileSampling_restartsLocation_andRejectsStaleZoneReadings() {
        val old = listOf(zA, zB)
        val r = samplingRig(old)
        assertEquals(1, r.loc.startCalls)

        val edited = listOf(zB.copy(radiusM = 50), zA)
        r.config.zoneList = edited
        r.core.onZonesChanged()
        assertEquals(1, r.loc.stopCalls)
        assertEquals(2, r.loc.startCalls)
        assertTrue(r.core.isSampling)

        // A late callback computed for the old zone set must not count.
        r.core.onLocationReading(listOf(1.0, 1.0), 3.0, old)
        var s = snap(r.step())
        assertEquals(ZoneState.UNKNOWN, s.zone)
        assertEquals(0, r.blocker.blocks.size)

        r.core.onLocationReading(listOf(1.0, 1000.0), 3.0, edited)
        s = snap(r.step())
        assertEquals(ZoneState.INSIDE, s.zone)
        assertEquals(listOf(0), r.blocker.blocks)
        assertEquals(2, r.loc.startCalls) // no extra restarts from ticks
    }

    @Test fun zonesRemovedWhileSampling_stopsLocation() {
        val r = samplingRig(listOf(zA))
        r.config.zoneList = emptyList()
        r.core.onZonesChanged()
        assertEquals(1, r.loc.stopCalls)
        assertEquals(1, r.loc.startCalls)
        assertFalse(r.core.isSampling)
    }

    @Test fun blockedApp_releasedAfterTwoOutside_thenInsideBlocksAgainOnce() {
        val r = samplingRig(listOf(zA))
        r.inside()
        var s = snap(r.step())
        assertEquals(AppState.BLOCKED, s.apps[0].state)
        assertFalse(s.releases(0))
        assertEquals(1, r.blocker.blocks.size)
        r.core.markOwnUiShown(r.clock.now()) // Blocked screen resumed

        r.outside()
        s = snap(r.step())
        assertFalse(s.releases(0)) // one outside reading is not enough
        r.outside()
        s = snap(r.step())
        assertEquals(ZoneState.OUTSIDE, s.zone)
        assertEquals(AppState.TIMES_UP_OUTSIDE, s.apps[0].state)
        assertTrue(s.releases(0)) // BlockedActivity finishes on this

        // Activity finished; the user is back in the app, still outside: no block.
        r.resumed(IG, r.clock.now() + 500)
        r.outside()
        s = snap(r.step())
        assertEquals(Verdict(Decision.ALLOWED, Reason.LIMIT_OUTSIDE_ZONE), s.verdict)
        assertEquals(1, r.blocker.blocks.size)

        // Back inside: blocked again exactly once.
        r.inside()
        s = snap(r.step())
        assertEquals(AppState.BLOCKED, s.apps[0].state)
        assertEquals(2, r.blocker.blocks.size)
        r.core.markOwnUiShown(r.clock.now())
        repeat(5) {
            r.inside()
            r.step()
        }
        assertEquals(2, r.blocker.blocks.size)
    }

    @Test fun unknownDoesNotReleaseBlockedScreen() {
        val r = samplingRig(listOf(zA))
        r.inside()
        r.step()
        r.core.onLocationUnavailable()
        val s = snap(r.step())
        assertEquals(ZoneState.UNKNOWN, s.zone)
        assertFalse(s.releases(0))
    }

    @Test fun bothProvidersFail_failsOpen() {
        val r = samplingRig(listOf(zA))
        r.inside()
        r.step()
        r.core.markOwnUiShown(r.clock.now())
        // GpsSource reports onUnavailable when fused stalls and GPS cannot start.
        r.core.onLocationUnavailable()
        r.resumed(IG, r.clock.now() + 500)
        val s = snap(r.step())
        assertEquals(ZoneState.UNKNOWN, s.zone)
        assertEquals(Verdict(Decision.ALLOWED, Reason.LIMIT_LOCATION_UNKNOWN), s.verdict)
        assertEquals(1, r.blocker.blocks.size)
    }

    @Test fun multiZone_anyInsideBlocks_allOutsideReleases() {
        val zones = listOf(zA, zB)
        val r = samplingRig(zones)
        r.core.onLocationReading(listOf(100.0, 1.0), 3.0, zones) // inside the second zone only
        var s = snap(r.step())
        assertEquals(ZoneState.INSIDE, s.zone)
        assertEquals(listOf(0), r.blocker.blocks)
        r.core.markOwnUiShown(r.clock.now())

        // Outside one zone but still inside the other: stays blocked.
        repeat(3) {
            r.core.onLocationReading(listOf(100.0, 1.0), 3.0, zones)
            s = snap(r.step())
        }
        assertEquals(ZoneState.INSIDE, s.zone)

        repeat(2) {
            r.core.onLocationReading(listOf(100.0, 200.0), 3.0, zones)
            s = snap(r.step())
        }
        assertEquals(ZoneState.OUTSIDE, s.zone)
        assertTrue(s.releases(0))
        assertEquals(1, r.blocker.blocks.size)
    }
}
