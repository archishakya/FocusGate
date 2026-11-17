package app.focusgate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneRulesTest {
    private fun z(name: String = "Home", lat: Double = 10.0, lon: Double = 20.0, r: Int = 5, id: String = "a") =
        Zone(id, name, lat, lon, r)

    @Test fun name_trimmedAndLength() {
        assertEquals("Home", ZoneRules.validName("  Home "))
        assertNull(ZoneRules.validName("   "))
        assertNull(ZoneRules.validName("x".repeat(25)))
        assertEquals("x".repeat(24), ZoneRules.validName("x".repeat(24)))
    }

    @Test fun radius_range() {
        assertFalse(ZoneRules.isValidRadius(4))
        assertTrue(ZoneRules.isValidRadius(5))
        assertTrue(ZoneRules.isValidRadius(200))
        assertFalse(ZoneRules.isValidRadius(201))
        assertEquals(10, ZoneRules.RADIUS_DEFAULT)
        assertEquals(200, ZoneRules.clampRadius(500))
        assertEquals(5, ZoneRules.clampRadius(3))
    }

    @Test fun radiusWarning_below10() {
        assertTrue(ZoneRules.radiusWarning(9))
        assertFalse(ZoneRules.radiusWarning(10))
    }

    @Test fun sanitize_keepsLegacySmallZones_raisedToMinimum() {
        val out = ZoneRules.sanitize(listOf(z(r = 3)))
        assertEquals(1, out.size)
        assertEquals(ZoneRules.RADIUS_MIN, out[0].radiusM)
    }

    @Test fun coords() {
        assertTrue(ZoneRules.isValid(z()))
        assertFalse(ZoneRules.isValid(z(lat = 91.0)))
        assertFalse(ZoneRules.isValid(z(lon = -181.0)))
        assertFalse(ZoneRules.isValid(z(lat = Double.NaN)))
        assertFalse(ZoneRules.isValid(z(lon = Double.POSITIVE_INFINITY)))
    }

    @Test fun sanitize_dropsInvalid_andCapsAtTen() {
        val many = (1..12).map { z(id = "id$it") } + z(name = "")
        assertEquals(10, ZoneRules.sanitize(many).size)
        assertFalse(ZoneRules.canAdd(ZoneRules.sanitize(many)))
    }

    @Test fun haversine_roughlyCorrect() {
        // 1e-4 degrees of latitude is about 11.1 m.
        assertEquals(11.12, Geo.distanceM(0.0, 0.0, 1e-4, 0.0), 0.05)
    }
}
