package app.focusgate.system

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Own permission/status checks only (finding 4 allow-list); never a data read. Safe while disarmed.
 */
object Permissions {
    fun usageAccessAllowed(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        ops != null && ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }

    fun notificationsAllowed(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    fun preciseLocationGranted(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun coarseOnly(ctx: Context): Boolean = !preciseLocationGranted(ctx) &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun canDrawOverlays(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /** Global location switch state; no location data. */
    fun locationEnabled(ctx: Context): Boolean = try {
        val lm = ctx.getSystemService(LocationManager::class.java)
        lm != null && lm.isLocationEnabled
    } catch (e: Exception) {
        false
    }

    fun ignoringBatteryOptimizations(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(PowerManager::class.java)
        pm != null && pm.isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (e: Exception) {
        false
    }

    // ---- settings intents (all start activities; caller wraps in try/catch) ----

    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun appNotificationSettingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)

    fun overlayIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))

    fun locationSourceIntent(): Intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

    fun batteryListIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun appDetailsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
}
