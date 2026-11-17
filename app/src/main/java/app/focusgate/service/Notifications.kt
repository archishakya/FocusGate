package app.focusgate.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.focusgate.R
import app.focusgate.core.AppState
import app.focusgate.core.EngineSnapshot
import app.focusgate.core.MonitoredApps
import app.focusgate.core.ServiceStart
import app.focusgate.system.Permissions
import app.focusgate.ui.MainActivity

object Notifications {
    const val CH_STATUS = "status"
    const val CH_ALERTS = "alerts"
    const val NOTIF_ID = 1
    private const val ID_CRASH = 2
    private const val ID_PAUSED = 3
    private const val ID_BLOCK_FAIL = 4
    private const val HOUR_MS = 3_600_000L

    @Volatile private var lastBlockFailAlert = 0L

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val status = NotificationChannel(CH_STATUS, "Status", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            setShowBadge(false)
        }
        val alerts = NotificationChannel(CH_ALERTS, "Alerts", NotificationManager.IMPORTANCE_DEFAULT)
        nm.createNotificationChannel(status)
        nm.createNotificationChannel(alerts)
    }

    private fun contentIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun panicIntent(ctx: Context): PendingIntent = PendingIntent.getService(
        ctx, 1,
        Intent(ctx, FocusService::class.java).setAction(ServiceStart.ACTION_PANIC),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Finding 2: explicit delete intent. */
    private fun deleteIntent(ctx: Context): PendingIntent = PendingIntent.getService(
        ctx, 2,
        Intent(ctx, FocusService::class.java).setAction(ServiceStart.ACTION_NOTIF_DISMISSED),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Status line, e.g. "Instagram 12 min · Chrome 40 min · YouTube time's up". No coordinates. */
    fun statusText(s: EngineSnapshot, degraded: Boolean): String {
        if (degraded) return "Open FocusGate to resume location checks"
        if (s.apps.isEmpty()) return "Watching your time limits"
        return s.apps.joinToString(" · ") { a ->
            val label = MonitoredApps.all[a.appId].label
            if (a.state == AppState.HAS_TIME) "$label ${(a.remainingMs + 59_999) / 60_000} min" else "$label time's up"
        }
    }

    fun buildStatus(ctx: Context, text: String): Notification {
        val public = NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("FocusGate is on")
            .build()
        return NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("FocusGate is on")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(contentIntent(ctx))
            .setDeleteIntent(deleteIntent(ctx))
            .addAction(0, "Disable now", panicIntent(ctx))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun buildMinimal(ctx: Context): Notification = NotificationCompat.Builder(ctx, CH_STATUS)
        .setSmallIcon(R.drawable.ic_stat_shield)
        .setContentTitle("FocusGate is off")
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    fun notifyStatus(ctx: Context, n: Notification) = post(ctx, NOTIF_ID, n)

    fun postCrashLoop(ctx: Context) = postAlert(
        ctx, ID_CRASH, "FocusGate turned itself off after repeated errors. Open to turn it back on.",
    )

    fun postPaused(ctx: Context) = postAlert(ctx, ID_PAUSED, "FocusGate paused. Tap to resume")

    /** At most once per hour. */
    fun postBlockLaunchFailed(ctx: Context, nowMs: Long) {
        if (nowMs - lastBlockFailAlert < HOUR_MS) return
        lastBlockFailAlert = nowMs
        postAlert(ctx, ID_BLOCK_FAIL, "FocusGate can't open the block screen. Tap to fix")
    }

    private fun postAlert(ctx: Context, id: Int, text: String) {
        val n = NotificationCompat.Builder(ctx, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("FocusGate")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent(ctx))
            .setAutoCancel(true)
            .build()
        post(ctx, id, n)
    }

    @SuppressLint("MissingPermission") // checked via Permissions.notificationsAllowed
    private fun post(ctx: Context, id: Int, n: Notification) {
        if (!Permissions.notificationsAllowed(ctx)) return
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (e: Exception) {
            // Notifications unavailable; fail quietly.
        }
    }
}
