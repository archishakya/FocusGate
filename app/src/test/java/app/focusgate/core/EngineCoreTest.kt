package app.focusgate.core

import app.focusgate.core.EngineRig.Companion.IG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineCoreTest {

    private fun snap(o: TickOutcome): EngineSnapshot = (o as TickOutcome.Continue).snapshot

    /** IG at limit, inside a zone, blocked once. Returns the rig. */
    private fun blockedOnce(): EngineRig {
        val r = EngineRig(igAllowanceMin = 0)
        r.resumed(IG, r.t0 + 500)
        r.step() // t0+1s: location sampling starts, zone UNKNOWN -> allowed
        assertTrue(r.core.isSampling)
        repeat(3) { r.inside() }
        val s = snap(r.step()) // t0+2s
        assertEquals(ZoneState.INSIDE, s.zone)
        assertEquals(Verdict(Decision.BLOCKED, Reason.LIMIT_INSIDE_ZONE), s.verdict)
        assertEquals(listOf(0), r.blocker.blocks)
        return r
    }

    @Test fun lostStopped_blockedShown_noRelaunch_noFurtherAccrual_newResumedBlocksAgain() {
        val r = blockedOnce()
        r.core.markOwnUiShown(r.clock.now()) // Blocked screen resumed
        r.resumed(IG, r.clock.now()) // a re-delivered RESUMED with ts <= ownUiShownTs
        val usedAfterMark = snap(r.step()).apps[0].usedMs
        for (k in 0 until 10) {
            r.inside()
            val s = snap(r.step())
            assertEquals(usedAfterMark, s.apps[0].usedMs)
            assertNull(s.foregroundAppId)
        }
        assertEquals(1, r.blocker.blocks.size)
        assertEquals(0, r.blocker.homeCalls)

        r.resumed(IG, r.clock.now() + 500)
        r.inside()
        r.step()
        assertEquals(2, r.blocker.blocks.size)
    }

    @Test fun queryFloor_everyBeginAtOrAfterArmedAt_noExtraQueryForVerify() {
        val r = blockedOnce()
        var ticks = 2
        for (k in 0 until 5) { r.inside(); r.step(); ticks++ } // includes block verify after 1.5 s
        r.core.onScreenOff(); ticks++
        r.clock.advance(5 * 60_000)
        r.core.onScreenOn(r.clock.now())
        for (k in 0 until 3) { r.step(); ticks++ }
        assertEquals(ticks, r.usage.calls.size) // exactly one query per tick, none for the verify
        assertTrue(r.usage.calls.all { it.first >= r.t0 })

        // Sticky-restart replay: cursor 1 h after arm, lookback 6 h is floored at armedAt.
        val s = EngineRig(initialCursor = null).let { base ->
            EngineRig(initialCursor = base.t0 + 3_600_000)
        }
        s.clock.nowMs = s.t0 + 3_601_000
        s.core.tick()
        assertEquals(2, s.usage.calls.size)
        assertEquals(s.t0, s.usage.calls[0].first) // replay begin = max(armedAt, cursor - 6 h)
        assertEquals(s.t0 + 3_600_000, s.usage.calls[0].second)
        assertTrue(s.usage.calls.all { it.first >= s.t0 })
    }

    @Test fun deferredReplay_waitsForInteractiveTick_andSkipsEmptyWindow() {
        val r = EngineRig(initialCursor = null, screenOn = false)
        r.step()
        assertEquals(0, r.usage.calls.size) // screen off: nothing queried
        r.core.onScreenOn(r.clock.now())
        r.step()
        assertEquals(1, r.usage.calls.size) // fresh arm: replay window empty, skipped

        val s = EngineRig(initialCursor = EngineRig().t0 + 60_000, screenOn = false)
        s.clock.nowMs = s.t0 + 120_000
        s.core.tick()
        assertEquals(0, s.usage.calls.size)
        s.core.onScreenOn(s.clock.now())
        s.core.tick()
        assertEquals(2, s.usage.calls.size) // replay + regular query on the first interactive tick
    }

    @Test fun readFailed_accessAllowed_failOpen_errorCounted_tenInARowDisarms() {
        val r = EngineRig(igAllowanceMin = 0)
        r.resumed(IG, r.t0 + 500)
        repeat(3) { r.inside() }
        r.step()
        val startsBefore = r.loc.startCalls
        r.usage.failNext = 1
        val s = snap(r.step())
        assertEquals(Verdict(Decision.ALLOWED, Reason.ERROR_FAIL_OPEN), s.verdict)
        assertEquals(1, s.consecutiveErrors)
        assertEquals(0, r.blocker.blocks.size)
        assertEquals(startsBefore, r.loc.startCalls)
        r.usage.failNext = 9
        for (k in 2..9) assertTrue(r.step() is TickOutcome.Continue)
        assertEquals(TickOutcome.RequestDisarm(DisarmReason.CRASH_LOOP), r.step())
    }

    @Test fun readFailed_accessRevoked_permissionLost_noErrorCounted() {
        val r = EngineRig()
        r.step()
        r.perms.usageAllowed = false
        r.usage.failNext = 1
        assertEquals(TickOutcome.RequestDisarm(DisarmReason.PERMISSION_LOST), r.step())
        assertEquals(0, r.core.snapshot.consecutiveErrors)
    }

    @Test fun testBlocking_firesOnlyWhenAllConditionsHold_thenPasses() {
        val r = EngineRig()
        r.core.requestTestBlock()
        r.presence.main = true
        r.step()
        assertEquals(TestBlockState.Pending, r.core.snapshot.test)
        r.presence.main = false
        r.presence.lastOwnElapsed = r.clock.elapsedMs
        r.core.onOwnUiStopped()
        for (k in 1..10) {
            r.step()
            assertEquals("k=$k", 0, r.blocker.testLaunches)
        }
        r.step() // 11 s after leaving: >= 5 s and >= 11 s since the last own-activity event
        assertEquals(1, r.blocker.testLaunches)
        assertEquals(TestBlockState.Fired, r.core.snapshot.test)
        r.presence.blocked = true
        r.step()
        assertEquals(TestBlockState.Passed(11), r.core.snapshot.test)
    }

    @Test fun testBlocking_waitsForOwnActivityGrace() {
        val r = EngineRig()
        r.core.requestTestBlock()
        r.core.onOwnUiStopped()
        r.presence.lastOwnElapsed = r.clock.elapsedMs + 4_000 // e.g. a FocusGate activity finished 4 s later
        for (k in 1..14) r.step()
        assertEquals(0, r.blocker.testLaunches)
        r.step()
        assertEquals(1, r.blocker.testLaunches)
    }

    @Test fun testBlocking_returningFirst_inconclusive() {
        val r = EngineRig()
        r.core.requestTestBlock()
        r.core.onOwnUiStopped()
        r.step()
        r.presence.main = true
        r.step()
        assertEquals(TestBlockState.Inconclusive, r.core.snapshot.test)
        assertEquals(0, r.blocker.testLaunches)
    }

    @Test fun testBlocking_sixtySeconds_inconclusive() {
        val r = EngineRig()
        r.core.requestTestBlock()
        r.core.onOwnUiStopped()
        for (k in 1..60) {
            r.presence.lastOwnElapsed = r.clock.elapsedMs // never 11 s quiet
            r.step()
        }
        assertEquals(TestBlockState.Inconclusive, r.core.snapshot.test)
        assertEquals(0, r.blocker.testLaunches)
    }

    @Test fun testBlocking_notResumedWithin3s_failed() {
        val r = EngineRig()
        r.presence.lastOwnElapsed = r.clock.elapsedMs
        r.core.requestTestBlock()
        r.core.onOwnUiStopped()
        for (k in 1..11) r.step()
        assertEquals(TestBlockState.Fired, r.core.snapshot.test)
        for (k in 1..3) r.step()
        assertEquals(TestBlockState.Failed, r.core.snapshot.test)
    }

    @Test fun notificationDeleteIntent_repostOnNextTick() {
        val r = EngineRig()
        assertFalse(snap(r.step()).repostNotification)
        r.core.flagNotificationRepost()
        assertTrue(snap(r.step()).repostNotification)
        assertFalse(snap(r.step()).repostNotification)
    }

    @Test fun noZones_timesUp_mapsToNoZones_noLocation() {
        val r = EngineRig(igAllowanceMin = 0, withZone = false)
        val s = snap(r.step())
        assertEquals(AppState.TIMES_UP_NO_ZONES, s.apps[0].state)
        assertEquals(0, r.loc.startCalls)
    }

    @Test fun leavingZone_unblocks_returning_blocks() {
        val r = blockedOnce()
        repeat(3) { r.outside() }
        val s = snap(r.step())
        assertEquals(Verdict(Decision.ALLOWED, Reason.LIMIT_OUTSIDE_ZONE), s.verdict)
        r.step()
        repeat(3) { r.inside() }
        r.step()
        assertEquals(2, r.blocker.blocks.size)
    }

    @Test fun singleInsideReading_blocks_twoOutsideReadings_unblock() {
        val r = EngineRig(igAllowanceMin = 0)
        r.resumed(IG, r.t0 + 500)
        r.step()
        r.inside()
        assertEquals(Verdict(Decision.BLOCKED, Reason.LIMIT_INSIDE_ZONE), snap(r.step()).verdict)
        r.outside()
        assertEquals(ZoneState.INSIDE, snap(r.step()).zone)
        r.outside()
        val s = snap(r.step())
        assertEquals(ZoneState.OUTSIDE, s.zone)
        assertEquals(Verdict(Decision.ALLOWED, Reason.LIMIT_OUTSIDE_ZONE), s.verdict)
    }

    @Test fun warmStart_samplingBeginsAtTwoMinutesLeft_notBefore() {
        val r = EngineRig(igAllowanceMin = 3)
        r.resumed(IG, r.t0 + 1)
        r.step()
        assertFalse(r.core.isSampling)
        assertEquals(0, r.loc.startCalls)
        repeat(55) { r.step() } // ~56 s used, 2 min 4 s left
        assertFalse(r.core.isSampling)
        repeat(6) { r.step() } // > 60 s used, <= 2 min left
        assertTrue(r.core.isSampling)
        assertEquals(1, r.loc.startCalls)
        r.inside()
        val s = snap(r.step())
        assertEquals(ZoneState.INSIDE, s.zone)
        assertEquals(AppState.HAS_TIME, s.apps[0].state) // still has time: not blocked yet
        assertEquals(0, r.blocker.blocks.size)
    }

    @Test fun warmStart_gateClosed_zeroLocationCalls() {
        val r = EngineRig(igAllowanceMin = 2, gateOpen = false)
        r.resumed(IG, r.t0 + 1)
        repeat(30) { r.step() }
        assertEquals(0, r.loc.startCalls)
        assertFalse(r.core.isSampling)
    }

    @Test fun persistCadence_crossingZero_and15s() {
        val r = EngineRig(igAllowanceMin = 1)
        r.resumed(IG, r.t0 + 1)
        r.step()
        assertNull(r.core.persistIfDue())
        for (k in 1..13) {
            r.step()
            assertNull("k=$k", r.core.persistIfDue())
        }
        r.step() // 15 s since start
        assertTrue(r.core.persistIfDue() != null)
        repeat(50) { r.step() } // crosses 60 s used
        val p = r.core.persistIfDue()
        assertTrue(p != null && p.usedMs[0] >= 60_000)
    }

    @Test fun screenOff_persistsAndStopsSampling() {
        val r = EngineRig(igAllowanceMin = 0)
        r.resumed(IG, r.t0 + 500)
        r.step()
        assertTrue(r.core.isSampling)
        r.core.onScreenOff()
        assertFalse(r.core.isSampling)
        assertEquals(1, r.loc.stopCalls)
        assertTrue(r.core.persistIfDue() != null)
        val calls = r.usage.calls.size
        r.step()
        assertEquals(calls, r.usage.calls.size) // nothing queried while the screen is off
    }
}
