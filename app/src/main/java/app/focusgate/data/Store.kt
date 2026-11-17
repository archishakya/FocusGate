package app.focusgate.data

import android.content.Context
import android.content.SharedPreferences
import app.focusgate.core.ArmState
import app.focusgate.core.BootId
import app.focusgate.core.CrashLoopGuard
import app.focusgate.core.DisarmReason
import app.focusgate.core.MonitoredApps
import app.focusgate.core.PersistedUsage
import app.focusgate.core.Zone
import app.focusgate.core.ZoneRules
import org.json.JSONArray
import org.json.JSONObject

/** The single private SharedPreferences file `fg_store` holding exactly the DESIGN §12 keys. */
object Store {
    private const val FILE = "fg_store"
    const val DEFAULT_ALLOWANCE_MIN = 30

    private const val K_ALLOWANCE = "allowance_min_"
    private const val K_USED = "used_ms_"
    private const val K_DAY = "day_key"
    private const val K_CURSOR = "cursor_ts"
    private const val K_ZONES = "zones"
    private const val K_ARMED = "armed"
    private const val K_REASON = "disarm_reason"
    private const val K_BOOT_COUNT = "boot_count"
    private const val K_BOOT_EPOCH = "boot_epoch_ms"
    private const val K_ELAPSED = "elapsed_at_save"
    private const val K_ARMED_AT = "armed_at_ts"
    private const val K_RESTARTS = "restart_ts"
    private const val K_ALIVE = "service_alive"
    private const val K_UI_MODE = "ui_mode"
    private const val K_THEME = "theme"
    private const val K_FEED = "feed_visible"
    private const val K_ONBOARDING = "onboarding_done"
    private const val K_CRASH_BANNER = "crash_banner"

    private lateinit var prefs: SharedPreferences

