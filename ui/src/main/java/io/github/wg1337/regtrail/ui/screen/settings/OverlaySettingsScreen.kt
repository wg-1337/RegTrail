package io.github.wg1337.regtrail.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.wg1337.regtrail.shared.model.OverlayMechanism
import io.github.wg1337.regtrail.shared.model.TargetApp
import io.github.wg1337.regtrail.ui.screen.OverlayMechanismCard
import io.github.wg1337.regtrail.ui.state.AppSettings

/**
 * Floating-window behaviour: how the window is realised, when it appears, and
 * where.
 *
 * Split from 外观 on purpose: colours and sizes are cosmetic and cheap to change,
 * while this page contains the one setting that can leave a device unbootable
 * (the `system_server` hook) and the list of apps that will actually be
 * decorated. Mixing the two under one tab was how the risk dialog ended up
 * hidden three taps deep behind 外观 → 注入悬浮窗.
 */
@Composable
fun OverlaySettingsScreen(
    mechanism: OverlayMechanism,
    acknowledged: Boolean,
    settings: AppSettings,
    overlayTargets: Set<String>,
    apps: List<TargetApp>,
    onMechanismChange: (OverlayMechanism, Boolean) -> Unit,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onOpenScope: () -> Unit,
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
            title = "悬浮窗",
            subtitle = "实现方式、显示时机与目标应用",
            onBack = onBack,
        )

        // The mechanism choice first: it is a safety decision, and it determines
        // whether a system_server hook is installed at all.
        OverlayMechanismCard(
            selected = mechanism,
            acknowledged = acknowledged,
            onSelect = onMechanismChange,
        )

        SettingsGroup("显示时机") {
            SettingsSwitchRow(
                title = "随目标应用启动时显示",
                subtitle = if (settings.overlayAutoAttach) {
                    "目标应用一启动，悬浮窗自动出现（仍需在下面选过目标应用）"
                } else {
                    "已关闭：任何应用都不会出现悬浮窗。模块仍会加载，调试与 Root 功能不受影响。"
                },
                checked = settings.overlayAutoAttach,
                onCheckedChange = { on -> onSettingsChange { it.copy(overlayAutoAttach = on) } },
            )
        }

        SettingsGroup("悬浮窗目标应用") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    if (overlayTargets.isEmpty()) {
                        "尚未选择任何应用 —— 当前不会有悬浮窗出现。"
                    } else {
                        "已选择 ${overlayTargets.size} 个应用："
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (overlayTargets.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    overlayTargets
                        .map { pkg -> apps.firstOrNull { it.packageName == pkg }?.label ?: pkg }
                        .sorted()
                        .take(12)
                        .forEach { label ->
                            Text(
                                "· $label",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    if (overlayTargets.size > 12) {
                        Text(
                            "…以及另外 ${overlayTargets.size - 12} 个",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        overlayTargets.sorted().joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SettingsNavRow(
                title = "在「目标」页选择应用",
                subtitle = "悬浮窗开关是每个应用一行；作用域（是否允许加载）与它是两件事",
                icon = Icons.Filled.Shield,
                onClick = onOpenScope,
                dividerAbove = true,
            )
        }

        Text(
            "悬浮窗的颜色、字号、圆角与默认尺寸在「外观」里；" +
                "system 作用域的状态在设置的「高级 / 危险」分组里可见。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
    }
}

/**
 * One-line description of the selected mechanism, reused by the settings hub.
 *
 * Centralised so the hub and this page cannot describe the same state
 * differently — the previous copy disagreed with itself about whether `system`
 * scope was required.
 */
internal fun overlayMechanismSummary(
    mechanism: OverlayMechanism,
    acknowledged: Boolean,
): String = when {
    mechanism == OverlayMechanism.ApplicationOverlay && acknowledged ->
        "免权限悬浮窗（有风险，已确认）· 需要 system 作用域"
    mechanism == OverlayMechanism.ApplicationOverlay ->
        "免权限悬浮窗（尚未确认风险，不会启用）"
    else ->
        "内嵌式（安全）· 不需要权限，也不会 Hook system_server"
}
