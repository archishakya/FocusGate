package app.focusgate.core

/** Restart-based crash-loop guard (DESIGN §11c). Pure; the list is persisted as a string by Store. */
object CrashLoopGuard {
    const val MAX_ENTRIES = 5
    const val WINDOW_MS = 10L * 60 * 1000
    const val TRIP_COUNT = 3

    fun record(list: List<Long>, now: Long): List<Long> = (list + now).takeLast(MAX_ENTRIES)

    fun isTripped(list: List<Long>, now: Long): Boolean =
        list.count { now - it in 0..WINDOW_MS } >= TRIP_COUNT

    /** A user arm clears the list. */
    fun cleared(): List<Long> = emptyList()

    fun encode(list: List<Long>): String = list.joinToString(",")

    fun decode(s: String?): List<Long> =
        s.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.takeLast(MAX_ENTRIES)
}

/** Records at most one unclean restart per service instance (in-memory once flag). */
class RestartRecorder {
    var recorded: Boolean = false
        private set

    /** Returns the new list; a second signal on the same instance leaves it unchanged. */
    fun record(list: List<Long>, now: Long): List<Long> {
        if (recorded) return list
        recorded = true
        return CrashLoopGuard.record(list, now)
    }
}
