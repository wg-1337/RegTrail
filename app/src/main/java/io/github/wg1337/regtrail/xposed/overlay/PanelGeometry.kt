package io.github.wg1337.regtrail.xposed.overlay

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import io.github.wg1337.regtrail.shared.model.OverlaySizeLimits

/**
 * The panel's *intent*: how big it is and where the user last put it.
 *
 * ## The one rule this type exists for
 * A panel attached to an `Activity` is re-created on every navigation, and the
 * thing that used to change the window was not the size itself but two leaks:
 *
 *  1. the **clamp**, which overwrote the remembered position with the position
 *     that happened to fit the new `android.R.id.content`, and
 *  2. the **density**, which was read from the Activity's own `Resources`.
 *
 * So the intent is immutable and authoritative, and it is the *only* thing that
 * gets persisted. Clamping produces a [PanelPlacement] — a derived, throw-away
 * value that says where the panel has to be drawn inside *this* container — and
 * never feeds back into the intent. Returning to an Activity whose content area is
 * large again therefore reproduces the exact original position, and so does a
 * collapse/expand cycle.
 *
 * ## The units (measured on the test device, not assumed)
 * They are deliberately mixed, because the two quantities are conceptually
 * different and the device proves it:
 *
 *  - **Size is dp**, converted with the *display* density
 *    ([PanelLayoutMath.density]) and never with the Activity's. The test device
 *    showed why: `MainActivity` runs at density ≈2.2222 (a per-Activity 屏幕适配
 *    override — the same `302x335 dp` panel measured `671x744 px` there and
 *    `604x670 px` in `MessageActivity` at density 2.0). A size is conceptually a
 *    dp, so it is stored as one; but it must be converted in the one density that
 *    does not depend on which Activity is in front.
 *  - **Position is screen pixels**, because a position is not a size: it is a place
 *    on the glass. Converting it to dp would re-scale it by whatever density the
 *    next Activity reports and move the panel by 11 % on this device — which is
 *    exactly the report this class exists to fix. Pixels in the display's
 *    coordinate space are the one unit every Activity agrees on.
 *
 * ## The coordinate space
 * [topPx] and [endPx] are measured against the **display**, not against the
 * Activity's content view:
 *
 *  - [topPx] is the distance from the top edge of the display to the panel's top
 *    edge,
 *  - [endPx] is the distance from the right edge of the display to the panel's
 *    right edge (so a *larger* value is further left).
 *
 * Anchoring to the display rather than to the content view is what keeps the panel
 * visually still when two Activities have different insets — an ActionBar, a
 * fullscreen homework page and a status-bar-less activity all move the top of
 * `android.R.id.content`, and a content-relative offset would jump by exactly that
 * amount on every navigation. The gap to the *right* edge is used instead of a left
 * offset because the panel is laid out with `Gravity.TOP or Gravity.END`: expanding
 * it grows it leftwards and downwards, so the right edge is the stable one.
 */
internal data class PanelGeometry(
    /** Width of the **expanded** panel, in dp. The pill size is not stored here. */
    val widthDp: Int = OverlaySizeLimits.DEFAULT_WIDTH_DP,

    /** Height of the **expanded** panel, in dp. */
    val heightDp: Int = OverlaySizeLimits.DEFAULT_HEIGHT_DP,

    /** Panel top edge, measured from the display top, in screen px. Null until placed. */
    val topPx: Int? = null,

    /** Gap between the panel's right edge and the display's right edge, px. */
    val endPx: Int? = null,
) {

    /** True once a position is known — per-app preferences or a fresh default. */
    val placed: Boolean get() = topPx != null && endPx != null

    /** The expanded size, in dp, in the order `LayoutParams` wants it. */
    val sizeDp: Pair<Int, Int> get() = widthDp to heightDp

    /** Same panel, new size; the position is deliberately untouched. */
    fun resized(widthDp: Int, heightDp: Int): PanelGeometry =
        copy(widthDp = widthDp, heightDp = heightDp)

    /** Same panel, new position. */
    fun placedAt(topPx: Int, endPx: Int): PanelGeometry =
        copy(topPx = topPx, endPx = endPx)

    /**
     * One-line description for logs and the 状态 tab.
     *
     * Deliberately shows the *intent* and not a placement: when a bug report says
     * "the window moved", the useful half of the answer is what the module believes
     * the panel should be, which is the value the next re-attach restores.
     */
    fun describe(): String {
        val position = if (topPx == null || endPx == null) {
            "unplaced"
        } else {
            "top=${topPx}px end=${endPx}px"
        }
        return "${widthDp}x${heightDp}dp $position"
    }
}

