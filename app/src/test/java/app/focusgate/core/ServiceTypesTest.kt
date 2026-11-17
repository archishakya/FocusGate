package app.focusgate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ServiceTypesTest {
    @Test fun api34_withLocation() {
        assertEquals(setOf(FgsType.SPECIAL_USE, FgsType.LOCATION), ServiceTypes.compute(34, preciseGranted = true, hasZones = true))
    }

    @Test fun api34_withoutLocation() {
        assertEquals(setOf(FgsType.SPECIAL_USE), ServiceTypes.compute(34, preciseGranted = false, hasZones = true))
        assertEquals(setOf(FgsType.SPECIAL_USE), ServiceTypes.compute(34, preciseGranted = true, hasZones = false))
        assertEquals(setOf(FgsType.SPECIAL_USE), ServiceTypes.degraded(34))
    }

    @Test fun api29to33_locationOrEmpty_neverSpecialUse() {
        for (sdk in 29..33) {
            assertEquals(setOf(FgsType.LOCATION), ServiceTypes.compute(sdk, true, true))
            assertEquals(emptySet<FgsType>(), ServiceTypes.compute(sdk, false, true))
            assertEquals(emptySet<FgsType>(), ServiceTypes.degraded(sdk))
            for (p in listOf(true, false)) for (z in listOf(true, false)) {
                assertFalse(FgsType.SPECIAL_USE in ServiceTypes.compute(sdk, p, z))
            }
        }
    }
}