    /** Set when the stored zones JSON failed to parse or contained invalid entries. */
    @Volatile var zonesCorrupt: Boolean = false
        private set

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) prefs = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    // ---- allowances / usage ----

    fun allowanceMin(appId: Int): Int = prefs.getInt(K_ALLOWANCE + appId, DEFAULT_ALLOWANCE_MIN)

    fun setAllowanceMin(appId: Int, min: Int) {
        prefs.edit().putInt(K_ALLOWANCE + appId, min.coerceIn(0, 600)).apply()
    }

    fun readUsage(): PersistedUsage = PersistedUsage(
        dayKey = prefs.getLong(K_DAY, Long.MIN_VALUE),
        usedMs = MonitoredApps.all.map { prefs.getLong(K_USED + it.appId, 0L) },
        cursorTs = prefs.getLong(K_CURSOR, 0L),
    )

    /** Counters + cursor in one Editor (apply). */
    fun writeUsage(p: PersistedUsage, elapsedNow: Long? = null) {
        val e = prefs.edit()
        putCounters(e, p)
        e.putLong(K_CURSOR, p.cursorTs)
        if (elapsedNow != null) e.putLong(K_ELAPSED, elapsedNow)
        e.apply()
    }

    /** Finding 5: final persist on engine stop removes cursor_ts and armed_at_ts in the same Editor. */
    fun writeUsageAndClearCursor(p: PersistedUsage) {
        val e = prefs.edit()
        putCounters(e, p)
        e.remove(K_CURSOR).remove(K_ARMED_AT)
        e.apply()
    }

    /** Disarmed-only display rollover write (counters reset for a new day). */
    fun resetCounters(dayKey: Long) {
        val e = prefs.edit()
        e.putLong(K_DAY, dayKey)
        for (a in MonitoredApps.all) e.putLong(K_USED + a.appId, 0L)
        e.apply()
    }

    private fun putCounters(e: SharedPreferences.Editor, p: PersistedUsage) {
        e.putLong(K_DAY, p.dayKey)
        for (a in MonitoredApps.all) e.putLong(K_USED + a.appId, p.usedMs.getOrElse(a.appId) { 0L })
    }

    // ---- zones ----

    fun zones(): List<Zone> {
        val raw = prefs.getString(K_ZONES, null) ?: run {
            zonesCorrupt = false
            return emptyList()
        }
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<Zone>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += Zone(o.getString("id"), o.getString("name"), o.getDouble("lat"), o.getDouble("lon"), o.getInt("r"))
            }
            val clean = ZoneRules.sanitize(list)
            zonesCorrupt = clean.size != list.size
            clean
        } catch (e: Exception) {
            zonesCorrupt = true
            emptyList()
        }
    }

    fun setZones(zones: List<Zone>) {
        val arr = JSONArray()
        for (z in ZoneRules.sanitize(zones)) {
            arr.put(JSONObject().put("id", z.id).put("name", z.name).put("lat", z.lat).put("lon", z.lon).put("r", z.radiusM))
        }
        prefs.edit().putString(K_ZONES, arr.toString()).apply()
        zonesCorrupt = false
    }

    // ---- arm state (commit) ----

    fun armState(): ArmState {
        if (prefs.getBoolean(K_ARMED, false)) {
            return ArmState.Armed(
                bootCount = if (prefs.contains(K_BOOT_COUNT)) prefs.getInt(K_BOOT_COUNT, 0) else null,
                bootEpochMs = prefs.getLong(K_BOOT_EPOCH, 0L),
                armedAtTs = prefs.getLong(K_ARMED_AT, 0L),
            )
        }
        val r = prefs.getString(K_REASON, null)
        val reason = DisarmReason.entries.firstOrNull { it.name == r } ?: DisarmReason.NEVER_ARMED
        return ArmState.Disarmed(reason)
    }

    fun bootId(): BootId? {
        if (!prefs.contains(K_BOOT_EPOCH) || !prefs.contains(K_ELAPSED)) return null
        return BootId(
            bootCount = if (prefs.contains(K_BOOT_COUNT)) prefs.getInt(K_BOOT_COUNT, 0) else null,
            bootEpochMs = prefs.getLong(K_BOOT_EPOCH, 0L),
            elapsedAtSave = prefs.getLong(K_ELAPSED, 0L),
        )
    }

    /** Arm: armed flag, boot id, armed_at_ts = cursor_ts = now, cleared restart list. Synchronous commit. */
    fun commitArmed(boot: BootId, now: Long): Boolean {
        val e = prefs.edit()
        e.putBoolean(K_ARMED, true).remove(K_REASON)
        if (boot.bootCount != null) e.putInt(K_BOOT_COUNT, boot.bootCount) else e.remove(K_BOOT_COUNT)
        e.putLong(K_BOOT_EPOCH, boot.bootEpochMs).putLong(K_ELAPSED, boot.elapsedAtSave)
        e.putLong(K_ARMED_AT, now).putLong(K_CURSOR, now)
        e.putString(K_RESTARTS, CrashLoopGuard.encode(CrashLoopGuard.cleared()))
        return e.commit()
    }

    fun commitDisarmed(reason: DisarmReason): Boolean =
        prefs.edit().putBoolean(K_ARMED, false).putString(K_REASON, reason.name).commit()

    // ---- crash-loop / service marker ----

    fun restarts(): List<Long> = CrashLoopGuard.decode(prefs.getString(K_RESTARTS, null))

    fun setRestarts(list: List<Long>) {
        prefs.edit().putString(K_RESTARTS, CrashLoopGuard.encode(list)).commit()
    }

    fun serviceAlive(): Boolean = prefs.getBoolean(K_ALIVE, false)

    fun setServiceAlive(alive: Boolean) {
        prefs.edit().putBoolean(K_ALIVE, alive).commit()
    }

    // ---- UI flags ----

    fun uiMode(): String = prefs.getString(K_UI_MODE, "ESSENTIALS") ?: "ESSENTIALS"
    fun setUiMode(v: String) = prefs.edit().putString(K_UI_MODE, v).apply()
    fun theme(): String = prefs.getString(K_THEME, "SYSTEM") ?: "SYSTEM"
    fun setTheme(v: String) = prefs.edit().putString(K_THEME, v).apply()
    fun feedVisible(): Boolean = prefs.getBoolean(K_FEED, false)
    fun setFeedVisible(v: Boolean) = prefs.edit().putBoolean(K_FEED, v).apply()
    fun onboardingDone(): Boolean = prefs.getBoolean(K_ONBOARDING, false)
    fun setOnboardingDone(v: Boolean) = prefs.edit().putBoolean(K_ONBOARDING, v).apply()
    fun crashBanner(): Boolean = prefs.getBoolean(K_CRASH_BANNER, false)
    fun setCrashBanner(v: Boolean) = prefs.edit().putBoolean(K_CRASH_BANNER, v).apply()
}
