package io.github.wg1337.regtrail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.wg1337.regtrail.ui.RegTrailApp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val app = application as RegTrailApplication

        setContent {
            RegTrailApp(
                store = app.config,
                platform = app.platform,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have changed the scope in the LSPosed manager while we
        // were backgrounded; re-read rather than trusting stale state.
        (application as RegTrailApplication).platform.refreshScope()
    }
}
