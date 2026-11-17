package app.focusgate.core

/** Hand-rolled recording fakes (no mocking library). */

class FakeClock(var nowMs: Long, var elapsedMs: Long = 1_000_000L) : Clock {
    override fun now(): Long = nowMs
    override fun elapsed(): Long = elapsedMs
    fun advance(ms: Long) {
        nowMs += ms
        elapsedMs += ms
    }
}

/** Simulates the system event log; returns events with ts in [begin, end] and records every call. */
class FakeUsageReader : UsageReader {
    val log = mutableListOf<MonitoredEvent>()
    val calls = mutableListOf<Pair<Long, Long>>()
    var failNext = 0
    override fun read(begin: Long, end: Long): UsageRead {
        calls += begin to end
        if (failNext > 0) {
            failNext--
            return UsageRead.Failed
        }
        return UsageRead.Ok(log.filter { it.ts in begin..end })
    }
}

class FakeLocationReader : LocationReader {
    var startCalls = 0
    var stopCalls = 0
    var startResult = true
    override fun start(): Boolean {
        startCalls++
        return startResult
    }
    override fun stop() {
        stopCalls++
    }
}

class FakeBlocker : Blocker {
    val blocks = mutableListOf<Int>()
    var homeCalls = 0
    var testLaunches = 0
    var blockResult = true
    override fun block(appId: Int): Boolean {
        blocks += appId
        return blockResult
    }
    override fun goHome(): Boolean {
        homeCalls++
        return true
    }
    override fun launchTest(): Boolean {
        testLaunches++
        return true
    }
}

class FakePermissionProbe : PermissionProbe {
    var usageAllowed = true
    var precise = true
    var locationType = true
    var usageChecks = 0
    override fun usageAccessAllowed(): Boolean {
        usageChecks++
        return usageAllowed
    }
    override fun preciseLocationGranted(): Boolean = precise
    override fun fgsHasLocationType(): Boolean = locationType
}

class FakePresenceProbe : PresenceProbe {
    var main = false
    var blocked = false
    var lastOwnElapsed = 0L
    override fun mainResumed(): Boolean = main
    override fun blockedResumed(): Boolean = blocked
    override fun lastOwnActivityEventElapsed(): Long = lastOwnElapsed
}

class FakeConfigSource : ConfigSource {
    val allowances = intArrayOf(30, 30, 30)
    var zoneList: List<Zone> = emptyList()
    override fun allowanceMin(appId: Int): Int = allowances[appId]
    override fun zones(): List<Zone> = zoneList
}

/** Mutable gate for tests (production passes DataGate::isOpen / DataGate::armedAtTs). */
class FakeGate(var open: Boolean, var armedAt: Long) {
    fun isOpen(): Boolean = open
    fun armedAtTs(): Long = if (open) armedAt else Long.MAX_VALUE
}

class FakeServicePort(
    var gate: Boolean = true,
    var engine: Boolean = false,
    var tripOnRecord: Boolean = false,
) : ServicePort {
    val calls = mutableListOf<String>()
    var engineCreated = 0
    var unclean = 0
    var degradedState: Boolean? = null
    val startedTypes = mutableListOf<Set<FgsType>>()
    var full: Set<FgsType> = setOf(FgsType.SPECIAL_USE, FgsType.LOCATION)
    var degradedSet: Set<FgsType> = setOf(FgsType.SPECIAL_USE)

    /** Exceptions thrown by successive startForeground calls (null = success). */
    val throwQueue = ArrayDeque<Exception?>()

    override fun gateOpen(): Boolean = gate
    override fun hasEngine(): Boolean = engine
    override fun fullTypes(): Set<FgsType> = full
    override fun degradedTypes(): Set<FgsType> = degradedSet
    override fun startForeground(types: Set<FgsType>) {
        calls += "startForeground"
        startedTypes += types
        val ex = if (throwQueue.isEmpty()) null else throwQueue.removeFirst()
        if (ex != null) throw ex
    }
    override fun startForegroundMinimal() { calls += "startForegroundMinimal" }
    override fun stopForegroundRemove() { calls += "stopForegroundRemove" }
    override fun stopSelf() { calls += "stopSelf" }
    override fun stopSelf(startId: Int) { calls += "stopSelf($startId)" }
    override fun createEngine() {
        calls += "createEngine"
        engineCreated++
        engine = true
    }
    override fun disarm(reason: DisarmReason) {
        calls += "disarm($reason)"
        gate = false
    }
    override fun recordUncleanRestart(): Boolean {
        calls += "recordUncleanRestart"
        unclean++
        return tripOnRecord
    }
    override fun setDegraded(degraded: Boolean) {
        degradedState = degraded
    }
    override fun refreshNotification() { calls += "refreshNotification" }
    override fun engineTick() { calls += "engineTick" }
    override fun engineRequestTestBlock() { calls += "engineRequestTestBlock" }
    override fun engineFlagNotificationRepost() { calls += "engineFlagNotificationRepost" }
    override fun postPausedAlert() { calls += "postPausedAlert" }
}
