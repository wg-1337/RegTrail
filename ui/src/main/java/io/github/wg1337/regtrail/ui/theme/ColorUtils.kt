package io.github.wg1337.regtrail.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.dynamiccolor.DynamicScheme
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeContent
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeFidelity
import com.materialkolor.scheme.SchemeFruitSalad
import com.materialkolor.scheme.SchemeMonochrome
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeRainbow
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant
import io.github.wg1337.regtrail.shared.model.PaletteStyle

/**
 * Bridges our UI-framework-agnostic [io.github.wg1337.regtrail.shared.model.ThemeConfig]
 * (which stores colors as packed ARGB `Long`s) to Compose [Color] values.
 */
fun Long.toComposeColor(): Color = Color(this.toInt())

fun Color.toPackedArgb(): Long = this.toArgb().toLong() and 0xFFFFFFFFL

/**
 * Builds a Material 3 [DynamicScheme] from a user-picked seed color.
 *
 * Uses `material-color-utilities` (MIT / Apache-2.0) directly instead of
 * Compose Multiplatform's `material3` artifact: CMP's M3 is still alpha and
 * would silently upgrade AndroidX Material 3 to `1.5.0-alpha*` (see
 * `docs/PLAN.md` 3.3). This gives us the full set of M3 color roles on every
 * API level, not just API 31+.
 */
internal fun buildScheme(
    seedArgb: Int,
    dark: Boolean,
    style: PaletteStyle,
    contrastLevel: Double = 0.0,
): DynamicScheme {
    val hct = Hct.fromInt(seedArgb)
    val spec = ColorSpec.SpecVersion.SPEC_2025
    val platform = DynamicScheme.Platform.PHONE
    return when (style) {
        PaletteStyle.TonalSpot -> SchemeTonalSpot(hct, dark, contrastLevel, spec, platform)
        PaletteStyle.Neutral -> SchemeNeutral(hct, dark, contrastLevel, spec, platform)
        PaletteStyle.Vibrant -> SchemeVibrant(hct, dark, contrastLevel, spec, platform)
        PaletteStyle.Expressive -> SchemeExpressive(hct, dark, contrastLevel, spec, platform)
        PaletteStyle.Content -> SchemeContent(hct, dark, contrastLevel, spec, platform)
        PaletteStyle.Fidelity -> SchemeFidelity(hct, dark, contrastLevel, spec, platform)
        PaletteStyle.Monochrome -> SchemeMonochrome(hct, dark, contrastLevel, spec, platform)
        PaletteStyle.Rainbow -> SchemeRainbow(hct, dark, contrastLevel, spec, platform)
        PaletteStyle.FruitSalad -> SchemeFruitSalad(hct, dark, contrastLevel, spec, platform)
    }
}

/** Converts an ARGB int from the scheme into a Compose [Color]. */
internal fun Int.toColor(): Color = Color(this)
