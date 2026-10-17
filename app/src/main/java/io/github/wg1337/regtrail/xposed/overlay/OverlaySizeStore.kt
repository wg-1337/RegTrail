package io.github.wg1337.regtrail.xposed.overlay

import android.content.Context
import android.content.SharedPreferences
import io.github.wg1337.regtrail.shared.model.OverlaySizeLimits
import io.github.wg1337.regtrail.xposed.RegTrailLog

/**
 * Remembers the size the user dragged the floating window to.
 *
 * ## Why this is not just a field in `config.json`
 * The published snapshot lives at `/data/local/tmp/regtrail/config.json`, is
 * written by the settings app **through a root shell**, and is `0644 root:root`.
 * The module runs inside a *third-party* app's process with that app's own
 * (unprivileged) UID, so it can read that file but it can never write it. Asking
 * the user for root inside every target app is not an option.
 *
 * What the module *can* always write is the target app's own private storage,
 * which is exactly where a per-app UI preference belongs anyway. `SharedPreferences`
 * there survives the target process being killed, which is the property the
 * requirement actually asks for ("persist across process restarts of the target
 * app"); persisting across Activities is free because the mechanism that adds the
 * window either lives for the whole process (`TYPE_APPLICATION_OVERLAY`) or
 * re-reads this store on every attach (`DecorView` fallback).
 *
 * ## Why the config values are stored alongside the dragged size
 * Two writers disagree about the size: the user dragging the grip, and the
 * settings app publishing `overlayWidthDp` / `overlayHeightDp`. Without a rule,
 * one silently always wins — either the slider does nothing, or every restart
 * throws away the user's drag.
 *
 * The rule implemented here is "last *decision* wins":
 *
 *  - A drag remembers the size **and** the published defaults it was made on top
 *    of ([KEY_BASE_WIDTH] / [KEY_BASE_HEIGHT]).
 *  - On attach, the remembered size is used only while those recorded defaults
 *    still equal what the settings app publishes now.
 *  - If the settings app published a different default in the meantime, its
 *    value is used and the override is dropped — so moving the slider in the
 *    settings app always resets a window the user sized badly, while simply
 *    reopening the target app keeps the user's own size.
 *
 * That is why [resolve] takes the two published values as arguments instead of
 * hiding the comparison in here.
 */
internal class OverlaySizeStore(context: Context) {

