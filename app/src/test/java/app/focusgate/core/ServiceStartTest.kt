package app.focusgate.core

import app.focusgate.core.ServiceStart.ACTION_NOTIF_DISMISSED
import app.focusgate.core.ServiceStart.ACTION_PANIC
import app.focusgate.core.ServiceStart.ACTION_START
import app.focusgate.core.ServiceStart.ACTION_TEST_BLOCK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStartTest {

    @Test fun start_gateOpen_startForegroundThrows_noEngine() {
        val p = FakeServicePort()
        p.throwQueue.addLast(IllegalStateException("not allowed"))
        assertEquals(StartReturn.NOT_STICKY, ServiceStart.handle(ACTION_START, 1, p))
        assertEquals(0, p.engineCreated) // so zero usage reads can happen
        assertTrue("stopSelf" in p.calls)
        assertFalse(p.calls.any { it.startsWith("disarm") })
    }

    @Test fun start_gateOpen_success_createsEngineAfterStartForeground() {
        val p = FakeServicePort()
        assertEquals(StartReturn.STICKY, ServiceStart.handle(ACTION_START, 1, p))
        assertEquals(listOf("startForeground", "createEngine"), p.calls)
        assertEquals(false, p.degradedState)
    }

    @Test fun start_gateClosed_minimalForeground_stop_noEngine() {
        val p = FakeServicePort(gate = false)
        assertEquals(StartReturn.NOT_STICKY, ServiceStart.handle(ACTION_START, 1, p))
        assertEquals(listOf("startForegroundMinimal", "stopForegroundRemove", "stopSelf"), p.calls)
        assertEquals(0, p.engineCreated)
    }

    @Test fun panic_disarms_stops_notSticky_noEngine() {
        val p = FakeServicePort()
        assertEquals(StartReturn.NOT_STICKY, ServiceStart.handle(ACTION_PANIC, 1, p))
        assertEquals(listOf("disarm(PANIC)", "stopForegroundRemove", "stopSelf"), p.calls)
        assertEquals(0, p.engineCreated)
    }

    @Test fun nullIntent_gateOpen_recordsOneUncleanRestart_thenStarts() {
        val p = FakeServicePort()
        assertEquals(StartReturn.STICKY, ServiceStart.handle(null, 1, p))
        assertEquals(1, p.unclean)
        assertEquals(listOf("recordUncleanRestart", "startForeground", "createEngine"), p.calls)
    }

    @Test fun nullIntent_gateClosed_stops() {
        val p = FakeServicePort(gate = false)
        assertEquals(StartReturn.NOT_STICKY, ServiceStart.handle(null, 1, p))
        assertEquals(listOf("stopSelf"), p.calls)
        assertEquals(0, p.unclean)
    }

    @Test fun nullIntent_crashLoopTrips_disarms_noEngine() {
        val p = FakeServicePort(tripOnRecord = true)
        assertEquals(StartReturn.NOT_STICKY, ServiceStart.handle(null, 1, p))
        assertTrue("disarm(CRASH_LOOP)" in p.calls)
        assertEquals(0, p.engineCreated)
    }

    @Test fun nullIntent_securityException_retriesDegraded() {
        val p = FakeServicePort()
        p.throwQueue.addLast(SecurityException("no while-in-use"))
        p.throwQueue.addLast(null)
        assertEquals(StartReturn.STICKY, ServiceStart.handle(null, 1, p))
        assertEquals(listOf(p.full, p.degradedSet), p.startedTypes)
        assertEquals(true, p.degradedState)
        assertEquals(1, p.engineCreated)
    }

    @Test fun nullIntent_otherException_stopsWithoutDisarm_postsPaused() {
        val p = FakeServicePort()
        p.throwQueue.addLast(IllegalStateException("fgs start not allowed"))
        assertEquals(StartReturn.NOT_STICKY, ServiceStart.handle(null, 1, p))
        assertTrue("stopSelf" in p.calls)
        assertTrue("postPausedAlert" in p.calls)
        assertFalse(p.calls.any { it.startsWith("disarm") })
        assertEquals(0, p.engineCreated)
    }

    @Test fun testBlock_withoutEngine_stopsStrayInstance() {
        val p = FakeServicePort(engine = false)
        assertEquals(StartReturn.NOT_STICKY, ServiceStart.handle(ACTION_TEST_BLOCK, 7, p))
        assertEquals(listOf("stopSelf(7)"), p.calls)
        assertEquals(0, p.unclean)
    }

    @Test fun notifDismissed_withoutEngine_stopsStrayInstance() {
        val p = FakeServicePort(engine = false)
        assertEquals(StartReturn.NOT_STICKY, ServiceStart.handle(ACTION_NOTIF_DISMISSED, 9, p))
        assertEquals(listOf("stopSelf(9)"), p.calls)
        assertEquals(0, p.unclean)
    }

    @Test fun testBlock_and_notifDismissed_withEngine_forwarded() {
        val p = FakeServicePort(engine = true)
        assertEquals(StartReturn.STICKY, ServiceStart.handle(ACTION_TEST_BLOCK, 1, p))
        assertEquals(StartReturn.STICKY, ServiceStart.handle(ACTION_NOTIF_DISMISSED, 2, p))
        assertEquals(listOf("engineRequestTestBlock", "engineFlagNotificationRepost"), p.calls)
    }

    @Test fun secondStart_withRunningEngine_noSecondEngine() {
        val p = FakeServicePort()
        ServiceStart.handle(ACTION_START, 1, p)
        ServiceStart.handle(ACTION_START, 2, p)
        assertEquals(1, p.engineCreated)
        assertTrue("engineTick" in p.calls)
    }

    @Test fun degradedEngine_start_repromotesWithLocation_noSecondEngine() {
        val p = FakeServicePort(engine = true)
        p.degradedState = true
        assertEquals(StartReturn.STICKY, ServiceStart.handle(ACTION_START, 3, p))
        assertEquals(listOf(setOf(FgsType.SPECIAL_USE, FgsType.LOCATION)), p.startedTypes)
        assertEquals(false, p.degradedState)
        assertEquals(listOf("startForeground", "refreshNotification", "engineTick"), p.calls)
        assertEquals(0, p.engineCreated)
    }

    @Test fun degradedEngine_start_securityException_staysDegraded() {
        val p = FakeServicePort(engine = true)
        p.degradedState = true
        p.throwQueue.addLast(SecurityException("still background"))
        assertEquals(StartReturn.STICKY, ServiceStart.handle(ACTION_START, 3, p))
        assertEquals(true, p.degradedState)
        assertEquals(0, p.engineCreated)
    }
}
