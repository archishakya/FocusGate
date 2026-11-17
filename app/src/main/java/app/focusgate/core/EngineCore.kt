package app.focusgate.core

import java.time.Instant
import java.time.ZoneId

/** The only usage data path into the engine (adapter over UsageEventSource). */
interface UsageReader {
    fun read(begin: Long, end: Long): UsageRead
}

/** The only location path into the engine (adapter over GpsSource). Readings come back via onLocationReading. */
interface LocationReader {
    /** Returns true if sampling started. */
    fun start(): Boolean
    fun stop()
}

interface Blocker {
    /** Launches the Blocked screen for [appId]; false on failure. */
    fun block(appId: Int): Boolean
    /** Home fallback; false on failure. */
    fun goHome(): Boolean
    /** "Test blocking" launch; false on failure. */
    fun launchTest(): Boolean
}

/** Permission/status checks only (not data reads). */
interface PermissionProbe {
    fun usageAccessAllowed(): Boolean
    fun preciseLocationGranted(): Boolean
    fun fgsHasLocationType(): Boolean
}

interface PresenceProbe {
    fun mainResumed(): Boolean
    fun blockedResumed(): Boolean
    /** elapsedRealtime of the last own-activity resume/finish. */
    fun lastOwnActivityEventElapsed(): Long
}

interface ConfigSource {
    fun allowanceMin(appId: Int): Int
    fun zones(): List<Zone>
}

interface Clock {
    /** Wall clock ms (same clock as UsageEvents timestamps). */
    fun now(): Long
    /** elapsedRealtime ms. */
    fun elapsed(): Long
}

/**
 * Pure engine tick logic (DESIGN §7 steps 1–5, findings 6 and 7). The Android Engine wraps it with
 * the HandlerThread, the screen receiver and the service. Every reader call is preceded by gateOpen().
 */
