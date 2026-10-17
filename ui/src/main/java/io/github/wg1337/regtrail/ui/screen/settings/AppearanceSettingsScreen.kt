package io.github.wg1337.regtrail.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.wg1337.regtrail.shared.model.OverlaySizeLimits
import io.github.wg1337.regtrail.shared.model.PaletteStyle
import io.github.wg1337.regtrail.shared.model.ThemeConfig
import io.github.wg1337.regtrail.ui.component.HsvColorPicker
import io.github.wg1337.regtrail.ui.component.toHexString
import io.github.wg1337.regtrail.ui.theme.toComposeColor
import io.github.wg1337.regtrail.ui.theme.toPackedArgb

/**
 * The one page that holds every colour.
 *
 * ## Why the picker is the first thing here
 * The colour picker used to live behind a tab of a screen that was not on the
 * bottom bar, and the user could not find it at all. Two changes fix that:
 *
 *  - 外观 is now a row on 设置 with the current colour drawn on it, so the door is
 *    visible from the settings hub;
 *  - both pickers (app theme, overlay theme) are on this page at once, each with
 *    a preset row and a live preview, and neither is behind a tab.
 *
 * The previews are not decoration: the app scheme is derived from the seed
 * colour, so the resolved M3 roles are shown next to the wheel — otherwise the
 * user picks a colour and sees a *different* colour on screen.
 */
@Composable
fun AppearanceSettingsScreen(
    config: ThemeConfig,
    onChange: ((ThemeConfig) -> ThemeConfig) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SubPageHeader(
            title = "外观",
            subtitle = "两个主题色都从这里选，改完立即生效",
            onBack = onBack,
            trailing = {
                TextButton(onClick = onReset) { Text("恢复默认") }
            },
        )

        // ---- App theme colour --------------------------------------------
        SettingsCard(
            title = "应用主题色",
            description = "决定整个设置应用的 Material 3 配色。拖动色盘或点上面的预设色都能改。",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Swatch("primary", MaterialTheme.colorScheme.primary)
                Swatch("secondary", MaterialTheme.colorScheme.secondary)
                Swatch("tertiary", MaterialTheme.colorScheme.tertiary)
                Swatch("surface", MaterialTheme.colorScheme.surfaceVariant)
                Swatch("error", MaterialTheme.colorScheme.error)
            }
            Text(
                "当前 ${config.appPrimary.toComposeColor().toHexString()}" +
                    if (config.useDynamicColor) "（已开启系统取色，主题色暂不生效）" else "",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PresetColorRow(
                current = config.appPrimary,
                onPick = { packed -> onChange { it.copy(appPrimary = packed) } },
            )

            HsvColorPicker(
                color = config.appPrimary.toComposeColor(),
                onColorChange = { picked ->
                    onChange { it.copy(appPrimary = picked.toPackedArgb()) }
                },
            )
        }

        // ---- Overlay theme colour ----------------------------------------
        SettingsCard(
            title = "悬浮窗主题色",
            description = "与软件主题色相互独立。注入后该颜色会下发到悬浮窗的 CSS 变量 --regtrail-primary。",
        ) {
            OverlayAccentPreview(config)
            Text(
                "当前 ${config.overlayPrimary.toComposeColor().toHexString()}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PresetColorRow(
                current = config.overlayPrimary,
                onPick = { packed -> onChange { it.copy(overlayPrimary = packed) } },
            )

            HsvColorPicker(
                color = config.overlayPrimary.toComposeColor(),
                onColorChange = { picked ->
                    onChange { it.copy(overlayPrimary = picked.toPackedArgb()) }
                },
            )
        }

        // ---- Scheme ------------------------------------------------------
        SettingsCard(
            title = "配色方案",
            description = "决定由主题色派生整套 Material 3 配色的方式",
        ) {
            ToggleRow(
                label = "使用系统取色（Material You）",
                description = "Android 12 及以上可用；开启后忽略下面的方案与主题色",
                checked = config.useDynamicColor,
                onCheckedChange = { on -> onChange { it.copy(useDynamicColor = on) } },
            )

            PaletteStyle.entries.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { style ->
                        FilterChip(
                            selected = config.paletteStyle == style,
                            onClick = { onChange { it.copy(paletteStyle = style) } },
                            label = { Text(style.name) },
                            enabled = !config.useDynamicColor,
                        )
                    }
                }
            }
        }

        // ---- Overlay window visuals --------------------------------------
        SettingsCard(
            title = "悬浮窗外观",
            description = "字号、圆角、不透明度与默认尺寸；尺寸是下发给模块的起始值。",
        ) {
            val minWidthDp = OverlaySizeLimits.MIN_WIDTH_DP.toFloat()
            val maxWidthDp = OverlaySizeLimits.SLIDER_MAX_WIDTH_DP.toFloat()
            val minHeightDp = OverlaySizeLimits.MIN_HEIGHT_DP.toFloat()
            val maxHeightDp = OverlaySizeLimits.SLIDER_MAX_HEIGHT_DP.toFloat()
            val widthSteps = ((OverlaySizeLimits.SLIDER_MAX_WIDTH_DP -
                OverlaySizeLimits.MIN_WIDTH_DP) / 20) - 1
            val heightSteps = ((OverlaySizeLimits.SLIDER_MAX_HEIGHT_DP -
                OverlaySizeLimits.MIN_HEIGHT_DP) / 20) - 1

            LabeledValue("字体大小", "${(config.overlayFontScale * 100).toInt()}%")
            Slider(
                value = config.overlayFontScale,
                onValueChange = { v -> onChange { it.copy(overlayFontScale = v) } },
                valueRange = 0.75f..2.0f,
                steps = 24,
            )

            LabeledValue("不透明度", "${(config.overlayAlpha * 100).toInt()}%")
            Slider(
                value = config.overlayAlpha,
                onValueChange = { v -> onChange { it.copy(overlayAlpha = v) } },
                valueRange = 0.3f..1.0f,
                steps = 13,
            )

            LabeledValue("圆角", "${config.overlayCornerDp} dp")
            Slider(
                value = config.overlayCornerDp.toFloat(),
                onValueChange = { v -> onChange { it.copy(overlayCornerDp = v.toInt()) } },
                valueRange = 0f..28f,
                steps = 13,
            )

            LabeledValue("默认位置 X", "${(config.overlayDefaultX * 100).toInt()}%")
            Slider(
                value = config.overlayDefaultX,
                onValueChange = { v -> onChange { it.copy(overlayDefaultX = v) } },
                valueRange = 0f..1f,
            )

            LabeledValue("默认位置 Y", "${(config.overlayDefaultY * 100).toInt()}%")
            Slider(
                value = config.overlayDefaultY,
                onValueChange = { v -> onChange { it.copy(overlayDefaultY = v) } },
                valueRange = 0f..1f,
            )

            // Size is a *published default*, not a live control: the window can
            // also be dragged to a size inside the target app, and that choice is
            // stored there. Publishing here is what resets it.
            //
            // The slider boundaries come from `OverlaySizeLimits` so the range the
            // user can pick is exactly the range the module accepts. The stored
            // value is coerced too: a config written by an older/newer build (or by
            // hand) must not put the slider outside its own range.
            LabeledValue("窗口宽度", "${config.overlayWidthDp} dp")
            Slider(
                value = config.overlayWidthDp.coerceIn(
                    OverlaySizeLimits.MIN_WIDTH_DP,
                    OverlaySizeLimits.SLIDER_MAX_WIDTH_DP,
                ).toFloat(),
                onValueChange = { v -> onChange { it.copy(overlayWidthDp = v.toInt()) } },
                valueRange = minWidthDp..maxWidthDp,
                steps = widthSteps,
            )

            LabeledValue("窗口高度", "${config.overlayHeightDp} dp")
            Slider(
                value = config.overlayHeightDp.coerceIn(
                    OverlaySizeLimits.MIN_HEIGHT_DP,
                    OverlaySizeLimits.SLIDER_MAX_HEIGHT_DP,
                ).toFloat(),
                onValueChange = { v -> onChange { it.copy(overlayHeightDp = v.toInt()) } },
                valueRange = minHeightDp..maxHeightDp,
                steps = heightSteps,
            )
            Text(
                "最小 ${OverlaySizeLimits.MIN_WIDTH_DP}x" +
                    "${OverlaySizeLimits.MIN_HEIGHT_DP} dp；模块会把超出屏幕 90% 的" +
                    "尺寸压回可用范围。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OverlayPreview(config)
        }

        Text(
            "配色方案、深色模式与悬浮窗尺寸都会随「恢复默认」一起复位；" +
                "悬浮窗的实现方式与目标应用在「悬浮窗」分组里。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
    }
}

