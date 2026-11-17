package app.focusgate.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure foreground model and per-app usage counter (DESIGN §4 + findings 6, 7).
 *
 * Invariant: time in [a, b) is added to usedMs only together with moving cursorTs from a to b,
 * and the caller persists both atomically via [toPersisted].
 */
class UsageAccumulator(
    private val zone: ZoneId,
    dayKey: Long,
    usedMs: List<Long>,
    cursorTs: Long,
) {
    private val n = MonitoredApps.all.size

    var dayKey: Long = dayKey
        private set
    var cursorTs: Long = cursorTs
        private set
    var ownUiShownTs: Long = Long.MIN_VALUE
        private set

    private val used = LongArray(n) { i -> usedMs.getOrNull(i) ?: 0L }
    private val resumed = Array(n) { mutableSetOf<String>() }
    private val openSince = arrayOfNulls<Long>(n)
    private val latestResumed = LongArray(n) { Long.MIN_VALUE }

    /** Something changed since the last [toPersisted]. */
    var isDirty: Boolean = false
        private set
    private var sessionClosed = false

    fun usedMs(appId: Int): Long = used[appId]
    fun latestResumedTs(appId: Int): Long = latestResumed[appId]
    fun isOpen(appId: Int): Boolean = resumed[appId].isNotEmpty()

    /** Foreground monitored app (latest RESUMED among open ones), or -1. */
    fun foregroundApp(): Int {
        var best = -1
        for (i in 0 until n) {
            if (resumed[i].isNotEmpty() && (best < 0 || latestResumed[i] > latestResumed[best])) best = i
        }
        return best
    }

    fun foregroundPkg(): String? = foregroundApp().let { if (it < 0) null else MonitoredApps.all[it].pkg }

    /** Query begin for every regular query: max(armedAtTs, cursorTs - 2 s). */
    fun queryBegin(armedAtTs: Long): Long = maxOf(armedAtTs, cursorTs - OVERLAP_MS)

    /** Begin of the state-only replay query: max(armedAtTs, cursorTs - 6 h). */
    fun replayBegin(armedAtTs: Long): Long = maxOf(armedAtTs, cursorTs - REPLAY_LOOKBACK_MS)

    fun advance(events: List<MonitoredEvent>, now: Long) {
        // 1. Clock sanity.
        if (now < cursorTs) {
            cursorTs = now
            closeAllNoAccrue()
            isDirty = true
            return
        }
        // 3. Events in timestamp order; ts < cursorTs is state-only (accrualFloor = cursorTs).
        for (e in events.sortedBy { it.ts }) {
            if (e.ts > now) continue
            apply(e, accrueAllowed = true)
        }
        // 4. Still open at query time.
        for (i in 0 until n) {
            val from = openSince[i] ?: continue
            accrue(i, from, now)
            openSince[i] = now
        }
        // 5. Move the cursor.
        if (cursorTs != now) isDirty = true
        cursorTs = now
        // 2. Rollover on every tick (after accrual so a pre-midnight part goes to the old day).
        rolloverTo(epochDay(now))
    }

    /**
     * State-only replay after a sticky restart (finding 6). Rebuilds the resumed sets as of
     * cursorTs without any accrual, then sets openSince = cursorTs for every non-empty set.
     */
    fun replayStateOnly(events: List<MonitoredEvent>) {
        for (e in events.sortedBy { it.ts }) {
            if (e.ts >= cursorTs) continue
            apply(e, accrueAllowed = false)
        }
        for (i in 0 until n) openSince[i] = if (resumed[i].isNotEmpty()) cursorTs else null
    }

    /** Screen-on re-sync (finding 7): the cursor skips the screen-off period, no accrual. */
    fun onScreenOn(ts: Long) {
        val c = maxOf(cursorTs, ts - OVERLAP_MS)
        if (c != cursorTs) {
            cursorTs = c
            isDirty = true
        }
        for (i in 0 until n) if (openSince[i] != null) openSince[i] = maxOf(openSince[i]!!, cursorTs)
    }

    /** Closes every session without accruing (screen off, clock moved back, teardown). */
    fun closeAllNoAccrue() {
        for (i in 0 until n) {
            resumed[i].clear()
            openSince[i] = null
        }
    }

    /** Own-UI reset (lost STOPPED protection, DESIGN §4). */
    fun markOwnUiShown(ts: Long) {
        ownUiShownTs = maxOf(ownUiShownTs, ts)
        for (i in 0 until n) {
            if (resumed[i].isNotEmpty() && latestResumed[i] < ts) {
                val from = openSince[i]
                if (from != null) accrue(i, from, maxOf(from, ts))
                resumed[i].clear()
                openSince[i] = null
                sessionClosed = true
                isDirty = true
            }
        }
    }

    /** True once after a session closed (persist trigger). */
    fun takeSessionClosed(): Boolean {
        val s = sessionClosed
        sessionClosed = false
        return s
    }

    fun toPersisted(): PersistedUsage {
        isDirty = false
        return PersistedUsage(dayKey, used.toList(), cursorTs)
    }

    /** Applies the rollover rule for a tick-free moment (no events). */
    fun rolloverCheck(now: Long) = rolloverTo(epochDay(now))

    private fun apply(e: MonitoredEvent, accrueAllowed: Boolean) {
        val i = MonitoredApps.indexOf(e.pkg)
        if (i < 0) return
        when (e.type) {
            EventType.RESUMED -> {
                if (e.ts <= ownUiShownTs) return // re-delivered stale RESUMED: no effect
                val wasEmpty = resumed[i].isEmpty()
                resumed[i].add(e.activityKey)
                if (e.ts > latestResumed[i]) latestResumed[i] = e.ts
                if (wasEmpty) openSince[i] = maxOf(e.ts, cursorTs)
            }
            EventType.PAUSED, EventType.STOPPED -> {
                if (resumed[i].isEmpty()) return
                resumed[i].remove(e.activityKey)
                if (resumed[i].isEmpty()) {
                    val from = openSince[i]
                    if (accrueAllowed && from != null) accrue(i, from, e.ts)
                    openSince[i] = null
                    // Re-delivered overlap events (ts < cursorTs) are not a new session close.
                    if (e.ts >= cursorTs) sessionClosed = true
                    isDirty = true
                }
            }
        }
    }

    /** Adds [from, to) split at local midnights; a segment in a newer day resets the counters first. */
    private fun accrue(app: Int, from: Long, to: Long) {
        var s = from
        while (s < to) {
            val d = epochDay(s)
            val nextMidnight = LocalDate.ofEpochDay(d + 1).atStartOfDay(zone).toInstant().toEpochMilli()
            val e = minOf(to, nextMidnight)
            rolloverTo(d)
            used[app] += e - s
            isDirty = true
            s = e
        }
    }

    /** Reset only when [day] > dayKey; an earlier day (backward TZ change) keeps the counters. */
    private fun rolloverTo(day: Long) {
        if (day > dayKey) {
            dayKey = day
            for (i in 0 until n) used[i] = 0L
            isDirty = true
        }
    }

    private fun epochDay(ts: Long): Long = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate().toEpochDay()

    companion object {
        const val OVERLAP_MS = 2_000L
        const val REPLAY_LOOKBACK_MS = 6L * 60 * 60 * 1000

        /** Fresh state on arm: cursorTs = armedAtTs = now, empty sets, zero counters for today. */
        fun fresh(zone: ZoneId, now: Long): UsageAccumulator =
            UsageAccumulator(zone, Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay(), emptyList(), now)

        fun fromPersisted(zone: ZoneId, p: PersistedUsage): UsageAccumulator =
            UsageAccumulator(zone, p.dayKey, p.usedMs, p.cursorTs)
    }
}