class EngineCore(
    private val gateOpen: () -> Boolean,
    private val armedAtTs: () -> Long,
    private val usage: UsageReader,
    private val location: LocationReader,
    private val blocker: Blocker,
    private val perms: PermissionProbe,
    private val presence: PresenceProbe,
    private val config: ConfigSource,
    private val clock: Clock,
    private val zoneId: ZoneId,
    initial: PersistedUsage,
    screenOn: Boolean = true,
) {
    private enum class ReadResult { OK, FAILED, REFUSED }

    private data class PendingVerify(val appId: Int, val at: Long, val homeTried: Boolean)

    private val n = MonitoredApps.all.size
    private var acc = UsageAccumulator.fromPersisted(zoneId, initial)
    private var pendingReplay = true
    private var screenOn = screenOn
    private var stopped = false

    private val evaluator = ZoneEvaluator()
    private var sampling = false
    private var lastAccuracy: Double? = null
    private var locationErrors = 0

    private var consecutiveErrors = 0
    private var blockLaunchHealthy = true
    private var pendingVerify: PendingVerify? = null

    private var test: TestBlockState = TestBlockState.Idle
    private var testLeftAt = 0L
    private var testFiredAt = 0L
    private var testWaitedS = 0

    private var repost = false
    private val feed = DecisionFeed()
    private val prevStates = arrayOfNulls<AppState>(n)
    private var prevZone = ZoneState.UNKNOWN
    private val prevRemaining = LongArray(n) { Long.MAX_VALUE }

    private var lastPersistTs = clock.now()
    private var persistRequested = false
    private var lastPermCheckTs = clock.now()

    /** Set by the Android Engine after a degraded sticky restart. */
    var locationDegraded: Boolean = false

    var snapshot: EngineSnapshot = EngineSnapshot(running = true)
        private set

    val isSampling: Boolean get() = sampling

    /** Current hysteresis streak (for diagnostics and tests). */
    val zoneStreak: Int get() = evaluator.streak

    fun tick(): TickOutcome {
        if (!gateOpen()) {
            stopped = true
            return TickOutcome.Continue(snapshot)
        }
        if (stopped || !screenOn) return TickOutcome.Continue(snapshot)
        val now = clock.now()

        val rr = try {
            readAndAdvance(now)
        } catch (t: Throwable) {
            ReadResult.FAILED
        }
        when (rr) {
            ReadResult.REFUSED -> return TickOutcome.Continue(snapshot)
            ReadResult.FAILED -> {
                if (!perms.usageAccessAllowed()) return TickOutcome.RequestDisarm(DisarmReason.PERMISSION_LOST)
                consecutiveErrors++
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) return TickOutcome.RequestDisarm(DisarmReason.CRASH_LOOP)
                val fg = acc.foregroundApp()
                snapshot = snapshot.copy(
                    foregroundAppId = if (fg >= 0) fg else null,
                    verdict = if (fg >= 0) Verdict(Decision.ALLOWED, Reason.ERROR_FAIL_OPEN) else null,
                    consecutiveErrors = consecutiveErrors,
                )
                return TickOutcome.Continue(snapshot)
            }
            ReadResult.OK -> consecutiveErrors = 0
        }

        if (now - lastPermCheckTs >= PERMISSION_CHECK_MS) {
            lastPermCheckTs = now
            if (!perms.usageAccessAllowed()) return TickOutcome.RequestDisarm(DisarmReason.PERMISSION_LOST)
        }

        return try {
            enforce(now)
        } catch (t: Throwable) {
            TickOutcome.Continue(snapshot) // fail open: skip enforcement for this tick
        }
    }

    private fun readAndAdvance(now: Long): ReadResult {
        if (pendingReplay) {
            // Finding 6: deferred to the first interactive tick; skipped when the window is empty.
            pendingReplay = false
            val b = acc.replayBegin(armedAtTs())
            if (b < acc.cursorTs) {
                if (!gateOpen()) return ReadResult.REFUSED
                when (val r = usage.read(b, acc.cursorTs)) {
                    is UsageRead.Ok -> acc.replayStateOnly(r.events)
                    UsageRead.Refused -> return ReadResult.REFUSED
                    UsageRead.Failed -> Unit // undercount (fail open)
                }
            }
        }
        if (!gateOpen()) return ReadResult.REFUSED
        return when (val r = usage.read(acc.queryBegin(armedAtTs()), now)) {
            is UsageRead.Ok -> {
                acc.advance(r.events, now)
                ReadResult.OK
            }
            UsageRead.Failed -> ReadResult.FAILED
            UsageRead.Refused -> ReadResult.REFUSED
        }
    }

    private fun enforce(now: Long): TickOutcome {
        val zones = config.zones()
        val hasZones = zones.isNotEmpty()
        val remaining = LongArray(n) { config.allowanceMin(it) * 60_000L - acc.usedMs(it) }
        for (i in 0 until n) {
            if (prevRemaining[i] > 0 && remaining[i] <= 0) persistRequested = true
            prevRemaining[i] = remaining[i]
        }
        if (acc.takeSessionClosed()) persistRequested = true

        // Step 2: need-location predicate. Warm start: sample once any app is within WARM_START_MS of its limit,
        // so a fix is already committed when time runs out. Never while the gate is closed (tick returns early).
        val need = screenOn && hasZones && perms.preciseLocationGranted() && perms.fgsHasLocationType() &&
            remaining.any { it <= WARM_START_MS }
        if (need && !sampling) {
            if (gateOpen() && location.start()) {
                sampling = true
                evaluator.start(now)
            }
        } else if (!need && sampling) {
            stopSampling(now)
        }
        val zone = if (sampling) evaluator.state(now) else ZoneState.UNKNOWN
        if (zone != prevZone) {
            feed.add(minuteOfDay(now), null, zoneMessage(zone))
            prevZone = zone
        }

        // Step 3: decide for the foreground monitored app that passes the own-UI guard.
        val fg = acc.foregroundApp()
        val guarded = fg >= 0 && acc.latestResumedTs(fg) > acc.ownUiShownTs
        var verdict: Verdict? = null
        if (guarded) {
            val v = safeDecide { remaining[fg] to zone }
            verdict = v
            if (v.decision == Decision.BLOCKED && pendingVerify == null && gateOpen()) {
                val ok = try {
                    blocker.block(fg)
                } catch (t: Throwable) {
                    false
                }
                if (ok) {
                    pendingVerify = PendingVerify(fg, now, homeTried = false)
                } else {
                    if (!safeHome()) blockLaunchHealthy = false
                    pendingVerify = PendingVerify(fg, now, homeTried = true)
                }
            }
        }
        val blockedFg = if (guarded && verdict?.decision == Decision.BLOCKED) fg else -1
        verifyBlock(now, blockedFg)
        runTest(now)

        // Step 4: snapshot + feed on per-app state changes only.
        val apps = (0 until n).map { i ->
            val st = appState(remaining[i], zone, hasZones, blockedFg == i)
            if (prevStates[i] != null && prevStates[i] != st) {
                feed.add(minuteOfDay(now), i, "${MonitoredApps.all[i].label}: ${stateMessage(st)}")
            }
            prevStates[i] = st
            AppSnapshot(i, acc.usedMs(i), remaining[i], st)
        }
        val rp = repost
        repost = false
        snapshot = EngineSnapshot(
            running = true,
            apps = apps,
            zone = zone,
            consecutive = evaluator.streak,
            lastAccuracyM = lastAccuracy,
            foregroundAppId = if (guarded) fg else null,
            verdict = verdict,
            locationDegraded = locationDegraded,
            blockLaunchHealthy = blockLaunchHealthy,
            locationErrors = locationErrors,
            consecutiveErrors = consecutiveErrors,
            feed = feed.entries(),
            test = test,
            repostNotification = rp,
        )
        return TickOutcome.Continue(snapshot)
    }

    /**
     * Pending block verify (DESIGN §8 step 3/4); uses this tick's foreground state, no extra query.
     * [blockedFg] is the guarded foreground app if it is still BLOCKED this tick, else -1.
     */
    private fun verifyBlock(now: Long, blockedFg: Int) {
        val pv = pendingVerify ?: return
        if (now - pv.at < VERIFY_DELAY_MS) return
        val stillThere = blockedFg == pv.appId && !presence.blockedResumed()
        if (!stillThere) {
            if (!pv.homeTried) blockLaunchHealthy = true
            pendingVerify = null
            return
        }
        if (!pv.homeTried) {
            safeHome()
            pendingVerify = pv.copy(at = now, homeTried = true)
        } else {
            blockLaunchHealthy = false
            pendingVerify = null
        }
    }

    private fun safeHome(): Boolean = try {
        gateOpen() && blocker.goHome()
    } catch (t: Throwable) {
        false
    }

    /** "Test blocking" state machine (DESIGN §13 self-test 1). */
    private fun runTest(now: Long) {
        when (test) {
            TestBlockState.Waiting -> when {
                presence.mainResumed() -> test = TestBlockState.Inconclusive
                now - testLeftAt >= TEST_MAX_WAIT_MS -> test = TestBlockState.Inconclusive
                now - testLeftAt >= TEST_MIN_DELAY_MS &&
                    clock.elapsed() - presence.lastOwnActivityEventElapsed() >= TEST_BAL_GRACE_MS -> {
                    val ok = try {
                        gateOpen() && blocker.launchTest()
                    } catch (t: Throwable) {
                        false
                    }
                    if (ok) {
                        test = TestBlockState.Fired
                        testFiredAt = now
                        testWaitedS = ((now - testLeftAt) / 1000).toInt()
                    } else {
                        test = TestBlockState.Failed
                    }
                }
            }
            TestBlockState.Fired -> when {
                presence.blockedResumed() -> test = TestBlockState.Passed(testWaitedS)
                now - testFiredAt >= TEST_FIRE_TIMEOUT_MS -> test = TestBlockState.Failed
            }
            else -> Unit
        }
    }

    fun requestTestBlock() {
        if (stopped) return
        test = TestBlockState.Pending
    }

    /** MainActivity.onStop: starts the test countdown. */
    fun onOwnUiStopped() {
        if (stopped) return
        if (test == TestBlockState.Pending) {
            testLeftAt = clock.now()
            test = TestBlockState.Waiting
        }
    }

    fun flagNotificationRepost() {
        repost = true
    }

    fun markOwnUiShown(ts: Long) {
        if (!stopped) acc.markOwnUiShown(ts)
    }

    /** Screen off: one final tick (accrues to now), then close all sessions, stop GPS, persist. */
    fun onScreenOff(): TickOutcome {
        val out = if (!stopped && screenOn && gateOpen()) tick() else TickOutcome.Continue(snapshot)
        screenOn = false
        acc.closeAllNoAccrue()
        if (sampling) stopSampling(clock.now())
        if (test == TestBlockState.Pending || test == TestBlockState.Waiting || test == TestBlockState.Fired) {
            test = TestBlockState.Inconclusive
        }
        pendingVerify = null
        persistRequested = true
        return out
    }

    /** Screen on re-sync (finding 7): no reader call here; the next tick queries. */
    fun onScreenOn(ts: Long) {
        if (stopped) return
        screenOn = true
        acc.onScreenOn(ts)
    }

    /** A GPS reading already reduced to (distances to each zone centre, accuracy). Dropped if the gate is closed. */
    fun onLocationReading(distancesM: List<Double>, accuracyM: Double, forZones: List<Zone>? = null) {
        if (!gateOpen() || stopped || !sampling) return
        try {
            val current = config.zones()
            // Never pair distances from one zone snapshot with radii from another.
            if (forZones != null && forZones != current) return
            lastAccuracy = accuracyM
            evaluator.onReading(current.map { it.radiusM }, distancesM, accuracyM, clock.now())
        } catch (t: Throwable) {
            locationErrors++
            evaluator.markUnavailable()
        }
    }

    /** Provider disabled / permission lost: UNKNOWN immediately. */
    fun onLocationUnavailable() {
        evaluator.markUnavailable()
    }

    /** Zones edited: drop state for the old set and restart an active session with the new zones. */
    fun onZonesChanged() {
        evaluator.clearCarryOver()
        evaluator.markUnavailable()
        if (stopped || !sampling) return
        val now = clock.now()
        location.stop()
        val stillNeeded = screenOn && config.zones().isNotEmpty()
        if (stillNeeded && gateOpen() && location.start()) {
            evaluator.start(now)
        } else {
            sampling = false
            evaluator.stop(now)
            evaluator.clearCarryOver()
        }
    }



    fun persistIfDue(): PersistedUsage? {
        if (stopped) return null
        val now = clock.now()
        val due = persistRequested || (acc.isDirty && now - lastPersistTs >= PERSIST_INTERVAL_MS)
        if (!due) return null
        persistRequested = false
        lastPersistTs = now
        return acc.toPersisted()
    }

    /** Teardown: returns the final counters (no query) and clears every in-memory state. */
    fun stop(): PersistedUsage {
        val p = acc.toPersisted()
        if (sampling) {
            sampling = false
            location.stop()
        }
        acc = UsageAccumulator(zoneId, p.dayKey, emptyList(), p.cursorTs)
        evaluator.reset()
        feed.clear()
        for (i in 0 until n) prevStates[i] = null
        test = TestBlockState.Idle
        pendingVerify = null
        pendingReplay = false
        lastAccuracy = null
        repost = false
        snapshot = EngineSnapshot.Idle
        stopped = true
        return p
    }

    private fun stopSampling(now: Long) {
        sampling = false
        evaluator.stop(now)
        location.stop()
    }

    private fun minuteOfDay(now: Long): Int {
        val t = Instant.ofEpochMilli(now).atZone(zoneId)
        return t.hour * 60 + t.minute
    }

    private fun zoneMessage(z: ZoneState): String = when (z) {
        ZoneState.INSIDE -> "Inside a zone (confirmed${accText()})"
        ZoneState.OUTSIDE -> "Outside your zones (confirmed${accText()})"
        ZoneState.UNKNOWN -> "Location unclear: allowing"
    }

    private fun accText(): String = lastAccuracy?.let { ", ±${it.toInt()} m" } ?: ""

    private fun stateMessage(s: AppState): String = when (s) {
        AppState.HAS_TIME -> "time left"
        AppState.TIMES_UP_OUTSIDE -> "time's up · open here (outside zones)"
        AppState.TIMES_UP_UNKNOWN -> "time's up · location unclear, staying open"
        AppState.TIMES_UP_NO_ZONES -> "time's up · no zones set, staying open"
        AppState.BLOCKED -> "blocked in a zone"
    }

    companion object {
        const val MAX_CONSECUTIVE_ERRORS = 10
        /** Start location sampling when any monitored app has this much time left (or less). */
        const val WARM_START_MS = 120_000L
        const val PERMISSION_CHECK_MS = 60_000L
        const val PERSIST_INTERVAL_MS = 15_000L
        const val VERIFY_DELAY_MS = 1_500L
        const val TEST_MIN_DELAY_MS = 5_000L
        const val TEST_BAL_GRACE_MS = 11_000L
        const val TEST_MAX_WAIT_MS = 60_000L
        const val TEST_FIRE_TIMEOUT_MS = 3_000L
    }
}
