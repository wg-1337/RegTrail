package io.github.wg1337.regtrail.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.wg1337.regtrail.platform.RegTrailPlatform

/**
 * Re-arms the module after a reboot.
 *
 * The system_server-side pieces (the overlay permission hook) are installed by
 * the framework when the module loads, so they need no help. What does need
 * re-publishing is the configuration snapshot: it lives under
 * `/data/local/tmp`, which Android clears on boot on most devices, and a target
 * app launched before the user opens RegTrail would otherwise see defaults.
 *
 * This receiver therefore just republishes the snapshot. It is intentionally
 * cheap and silent, and does nothing if root is unavailable.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        runCatching {
            val app = context.applicationContext as? io.github.wg1337.regtrail.RegTrailApplication
                ?: return
            // start() is idempotent and republishes the config; it is what the
            // Application already does on cold start.
            app.platform.publishConfig()
        }
    }
}
