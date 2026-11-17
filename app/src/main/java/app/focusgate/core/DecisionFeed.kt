package app.focusgate.core

/** In-memory ring buffer of decision messages. Never persisted; never holds coordinates. */
class DecisionFeed(private val capacity: Int = CAPACITY) {
    private val buf = ArrayDeque<FeedEntry>()

    fun add(minuteOfDay: Int, appId: Int?, message: String) {
        buf.addLast(FeedEntry(minuteOfDay, appId, message))
        while (buf.size > capacity) buf.removeFirst()
    }

    fun entries(): List<FeedEntry> = buf.toList()

    val size: Int get() = buf.size

    fun clear() = buf.clear()

    companion object {
        const val CAPACITY = 50
    }
}
