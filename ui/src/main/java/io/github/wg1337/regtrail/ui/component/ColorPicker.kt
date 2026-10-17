package io.github.wg1337.regtrail.ui.component

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A hue/saturation wheel with a brightness slider, a hex field, and a preview.
 *
 * Implemented from scratch on Compose `Canvas` + `pointerInput` rather than
 * pulling in a third-party picker: the maintained Compose color-picker
 * libraries ship an HSV *square*, not a wheel, and the ones that do offer a
 * wheel are abandoned or Android-View based (see docs/PLAN.md 3.3).
 *
 * The wheel bitmap is generated once per size and cached; hue is the angle and
 * saturation the radius, so the centre is white and the rim is fully saturated.
 */
@Composable
fun HsvColorPicker(
    color: Color,
    onColorChange: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hsv = remember(color) { color.toHsv() }

    var hue by remember(hsv) { mutableStateOf(hsv[0]) }
    var sat by remember(hsv) { mutableStateOf(hsv[1]) }
    var value by remember(hsv) { mutableStateOf(hsv[2]) }

    // Drag the wheel -> recompute hue/sat, keep the current brightness.
    fun emit(newHue: Float, newSat: Float, newValue: Float) {
        hue = newHue
        sat = newSat
        value = newValue
        onColorChange(hsvToColor(newHue, newSat, newValue))
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // ---- The wheel -----------------------------------------------------
        var wheelSize by remember { mutableStateOf(IntSize.Zero) }
        var wheelBitmap by remember { mutableStateOf<Bitmap?>(null) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .onSizeChanged { size ->
                        if (size != wheelSize && size.width > 0) {
                            wheelSize = size
                            wheelBitmap = renderWheelBitmap(size.width)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val (h, s) = offset.toHueSat(size.width.toFloat())
                            emit(h, s, value)
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            val (h, s) = change.position.toHueSat(size.width.toFloat())
                            emit(h, s, value)
                        }
                    },
            ) {
                val bmp = wheelBitmap
                if (bmp != null) {
                    drawImage(bmp.asImageBitmap())
                }

                // Selection indicator: small dot at the current hue/sat.
                val radius = size.minDimension / 2f
                val angle = hue.toDouble() * PI / 180.0
                val dist = sat * radius
                val cx = size.width / 2f + (cos(angle) * dist).toFloat()
                val cy = size.height / 2f + (sin(angle) * dist).toFloat()

                val ringColor = if (value > 0.6f) Color.Black else Color.White
                drawCircle(
                    color = ringColor.copy(alpha = 0.9f),
                    radius = 9.dp.toPx(),
                    center = Offset(cx, cy),
                    style = Stroke(width = 2.dp.toPx()),
                )
                drawCircle(
                    color = Color.White,
                    radius = 4.dp.toPx(),
                    center = Offset(cx, cy),
                )
            }
        }

        // ---- Brightness ----------------------------------------------------
        LabeledSlider(
            label = "明度",
            value = value,
            onValueChange = { emit(hue, sat, it) },
            trackBrush = { hsvToColor(hue, sat, 1f) },
        )

        // ---- Hue (numeric, for precision) ----------------------------------
        LabeledSlider(
            label = "色相",
            value = hue,
            valueRange = 0f..360f,
            onValueChange = { emit(it, sat, value) },
            trackBrush = { hsvToColor(it, 1f, 1f) },
            display = { "${it.toInt()}°" },
        )

        // ---- Saturation ----------------------------------------------------
        LabeledSlider(
            label = "饱和度",
            value = sat,
            onValueChange = { emit(hue, it, value) },
            trackBrush = { hsvToColor(hue, it, value) },
            display = { "${(it * 100).toInt()}%" },
        )

        // ---- Hex + preview -------------------------------------------------
        val alpha = color.alpha
        var hexText by remember(color) { mutableStateOf(color.toHexString()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(10.dp)),
                color = color,
                contentColor = if (value > 0.6f) Color.Black else Color.White,
                tonalElevation = 2.dp,
            ) {}
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = hexText,
                onValueChange = { raw ->
                    hexText = raw
                    parseHex(raw)?.let { parsed ->
                        onColorChange(parsed.copy(alpha = alpha))
                    }
                },
                label = { Text("HEX") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                isError = parseHex(hexText) == null,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    trackBrush: (Float) -> Color,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    display: (Float) -> String = { "${(it * 100).toInt()}%" },
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                display(value),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A tiny gradient strip that previews what the slider controls.
            Canvas(
                modifier = Modifier
                    .width(28.dp)
                    .height(12.dp)
                    .clip(RoundedCornerShape(3.dp)),
            ) {
                val steps = 24
                for (i in 0 until steps) {
                    val t = i / (steps - 1f)
                    val x = size.width * i / steps
                    drawRect(
                        color = trackBrush(valueRange.start + t * (valueRange.endInclusive - valueRange.start)),
                        topLeft = Offset(x, 0f),
                        size = androidx.compose.ui.geometry.Size(size.width / steps + 1f, size.height),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Maps a tap/drag position inside a square wheel to (hue°, saturation 0..1). */
private fun Offset.toHueSat(sizePx: Float): Pair<Float, Float> {
    val cx = sizePx / 2f
    val cy = sizePx / 2f
    val dx = x - cx
    val dy = y - cy
    val radius = sizePx / 2f
    val dist = hypot(dx, dy)
    val sat = (dist / radius).coerceIn(0f, 1f)
    var deg = (atan2(dy.toDouble(), dx.toDouble()) * 180.0 / PI).toFloat()
    if (deg < 0f) deg += 360f
    return deg to sat
}

/**
 * Renders the hue/saturation wheel into an ARGB bitmap.
 *
 * Hue = angle, saturation = radius, brightness fixed at 1. Pixel colours are
 * computed directly in HSV so no intermediate gradient compositing is needed.
 */
private fun renderWheelBitmap(sizePx: Int): Bitmap {
    val safe = sizePx.coerceAtLeast(1)
    val pixels = IntArray(safe * safe)
    val cx = safe / 2f
    val cy = safe / 2f
    val radius = safe / 2f
    val radiusSq = radius * radius

    // Anti-aliasing: blend the outer few pixels toward transparent by writing
    // alpha proportional to coverage.
    for (y in 0 until safe) {
        val dy = y + 0.5f - cy
        val dySq = dy * dy
        for (x in 0 until safe) {
            val dx = x + 0.5f - cx
            val distSq = dx * dx + dySq
            if (distSq > radiusSq) {
                pixels[y * safe + x] = 0
                continue
            }
            val dist = kotlin.math.sqrt(distSq)
            val sat = (dist / radius).coerceIn(0f, 1f)
            var deg = (atan2(dy.toDouble(), dx.toDouble()) * 180.0 / PI).toFloat()
            if (deg < 0f) deg += 360f

            val a = if (radius - dist < 1f) ((radius - dist).coerceIn(0f, 1f) * 255f).toInt() else 255
            pixels[y * safe + x] = hsvToArgb(deg, sat, 1f, a)
        }
    }
    return Bitmap.createBitmap(pixels, safe, safe, Bitmap.Config.ARGB_8888)
}

// ---------------------------------------------------------------------------
// HSV <-> ARGB conversions
// ---------------------------------------------------------------------------

/** @return [hue 0..360, saturation 0..1, value 0..1] */
internal fun Color.toHsv(): FloatArray {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min

    val h = when {
        delta < 1e-6f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }

    val s = if (max < 1e-6f) 0f else delta / max
    return floatArrayOf(h, s, max)
}

internal fun hsvToColor(hue: Float, sat: Float, value: Float, alpha: Float = 1f): Color =
    Color(hsvToArgb(hue, sat, value, (alpha * 255f).toInt().coerceIn(0, 255)))

internal fun hsvToArgb(hue: Float, sat: Float, value: Float, alpha: Int = 255): Int {
    val h = ((hue % 360f) + 360f) % 360f
    val s = sat.coerceIn(0f, 1f)
    val v = value.coerceIn(0f, 1f)

    val c = v * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = v - c

    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }

    val r = ((r1 + m) * 255f).toInt().coerceIn(0, 255)
    val g = ((g1 + m) * 255f).toInt().coerceIn(0, 255)
    val b = ((b1 + m) * 255f).toInt().coerceIn(0, 255)

    return (alpha.coerceIn(0, 255) shl 24) or (r shl 16) or (g shl 8) or b
}

/** Formats a colour as `#RRGGBB`, hiding alpha unless it is not fully opaque. */
internal fun Color.toHexString(): String {
    val a = (alpha * 255f).toInt().coerceIn(0, 255)
    val r = (red * 255f).toInt().coerceIn(0, 255)
    val g = (green * 255f).toInt().coerceIn(0, 255)
    val b = (blue * 255f).toInt().coerceIn(0, 255)
    return if (a == 255) {
        String.format("#%02X%02X%02X", r, g, b)
    } else {
        String.format("#%02X%02X%02X%02X", a, r, g, b)
    }
}

/**
 * Parses `#RGB`, `#RRGGBB` or `#AARRGGBB` (with or without the leading `#`).
 * Returns null when the text is not a valid colour.
 */
internal fun parseHex(raw: String): Color? {
    val s = raw.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
    if (s.isEmpty() || !s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
    return when (s.length) {
        3 -> {
            val r = s[0].digitToInt(16) * 17
            val g = s[1].digitToInt(16) * 17
            val b = s[2].digitToInt(16) * 17
            Color(red = r / 255f, green = g / 255f, blue = b / 255f)
        }
        6 -> {
            val v = s.toLong(16)
            Color(
                red = ((v shr 16) and 0xFF) / 255f,
                green = ((v shr 8) and 0xFF) / 255f,
                blue = (v and 0xFF) / 255f,
            )
        }
        8 -> {
            val v = s.toLong(16)
            Color(
                alpha = ((v shr 24) and 0xFF) / 255f,
                red = ((v shr 16) and 0xFF) / 255f,
                green = ((v shr 8) and 0xFF) / 255f,
                blue = (v and 0xFF) / 255f,
            )
        }
        else -> null
    }
}