/**
 * Preset seeds, offered before the wheel.
 *
 * A wheel is precise but slow; most users want "a blue-ish one". These are the
 * Material reference hues, with the shipping default first, so the common case
 * is one tap and the wheel stays for the case that actually needs it.
 */
private val PRESET_COLORS: List<Long> = listOf(
    ThemeConfig.DEFAULT_APP_PRIMARY,
    ThemeConfig.DEFAULT_OVERLAY_PRIMARY,
    0xFF00897BL, // 青
    0xFF2E7D32L, // 绿
    0xFFEF6C00L, // 橙
    0xFFC62828L, // 红
    0xFFAD1457L, // 玫红
    0xFF6A1B9AL, // 紫
    0xFF1565C0L, // 蓝
    0xFF455A64L, // 蓝灰
)

/** A horizontally scrollable row of preset seeds; tapping one applies it. */
@Composable
private fun PresetColorRow(
    current: Long,
    onPick: (Long) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PRESET_COLORS.forEach { packed ->
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable { onPick(packed) },
                contentAlignment = Alignment.Center,
            ) {
                ColorDot(
                    color = packed.toComposeColor(),
                    selected = packed == current,
                    size = 30.dp,
                )
            }
        }
    }
}

/** A resolved-M3-role swatch with its role name under it. */
@Composable
private fun Swatch(label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(10.dp)),
            color = color,
            tonalElevation = 1.dp,
        ) {}
        Spacer(Modifier.height(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * The overlay accent applied to a mock window header.
 *
 * Small on purpose: the point is to show the *accent* against a neutral surface,
 * not to reproduce the whole injected UI (that lives in `OverlayPreview`).
 */
@Composable
private fun OverlayAccentPreview(config: ThemeConfig) {
    val accent = config.overlayPrimary.toComposeColor()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(config.overlayCornerDp.dp),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColorDot(color = accent, size = 12.dp)
            Spacer(Modifier.width(8.dp))
            Text("RegTrail · 悬浮窗", style = MaterialTheme.typography.labelMedium, color = accent)
            Spacer(Modifier.width(8.dp))
            Text(
                "methods: 128  hooks: 3",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OverlayPreview(config: ThemeConfig) {
    val accent = config.overlayPrimary.toComposeColor()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.width(220.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(config.overlayCornerDp.dp),
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(5.dp)),
                        color = accent,
                    ) {}
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "RegTrail",
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "示例文本 Sample 12345",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize * config.overlayFontScale,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "methods: 128  hooks: 3",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = MaterialTheme.typography.bodySmall.fontSize * config.overlayFontScale,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
