package app.focusgate.core

enum class FgsType { SPECIAL_USE, LOCATION }

/** Foreground-service types per SDK (DESIGN §7). An empty set means the 2-argument startForeground. */
object ServiceTypes {
    private const val API_34 = 34

    fun compute(sdkInt: Int, preciseGranted: Boolean, hasZones: Boolean): Set<FgsType> {
        val loc = preciseGranted && hasZones
        return if (sdkInt >= API_34) {
            if (loc) setOf(FgsType.SPECIAL_USE, FgsType.LOCATION) else setOf(FgsType.SPECIAL_USE)
        } else {
            if (loc) setOf(FgsType.LOCATION) else emptySet()
        }
    }

    /** Fallback after a SecurityException on a sticky restart: no location type. */
    fun degraded(sdkInt: Int): Set<FgsType> = if (sdkInt >= API_34) setOf(FgsType.SPECIAL_USE) else emptySet()
}