/**
 * Where the panel actually has to be drawn inside one particular container.
 *
 * Derived from a [PanelGeometry] on every apply and never persisted: it is the
 * answer to "if the user's position does not fit this Activity, what is the nearest
 * position that does?" — a question whose answer must not be remembered.
 *
 * [marginTopPx] / [marginEndPx] are what a `FrameLayout.LayoutParams` with
 * `Gravity.TOP or Gravity.END` needs; [leftPx] / [topPx] / [endGapPx] are the same
 * rectangle expressed in screen pixels, which is the space drags work in.
 */
internal data class PanelPlacement(
    /** Applied panel width in pixels (pill size while collapsed). */
    val widthPx: Int,

    /** Applied panel height in pixels (pill size while collapsed). */
    val heightPx: Int,

    /** Left edge in screen pixels. */
    val leftPx: Int,

    /** Top edge in screen pixels. */
    val topPx: Int,

    /** Distance from the display's right edge to the panel's right edge, px. */
    val endGapPx: Int,

    /** `FrameLayout` top margin that produces [topPx] inside the container. */
    val marginTopPx: Int,

    /** `FrameLayout` end margin that produces [endGapPx]. */
    val marginEndPx: Int,
)

/**
 * The one place that turns a [PanelGeometry] into a [PanelPlacement].
 *
 * Both the application-overlay window and the Activity-embedded fallback size a
 * `View`, so the dp→px conversion and the "keep it inside the parent" rule must
 * exist exactly once. Duplicating them per mechanism is how the two paths drift
 * apart about what "fits on screen" means.
 */
internal object PanelLayoutMath {

    /** Distance from the top of the content area a fresh panel opens at, dp. */
    const val DEFAULT_TOP_DP = 80

    /** Gap to the right edge of the content area a fresh panel opens at, dp. */
    const val DEFAULT_END_DP = 8

    /** dp → px in the display's density. */
    fun px(context: Context, dp: Int): Int = px(dp, density(context))

    /** dp → px with an explicit density. */
    fun px(dp: Int, density: Float): Int = (dp * density + 0.5f).toInt()

    /**
     * px → dp, for translating a drag the page measured in dp into the pixels the
     * window is moved by.
     *
     * Must use [density] and not the Activity's: inside the WebView one CSS pixel
     * is one dp rendered at the *display* density, so a drag that reports 30 dp
     * moved the finger by `30 * displayDensity` device pixels. Converting with an
     * overridden Activity density made that drag overshoot by 11 %.
     */
    fun dpOf(px: Int, density: Float): Int =
        kotlin.math.round(px / density.coerceAtLeast(0.1f)).toInt()

