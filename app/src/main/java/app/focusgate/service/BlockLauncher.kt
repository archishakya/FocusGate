package app.focusgate.service

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.focusgate.core.Blocker
import app.focusgate.gate.DataGate
import app.focusgate.ui.BlockedActivity

/** Starts FocusGate's own Blocked screen (or Home) with a direct startActivity from the service context. */
class BlockLauncher(private val ctx: Context) : Blocker {
    private var lastLaunch = 0L

    override fun block(appId: Int): Boolean {
        if (!DataGate.isOpen()) return false
        val now = SystemClock.elapsedRealtime()
        if (BlockedActivity.isResumed || now - lastLaunch < DEBOUNCE_MS) return true
        lastLaunch = now
        return launch(
            Intent(ctx, BlockedActivity::class.java)
                .putExtra(BlockedActivity.EXTRA_APP_ID, appId)
                .addFlags(FLAGS),
        )
    }

    override fun goHome(): Boolean = launch(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    override fun launchTest(): Boolean {
        if (!DataGate.isOpen()) return false
        return launch(
            Intent(ctx, BlockedActivity::class.java)
                .putExtra(BlockedActivity.EXTRA_TEST, true)
                .addFlags(FLAGS),
        )
    }

    private fun launch(i: Intent): Boolean = try {
        ctx.startActivity(i)
        true
    } catch (e: android.content.ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    } catch (e: RuntimeException) {
        false
    }

    companion object {
        const val DEBOUNCE_MS = 1_500L
        private const val FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_NO_ANIMATION
    }
}
