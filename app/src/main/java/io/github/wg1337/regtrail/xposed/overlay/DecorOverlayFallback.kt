package io.github.wg1337.regtrail.xposed.overlay

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.libxposed.api.XposedModule
import io.github.wg1337.regtrail.shared.model.OverlaySizeLimits
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
 * - It is torn down and rebuilt as Activities change, so the *view* must not be
 *   the source of truth for anything. The only state this class carries is the
 *   panel size and its position, and both are re-applied to every freshly
 *   attached view — which is why a drag survives an Activity change. The persisted
 *   copy of the size lives outside the process (see `OverlaySizeStore`).
 *
 * That trade is stated in the UI so the user understands which mechanism is live.
 */
internal class DecorOverlayFallback(
    private val module: XposedModule,
    private val assets: ModuleAssets,
    /**
     * Expanded panel size in dp the first time an Activity is decorated.
     *
     * Resolved by [OverlayController] *before* the mechanism is chosen (from the
     * published default, overriding it with a remembered drag), so the two
     * mechanisms open at the same size. It is kept up to date afterwards by
     * [resizeTo].
     */
    private var panelSizeDp: Pair<Int, Int> = OverlaySizeLimits.DEFAULT_WIDTH_DP to
        OverlaySizeLimits.DEFAULT_HEIGHT_DP,
) {

    private var currentContainer: FrameLayout? = null
    private var currentView: FloatingOverlayView? = null
    private var registered = false

    /** Mirrors [FloatingOverlayView.isCollapsed]; decides which size to apply. */
    private var collapsed = false

    /**
     * True after an explicit 「隐藏窗口」.
     *
     * This mechanism has no window to remove, so "hidden" can only mean "stop
     * attaching". Kept as state (rather than just detaching once) because every
     * Activity resume would otherwise silently bring the panel back, which reads
     * as "the hide button does not work".
     */
    private var hidden = false

    /**
     * Position of the panel inside the container, in pixels.
     *
     * The container is `MATCH_PARENT`, so "moving the window" here means moving
     * the *child* inside it — i.e. these two margins. They deliberately survive
     * `detach()`, so the window stays where the user dragged it when the next
     * Activity attaches a fresh view.
     */
    private var marginTopPx = -1
    private var marginEndPx = -1

    /** The activity the overlay is currently attached to. */
    private var hostActivity: Activity? = null

    @Volatile
    private var onMessage: ((String, String) -> String?)? = null

    /**
     * Called when the collapsed pill is tapped.
     *
     * The tap happens on the pill inside *this* class, but only the controller
     * knows about the other mechanism and the persisted size, so it is routed
     * out rather than handled here.
     */
    @Volatile
    private var onCollapsedToggle: ((Boolean) -> Unit)? = null

    fun install(
        app: Application,
        handler: (String, String) -> String?,
        onCollapsed: (Boolean) -> Unit = {},
    ) {
        onMessage = handler
        onCollapsedToggle = onCollapsed
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
                if (hidden) {
                    // 「隐藏窗口」 was pressed: stay hidden until the settings app
                    // asks for it again, exactly like the real overlay window,
                    // which cannot bring itself back either.
                    RegTrailLog.d("decor overlay stays hidden while ${activity.javaClass.name} resumes")
                    return
                }
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
            onCollapsedChanged = { collapsedNow ->
                // Expanding from the pill: let the controller own the transition,
                // because it is the only side that knows the persisted size and
                // may also have a real overlay window to resize.
                RegTrailLog.d("decor overlay collapsed=$collapsedNow")
                onCollapsedToggle?.invoke(collapsedNow)
            },
        )
        view.onDrag = { dx, dy -> moveBy(dx, dy) }

        // Keep the overlay in the top-right corner, which is where it is least
        // likely to cover primary content. The size comes from the controller so
        // a remembered drag (or the settings app's slider) is honoured on every
        // re-attach, not just on the first one.
        if (marginTopPx < 0) marginTopPx = dp(context, MARGIN_TOP_DP)
        if (marginEndPx < 0) marginEndPx = dp(context, MARGIN_END_DP)

        val params = FrameLayout.LayoutParams(
            dp(context, sizeDp().first.toFloat()),
            dp(context, sizeDp().second.toFloat()),
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.END
            topMargin = marginTopPx
            marginEnd = marginEndPx
        }

        container.addView(view, params)
        root.addView(container)

        // Belt and braces: if the Activity added content in the same frame, make
        // sure our container is the last child so it draws on top.
        container.bringToFront()

        currentContainer = container
        currentView = view

        // A freshly built view starts expanded. Restore the collapsed state so an
        // Activity change while collapsed does not pop the panel open inside a
        // pill-sized window (the size below is already the pill size).
        if (collapsed) view.setCollapsed(true)

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

    /**
     * The size the panel should occupy, in dp.
     *
     * Collapsing is a *size* change here, not merely hiding the WebView: the
     * container is `MATCH_PARENT`, so leaving a 300x380 dp child behind would keep
     * a large — if invisible — touch target over the host app.
     */
    private fun sizeDp(): Pair<Int, Int> = if (collapsed) {
        FloatingOverlayView.COLLAPSED_WIDTH_DP.toInt() to
            FloatingOverlayView.COLLAPSED_HEIGHT_DP.toInt()
    } else {
        panelSizeDp
    }

    /**
     * Applies a size chosen by the user to the live view.
     *
     * The controller has already clamped it to [OverlaySizeLimits], so this only
     * converts dp → px. Recorded even when nothing is attached, because the next
     * Activity must open at the new size.
     */
    fun resizeTo(widthDp: Int, heightDp: Int) {
        panelSizeDp = widthDp to heightDp
        if (!collapsed) applySize()
    }

    /** Switches the panel to/from the collapsed pill. */
    fun setCollapsed(value: Boolean) {
        collapsed = value
        currentView?.setCollapsed(value)
        applySize()
    }

    val isCollapsed: Boolean get() = collapsed

    /**
     * Hides or re-shows the panel.
     *
     * @param value true to detach and stay detached; false to re-attach to the
     *   Activity that is currently in front, if it is still alive.
     */
    fun setHidden(value: Boolean) {
        hidden = value
        if (value) {
            detach()
            return
        }
        val activity = hostActivity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        attach(activity)
    }

    private fun applySize() {
        val view = currentView ?: return
        val context = view.context
        val size = sizeDp()
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.width = dp(context, size.first.toFloat())
        lp.height = dp(context, size.second.toFloat())
        view.layoutParams = lp
    }

    /**
     * Moves the panel inside the container by ([dx], [dy]) pixels.
     *
     * Clamped to the container so a drag can never push the window off-screen
     * where it could no longer be grabbed — the same failure mode that makes a
     * mis-sized panel unrecoverable.
     */
    private fun moveBy(dx: Int, dy: Int) {
        val view = currentView ?: return
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return

        val containerWidth = currentContainer?.width ?: 0
        val containerHeight = currentContainer?.height ?: 0

        // Gravity is TOP|END, so a rightward drag *decreases* `marginEnd`.
        val maxEnd = ((containerWidth - view.width).coerceAtLeast(0))
        val maxTop = ((containerHeight - view.height).coerceAtLeast(0))

        marginEndPx = (marginEndPx - dx).let {
            if (containerWidth > 0) it.coerceIn(0, maxEnd) else it.coerceAtLeast(0)
        }
        marginTopPx = (marginTopPx + dy).let {
            if (containerHeight > 0) it.coerceIn(0, maxTop) else it.coerceAtLeast(0)
        }

        lp.topMargin = marginTopPx
        lp.marginEnd = marginEndPx
        view.layoutParams = lp
    }

    val isAttached: Boolean get() = currentView != null

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        /** Distance from the top of the content area on first attach. */
        const val MARGIN_TOP_DP = 80f

        /** Gap to the right edge on first attach. */
        const val MARGIN_END_DP = 8f
    }
}
