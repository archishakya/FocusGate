package app.focusgate.core

data class MonitoredApp(val appId: Int, val pkg: String, val label: String)

object MonitoredApps {
    val all: List<MonitoredApp> = listOf(
        MonitoredApp(0, "com.instagram.android", "Instagram"),
        MonitoredApp(1, "com.android.chrome", "Chrome"),
        MonitoredApp(2, "com.google.android.youtube", "YouTube"),
    )

    /** Index (= appId) of the package, or -1 when not monitored. */
    fun indexOf(pkg: String?): Int = all.indexOfFirst { it.pkg == pkg }

    fun isMonitored(pkg: String?): Boolean = indexOf(pkg) >= 0
}
