package app.focusgate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneSettlerTest {
    private val lat = 48.0
    private val lon = 11.0

    @Test fun locksWithThreeFixesAt15m() {
        val s = ZoneSettler(0)
        assertNull(s.onFix(lat, lon, 15.0, 0))
        assertNull(s.onFix(lat, lon, 15.0, 2500))
        val c = s.onFix(lat, lon, 15.0, 5000)
        assertNotNull(c)
        assertEquals(15.0, c!!.meanAccuracyM, 1e-9)
        assertEquals(3, s.steadyCount)
    }

    @Test fun doesNotSettle_beforeFiveSeconds() {
        val s = ZoneSettler(0)
        for (k in 0..4) s.onFix(lat, lon, 5.0, k * 1000L)
        assertNull(s.settled)
        assertNotNull(s.onFix(lat, lon, 5.0, 5000))
    }

    @Test fun doesNotSettle_above20m() {
        val s = ZoneSettler(0)
        for (k in 0..9) s.onFix(lat, lon, 21.0, k * 2000L)
        assertNull(s.settled)
    }

    @Test fun doesNotSettle_whenSpreadLarge() {
        val s = ZoneSettler(0)
        for (k in 0..9) s.onFix(lat + if (k % 2 == 0) 0.0 else 4e-4, lon, 5.0, k * 3000L)
        assertNull(s.settled)
    }

    @Test fun timesOut_after90s() {
        val s = ZoneSettler(0)
        for (k in 0..30) s.onFix(lat, lon, 30.0, k * 3000L)
        assertNull(s.settled)
        assertFalse(s.timedOut(89_999))
        assertTrue(s.timedOut(90_000))
        assertTrue(s.canSaveAnyway())
        assertEquals(30, s.suggestedRadius(5))
    }

    @Test fun weightedMean_usesInverseSquareAccuracy() {
        val s = ZoneSettler(0)
        s.onFix(0.0, 0.0, 1.0, 0)
        s.onFix(1e-4, 0.0, 2.0, 1000)
        val c = s.currentCenter()!!
        assertEquals(2e-5, c.lat, 1e-12)
        assertEquals(0.0, c.lon, 1e-12)
    }
}
