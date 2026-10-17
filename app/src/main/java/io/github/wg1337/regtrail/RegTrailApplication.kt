package io.github.wg1337.regtrail

import android.app.Application
import android.util.Log
import io.github.wg1337.regtrail.data.ThemeConfigStore
import io.github.wg1337.regtrail.platform.RegTrailPlatform
import io.github.wg1337.regtrail.ui.state.ConfigStore

/**
 * Application entry point.
 *
 * Owns the long-lived objects so that rotations and Activity recreation never
 * tear down the framework binding or the config publisher.
 */
class RegTrailApplication : Application() {

    lateinit var config: ConfigStore
        private set

    lateinit var platform: RegTrailPlatform
        private set

    override fun onCreate() {
        super.onCreate()

        // Log first, before anything that could throw: if startup dies we need to
        // know whether it got this far at all.
        Log.i(TAG, "RegTrailApplication.onCreate entered")

        val persistence = ThemeConfigStore(this)

        config = ConfigStore(
            initial = persistence.load(),
            onPersist = { persistence.save(it) },
        )
        Log.i(TAG, "config store ready")

        platform = RegTrailPlatform(this, persistence)
        Log.i(TAG, "platform constructed; starting")

        platform.start()
        Log.i(TAG, "platform.start() returned")
    }

    private companion object {
        const val TAG = "RegTrail"
    }
}
