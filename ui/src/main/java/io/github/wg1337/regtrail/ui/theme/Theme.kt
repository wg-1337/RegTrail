package io.github.wg1337.regtrail.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.wg1337.regtrail.shared.model.PaletteStyle
import io.github.wg1337.regtrail.shared.model.ThemeConfig

/**
 * RegTrail's Material 3 theme.
 *
 * Color resolution order:
 *  1. [ThemeConfig.useDynamicColor] on API 31+ -> the system's Material You
 *     palette via `dynamicLight/DarkColorScheme`.
 *  2. Otherwise -> a full M3 scheme generated from the user's picked
 *     [ThemeConfig.appPrimary] seed colour, on every API level.
 */
@Composable
fun RegTrailTheme(
    config: ThemeConfig,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = if (config.followSystemDarkTheme) systemDark else false
    val context = LocalContext.current

    val scheme: ColorScheme = remember(
        config.appPrimary,
        config.useDynamicColor,
        config.paletteStyle,
        dark,
        context,
    ) {
        if (config.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            schemeFromSeed(
                seedArgb = config.appPrimary.toInt(),
                dark = dark,
                style = config.paletteStyle,
            )
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = RegTrailTypography,
        content = content,
    )
}

/**
 * Builds an AndroidX Material 3 [ColorScheme] from a seed colour using
 * `material-color-utilities`.
 */
internal fun schemeFromSeed(
    seedArgb: Int,
    dark: Boolean,
    style: PaletteStyle,
): ColorScheme {
    val s = buildScheme(seedArgb = seedArgb, dark = dark, style = style)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = s.primary.toColor(),
        onPrimary = s.onPrimary.toColor(),
        primaryContainer = s.primaryContainer.toColor(),
        onPrimaryContainer = s.onPrimaryContainer.toColor(),
        inversePrimary = s.inversePrimary.toColor(),

        secondary = s.secondary.toColor(),
        onSecondary = s.onSecondary.toColor(),
        secondaryContainer = s.secondaryContainer.toColor(),
        onSecondaryContainer = s.onSecondaryContainer.toColor(),

        tertiary = s.tertiary.toColor(),
        onTertiary = s.onTertiary.toColor(),
        tertiaryContainer = s.tertiaryContainer.toColor(),
        onTertiaryContainer = s.onTertiaryContainer.toColor(),

        error = s.error.toColor(),
        onError = s.onError.toColor(),
        errorContainer = s.errorContainer.toColor(),
        onErrorContainer = s.onErrorContainer.toColor(),

        background = s.background.toColor(),
        onBackground = s.onBackground.toColor(),

        surface = s.surface.toColor(),
        onSurface = s.onSurface.toColor(),
        surfaceVariant = s.surfaceVariant.toColor(),
        onSurfaceVariant = s.onSurfaceVariant.toColor(),
        surfaceTint = s.surfaceTint.toColor(),
        inverseSurface = s.inverseSurface.toColor(),
        inverseOnSurface = s.inverseOnSurface.toColor(),

        outline = s.outline.toColor(),
        outlineVariant = s.outlineVariant.toColor(),
        scrim = s.scrim.toColor(),

        surfaceBright = s.surfaceBright.toColor(),
        surfaceDim = s.surfaceDim.toColor(),
        surfaceContainer = s.surfaceContainer.toColor(),
        surfaceContainerHigh = s.surfaceContainerHigh.toColor(),
        surfaceContainerHighest = s.surfaceContainerHighest.toColor(),
        surfaceContainerLow = s.surfaceContainerLow.toColor(),
        surfaceContainerLowest = s.surfaceContainerLowest.toColor(),

        // Fixed (non-adaptive) accent roles, Material 3 "tonal spot" extras.
        primaryFixed = s.primaryFixed.toColor(),
        primaryFixedDim = s.primaryFixedDim.toColor(),
        onPrimaryFixed = s.onPrimaryFixed.toColor(),
        onPrimaryFixedVariant = s.onPrimaryFixedVariant.toColor(),

        secondaryFixed = s.secondaryFixed.toColor(),
        secondaryFixedDim = s.secondaryFixedDim.toColor(),
        onSecondaryFixed = s.onSecondaryFixed.toColor(),
        onSecondaryFixedVariant = s.onSecondaryFixedVariant.toColor(),

        tertiaryFixed = s.tertiaryFixed.toColor(),
        tertiaryFixedDim = s.tertiaryFixedDim.toColor(),
        onTertiaryFixed = s.onTertiaryFixed.toColor(),
        onTertiaryFixedVariant = s.onTertiaryFixedVariant.toColor(),
    )
}
