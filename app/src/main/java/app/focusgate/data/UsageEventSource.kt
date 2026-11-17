package app.focusgate.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import app.focusgate.core.EventType
import app.focusgate.core.MonitoredApps
import app.focusgate.core.MonitoredEvent
import app.focusgate.core.UsageRead
import app.focusgate.gate.DataGate

/** The only UsageStatsManager call site. */
class UsageEventSource(private val ctx: Context) {
    fun read(begin: Long, end: Long): UsageRead {
        if (!DataGate.isOpen() || begin < DataGate.armedAtTs()) return UsageRead.Refused
        return try {
            val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return UsageRead.Failed
            val events = usm.queryEvents(begin, end) ?: return UsageRead.Failed
            val out = ArrayList<MonitoredEvent>()
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                val pkg = e.packageName
                if (!MonitoredApps.isMonitored(pkg)) continue // nothing else is read for other apps
                val type = when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> EventType.RESUMED
                    UsageEvents.Event.ACTIVITY_PAUSED -> EventType.PAUSED
                    UsageEvents.Event.ACTIVITY_STOPPED -> EventType.STOPPED
                    else -> continue
                }
                out += MonitoredEvent(pkg, e.className ?: "", type, e.timeStamp)
            }
            UsageRead.Ok(out)
        } catch (t: Exception) {
            UsageRead.Failed
        }
    }
}