    /**
     * Target app's private preferences.
     *
     * `applicationContext` is used because a DecorView attach can be handed an
     * Activity whose lifetime ends on the next navigation, and a preference
     * handle outliving it is the whole point.
     */
    private val prefs: SharedPreferences by lazy {
        (context.applicationContext ?: context)
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * The size the panel should open at.
     *
     * @param configWidthDp published default width, already trusted to be sane.
     * @param configHeightDp published default height.
     * @return a remembered drag when it is still valid, otherwise the published
     *   default. Values are *not* clamped here — clamping needs the screen size,
     *   which the caller ([OverlayController]) owns.
     */
    fun resolve(configWidthDp: Int, configHeightDp: Int): Pair<Int, Int> {
        val stored = runCatching {
            val width = prefs.getInt(KEY_WIDTH, ABSENT)
            val height = prefs.getInt(KEY_HEIGHT, ABSENT)
            if (width <= 0 || height <= 0) return@runCatching null

            val baseWidth = prefs.getInt(KEY_BASE_WIDTH, ABSENT)
            val baseHeight = prefs.getInt(KEY_BASE_HEIGHT, ABSENT)
            if (baseWidth != configWidthDp || baseHeight != configHeightDp) {
                // The settings app published a different default since this drag:
                // its decision is newer, so it wins.
                return@runCatching null
            }
            width to height
        }.getOrNull()

        if (stored == null) {
            clear()
            return configWidthDp to configHeightDp
        }
        return stored
    }

    /** Records a user-chosen size together with the defaults it was based on. */
    fun remember(
        widthDp: Int,
        heightDp: Int,
        configWidthDp: Int,
        configHeightDp: Int,
    ) {
        runCatching {
            prefs.edit()
                .putInt(KEY_WIDTH, widthDp)
                .putInt(KEY_HEIGHT, heightDp)
                .putInt(KEY_BASE_WIDTH, configWidthDp)
                .putInt(KEY_BASE_HEIGHT, configHeightDp)
                // `apply()`, not `commit()`: this is called from the main thread
                // between layout passes while the user is dragging a grip, and a
                // synchronous fsync there would stutter the resize.
                .apply()
        }.onFailure {
            // Never let a preferences failure break the window: the size simply
            // will not survive a restart.
            RegTrailLog.w("overlay: cannot persist panel size", it)
        }
    }

    /**
     * The remembered window position, in pixels, or null when never dragged.
     *
     * Only the `TYPE_APPLICATION_OVERLAY` mechanism uses this: there the window
     * has a real screen position. The remembered value is *not* trusted to still
     * be on screen — the controller clamps it against the current display when it
     * applies it (see `OverlayController.snapIntoBounds`), because a position saved
     * in landscape can be far off-screen after a rotation.
     */
    fun position(): Pair<Int, Int>? = runCatching {
        val x = prefs.getInt(KEY_X, ABSENT)
        val y = prefs.getInt(KEY_Y, ABSENT)
        if (x == ABSENT || y == ABSENT) null else x to y
    }.getOrNull()

    /** Records a dragged window position, in pixels. */
    fun rememberPosition(xPx: Int, yPx: Int) {
        runCatching {
            prefs.edit().putInt(KEY_X, xPx).putInt(KEY_Y, yPx).apply()
        }.onFailure {
            RegTrailLog.w("overlay: cannot persist panel position", it)
        }
    }

    /**
     * The remembered panel position **inside the Activity content area**, in
     * pixels, for the DecorView mechanism (`topMargin`, `marginEnd`).
     *
     * Kept separately from [position] because the two mechanisms measure position
     * in different spaces: one against the screen, one against the host Activity's
     * content view. Sharing one pair would make switching mechanisms teleport the
     * panel.
     */
    fun margins(): Pair<Int, Int>? = runCatching {
        val top = prefs.getInt(KEY_MARGIN_TOP, ABSENT)
        val end = prefs.getInt(KEY_MARGIN_END, ABSENT)
        if (top == ABSENT || end == ABSENT) null else top to end
    }.getOrNull()

    /** Records a dragged DecorView margin pair, in pixels. */
    fun rememberMargins(topPx: Int, endPx: Int) {
        runCatching {
            prefs.edit().putInt(KEY_MARGIN_TOP, topPx).putInt(KEY_MARGIN_END, endPx).apply()
        }.onFailure {
            RegTrailLog.w("overlay: cannot persist panel margins", it)
        }
    }

    /** Drops the remembered size, falling back to the published default. */
    private fun clear() {
        runCatching {
            if (prefs.contains(KEY_WIDTH) || prefs.contains(KEY_HEIGHT)) {
                prefs.edit()
                    .remove(KEY_WIDTH)
                    .remove(KEY_HEIGHT)
                    .remove(KEY_BASE_WIDTH)
                    .remove(KEY_BASE_HEIGHT)
                    .apply()
            }
        }
    }

    private companion object {
        /**
         * Preference file name.
         *
         * Deliberately prefixed with the module name and **not** the settings
         * app's own file (`regtrail_config`): this file is created inside the
         * *target* app's storage, and a distinctive name is what makes it
         * findable when debugging a "my size was not remembered" report.
         */
        const val PREFS_NAME = "regtrail_overlay"

        const val KEY_WIDTH = "panel_width_dp"
        const val KEY_HEIGHT = "panel_height_dp"
        const val KEY_BASE_WIDTH = "panel_base_width_dp"
        const val KEY_BASE_HEIGHT = "panel_base_height_dp"

        /** Window position, in screen pixels (application-overlay mechanism). */
        const val KEY_X = "panel_x_px"
        const val KEY_Y = "panel_y_px"

        /** Panel position inside the Activity content area, in pixels. */
        const val KEY_MARGIN_TOP = "panel_margin_top_px"
        const val KEY_MARGIN_END = "panel_margin_end_px"

        /** Sentinel for "no such key"; any non-positive width means the same. */
        const val ABSENT = -1
    }
}

/**
 * Size arithmetic shared by both overlay mechanisms.
 *
 * Both `TYPE_APPLICATION_OVERLAY` and the DecorView fallback ultimately set a
 * `View`'s layout size, so both need the identical dp→px and clamping rules.
 * Duplicating them per mechanism is how the two paths end up disagreeing about
 * what "clamped to the screen" means.
 */
internal object OverlaySizeMath {

    /** Converts the panel size in dp to pixels using the **target's** density. */
    fun px(context: Context, widthDp: Int, heightDp: Int): Pair<Int, Int> {
        val density = context.resources.displayMetrics.density
        val width = (widthDp * density + 0.5f).toInt()
        val height = (heightDp * density + 0.5f).toInt()
        return width to height
    }

    /**
     * Clamps a requested size to the documented bounds for the current screen.
     *
     * @param screenWidthPx usable screen width in pixels (the caller may already
     *   have subtracted insets; the 90 % ceiling is applied on top).
     * @param density display density, so the dp ceiling matches what the user sees.
     */
    fun clamp(
        widthDp: Int,
        heightDp: Int,
        screenWidthPx: Int,
        screenHeightPx: Int,
        density: Float,
    ): Pair<Int, Int> = OverlaySizeLimits.clampWidth(
        widthDp,
        OverlaySizeLimits.maxWidthDp(screenWidthPx, density),
    ) to OverlaySizeLimits.clampHeight(
        heightDp,
        OverlaySizeLimits.maxHeightDp(screenHeightPx, density),
    )
}
