package app.focusgate.core

import kotlin.math.abs

object BootCheck {
    const val EPOCH_TOLERANCE_MS = 120_000L

    /** DESIGN §9 rules, in order. A missing stored id means "not the same boot" (disarm). */
    fun isSameBoot(stored: BootId?, now: BootId): Boolean {
        if (stored == null) return false
        if (stored.bootCount != null && now.bootCount != null) return stored.bootCount == now.bootCount
        if (now.elapsedAtSave < stored.elapsedAtSave) return false
        return abs(now.bootEpochMs - stored.bootEpochMs) <= EPOCH_TOLERANCE_MS
    }
}
