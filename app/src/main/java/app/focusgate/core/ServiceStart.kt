package app.focusgate.core

enum class StartReturn { STICKY, NOT_STICKY }

/** Everything ServiceStart needs from FocusService. FocusService is a thin adapter over this. */
interface ServicePort {
    fun gateOpen(): Boolean
    fun hasEngine(): Boolean
    fun fullTypes(): Set<FgsType>
    fun degradedTypes(): Set<FgsType>

    /** May throw SecurityException or any other exception. */
    fun startForeground(types: Set<FgsType>)

    /** startForeground with the minimal "FocusGate is off" notification. */
    fun startForegroundMinimal()
    fun stopForegroundRemove()
    fun stopSelf()
    fun stopSelf(startId: Int)
    fun createEngine()
    fun disarm(reason: DisarmReason)

    /** Records the unclean restart (at most once per instance); returns true if the crash-loop guard tripped. */
    fun recordUncleanRestart(): Boolean
    fun setDegraded(degraded: Boolean)
    fun refreshNotification()
    fun engineTick()
    fun engineRequestTestBlock()
    fun engineFlagNotificationRepost()
    fun postPausedAlert()
}

/** Pure onStartCommand dispatch (DESIGN §7 table + findings 3 and 8). */
object ServiceStart {
    const val ACTION_START = "app.focusgate.action.START"
    const val ACTION_PANIC = "app.focusgate.action.PANIC"
    const val ACTION_TEST_BLOCK = "app.focusgate.action.TEST_BLOCK"
    const val ACTION_NOTIF_DISMISSED = "app.focusgate.action.NOTIF_DISMISSED"

    fun handle(action: String?, startId: Int, port: ServicePort): StartReturn = when (action) {
        ACTION_PANIC -> {
            port.disarm(DisarmReason.PANIC)
            port.stopForegroundRemove()
            port.stopSelf()
            StartReturn.NOT_STICKY
        }
        ACTION_START ->
            if (port.gateOpen()) promote(port, sticky = false)
            else {
                try {
                    port.startForegroundMinimal()
                } catch (e: Exception) {
                    // Nothing to do: we stop anyway.
                }
                port.stopForegroundRemove()
                port.stopSelf()
                StartReturn.NOT_STICKY
            }
        null ->
            if (!port.gateOpen()) {
                port.stopSelf()
                StartReturn.NOT_STICKY
            } else if (port.recordUncleanRestart()) {
                port.disarm(DisarmReason.CRASH_LOOP)
                port.stopSelf()
                StartReturn.NOT_STICKY
            } else {
                promote(port, sticky = true)
            }
        ACTION_TEST_BLOCK -> engineOnly(startId, port) { port.engineRequestTestBlock() }
        ACTION_NOTIF_DISMISSED -> engineOnly(startId, port) { port.engineFlagNotificationRepost() }
        else -> engineOnly(startId, port) { }
    }

    /** Finding 8: with no running engine the stray instance stops itself and is never sticky. */
    private inline fun engineOnly(startId: Int, port: ServicePort, block: () -> Unit): StartReturn {
        if (!port.hasEngine()) {
            port.stopSelf(startId)
            return StartReturn.NOT_STICKY
        }
        if (port.gateOpen()) block()
        return StartReturn.STICKY
    }

    private fun promote(port: ServicePort, sticky: Boolean): StartReturn {
        if (port.hasEngine()) {
            // Finding 3: re-promote an existing (possibly degraded) engine; never a second engine.
            try {
                port.startForeground(port.fullTypes())
                port.setDegraded(false)
                port.refreshNotification()
            } catch (e: SecurityException) {
                // Stay degraded.
            } catch (e: Exception) {
                // Keep running with the current types.
            }
            port.engineTick()
            return StartReturn.STICKY
        }
        try {
            port.startForeground(port.fullTypes())
            port.setDegraded(false)
        } catch (e: SecurityException) {
            try {
                port.startForeground(port.degradedTypes())
                port.setDegraded(true)
            } catch (e2: Exception) {
                return failStart(port, sticky)
            }
        } catch (e: Exception) {
            return failStart(port, sticky)
        }
        // Only after startForeground returned successfully.
        port.createEngine()
        return StartReturn.STICKY
    }

    /** Could not become foreground: stop without disarming (the armed flag persists). */
    private fun failStart(port: ServicePort, sticky: Boolean): StartReturn {
        port.stopSelf()
        if (sticky) port.postPausedAlert()
        return StartReturn.NOT_STICKY
    }
}
