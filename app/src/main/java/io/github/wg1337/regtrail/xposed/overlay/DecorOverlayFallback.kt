package io.github.wg1337.regtrail.xposed.overlay

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
 * ## Why the geometry is not this class's state (the Activity-switch regression)
 * The container is thrown away and rebuilt on every navigation, so the *view* must
 * not be the source of truth for anything. The old implementation kept the panel
 * position in fields here and **wrote the clamped margins back into them**:
 *
 * ```
 * clampIntoContainer(): marginTopPx = clampedTop; marginEndPx = clampedEnd
 * ```
 *
 * Measured on the test device, that is precisely the reported bug. Two Activities
 * of the same app have different `android.R.id.content` rectangles (a status bar,
 * an ActionBar, a fullscreen homework page), so the clamp against the *new*
 * container shortened the remembered offset, and returning to the first Activity
 * did not restore it — the panel walked up the screen one navigation at a time.
 * The same overwrite made a collapse/expand cycle move the panel, because
 * expanding grows a pill-sized window into a panel-sized one and re-clamped
 * against the pill's position.
 *
 * The fix is a strict separation, and it is the invariant of this class:
 *
 *  - **[PanelGeometry] is the intent.** Owned by [OverlayController], persisted by
 *    [OverlaySizeStore], never modified by a clamp. Every attach starts from it.
 *  - **[PanelPlacement] is derived.** Recomputed from the intent against the
 *    current container whenever one is needed, and thrown away.
 *
 * So a position that does not fit the current Activity is clamped *for display
 * only*, and leaving that Activity restores the user's position exactly. A drag is
 * the only thing that changes the intent.
 *
 * ## Re-applying after layout
 * The container has no bounds when it is added, and the previous implementation
 * clamped once from a `post {}` whose `view.width` could still be the *old
 * Activity's* width. Here a layout listener on the container refreshes the cached
 * screen rectangle and re-applies, so every clamp is computed against the real,
 * measured parent — and again on any later change (rotation, IME, insets, a
 * split-screen resize), which is the other half of "the window changed by itself".
 *
 * ## What you give up
 * - The window sits *inside* the Activity window, so it is clipped by the system
 *   bars and hidden behind dialogs rather than floating above them.
 * - It is torn down and rebuilt as Activities change, so the panel must be
 *   rebuildable from [PanelGeometry] alone — which is what it now is.
 *
 * That trade is stated in the UI so the user understands which mechanism is live.
 */
