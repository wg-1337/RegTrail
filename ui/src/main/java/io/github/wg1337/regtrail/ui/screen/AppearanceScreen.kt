package io.github.wg1337.regtrail.ui.screen

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.wg1337.regtrail.shared.model.OverlayMechanism
import io.github.wg1337.regtrail.shared.model.PaletteStyle
import io.github.wg1337.regtrail.shared.model.ThemeConfig
import io.github.wg1337.regtrail.ui.component.HsvColorPicker
import io.github.wg1337.regtrail.ui.component.toHexString
import io.github.wg1337.regtrail.ui.theme.toComposeColor
import io.github.wg1337.regtrail.ui.theme.toPackedArgb

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    config: ThemeConfig,
    overlayMechanism: OverlayMechanism,
    systemHookAcknowledged: Boolean,
    onMechanismChange: (OverlayMechanism, Boolean) -> Unit,
    onChange: ((ThemeConfig) -> ThemeConfig) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("外观", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "从同一选色盘分别设置软件主题色与悬浮窗主题色",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onReset) { Text("恢复默认") }
        }

        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text("软件主题") },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text("注入悬浮窗") },
            )
        }

        when (tab) {
            0 -> AppThemeTab(config, onChange)
            else -> OverlayTab(
                config = config,
                onChange = onChange,
                overlayMechanism = overlayMechanism,
                systemHookAcknowledged = systemHookAcknowledged,
                onMechanismChange = onMechanismChange,
            )
        }
    }
}

@Composable
private fun AppThemeTab(
    config: ThemeConfig,
    onChange: ((ThemeConfig) -> ThemeConfig) -> Unit,
) {
    // Live preview of the picked seed colour against real M3 surfaces.
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("主题色预览", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Swatch("primary", MaterialTheme.colorScheme.primary)
                Swatch("secondary", MaterialTheme.colorScheme.secondary)
                Swatch("tertiary", MaterialTheme.colorScheme.tertiary)
                Swatch("surface", MaterialTheme.colorScheme.surfaceVariant)
                Swatch("error", MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "当前主题色 ${config.appPrimary.toComposeColor().toHexString()}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("选择主题色", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            HsvColorPicker(
                color = config.appPrimary.toComposeColor(),
                onColorChange = { picked ->
                    onChange { it.copy(appPrimary = picked.toPackedArgb()) }
                },
            )
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("配色方案", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "决定由主题色派生整套 Material 3 配色的方式",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            ToggleRow(
                label = "使用系统取色（Material You）",
                description = "Android 12 及以上可用；开启后忽略下面的方案与主题色",
                checked = config.useDynamicColor,
                onCheckedChange = { on -> onChange { it.copy(useDynamicColor = on) } },
            )

            Spacer(Modifier.height(10.dp))

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
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("深色模式", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            ToggleRow(
                label = "跟随系统",
                description = "关闭后固定使用浅色方案（P0 暂不提供强制深色）",
                checked = config.followSystemDarkTheme,
                onCheckedChange = { on -> onChange { it.copy(followSystemDarkTheme = on) } },
            )
        }
    }
}

@Composable
private fun OverlayTab(
    config: ThemeConfig,
    onChange: ((ThemeConfig) -> ThemeConfig) -> Unit,
    overlayMechanism: OverlayMechanism,
    systemHookAcknowledged: Boolean,
    onMechanismChange: (OverlayMechanism, Boolean) -> Unit,
) {
    // The mechanism choice first: it is a safety decision, and it determines
    // whether a system_server hook is installed at all.
    OverlayMechanismCard(
        selected = overlayMechanism,
        acknowledged = systemHookAcknowledged,
        onSelect = onMechanismChange,
    )

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("悬浮窗主题色", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "与软件主题色相互独立。注入后该颜色会下发到悬浮窗的 CSS 变量 --regtrail-primary。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            HsvColorPicker(
                color = config.overlayPrimary.toComposeColor(),
                onColorChange = { picked ->
                    onChange { it.copy(overlayPrimary = picked.toPackedArgb()) }
                },
            )
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("悬浮窗外观", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))

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
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("预览", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            OverlayPreview(config)
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
private fun Swatch(label: String, color: androidx.compose.ui.graphics.Color) {
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
