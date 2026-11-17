package app.focusgate.core

import app.focusgate.core.EngineRig.Companion.IG
import app.focusgate.gate.DataGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class DataGateTest {

    @After fun closeGate() {
        DataGate.close()
    }

    @Test fun gateClosed_zeroReaderCalls_overManyTicksAndScreenEvents() {
        val r = EngineRig(igAllowanceMin = 0, gateOpen = false)
        r.resumed(IG, r.t0 + 1)
        repeat(100) {
            r.step()
            r.inside()
        }
        r.core.onScreenOff()
        r.core.onScreenOn(r.clock.now())
        r.core.markOwnUiShown(r.clock.now())
        r.core.requestTestBlock()
        r.core.onOwnUiStopped()
        repeat(20) { r.step() }
        assertEquals(0, r.usage.calls.size)
        assertEquals(0, r.loc.startCalls)
        assertEquals(0, r.loc.stopCalls)
        assertEquals(0, r.blocker.blocks.size + r.blocker.homeCalls + r.blocker.testLaunches)
    }

    @Test fun productionGate_closed_zeroReaderCalls() {
        DataGate.close()
        val usage = FakeUsageReader()
        val loc = FakeLocationReader()
        val clock = FakeClock(1_717_236_000_000L)
        val core = EngineCore(
            gateOpen = DataGate::isOpen,
            armedAtTs = DataGate::armedAtTs,
            usage = usage,
            location = loc,
            blocker = FakeBlocker(),
            perms = FakePermissionProbe(),
            presence = FakePresenceProbe(),
            config = FakeConfigSource(),
            clock = clock,
            zoneId = ZoneId.of("UTC"),
            initial = PersistedUsage(0, listOf(0L, 0L, 0L), clock.nowMs),
        )
        repeat(50) {
            clock.advance(1_000)
            core.tick()
            core.onLocationReading(listOf(1.0), 3.0)
        }
        assertEquals(0, usage.calls.size)
        assertEquals(0, loc.startCalls)
    }

    @Test fun gateClosedMidSession_inFlightReadingIgnored_nextTickNoCalls() {
        val r = EngineRig(igAllowanceMin = 0)
        r.resumed(IG, r.t0 + 500)
        r.step()
        assertTrue(r.core.isSampling)
        r.inside()
        r.inside()
        assertEquals(2, r.core.zoneStreak)
        val usageCalls = r.usage.calls.size
        val starts = r.loc.startCalls

        r.gate.open = false // ArmController.disarm closes the gate first
        r.inside() // in-flight fix after close: dropped unread
        assertEquals(2, r.core.zoneStreak)
        repeat(10) { r.step() }
        assertEquals(usageCalls, r.usage.calls.size)
        assertEquals(starts, r.loc.startCalls)
        assertEquals(0, r.blocker.blocks.size)
    }

    @Test fun stop_clearsInMemoryState_andReturnsCounters() {
        val r = EngineRig(igAllowanceMin = 0)
        r.resumed(IG, r.t0 + 500)
        r.step()
        repeat(3) { r.inside() }
        r.step()
        r.core.requestTestBlock()
        val p = r.core.stop()
        assertEquals(1_500L, p.usedMs[0])
        assertEquals(EngineSnapshot.Idle, r.core.snapshot)
        assertFalse(r.core.isSampling)
        assertEquals(1, r.loc.stopCalls)
        assertEquals(0, r.core.zoneStreak)

        val calls = r.usage.calls.size
        repeat(5) { r.step() }
        assertEquals(calls, r.usage.calls.size) // a stopped core never queries again
        assertEquals(null, r.core.persistIfDue())
    }

    @Test fun dataGate_openClose_semantics() {
        DataGate.close()
        assertFalse(DataGate.isOpen())
        assertEquals(Long.MAX_VALUE, DataGate.armedAtTs())
        DataGate.open(123L)
        assertTrue(DataGate.isOpen())
        assertEquals(123L, DataGate.armedAtTs())
        DataGate.close()
        assertFalse(DataGate.isOpen())
        assertEquals(Long.MAX_VALUE, DataGate.armedAtTs())
    }
}
