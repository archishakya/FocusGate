package app.focusgate.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DecisionFeedTest {
    @Test fun capacity50_dropsOldest_andClears() {
        val f = DecisionFeed()
        for (k in 0 until 60) f.add(k, 0, "m$k")
        assertEquals(50, f.size)
        assertEquals("m10", f.entries().first().message)
        assertEquals("m59", f.entries().last().message)
        f.clear()
        assertEquals(0, f.entries().size)
    }
}
