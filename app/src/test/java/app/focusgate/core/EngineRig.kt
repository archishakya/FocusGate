package app.focusgate.core

import java.time.Instant
import java.time.ZoneId

/** Test wiring for EngineCore with recording fakes. */
class EngineRig(
    igAllowanceMin: Int = 30,
    withZone: Boolean = true,
    gateOpen: Boolean = true,
    initialCursor: Long? = null,
    screenOn: Boolean = true,
) {
    val zone: ZoneId = ZoneId.of("UTC")
    val t0: Long = Instant.parse("2024-06-01T10:00:00Z").toEpochMilli()
    val clock = FakeClock(t0)
    val gate = FakeGate(gateOpen, t0)
    val usage = FakeUsageReader()
    val loc = FakeLocationReader()
    val blocker = FakeBlocker()
    val perms = FakePermissionProbe()
    val presence = FakePresenceProbe()
    val config = FakeConfigSource().also {
        it.allowances[0] = igAllowanceMin
        if (withZone) it.zoneList = listOf(Zone("z1", "Home", 48.0, 11.0, 5))
    }
    val core = EngineCore(
        gateOpen = { gate.isOpen() },
        armedAtTs = { gate.armedAtTs() },
        usage = usage,
        location = loc,
        blocker = blocker,
        perms = perms,
        presence = presence,
        config = config,
        clock = clock,
        zoneId = zone,
        initial = PersistedUsage(Instant.ofEpochMilli(t0).atZone(zone).toLocalDate().toEpochDay(), listOf(0L, 0L, 0L), initialCursor ?: t0),
        screenOn = screenOn,
    )

    fun step(ms: Long = 1_000): TickOutcome {
        clock.advance(ms)
        return core.tick()
    }

    fun inside() = core.onLocationReading(listOf(1.0), 3.0)
    fun outside() = core.onLocationReading(listOf(100.0), 3.0)

    fun resumed(pkg: String, ts: Long) {
        usage.log += MonitoredEvent(pkg, "Main", EventType.RESUMED, ts)
    }

    fun paused(pkg: String, ts: Long) {
        usage.log += MonitoredEvent(pkg, "Main", EventType.PAUSED, ts)
    }

    companion object {
        const val IG = "com.instagram.android"
    }
}
