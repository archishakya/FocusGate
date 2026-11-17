package app.focusgate.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.focusgate.core.MonitoredApps

/** The only PackageManager call site; install/label metadata for the monitored packages only, never usage. */
object InstalledApps {
    fun isInstalled(ctx: Context, pkg: String): Boolean {
        if (!MonitoredApps.isMonitored(pkg)) return false
        return try {
            ctx.packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    fun label(ctx: Context, pkg: String): String {
        val fallback = MonitoredApps.all.firstOrNull { it.pkg == pkg }?.label ?: return pkg
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            fallback
        }
    }

    fun launchIntent(ctx: Context, pkg: String): Intent? {
        if (!MonitoredApps.isMonitored(pkg)) return null
        return try {
            ctx.packageManager.getLaunchIntentForPackage(pkg)
        } catch (e: Exception) {
            null
        }
    }
}
