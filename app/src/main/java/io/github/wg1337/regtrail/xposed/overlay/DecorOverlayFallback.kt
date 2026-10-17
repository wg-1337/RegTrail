package io.github.wg1337.regtrail.xposed.overlay

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.libxposed.api.XposedModule
import io.github.wg1337.regtrail.xposed.RegTrailLog
import io.github.wg1337.regtrail.xposed.debug.ActivityTracker

/**
 * The no-permission, no-framework fallback overlay.
 *
 * ## Why this exists
 * [OverlayController]'s preferred mechanism is a `TYPE_APPLICATION_OVERLAY`
 * window with the app-op gate bypassed by hooking system_server. That is the best
 * result — a top-level, application-global window above the system bars — but it
 * depends on two things the user may not have:
 *
 *  1. RegTrail in **system_server** scope, and
 *  2. a ROM whose `PhoneWindowManager.checkAddPermission` matches the shape we hook.
 *
 * When either is missing, the overlay would simply not appear. This class is the
 * answer: attach the same view into the **current Activity's content area** and
 * re-attach on every Activity that resumes. No permission, no framework internals,
 * nothing to bypass.
 *
 * ## What you give up
 * - The window sits *inside* the Activity window, so it is clipped by the system
 *   bars and hidden behind dialogs rather than floating above them.
 * - It is torn down and rebuilt as Activities change, so it must not hold state
 *   that matters (ours does not — the WebView is stateless apart from the config
 *   push, which is re-applied on attach).
 *
 * That trade is stated in the UI so the user understands which mechanism is live.
 */
internal class DecorOverlayFallback(
    private val module: XposedModule,
    private val assets: ModuleAssets,
) {

    private var currentContainer: FrameLayout? = null
    private var currentView: FloatingOverlayView? = null
    private var registered = false

    /** The activity the overlay is currently attached to. */
    private var hostActivity: Activity? = null

    @Volatile
    private var onMessage: ((String, String) -> String?)? = null

    fun install(app: Application, handler: (String, String) -> String?) {
        onMessage = handler
        if (registered) return
        registered = true

        app.registerActivityLifecycleCallbacks(object :
            Application.ActivityLifecycleCallbacks {

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // Deliberately NOT attaching here.
                //
                // `onActivityCreated` fires *before* the Activity inflates its
                // content, so a container added here is painted first and then
                // covered by the app's own `setContentView` view. The overlay was
                // attached (the log said so) yet invisible on screen — the app's
                // UI simply drew over it. Attaching on resume instead means the
                // content view already exists and our container is added last.
                RegTrailLog.d("activity created: ${activity.javaClass.name}")
            }

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) {
                // Resume is the earliest point at which the content view is
                // guaranteed to be populated. Re-attach when the Activity
                // changes, and bring to front if we are already present.
                if (hostActivity !== activity || currentView == null) {
                    attach(activity)
                } else {
                    currentContainer?.bringToFront()
                }
            }

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                if (hostActivity === activity) {
                    detach()
                    hostActivity = null
                }
            }
        })

        RegTrailLog.i("decor overlay fallback installed (no permission, no system_server hook)")
    }

    private fun attach(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: run {
            RegTrailLog.w("activity has no content view; cannot attach overlay")
            return
        }

        detach()
        hostActivity = activity

        val context: Context = activity
        val container = FrameLayout(context).apply {
            // The container is a sibling of the Activity's real content, so it
            // must not swallow touches meant for the app underneath.
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            isClickable = false
            isFocusable = false
            clipChildren = false
        }

        val view = FloatingOverlayView(
            context = context,
            assets = assets,
            bridge = JsBridge { action, payload -> onMessage?.invoke(action, payload) },
            onCollapsedChanged = { collapsed ->
                // Without a WindowManager there is no window to resize; the
                // WebView hides itself and the pill stays put.
                RegTrailLog.d("decor overlay collapsed=$collapsed")
            },
        )

        // Keep the overlay in the top-right corner, which is where it is least
        // likely to cover primary content.
        val params = FrameLayout.LayoutParams(
            dp(context, 300f),
            dp(context, 380f),
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.END
            topMargin = dp(context, 80f)
            marginEnd = dp(context, 8f)
        }

        container.addView(view, params)
        root.addView(container)

        // Belt and braces: if the Activity added content in the same frame, make
        // sure our container is the last child so it draws on top.
        container.bringToFront()

        currentContainer = container
        currentView = view

        RegTrailLog.d(
            "decor overlay attached to ${activity.javaClass.name} " +
                "container=${container.width}x${container.height}",
        )

        // Push state; the Activity may be the second or third one this process
        // has seen, and the fresh WebView knows nothing yet.
        ActivityTracker.register(activity.application)
    }

    private fun detach() {
        val container = currentContainer ?: return
        runCatching { (container.parent as? ViewGroup)?.removeView(container) }
        currentView?.destroy()
        currentContainer = null
        currentView = null
    }

    /** Pushes a config snapshot into the live view, if any. */
    fun applyConfig(configJson: String) {
        currentView?.applyConfig(configJson)
    }

    fun appendLog(line: String) {
        currentView?.appendLog(line)
    }

    val isAttached: Boolean get() = currentView != null

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    @Suppress("unused")
    private fun unusedViewType(v: View) = v
}
