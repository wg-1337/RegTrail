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

    /**
     * Margins a previous run dragged the panel to, in pixels.
     *
     * Consumed once, on the first attach; [OverlayController] reads them from
     * `OverlaySizeStore` so a dragged position survives the target process being
     * killed. They are clamped to the container as soon as it has been laid out —
     * a value saved on a larger screen must never leave the panel unreachable.
     */
    private var restoredMargins: Pair<Int, Int>? = null

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

    /**
     * Margin pair (top, end) at the moment a header drag started.
     *
     * Kept here rather than in the controller because only this class knows how a
     * position maps onto `FrameLayout` margins (the panel is laid out with
     * `Gravity.TOP or Gravity.END`, so moving right *decreases* `marginEnd`).
     */
    private var dragOriginTopPx = 0
    private var dragOriginEndPx = 0

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
        savedMargins: Pair<Int, Int>? = null,
    ) {
        onMessage = handler
        onCollapsedToggle = onCollapsed
        if (savedMargins != null && marginTopPx < 0) {
            restoredMargins = savedMargins
        }
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
        if (marginTopPx < 0) {
            val restored = restoredMargins
            marginTopPx = restored?.first ?: dp(context, MARGIN_TOP_DP)
        }
        if (marginEndPx < 0) {
            val restored = restoredMargins
            marginEndPx = restored?.second ?: dp(context, MARGIN_END_DP)
        }
        restoredMargins = null

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

        // A position restored from preferences — or simply a rotation since it was
        // saved — may put the panel outside the new container. Clamp once the
        // container has real bounds, otherwise the window could end up somewhere
        // the user cannot reach (this mechanism has no other entry point).
        container.post { clampIntoContainer() }

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
        // Growing from the pill back to the panel is a *size* change, and the position
        // was clamped against the pill's size while collapsed. A panel dragged near a
        // container edge would therefore expand half outside the Activity window —
        // measured on the test device: the title bar's first inch and the 状态 tab were
        // clipped away. Re-clamp once this new size has been laid out.
        view.post { clampIntoContainer() }
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

    // -----------------------------------------------------------------------
    // Header drag (the expanded panel's title bar)
    // -----------------------------------------------------------------------

    /**
     * Remembers the current position as the origin of a header drag.
     *
     * Called once per gesture from the controller, so the frame-by-frame path
     * ([dragToDp]) is pure arithmetic: the page sends the *total* delta since the
     * finger went down, and this class turns it into an absolute margin pair. That
     * is what makes clamping idempotent — asking for 500 dp twice gives the same
     * answer as asking once, so a dropped or reordered frame cannot drift.
     */
    fun beginDrag() {
        dragOriginTopPx = marginTopPx
        dragOriginEndPx = marginEndPx
    }

    /**
     * How far a header drag may travel, as `[minDx, maxDx, minDy, maxDy]` in dp.
     *
     * The page clamps its own visual position with these, so the panel stops at
     * the container edge instead of sliding out from under the finger and being
     * snapped back on release.
     */
    fun dragBoundsDp(): IntArray {
        val view = currentView ?: return intArrayOf(0, 0, 0, 0)
        val context = view.context
        val containerWidth = currentContainer?.width ?: 0
        val containerHeight = currentContainer?.height ?: 0
        val density = context.resources.displayMetrics.density.coerceAtLeast(0.1f)

        val maxEnd = ((containerWidth - view.width).coerceAtLeast(0))
        val maxTop = ((containerHeight - view.height).coerceAtLeast(0))

        // Rightward: marginEnd may fall to 0, i.e. dx <= dragOriginEndPx.
        // Leftward: marginEnd may grow to maxEnd, i.e. dx >= dragOriginEndPx - maxEnd.
        val maxDx = (dragOriginEndPx / density).toInt()
        val minDx = ((dragOriginEndPx - maxEnd) / density).toInt()
        // Downward: marginTop may grow to maxTop.
        val maxDy = ((maxTop - dragOriginTopPx) / density).toInt()
        val minDy = (-dragOriginTopPx / density).toInt()
        return intArrayOf(minDx, maxDx, minDy, maxDy)
    }

    /**
     * Applies a header-drag delta in dp; returns the delta that was really applied.
     *
     * Runs the layout work on the main thread (a `layoutParams` write off the UI
     * thread is undefined behaviour) but answers *immediately* with the clamped
     * value, because the clamp is deterministic: the page's local transform only
     * needs to know where the panel is going to be, not whether it has been laid
     * out yet.
     */
    fun dragToDp(dxDp: Int, dyDp: Int): Pair<Int, Int> {
        val view = currentView ?: return 0 to 0
        val context = view.context
        val density = context.resources.displayMetrics.density.coerceAtLeast(0.1f)
        val containerWidth = currentContainer?.width ?: 0
        val containerHeight = currentContainer?.height ?: 0
        val maxEnd = ((containerWidth - view.width).coerceAtLeast(0))
        val maxTop = ((containerHeight - view.height).coerceAtLeast(0))

        val targetEnd = (dragOriginEndPx - (dxDp * density).toInt()).let {
            if (containerWidth > 0) it.coerceIn(0, maxEnd) else it.coerceAtLeast(0)
        }
        val targetTop = (dragOriginTopPx + (dyDp * density).toInt()).let {
            if (containerHeight > 0) it.coerceIn(0, maxTop) else it.coerceAtLeast(0)
        }

        val appliedDx = ((dragOriginEndPx - targetEnd) / density).toInt()
        val appliedDy = ((targetTop - dragOriginTopPx) / density).toInt()

        // The *intent* is recorded synchronously so a release in the same frame can
        // persist it; only the `LayoutParams` write is handed to the UI thread.
        marginTopPx = targetTop
        marginEndPx = targetEnd

        view.post {
            val live = currentView ?: return@post
            val lp = live.layoutParams as? FrameLayout.LayoutParams ?: return@post
            if (lp.topMargin == targetTop && lp.marginEnd == targetEnd) return@post
            lp.topMargin = targetTop
            lp.marginEnd = targetEnd
            live.layoutParams = lp
        }
        return appliedDx to appliedDy
    }

    /** The current margin pair, in pixels, for persistence on drag release. */
    fun marginsPx(): Pair<Int, Int> = marginTopPx to marginEndPx

    /** Pulls the panel back inside the container after a restore or a rotation. */
    private fun clampIntoContainer() {
        val view = currentView ?: return
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
        if (view.width <= 0 || view.height <= 0) return
        val containerWidth = currentContainer?.width ?: 0
        val containerHeight = currentContainer?.height ?: 0
        if (containerWidth <= 0 && containerHeight <= 0) return

        val maxEnd = ((containerWidth - view.width).coerceAtLeast(0))
        val maxTop = ((containerHeight - view.height).coerceAtLeast(0))
        val clampedEnd = if (containerWidth > 0) marginEndPx.coerceIn(0, maxEnd) else marginEndPx
        val clampedTop = if (containerHeight > 0) marginTopPx.coerceIn(0, maxTop) else marginTopPx
        if (clampedEnd == marginEndPx && clampedTop == marginTopPx) return

        marginEndPx = clampedEnd
        marginTopPx = clampedTop
        lp.topMargin = marginTopPx
        lp.marginEnd = marginEndPx
        view.layoutParams = lp
        RegTrailLog.d("decor overlay margins clamped to top=$marginTopPx end=$marginEndPx")
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
