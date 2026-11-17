package app.focusgate.core

/** UNKNOWN = no zones / no permission / no fix / stale / inaccurate. */
enum class ZoneState { INSIDE, OUTSIDE, UNKNOWN }

enum class Decision { ALLOWED, BLOCKED }

enum class Reason { TIME_LEFT, LIMIT_INSIDE_ZONE, LIMIT_OUTSIDE_ZONE, LIMIT_LOCATION_UNKNOWN, ERROR_FAIL_OPEN }

data class Verdict(val decision: Decision, val reason: Reason)

/** Per-app status shown in the UI (design-review finding 10 adds TIMES_UP_NO_ZONES). */
enum class AppState { HAS_TIME, TIMES_UP_OUTSIDE, TIMES_UP_UNKNOWN, TIMES_UP_NO_ZONES, BLOCKED }

enum class EventType { RESUMED, PAUSED, STOPPED }

/** A usage event of a monitored package only. [activityKey] lives in memory only. */
data class MonitoredEvent(val pkg: String, val activityKey: String, val type: EventType, val ts: Long)

sealed class UsageRead {
    data class Ok(val events: List<MonitoredEvent>) : UsageRead()
    object Failed : UsageRead()
    object Refused : UsageRead()
}

/** A restricted zone. The centre is the only coordinate FocusGate ever stores. */
data class Zone(val id: String, val name: String, val lat: Double, val lon: Double, val radiusM: Int)

enum class DisarmReason { USER, PANIC, REBOOT, CRASH_LOOP, PERMISSION_LOST, NEVER_ARMED }

sealed class ArmState {
    data class Armed(val bootCount: Int?, val bootEpochMs: Long, val armedAtTs: Long) : ArmState()
    data class Disarmed(val reason: DisarmReason) : ArmState()
}

data class BootId(val bootCount: Int?, val bootEpochMs: Long, val elapsedAtSave: Long)

sealed class TestBlockState {
    object Idle : TestBlockState()
    object Pending : TestBlockState()
    object Waiting : TestBlockState()
    object Fired : TestBlockState()
    data class Passed(val waitedS: Int) : TestBlockState()
    object Failed : TestBlockState()
    object Inconclusive : TestBlockState()
}

/** Decision Feed entry. Never contains coordinates or distances. */
data class FeedEntry(val minuteOfDay: Int, val appId: Int?, val message: String)

data class AppSnapshot(val appId: Int, val usedMs: Long, val remainingMs: Long, val state: AppState)

/** Published by the engine; contains no coordinates (only the last accuracy). */
data class EngineSnapshot(
    val running: Boolean = false,
    val apps: List<AppSnapshot> = emptyList(),
    val zone: ZoneState = ZoneState.UNKNOWN,
    val consecutive: Int = 0,
    val lastAccuracyM: Double? = null,
    val foregroundAppId: Int? = null,
    val verdict: Verdict? = null,
    val locationDegraded: Boolean = false,
    val blockLaunchHealthy: Boolean = true,
    val locationErrors: Int = 0,
    val consecutiveErrors: Int = 0,
    val feed: List<FeedEntry> = emptyList(),
    val test: TestBlockState = TestBlockState.Idle,
    val repostNotification: Boolean = false,
) {
    companion object {
        val Idle = EngineSnapshot()
    }
}

sealed class TickOutcome {
    data class Continue(val snapshot: EngineSnapshot) : TickOutcome()
    data class RequestDisarm(val reason: DisarmReason) : TickOutcome()
}

/** usedMs is indexed by MonitoredApp.appId. */
data class PersistedUsage(val dayKey: Long, val usedMs: List<Long>, val cursorTs: Long)
