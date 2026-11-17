package app.focusgate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoredAppsTest {
    @Test fun exactlyThreePackages() {
        assertEquals(
            listOf("com.instagram.android", "com.android.chrome", "com.google.android.youtube"),
            MonitoredApps.all.map { it.pkg },
        )
    }

    @Test fun indexMatchesAppId() {
        MonitoredApps.all.forEachIndexed { i, a ->
            assertEquals(i, a.appId)
            assertEquals(i, MonitoredApps.indexOf(a.pkg))
        }
    }

    @Test fun isMonitored() {
        assertTrue(MonitoredApps.isMonitored("com.android.chrome"))
        assertFalse(MonitoredApps.isMonitored("com.other.app"))
        assertFalse(MonitoredApps.isMonitored(null))
        assertEquals(-1, MonitoredApps.indexOf("com.other.app"))
    }
}