    /**
     * The density everything the panel does is expressed in: **the display's**.
     *
     * ## Why not `context.resources.displayMetrics.density`
     * Because that value is not stable across the Activities of one app. Measured
     * on the test device (`net.xuele.xuelets`, 1200x2000 @ 320 dpi):
     *
     * ```
     * MessageActivity : density 2.0     -> panel 604x670 px = 302x335 dp
     * MainActivity    : density 2.2222  -> panel 671x744 px = 302x335 dp * 2.2222
     * ```
     *
     * `MainActivity` reports 2.2222 because the app applies a per-Activity density
     * override (the ubiquitous Chinese 屏幕适配 / AutoSize trick); its own ActionBar
     * measures 111 px, i.e. 49.95 dp at 2.2222 and 55.5 dp at 2.0 — so 2.2222 is the
     * app's real layout density, and 320 dpi is the display's. A panel sized with
     * the Activity's density therefore grows by 11 % every time the user reaches
     * that Activity, and shrinks again when they leave. That is the reported bug.
     *
     * The display density is also the one the **WebView** renders at
     * (`devicePixelRatio` comes from the display, not from the app's Resources):
     * on the same device the page's `#bar` — 41 CSS px — measured 82 device px in
     * *both* Activities. Sizing the panel with the display density therefore also
     * makes "1 CSS px == 1 dp" true again, which is the assumption the whole page
     * (`liveSizeDp`, the resize grip, the header drag) is written against.
     */
    @Suppress("DEPRECATION")
    fun density(context: Context): Float {
        val display = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val d = context.display ?: return@runCatching null
                val metrics = DisplayMetrics()
                d.getRealMetrics(metrics)
                metrics.density
            } else {
                null
            }
        }.getOrNull()

        // `Resources.getSystem()` carries the *global* configuration and is not
        // touched by an app's per-Activity override, so it is the next best
        // Activity-independent source.
        val system = runCatching { android.content.res.Resources.getSystem().displayMetrics.density }
            .getOrNull()

        val picked = display?.takeIf { it > 0.1f }
            ?: system?.takeIf { it > 0.1f }
            ?: context.resources.displayMetrics.density
        return picked.coerceAtLeast(0.1f)
    }

    /**
     * Resolves the placement of a panel of [widthPx] x [heightPx] inside
     * [container] (bounds in screen pixels) on a display [displayWidthPx] wide.
     *
     * When [PanelGeometry.placed] is false the panel opens at the documented
     * corner (80 dp from the top of the container, 8 dp from its right edge).
     *
     * **One-time effect of the upgrade:** the previous implementation persisted
     * *container-relative* pixel margins (`panel_margin_top_px` /
     * `panel_margin_end_px`), which is a different coordinate space from the
     * screen-absolute position this class stores — the two only coincide when the
     * content view happens to start at (0,0). Those keys are therefore not read:
     * the first attach after the upgrade places the panel at the default corner
     * once, locks it as an absolute position, and from then on every re-attach
     * (including across process restarts and reboots) reproduces it exactly.
     *
     * The clamp only ever *derives* a position: [geometry] is not modified. That
     * is the whole point (see [PanelGeometry]).
     *
     * @param container the container's bounds **in screen pixels**. A container
     *   that has not been laid out yet is passed as the full display, so the
     *   provisional placement is already the right shape; the caller re-applies
     *   once the real bounds arrive.
     */
    fun place(
        geometry: PanelGeometry,
        widthPx: Int,
        heightPx: Int,
        container: Rect,
        displayWidthPx: Int,
        density: Float,
    ): PanelPlacement {
        val laidOut = container.width() > 0 && container.height() > 0

        // The default is expressed against the container (that is what "top-right
        // corner of the content area" means); the stored value is absolute.
        val defaultEndGap = displayWidthPx - container.right + px(DEFAULT_END_DP, density)
        val desiredTopPx = geometry.topPx
            ?: (container.top + px(DEFAULT_TOP_DP, density))
        val desiredEndGapPx = geometry.endPx ?: defaultEndGap

        var marginTop = desiredTopPx - container.top
        var marginEnd = container.right - (displayWidthPx - desiredEndGapPx)

        if (laidOut) {
            // Fully inside the content area when it fits; pinned to the top/right
            // edge when the panel is taller/wider than the container.
            marginTop = marginTop.coerceIn(0, (container.height() - heightPx).coerceAtLeast(0))
            marginEnd = marginEnd.coerceIn(0, (container.width() - widthPx).coerceAtLeast(0))
        }

        val topPx = container.top + marginTop
        val endGapPx = displayWidthPx - (container.right - marginEnd)
        return PanelPlacement(
            widthPx = widthPx,
            heightPx = heightPx,
            leftPx = displayWidthPx - endGapPx - widthPx,
            topPx = topPx,
            endGapPx = endGapPx,
            marginTopPx = marginTop,
            marginEndPx = marginEnd,
        )
    }

    /**
     * The rectangle a missing container falls back to: the whole display.
     *
     * Used for the single frame between `addView` and the first layout pass, so
     * that the provisional `LayoutParams` already have the right shape instead of
     * starting at 0,0 and visibly jumping.
     */
    fun displayRect(context: Context): Rect {
        val metrics = context.resources.displayMetrics
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    /** The display width in pixels — the anchor [PanelGeometry.endPx] is measured from. */
    fun displayWidthPx(context: Context): Int =
        context.resources.displayMetrics.widthPixels
}