internal class DecorOverlayFallback(
    private val assets: ModuleAssets,
    /**
     * The current intent. Read on every apply rather than copied, so there is
     * exactly one source of truth and a re-attach can never invent a default.
     */
    private val geometry: () -> PanelGeometry,
    /**
     * Reports a geometry the **user** decided (a drag, or the first default being
     * locked in).
     *
     * @param persist true only for the last frame of a gesture: the frames in
     *   between must not each write a preference file.
     */
    private val onGeometryChanged: (PanelGeometry, Boolean) -> Unit,
    /** Restored from [OverlaySizeStore] so collapsing survives a re-attach. */
    initialCollapsed: Boolean = false,
) {

    private var currentContainer: FrameLayout? = null
    private var currentView: FloatingOverlayView? = null
    private var registered = false

    /**
     * Mirrors `FloatingOverlayView.isCollapsed`; decides which size to apply.
     *
     * Volatile because the bridge thread reads it through [applyCollapsedSize].
     */
    @Volatile
    private var collapsed = initialCollapsed

    /**
     * True after an explicit 「隐藏窗口」.
     *
     * This mechanism has no window to remove, so "hidden" can only mean "stop
     * attaching". Kept as state (rather than just detaching once) because every
     * Activity resume would otherwise silently bring the panel back, which reads
     * as "the hide button does not work".
     */
    private var hidden = false

    /** The activity the overlay is currently attached to. */
    private var hostActivity: Activity? = null

    /**
     * The container's bounds **in screen pixels**, cached from its last layout.
     *
     * Deliberately not cleared by [detach]: the previous Activity's content
     * rectangle is a far better guess for the provisional placement of the next
     * one than the whole display is, and it is replaced by the real rectangle in
     * the first layout pass anyway.
     */
    private var containerRect: Rect? = null

    /**
     * The display density, resolved once per attached Activity.
     *
     * [PanelLayoutMath.density] has to ask the display service, which is a binder
     * round trip — fine once per attach, wasteful once per drag frame. It can only
     * change with a configuration change, and that recreates the Activity, so a
     * per-attach cache is exact.
     */
    @Volatile
    private var cachedDensity: Float? = null

    /**
     * The placement last written into the `LayoutParams` — what the user sees, and
     * therefore the origin a pill drag has to start from.
     *
     * Volatile because the JS bridge thread reads it in [dragToDp].
     */
    @Volatile
    private var placement: PanelPlacement? = null

    /**
     * Placement at the moment a header drag started.
     *
     * The page sends the **total** delta since the finger went down, so this is
     * what keeps the maths idempotent: asking for 500 dp twice produces the same
     * answer as asking once, and a dropped or reordered frame cannot drift.
     */
    @Volatile
    private var dragOrigin: PanelPlacement? = null

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
                    // Re-apply even when the Activity did not change: the display
                    // metrics can settle *after* the first attach (measured right
                    // after a reboot), and a re-apply is a pure function of the
                    // persisted intent, so it can only heal the placement, never
                    // move a panel the user is happy with.
                    applyGeometry()
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

        RegTrailLog.i(
            "decor overlay fallback installed " +
                "(collapsed=$collapsed, intent=${geometry().describe()})",
        )
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
        // Persist the *end* of a pill drag: the frames in between must not write a
        // preference file each, and without this the pill position was the one
        // piece of state that still died with the target process.
        view.onDragEnd = { persistIntent() }
        // Restore the collapsed state before the first apply, so the very first
        // layout is already pill-sized: a fresh view always starts expanded, and
        // an Activity change while collapsed used to pop the panel open for a
        // frame (and re-clamp against the panel size).
        view.setCollapsed(collapsed)

        container.addView(
            view,
            FrameLayout.LayoutParams(0, 0).apply { gravity = GRAVITY },
        )
        root.addView(container)

        // Belt and braces: if the Activity added content in the same frame, make
        // sure our container is the last child so it draws on top.
        container.bringToFront()

        currentContainer = container
        currentView = view
        placement = null
        dragOrigin = null
        // A new Activity may report a different *Resources* density; the display's
        // is re-read once here so every frame of the coming drags is cheap.
        cachedDensity = null

        // Provisional: the real rectangle arrives with the first layout pass,
        // which the listener below handles. Applying now means the panel never
        // appears at 0,0 for a frame.
        applyGeometry()

        container.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            containerRect = screenRect(v) ?: return@addOnLayoutChangeListener
            applyGeometry()
        }

        RegTrailLog.d(
            "decor overlay attached to ${activity.javaClass.name} " +
                "intent=${geometry().describe()}",
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
        placement = null
        dragOrigin = null
    }

    /** Pushes a config snapshot into the live view, if any. */
    fun applyConfig(configJson: String) {
        currentView?.applyConfig(configJson)
    }

    fun appendLog(line: String) {
        currentView?.appendLog(line)
    }

    // -----------------------------------------------------------------------
    // Geometry — the one place size and position become LayoutParams
    // -----------------------------------------------------------------------

    /**
     * Re-applies the intent to the live view.
     *
     * Called by [OverlayController] after it changed the size (the grip, the
     * settings app's sliders) or the collapsed state, and by the layout listener
     * whenever the container's on-screen rectangle changed. Idempotent: an apply
     * that would not change anything writes nothing.
     */
    fun refresh() {
        applyGeometry()
    }

    /**
     * Computes the placement this intent has inside the current container and
     * writes it to the view.
     *
     * **Must never modify the intent** — that is the whole fix (see the class
     * KDoc). The only exception is the very first default, which has to be turned
     * into an absolute position once, otherwise the next Activity would re-derive
     * it from a different content rectangle and the panel would jump by exactly
     * the difference between the two rectangles.
     */
    private fun applyGeometry() {
        val view = currentView ?: return
        val context = view.context
        val d = density(context)
        val intent = geometry()
        val size = appliedSizeDp(intent)
        val widthPx = PanelLayoutMath.px(size.first, d)
        val heightPx = PanelLayoutMath.px(size.second, d)
        val displayWidthPx = displayWidth(context)
        val area = containerRect
            ?: currentContainer?.let { screenRect(it) }
            ?: PanelLayoutMath.displayRect(context)

        val next = PanelLayoutMath.place(
            geometry = intent,
            widthPx = widthPx,
            heightPx = heightPx,
            container = area,
            displayWidthPx = displayWidthPx,
            density = d,
        )
        placement = next
        writeLayoutParams(view, next)

        if (intent.placed || containerRect == null) return

        // First ever placement: lock the default in, then re-derive from it so that
        // what is on screen is exactly what a re-attach reproduces.
        commitPlacement(next, persist = true)
        val settled = PanelLayoutMath.place(
            geometry = geometry(),
            widthPx = widthPx,
            heightPx = heightPx,
            container = area,
            displayWidthPx = displayWidthPx,
            density = d,
        )
        placement = settled
        writeLayoutParams(view, settled)
        logPlacement("default locked", settled)
    }

    /** The panel size to apply right now: the pill while collapsed, else the intent. */
    private fun appliedSizeDp(intent: PanelGeometry): Pair<Int, Int> = if (collapsed) {
        FloatingOverlayView.COLLAPSED_WIDTH_DP.toInt() to
            FloatingOverlayView.COLLAPSED_HEIGHT_DP.toInt()
    } else {
        intent.sizeDp
    }

    /**
     * Writes [next] into the view's `LayoutParams`; silent when nothing changed.
     *
     * Logs only when the **size** changed (or on the very first write of a fresh
     * view), because a drag changes only the position and would otherwise emit one
     * line — and one spool-file append — per animation frame. The end of a gesture
     * is logged by [persistIntent], so a drag still leaves exactly one line with its
     * final numbers.
     */
    private fun writeLayoutParams(view: FloatingOverlayView, next: PanelPlacement) {
        val existing = view.layoutParams as? FrameLayout.LayoutParams
        val sizeChanged = existing == null ||
            existing.width != next.widthPx ||
            existing.height != next.heightPx
        if (!sizeChanged &&
            existing != null &&
            existing.topMargin == next.marginTopPx &&
            existing.marginEnd == next.marginEndPx &&
            existing.gravity == GRAVITY
        ) {
            return
        }

        val params = existing ?: FrameLayout.LayoutParams(next.widthPx, next.heightPx)
        params.width = next.widthPx
        params.height = next.heightPx
        params.topMargin = next.marginTopPx
        params.marginEnd = next.marginEndPx
        params.gravity = GRAVITY
        view.layoutParams = params

        if (sizeChanged) logPlacement("sized", next)
    }

    /** One line of geometry, in the units a screenshot can be compared against. */
    private fun logPlacement(reason: String, next: PanelPlacement) {
        RegTrailLog.d(
            "decor overlay $reason ${next.widthPx}x${next.heightPx}px " +
                "at(${next.leftPx},${next.topPx}) " +
                "margins(top=${next.marginTopPx},end=${next.marginEndPx}) " +
                "endGap=${next.endGapPx} collapsed=$collapsed " +
                "intent=${geometry().describe()} " +
                "container=${describeRect(containerRect)} " +
                "activity=${hostActivity?.javaClass?.simpleName}",
        )
    }

    /**
     * Turns the placement that is on screen back into an intent.
     *
     * No unit conversion: the intent's position is in screen pixels for the reason
     * [PanelGeometry] documents — dp is not a stable unit across the Activities of
     * one app on this device, so a dp position would move the panel by the ratio of
     * two densities the moment the user navigates.
     */
    private fun commitPlacement(next: PanelPlacement, persist: Boolean) {
        onGeometryChanged(
            geometry().placedAt(topPx = next.topPx, endPx = next.endGapPx),
            persist,
        )
    }

    /**
     * The placement a drag of ([dxPx], [dyPx]) *from* [origin] may reach.
     *
     * Increasing `dx` moves the panel right (which *decreases* the gap to the right
     * edge); increasing `dy` moves it down. The result is clamped so the panel
     * stays inside the container — this mechanism has no other entry point, so a
     * panel dragged out of reach could never be recovered.
     */
    private fun clampedTarget(
        origin: PanelPlacement,
        dxPx: Int,
        dyPx: Int,
    ): PanelPlacement? {
        val view = currentView ?: return null
        val area = containerRect ?: return null
        val displayWidthPx = displayWidth(view.context)

        val minTop = area.top
        val maxTop = (area.bottom - origin.heightPx).coerceAtLeast(minTop)
        // `endGap` is measured from the display's right edge, while the clamp is
        // against the container's edges; a container that is inset from the display
        // (a nav bar) shifts the window the gap may live in.
        val minEnd = displayWidthPx - area.right
        val maxEnd = (displayWidthPx - area.left - origin.widthPx).coerceAtLeast(minEnd)

        val top = (origin.topPx + dyPx).coerceIn(minTop, maxTop)
        val end = (origin.endGapPx - dxPx).coerceIn(minEnd, maxEnd)
        return PanelPlacement(
            widthPx = origin.widthPx,
            heightPx = origin.heightPx,
            leftPx = displayWidthPx - end - origin.widthPx,
            topPx = top,
            endGapPx = end,
            marginTopPx = top - area.top,
            marginEndPx = area.right - (displayWidthPx - end),
        )
    }

    /**
     * Moves the panel inside the container by ([dx], [dy]) screen pixels.
     *
     * Driven by the collapsed pill, whose touch listener reports per-frame deltas
     * from `MotionEvent.rawX/rawY` — i.e. already in screen pixels, the same space
     * the intent lives in.
     */
    private fun moveBy(dx: Int, dy: Int) {
        val origin = placement ?: return
        val target = clampedTarget(origin, dx, dy) ?: return
        commitPlacement(target, persist = false)
        applyGeometry()
    }

    /** Persists the intent the in-memory drags have already produced. */
    private fun persistIntent() {
        placement?.let { logPlacement("gesture committed", it) }
        onGeometryChanged(geometry(), true)
    }

    /**
     * Persists the current position now.
     *
     * Called by [OverlayController] when the page reports the last frame of a
     * header drag: the per-frame messages only move the panel, exactly like the
     * resize grip, so one gesture costs one preference write.
     */
    fun persistPlacement() {
        persistIntent()
    }

    // -----------------------------------------------------------------------
    // Collapsed state
    // -----------------------------------------------------------------------

    /** Switches the panel to/from the collapsed pill and re-applies its size. */
    fun setCollapsed(value: Boolean) {
        if (collapsed == value) {
            // Still re-apply: the caller may be re-asserting the state after a
            // re-attach, and the fresh view has to be sized for it either way.
            currentView?.setCollapsed(value)
            applyGeometry()
            return
        }
        collapsed = value
        currentView?.setCollapsed(value)
        applyGeometry()
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

    // -----------------------------------------------------------------------
    // Header drag (the expanded panel's title bar)
    // -----------------------------------------------------------------------

    /**
     * Remembers the current position as the origin of a header drag.
     *
     * Called once per gesture from the controller, so the frame-by-frame path
     * ([dragToDp]) is pure arithmetic against a fixed origin.
     */
    fun beginDrag() {
        dragOrigin = placement
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
        val area = containerRect ?: return intArrayOf(0, 0, 0, 0)
        val origin = dragOrigin ?: placement ?: return intArrayOf(0, 0, 0, 0)
        val d = density(view.context)
        val displayWidthPx = displayWidth(view.context)

        val minTop = area.top
        val maxTop = (area.bottom - origin.heightPx).coerceAtLeast(minTop)
        val minEnd = displayWidthPx - area.right
        val maxEnd = (displayWidthPx - area.left - origin.widthPx).coerceAtLeast(minEnd)

        val maxDx = PanelLayoutMath.dpOf(origin.endGapPx - minEnd, d)
        val minDx = PanelLayoutMath.dpOf(origin.endGapPx - maxEnd, d)
        val maxDy = PanelLayoutMath.dpOf(maxTop - origin.topPx, d)
        val minDy = PanelLayoutMath.dpOf(minTop - origin.topPx, d)
        return intArrayOf(minDx, maxDx, minDy, maxDy)
    }

    /**
     * Applies a header-drag delta in dp; returns the delta that was really applied.
     *
     * Runs on the **JS bridge thread** (a `@JavascriptInterface` call), so the
     * intent update is done inline — it is thread-safe state, and the reply has to
     * be immediate — while the `LayoutParams` write is handed to the view's own
     * thread. The reply is already correct because the clamp above is
     * deterministic: the page's local transform only needs to know where the panel
     * is going to be, not whether it has been laid out yet.
     */
    fun dragToDp(dxDp: Int, dyDp: Int): Pair<Int, Int> {
        val view = currentView ?: return 0 to 0
        val origin = dragOrigin ?: return 0 to 0
        val d = density(view.context)

        val target = clampedTarget(
            origin = origin,
            dxPx = (dxDp * d).toInt(),
            dyPx = (dyDp * d).toInt(),
        ) ?: return 0 to 0

        val appliedDx = PanelLayoutMath.dpOf(origin.endGapPx - target.endGapPx, d)
        val appliedDy = PanelLayoutMath.dpOf(target.topPx - origin.topPx, d)

        commitPlacement(target, persist = false)
        view.post { applyGeometry() }
        return appliedDx to appliedDy
    }

    /** The live placement, in screen pixels, for the 状态 diagnostics. */
    fun placementPx(): PanelPlacement? = placement

    val isAttached: Boolean get() = currentView != null

    /** The display density for this attach, cached (see [cachedDensity]). */
    private fun density(context: Context): Float {
        cachedDensity?.let { return it }
        return PanelLayoutMath.density(context).also { cachedDensity = it }
    }

    /**
     * The display width, with the container's right edge as a lower bound.
     *
     * `endPx` is measured from the display's right edge, so a wrong display width
     * translates the panel horizontally. The Activity's `displayMetrics` can be
     * wrong for a moment after boot (measured: 992 instead of 1200, which put the
     * panel at the screen's left edge on the first attach after a reboot), and the
     * container is always inside the display, so its right edge is a safe floor.
     */
    private fun displayWidth(context: Context): Int = maxOf(
        PanelLayoutMath.displayWidthPx(context),
        containerRect?.right ?: 0,
        1,
    )

    private companion object {
        /**
         * The panel is anchored to its **top-right** corner: positioning it by its
         * end edge means expanding a pill grows it leftwards and downwards, which
         * is the corner the user is looking at.
         */
        val GRAVITY: Int = Gravity.TOP or Gravity.END

        /** The on-screen rectangle of [view], or null while it has no bounds. */
        fun screenRect(view: View): Rect? {
            if (view.width <= 0 || view.height <= 0) return null
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            return Rect(
                location[0],
                location[1],
                location[0] + view.width,
                location[1] + view.height,
            )
        }

        fun describeRect(rect: Rect?): String =
            if (rect == null) "none" else "${rect.left},${rect.top},${rect.right},${rect.bottom}"
    }
}
