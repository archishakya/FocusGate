package app.focusgate.core

/** Pure zone validation and per-zone accuracy thresholds (DESIGN §6). */
object ZoneRules {
    const val NAME_MAX = 24
    const val RADIUS_MIN = 5
    const val RADIUS_MAX = 200
    const val RADIUS_DEFAULT = 10
    /** Below this radius the editor warns that indoor GPS may be unreliable. */
    const val RADIUS_WARN_BELOW = 10
    const val MAX_ZONES = 10

    /** A d <= r reading counts as INSIDE when accuracy is at most this (or maxAccuracyM, if larger). */
    const val INSIDE_MAX_ACCURACY_M = 30.0

    /** Trimmed name if 1–24 characters, else null. */
    fun validName(raw: String): String? = raw.trim().takeIf { it.length in 1..NAME_MAX }

    fun isValidRadius(r: Int): Boolean = r in RADIUS_MIN..RADIUS_MAX

    fun clampRadius(r: Int): Int = r.coerceIn(RADIUS_MIN, RADIUS_MAX)

    fun isValidCoords(lat: Double, lon: Double): Boolean =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

    fun isValid(z: Zone): Boolean =
        z.id.isNotEmpty() && validName(z.name) == z.name && isValidRadius(z.radiusM) && isValidCoords(z.lat, z.lon)

    /**
     * Raises radii saved under an older, lower minimum (3–4 m) to [RADIUS_MIN] so those zones are kept,
     * then drops invalid entries and keeps at most [MAX_ZONES].
     */
    fun sanitize(zones: List<Zone>): List<Zone> =
        zones.map { if (it.radiusM in 1 until RADIUS_MIN) it.copy(radiusM = RADIUS_MIN) else it }
            .filter { isValid(it) }.take(MAX_ZONES)

    fun canAdd(current: List<Zone>): Boolean = current.size < MAX_ZONES

    fun radiusWarning(r: Int): Boolean = r < RADIUS_WARN_BELOW

    /** clamp(max(20, 2 × radius), 20, 100) metres. */
    fun maxAccuracyM(radiusM: Int): Double = maxOf(20.0, 2.0 * radiusM).coerceIn(20.0, 100.0)

    /** Accuracy limit for an INSIDE (d <= r) reading: max(30, maxAccuracyM). */
    fun insideMaxAccuracyM(radiusM: Int): Double = maxOf(INSIDE_MAX_ACCURACY_M, maxAccuracyM(radiusM))

    /** max(3, 0.3 × radius) metres. */
    fun exitMargin(radiusM: Int): Double = maxOf(3.0, 0.3 * radiusM)
}
