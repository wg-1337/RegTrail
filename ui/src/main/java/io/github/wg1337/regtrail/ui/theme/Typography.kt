package io.github.wg1337.regtrail.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * RegTrail typography.
 *
 * Uses the platform default font family (no bundled font files yet) so the app
 * stays small; sizes are nudged slightly tighter than the Material 3 defaults,
 * which suits a dense tool UI full of lists and values.
 */
val RegTrailTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
        ),
        titleMedium = base.titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
        ),
        labelLarge = base.labelLarge.copy(
            fontWeight = FontWeight.Medium,
        ),
        // Monospace for anything that displays a value, path, or hex color.
        bodySmall = base.bodySmall.copy(fontFamily = FontFamily.Monospace),
    )
}
