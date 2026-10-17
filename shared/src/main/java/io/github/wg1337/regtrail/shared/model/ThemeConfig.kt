package io.github.wg1337.regtrail.shared.model

import kotlinx.serialization.Serializable

/**
 * Appearance configuration shared by the settings app and the injected payload.
 *
 * Colors are stored as packed ARGB [Long] values (not `android.graphics.Color`
 * or Compose `Color`) so this module stays UI-framework agnostic and can be
 * serialized straight to JSON for the overlay's CSS variables.
 *
 * `appPrimary` and `overlayPrimary` are deliberately separate: the user picks
 * the settings-app theme color and the injected floating window's theme color
 * independently, from the same color picker.
 */
@Serializable
data class ThemeConfig(
    /** Seed color for the settings app's Material 3 color scheme. */
    val appPrimary: Long = DEFAULT_APP_PRIMARY,
    /** Accent color used inside the injected floating window. */
    val overlayPrimary: Long = DEFAULT_OVERLAY_PRIMARY,
    /** Overlay text scale, 0.75x .. 2.0x. */
    val overlayFontScale: Float = 1.0f,
    /** Overlay window opacity, 0.3 .. 1.0. */
    val overlayAlpha: Float = 0.95f,
    /** Overlay corner radius in dp, 0 .. 28. */
    val overlayCornerDp: Int = 14,
    /** Overlay default X offset as a fraction of screen width, 0.0 .. 1.0. */
    val overlayDefaultX: Float = 0.75f,
    /** Overlay default Y offset as a fraction of screen height, 0.0 .. 1.0. */
    val overlayDefaultY: Float = 0.25f,
    /**
     * Default size of the expanded floating window, in dp.
     *
     * This is the *published* default, not the live size: the window can be
     * dragged to a different size at runtime, and that choice is remembered
     * inside the target process itself (`OverlaySizeStore`) because the module
     * cannot write the root-owned `config.json`. Changing either slider here
     * publishes a new default, which then wins over the remembered drag — that is
     * how the user resets a window they sized badly.
     *
     * Kept with defaults for the same reason as the fields on
     * `ModuleConfigSnapshot`: existing stored JSON has no such keys.
     */
    val overlayWidthDp: Int = OverlaySizeLimits.DEFAULT_WIDTH_DP,
    val overlayHeightDp: Int = OverlaySizeLimits.DEFAULT_HEIGHT_DP,
    /** When true, follow the system's Material You palette for the app scheme. */
    val useDynamicColor: Boolean = false,
    /** Community Material palette name used when [useDynamicColor] is false. */
    val paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    /** Whether the app follows the system light/dark setting. */
    val followSystemDarkTheme: Boolean = true,
) {
    companion object {
        /** A calm indigo — readable in both light and dark schemes. */
        const val DEFAULT_APP_PRIMARY: Long = 0xFF4C5FD7

        /** A warm amber, so the injected window is visually distinct from the host app. */
        const val DEFAULT_OVERLAY_PRIMARY: Long = 0xFFFFB300
    }
}

/** Material 3 color-scheme variants (from material-color-utilities). */
@Serializable
enum class PaletteStyle {
    TonalSpot,
    Neutral,
    Vibrant,
    Expressive,
    Content,
    Fidelity,
    Monochrome,
    Rainbow,
    FruitSalad,
}
