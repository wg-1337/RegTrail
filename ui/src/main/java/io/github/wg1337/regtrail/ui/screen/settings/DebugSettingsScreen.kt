package io.github.wg1337.regtrail.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.wg1337.regtrail.ui.debug.DebugUiState
import io.github.wg1337.regtrail.ui.state.AppSettings
import io.github.wg1337.regtrail.ui.state.HookMode

/**
 * Debugger configuration.
 *
 * The live debugger stays a separate screen because it is a work surface: it
 * searches, installs and removes hooks. What belongs here is the policy that
 * applies to *every* hook — what a hook does by default, whether the target
 * method is deoptimized first, and how much log is kept. Those used to be
 * hard-coded constants, which is what "调试 doesn't need a page, but its settings
 * must exist" is asking for.
 */
@Composable
fun DebugSettingsScreen(
    settings: AppSettings,
    debugState: DebugUiState,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onOpenDebugger: () -> Unit,
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
            title = "调试",
            subtitle = "Hook 默认行为、内联处理与日志",
            onBack = onBack,
        )

        SettingsCard(
            title = "Hook 默认行为",
            description = "在「实时调试」里对一个方法点主按钮时执行的操作；另一个操作仍然可以用旁边的次按钮。",
        ) {
            HookMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = settings.hookDefaultMode == mode,
                            role = Role.RadioButton,
                            onClick = { onChange { it.copy(hookDefaultMode = mode) } },
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = settings.hookDefaultMode == mode,
                        onClick = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(mode.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            mode.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SettingsGroup("内联处理") {
            SettingsSwitchRow(
                title = "自动 deoptimize",
                subtitle = if (settings.debugAutoDeoptimize) {
                    "挂 Hook 前先取消内联，已内联的方法也能被 Hook 到（会强制重新编译该方法）"
                } else {
                    "已关闭：已内联的方法可能挂上却永不触发，但不会强制重新编译目标方法"
                },
                checked = settings.debugAutoDeoptimize,
                onCheckedChange = { on -> onChange { it.copy(debugAutoDeoptimize = on) } },
            )
        }

        SettingsCard(
            title = "日志上限",
            description = "设置这一侧保留的调试日志条数。模块自己的日志文件不受影响。",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppSettings.DEBUG_LOG_LIMIT_CHOICES.forEach { limit ->
                    FilterChip(
                        selected = settings.debugLogLimit == limit,
                        onClick = { onChange { it.copy(debugLogLimit = limit) } },
                        label = { Text("$limit") },
                    )
                }
            }
            Text(
                "当前保留最近 ${settings.debugLogLimit} 条；超出后最旧的条目会被丢弃。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard(
            title = "目标进程",
            description = "实时调试需要目标进程里已经加载了模块：应用在作用域内，且勾选了悬浮窗。",
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (debugState.attached) "已连接目标进程" else "未连接目标进程",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (debugState.attached) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    if (debugState.attached) {
                        Text(
                            "${debugState.targetPackage ?: "—"} · " +
                                "${debugState.hookCount} 个 Hook · " +
                                "${debugState.activityCount} 个 Activity",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = onOpenDebugger, modifier = Modifier.fillMaxWidth()) {
                Text("打开实时调试")
            }
        }
    }
}
