package app.focusgate.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Funtouch OS deep-link chains (DESIGN §15). Components are unverified on device; fallbacks always exist. */
object OemSettings {
    fun openAutostart(ctx: Context): Boolean = tryChain(
        ctx,
        listOf(
            component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            Permissions.appDetailsIntent(ctx),
        ),
    )

    fun openBackgroundPopups(ctx: Context): Boolean = tryChain(
        ctx,
        listOf(
            component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity")
                .putExtra("packagename", ctx.packageName),
            Permissions.appDetailsIntent(ctx),
        ),
    )

    fun openBackgroundPower(ctx: Context): Boolean = tryChain(
        ctx,
        listOf(
            component("com.vivo.abe", "com.vivo.abe.unifiedpower.HighPowerDetailActivity"),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Permissions.appDetailsIntent(ctx),
        ),
    )

    private fun component(pkg: String, cls: String): Intent = Intent().setComponent(ComponentName(pkg, cls))

    /** Tries each intent in order, then Settings.ACTION_SETTINGS. */
    private fun tryChain(ctx: Context, chain: List<Intent>): Boolean {
        for (i in chain + Intent(Settings.ACTION_SETTINGS)) {
            try {
                if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                return true
            } catch (e: Exception) {
                // try the next one
            }
        }
        return false
    }
}
