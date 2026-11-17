package app.focusgate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class UsageAccumulatorTest {

    private val utc: ZoneId = ZoneId.of("UTC")
    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")
    private val ig = "com.instagram.android"
    private val chrome = "com.android.chrome"
    private val sec = 1_000L
    private val min = 60_000L

    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant().toEpochMilli()

    private fun res(pkg: String, ts: Long, key: String = "Main") = MonitoredEvent(pkg, key, EventType.RESUMED, ts)
    private fun pau(pkg: String, ts: Long, key: String = "Main") = MonitoredEvent(pkg, key, EventType.PAUSED, ts)
    private fun stp(pkg: String, ts: Long, key: String = "Main") = MonitoredEvent(pkg, key, EventType.STOPPED, ts)

    /** Feeds the accumulator like the engine does: query [queryBegin, now] over a full event log. */
    private fun tick(acc: UsageAccumulator, log: List<MonitoredEvent>, armedAt: Long, now: Long) {
        val begin = acc.queryBegin(armedAt)
        acc.advance(log.filter { it.ts in begin..now }, now)
    }

    private val t0 = at(utc, 2024, 6, 1, 10, 0)

    @Test fun singleSession_openAndClose() {
        val acc = UsageAccumulator.fresh(utc, t0)
        acc.advance(listOf(res(ig, t0 + 10 * sec), pau(ig, t0 + 70 * sec)), t0 + 80 * sec)
        assertEquals(60 * sec, acc.usedMs(0))
        assertEquals(-1, acc.foregroundApp())
        assertTrue(acc.takeSessionClosed())
    }

    @Test fun stillOpen_accruesToNowEachTick_sumEqualsWallTime() {
        val acc = UsageAccumulator.fresh(utc, t0)
        val log = listOf(res(ig, t0 + 1 * sec))
        for (k in 2..60) tick(acc, log, t0, t0 + k * sec)
        assertEquals(59 * sec, acc.usedMs(0))
        assertEquals(0, acc.foregroundApp())
    }

    @Test fun multiActivityHandoff_noGap() {
        val acc = UsageAccumulator.fresh(utc, t0)
        val log = listOf(
            res(ig, t0 + 1 * sec, "A"), res(ig, t0 + 10 * sec, "B"), pau(ig, t0 + 10 * sec, "A"),
            pau(ig, t0 + 30 * sec, "B"),
        )
        acc.advance(log, t0 + 40 * sec)
        assertEquals(29 * sec, acc.usedMs(0))
    }

    @Test fun twoAppsInSequence() {
        val acc = UsageAccumulator.fresh(utc, t0)
        val log = listOf(res(ig, t0 + 0), pau(ig, t0 + 20 * sec), res(chrome, t0 + 20 * sec), stp(chrome, t0 + 50 * sec))
        acc.advance(log, t0 + 60 * sec)
        assertEquals(20 * sec, acc.usedMs(0))
        assertEquals(30 * sec, acc.usedMs(1))
    }

    @Test fun rollover_sessionAcrossMidnight_givesPostMidnightPartToToday() {
        val start = at(utc, 2024, 6, 1, 23, 45)
        val acc = UsageAccumulator.fresh(utc, start)
        acc.advance(listOf(res(ig, at(utc, 2024, 6, 1, 23, 50))), at(utc, 2024, 6, 1, 23, 55))
        assertEquals(5 * min, acc.usedMs(0))
        acc.advance(listOf(pau(ig, at(utc, 2024, 6, 2, 0, 20))), at(utc, 2024, 6, 2, 0, 30))
        assertEquals(20 * min, acc.usedMs(0))
        assertEquals(java.time.LocalDate.of(2024, 6, 2).toEpochDay(), acc.dayKey)
    }

    @Test fun rollover_noEventTickAfterMidnightResets() {
        val start = at(utc, 2024, 6, 1, 22, 0)
        val acc = UsageAccumulator.fresh(utc, start)
        acc.advance(listOf(res(chrome, start + 1 * min), pau(chrome, start + 11 * min)), start + 12 * min)
        assertEquals(10 * min, acc.usedMs(1))
        acc.advance(emptyList(), at(utc, 2024, 6, 2, 0, 1))
        assertEquals(0L, acc.usedMs(1))
    }

    @Test fun dstDay_berlin_springForward() {
        // 2024-03-31: 02:00 -> 03:00 local. 01:30 to 03:30 local is 1 h of wall time.
        val start = at(berlin, 2024, 3, 31, 1, 0)
        val acc = UsageAccumulator.fresh(berlin, start)
        acc.advance(listOf(res(ig, at(berlin, 2024, 3, 31, 1, 30)), pau(ig, at(berlin, 2024, 3, 31, 3, 30))), at(berlin, 2024, 3, 31, 4, 0))
        assertEquals(60 * min, acc.usedMs(0))
    }

    @Test fun dstDay_berlin_midnightSplitStillCorrect() {
        // 2024-10-27 is a 25 h day; a session from 23:30 on the 27th to 00:30 on the 28th gives 30 min to the 28th.
        val start = at(berlin, 2024, 10, 27, 23, 0)
        val acc = UsageAccumulator.fresh(berlin, start)
        acc.advance(listOf(res(ig, at(berlin, 2024, 10, 27, 23, 30)), pau(ig, at(berlin, 2024, 10, 28, 0, 30))), at(berlin, 2024, 10, 28, 1, 0))
        assertEquals(30 * min, acc.usedMs(0))
    }

    @Test fun processDeath_noDoubleCount_noLoss() {
        val armedAt = t0
        val log = listOf(
            res(ig, t0 + 5 * sec), pau(ig, t0 + 25 * sec),
            res(chrome, t0 + 40 * sec), // still open across the crash
            pau(chrome, t0 + 100 * sec),
        )
        // Ground truth: IG 20 s, Chrome 60 s.
        val acc = UsageAccumulator.fresh(utc, armedAt)
        for (k in 1..50) tick(acc, log, armedAt, t0 + k * sec)
        val persisted = acc.toPersisted() // persisted at t0 + 50 s
        for (k in 51..70) tick(acc, log, armedAt, t0 + k * sec) // un-persisted ticks, then crash

        val again = UsageAccumulator.fromPersisted(utc, persisted)
        val replayBegin = again.replayBegin(armedAt)
        again.replayStateOnly(log.filter { it.ts >= replayBegin && it.ts < again.cursorTs })
        assertEquals(1, again.foregroundApp()) // Chrome rebuilt as open at the cursor
        for (k in 71..120) tick(again, log, armedAt, t0 + k * sec)
        assertEquals(20 * sec, again.usedMs(0))
        assertEquals(60 * sec, again.usedMs(1))
    }

    @Test fun overlapRefeedingSameEvents_noDoubleCount() {
        val acc = UsageAccumulator.fresh(utc, t0)
        val log = listOf(res(ig, t0 + 1 * sec), pau(ig, t0 + 3 * sec), res(ig, t0 + 4 * sec))
        acc.advance(log, t0 + 5 * sec)
        acc.advance(log, t0 + 6 * sec) // same events again (2 s overlap and more)
        acc.advance(log, t0 + 7 * sec)
        assertEquals(2 * sec + 3 * sec, acc.usedMs(0))
    }

    @Test fun clockMovedBackwards_closesWithoutAccrual() {
        val acc = UsageAccumulator.fresh(utc, t0)
        acc.advance(listOf(res(ig, t0 + 1 * sec)), t0 + 10 * sec)
        assertEquals(9 * sec, acc.usedMs(0))
        acc.advance(emptyList(), t0 + 5 * sec)
        assertEquals(t0 + 5 * sec, acc.cursorTs)
        assertEquals(-1, acc.foregroundApp())
        acc.advance(emptyList(), t0 + 8 * sec)
        assertEquals(9 * sec, acc.usedMs(0))
    }

    @Test fun screenOffClose_andResync() {
        val acc = UsageAccumulator.fresh(utc, t0)
        val log = mutableListOf(res(ig, t0 + 1 * sec))
        tick(acc, log, t0, t0 + 11 * sec)
        acc.closeAllNoAccrue() // screen off
        assertEquals(10 * sec, acc.usedMs(0))
        // Off for 10 min; IG's PAUSED during the off period is ignored.
        log += pau(ig, t0 + 12 * sec)
        val on = t0 + 11 * sec + 10 * min
        acc.onScreenOn(on)
        assertEquals(on - 2 * sec, acc.cursorTs)
        log += res(ig, on + 1 * sec)
        tick(acc, log, t0, on + 6 * sec)
        assertEquals(15 * sec, acc.usedMs(0))
    }

    @Test fun resumedDuringScreenOff_accruesOnlyFromScreenOn() {
        val acc = UsageAccumulator.fresh(utc, t0)
        tick(acc, emptyList(), t0, t0 + 1 * sec)
        acc.closeAllNoAccrue() // screen off at t0+1s
        val log = listOf(res(ig, t0 + 2 * min)) // show-when-locked activity during screen-off
        val on = t0 + 30 * min
        acc.onScreenOn(on)
        // Feed the stale RESUMED directly (as a wide pre-fix query would have returned it).
        acc.advance(log, on + 10 * sec)
        // Accrual starts at the re-synced cursor (screenOn - 2 s), never in the off period.
        assertEquals(12 * sec, acc.usedMs(0))
    }

    @Test fun eventsBeforeArmedAt_neverAccrued() {
        val armedAt = t0
        val acc = UsageAccumulator.fresh(utc, armedAt)
        acc.advance(listOf(res(ig, t0 - 10 * min), pau(ig, t0 + 5 * sec)), t0 + 10 * sec)
        assertEquals(5 * sec, acc.usedMs(0))
    }

    @Test fun queryBegin_neverBeforeArmedAt() {
        val acc = UsageAccumulator.fresh(utc, t0)
        assertEquals(t0, acc.queryBegin(t0))
        assertEquals(t0, acc.replayBegin(t0))
        acc.advance(emptyList(), t0 + 10 * sec)
        assertEquals(t0 + 8 * sec, acc.queryBegin(t0))
    }

    @Test fun backwardTimeZoneChange_keepsCounters() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        // 2024-06-02 08:00 Tokyo = 2024-06-01 23:00 UTC.
        val start = at(tokyo, 2024, 6, 2, 8, 0)
        val acc = UsageAccumulator.fresh(tokyo, start)
        acc.advance(listOf(res(ig, start + 1 * min), pau(ig, start + 11 * min)), start + 12 * min)
        assertEquals(10 * min, acc.usedMs(0))
        // Moved west: same instants interpreted in UTC are still 2024-06-01 (today < dayKey).
        val west = UsageAccumulator.fromPersisted(utc, acc.toPersisted())
        west.advance(listOf(res(ig, start + 13 * min), pau(ig, start + 18 * min)), start + 20 * min)
        assertEquals(15 * min, west.usedMs(0))
        assertEquals(java.time.LocalDate.of(2024, 6, 2).toEpochDay(), west.dayKey)
        // Only today > dayKey resets.
        west.advance(emptyList(), at(utc, 2024, 6, 3, 0, 1))
        assertEquals(0L, west.usedMs(0))
    }

    @Test fun markOwnUiShown_closesStaleSessions_andIgnoresRedeliveredResumed() {
        val acc = UsageAccumulator.fresh(utc, t0)
        val log = listOf(res(ig, t0 + 1 * sec)) // STOPPED lost
        tick(acc, log, t0, t0 + 5 * sec)
        acc.markOwnUiShown(t0 + 6 * sec)
        assertEquals(5 * sec, acc.usedMs(0))
        assertFalse(acc.isOpen(0))
        for (k in 7..20) tick(acc, log + res(ig, t0 + 6 * sec), t0, t0 + k * sec)
        assertEquals(5 * sec, acc.usedMs(0))
        assertEquals(-1, acc.foregroundApp())
        // A new RESUMED reopens.
        tick(acc, log + res(ig, t0 + 21 * sec), t0, t0 + 25 * sec)
        assertEquals(0, acc.foregroundApp())
        assertEquals(9 * sec, acc.usedMs(0))
    }

    @Test fun deferredReplay_setsOpenSinceToCursor() {
        val persisted = PersistedUsage(java.time.LocalDate.of(2024, 6, 1).toEpochDay(), listOf(0L, 0L, 0L), t0 + 60 * sec)
        val acc = UsageAccumulator.fromPersisted(utc, persisted)
        acc.replayStateOnly(listOf(res(ig, t0 + 10 * sec)))
        assertEquals(0L, acc.usedMs(0)) // state-only: nothing accrued
        acc.advance(emptyList(), t0 + 70 * sec)
        assertEquals(10 * sec, acc.usedMs(0)) // from cursorTs, not from the RESUMED at +10 s
    }

    @Test fun deferredReplay_skippedWhenWindowEmpty() {
        val acc = UsageAccumulator.fresh(utc, t0)
        // Fresh arm: armedAt == cursorTs, so replayBegin >= cursorTs and the engine skips the query.
        assertTrue(acc.replayBegin(t0) >= acc.cursorTs)
    }
}
