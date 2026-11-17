package app.focusgate.ui

/** Own-activity presence, read by the engine thread. */
object UiPresence {
    @Volatile var mainResumed: Boolean = false
    @Volatile var blockedResumed: Boolean = false
    @Volatile var lastOwnActivityEventElapsed: Long = 0L
}
