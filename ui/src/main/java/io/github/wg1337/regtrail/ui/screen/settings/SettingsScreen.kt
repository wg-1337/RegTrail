package io.github.wg1337.regtrail.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.wg1337.regtrail.shared.model.OverlayMechanism
import io.github.wg1337.regtrail.shared.model.ScopeUiState
import io.github.wg1337.regtrail.shared.model.ThemeConfig
import io.github.wg1337.regtrail.ui.agent.AgentUiState
import io.github.wg1337.regtrail.ui.component.toHexString
import io.github.wg1337.regtrail.ui.debug.DebugUiState
import io.github.wg1337.regtrail.ui.nav.Dest
import io.github.wg1337.regtrail.ui.patch.PatchUiState
import io.github.wg1337.regtrail.ui.script.ScriptUiState
import io.github.wg1337.regtrail.ui.state.AppSettings
import io.github.wg1337.regtrail.ui.theme.toComposeColor

/**
 * The single settings home: everything configuration-shaped, grouped by what it
 * affects.
 *
 * ## Why one page instead of six tabs
 * The old layout spread configuration across the bottom bar (终端/调试/打包 as
 * tabs) and one unlisted screen (外观, which also held 悬浮窗 and both colour
 * pickers). Finding a setting therefore required remembering which page owned
 * it. The groups below are named after the *thing* being configured, and each
 * row states its current value, so a setting can be found — and often changed
 * without leaving the page — by looking at a six-group list.
 *
 * Rows with a chevron open a focused sub-page; rows with a switch are changed in
 * place. Nothing is hidden behind a tab inside a tab.
 *
 * @param onNavigate navigation for the chevron rows. Kept as a single callback
 *   so the hub does not need a lambda per destination.
 * @param onResetAll clears the app's settings. The caller resets the two stores
 *   and the platform half (mechanism, acknowledgement, Agent credentials).
 */
@Composable
fun SettingsScreen(
    theme: ThemeConfig,
    settings: AppSettings,
    scopeState: ScopeUiState,
    debugState: DebugUiState,
    patchState: PatchUiState,
    agentState: AgentUiState,
    scriptState: ScriptUiState,
    overlayMechanism: OverlayMechanism,
    systemHookAcknowledged: Boolean,
    onThemeChange: ((ThemeConfig) -> ThemeConfig) -> Unit,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onResetAll: () -> Unit,
    onNavigate: (Dest) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmReset by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text("设置", style = MaterialTheme.typography.headlineSmall)
            Text(
                "外观、悬浮窗、终端、调试、Agent 与打包的全部配置都在这里；" +
                    "点带箭头的行进入细节，带开关的行当场生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- 外观 ---------------------------------------------------------
        SettingsGroup("外观") {
            SettingsNavRow(
                title = "应用主题色",
                subtitle = "当前 ${theme.appPrimary.toComposeColor().toHexString()} · 打开选色盘",
                icon = null,
                leading = { ColorDot(theme.appPrimary.toComposeColor()) },
                onClick = { onNavigate(Dest.Appearance) },
            )
            SettingsNavRow(
                title = "悬浮窗主题色",
                subtitle = "当前 ${theme.overlayPrimary.toComposeColor().toHexString()} · 与软件主题色独立",
                leading = { ColorDot(theme.overlayPrimary.toComposeColor()) },
                onClick = { onNavigate(Dest.Appearance) },
                dividerAbove = true,
            )
            SettingsNavRow(
                title = "悬浮窗外观",
                subtitle = "字号 ${(theme.overlayFontScale * 100).toInt()}% · " +
                    "圆角 ${theme.overlayCornerDp} dp · " +
                    "不透明度 ${(theme.overlayAlpha * 100).toInt()}% · " +
                    "${theme.overlayWidthDp}x${theme.overlayHeightDp} dp",
                icon = Icons.Filled.Palette,
                onClick = { onNavigate(Dest.Appearance) },
                dividerAbove = true,
            )
            SettingsSwitchRow(
                title = "深色模式跟随系统",
                subtitle = if (theme.followSystemDarkTheme) {
                    "系统切换深浅色时，RegTrail 一起切换"
                } else {
                    "固定使用浅色方案"
                },
                checked = theme.followSystemDarkTheme,
                onCheckedChange = { on -> onThemeChange { it.copy(followSystemDarkTheme = on) } },
                dividerAbove = true,
            )
        }

        // ---- 悬浮窗 -------------------------------------------------------
        SettingsGroup("悬浮窗") {
            SettingsNavRow(
                title = "注入方式",
                subtitle = overlayMechanismSummary(overlayMechanism, systemHookAcknowledged),
                icon = Icons.Filled.Layers,
                onClick = { onNavigate(Dest.Overlay) },
            )
            SettingsNavRow(
                title = "悬浮窗目标应用",
                subtitle = if (scopeState.overlayTargets.isEmpty()) {
                    "尚未选择应用 · 在「目标」页逐个打开悬浮窗开关"
                } else {
                    "已选择 ${scopeState.overlayTargets.size} 个应用"
                },
                icon = Icons.Filled.Shield,
                onClick = { onNavigate(Dest.Scope) },
                dividerAbove = true,
            )
            SettingsSwitchRow(
                title = "随目标应用启动时显示",
                subtitle = if (settings.overlayAutoAttach) {
                    "目标应用启动后悬浮窗自动出现"
                } else {
                    "已关闭：任何应用都不会出现悬浮窗（调试与 Root 功能不受影响）"
                },
                checked = settings.overlayAutoAttach,
                onCheckedChange = { on -> onSettingsChange { it.copy(overlayAutoAttach = on) } },
                dividerAbove = true,
            )
        }

        // ---- 终端 ---------------------------------------------------------
        SettingsGroup("终端") {
            SettingsNavRow(
                title = "默认模式",
                subtitle = "${settings.terminalDefaultMode.label} · " +
                    settings.terminalDefaultMode.description,
                icon = Icons.Filled.Terminal,
                onClick = { onNavigate(Dest.TerminalSettings) },
            )
            SettingsNavRow(
                title = "工作区路径",
                subtitle = settings.terminalWorkspacePath,
                onClick = { onNavigate(Dest.TerminalSettings) },
                dividerAbove = true,
            )
            SettingsNavRow(
                title = "字号",
                subtitle = "${settings.terminalFontSizeSp} sp",
                onClick = { onNavigate(Dest.TerminalSettings) },
                dividerAbove = true,
            )
            SettingsNavRow(
                title = "打开终端",
                subtitle = "以当前模式连接 shell；模式可在终端页顶部随时切换",
                leading = {
                    Text(
                        ">_",
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                onClick = { onNavigate(Dest.Terminal) },
                dividerAbove = true,
            )
        }

        // ---- 调试 ---------------------------------------------------------
        SettingsGroup("调试") {
            SettingsNavRow(
                title = "Hook 默认行为",
                subtitle = "${settings.hookDefaultMode.label} · ${settings.hookDefaultMode.description}",
                icon = Icons.Filled.BugReport,
                onClick = { onNavigate(Dest.DebugSettings) },
            )
            SettingsSwitchRow(
                title = "自动 deoptimize",
                subtitle = if (settings.debugAutoDeoptimize) {
                    "挂 Hook 前先取消内联，已内联的方法也能被 Hook 到"
                } else {
                    "已关闭：已内联的方法可能挂不上 Hook，但不会强制重新编译目标方法"
                },
                checked = settings.debugAutoDeoptimize,
                onCheckedChange = { on -> onSettingsChange { it.copy(debugAutoDeoptimize = on) } },
                dividerAbove = true,
            )
            SettingsNavRow(
                title = "日志上限",
                subtitle = "${settings.debugLogLimit} 条（完整日志仍写入模块日志文件）",
                onClick = { onNavigate(Dest.DebugSettings) },
                dividerAbove = true,
            )
            SettingsNavRow(
                title = "打开实时调试",
                subtitle = when {
                    debugState.attached -> "已连接 ${debugState.targetPackage ?: "目标进程"} · " +
                        "${debugState.hookCount} 个 Hook"
                    debugState.busy -> "正在读取目标进程状态…"
                    else -> "未连接目标进程"
                },
                icon = Icons.Filled.BugReport,
                onClick = { onNavigate(Dest.Debug) },
                dividerAbove = true,
            )
        }

        // ---- 脚本 ---------------------------------------------------------
        SettingsGroup("脚本") {
            SettingsNavRow(
                title = "脚本库",
                subtitle = when {
                    scriptState.entries.isEmpty() ->
                        "还没有脚本 · 可以新建，也可以让 Agent 把一次成功的调试记成脚本"
                    else ->
                        "${scriptState.entries.size} 个脚本 · " +
                            "${scriptState.entries.count { it.enabled }} 个启用 · " +
                            "每次保存生成一个新版本"
                },
                icon = Icons.Filled.Description,
                onClick = { onNavigate(Dest.Scripts) },
            )
        }

        // ---- Agent --------------------------------------------------------
        SettingsGroup("Agent") {
            SettingsNavRow(
                title = "模型配置",
                subtitle = when {
                    agentState.config.isConfigured ->
                        "${agentState.config.model} · 已保存 API Key · " +
                            "最多 ${agentState.config.maxToolRounds} 轮工具调用"
                    else -> "未配置 · 需要 Base URL、API Key 与模型名才能对话"
                },
                icon = Icons.Filled.SmartToy,
                onClick = { onNavigate(Dest.AgentSettings) },
            )
            SettingsNavRow(
                title = "打开对话",
                subtitle = "相关工具由目标进程内的模块执行，先选择作用域再对话",
                icon = null,
                leading = {
                    Text(
                        "A",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                onClick = { onNavigate(Dest.Agent) },
                dividerAbove = true,
            )
        }

        // ---- 打包 ---------------------------------------------------------
        SettingsGroup("打包") {
            SettingsNavRow(
                title = "免 Root 打包",
                subtitle = when {
                    patchState.busy -> "正在打包${patchState.activeLabel?.let { "：$it" } ?: "…"}"
                    patchState.records.isNotEmpty() -> "已生成 ${patchState.records.size} 个安装包"
                    else -> "把应用重打包成免 Root、免 LSPosed 的安装包"
                },
                icon = Icons.Filled.Inventory2,
                onClick = { onNavigate(Dest.Patch) },
            )
        }

        // ---- 环境 ---------------------------------------------------------
        SettingsGroup("环境") {
            SettingsNavRow(
                title = "环境自检",
                subtitle = "Root、LSPosed、Zygisk 与设备信息",
                icon = Icons.Filled.Build,
                onClick = { onNavigate(Dest.SelfCheck) },
            )
        }

        // ---- 高级 / 危险 ---------------------------------------------------
        SettingsGroup("高级 / 危险") {
            SettingsNavRow(
                title = "system 作用域",
                subtitle = when {
                    !scopeState.bound -> "未连接 LSPosed 框架，无法判定"
                    scopeState.hasSystemScope -> "已授权 · 免权限悬浮窗可以在 system_server 内 Hook"
                    scopeState.requiresSystemScope ->
                        "未授权 · 当前选择的悬浮窗方式必须先授权 system 作用域"
                    else -> "未授权 · 内嵌式悬浮窗不需要它，无需处理"
                },
                icon = Icons.Filled.Warning,
                onClick = { onNavigate(Dest.Scope) },
            )
            SettingsNavRow(
                title = "重置所有设置",
                subtitle = "恢复外观、终端、调试与 Agent 的默认值；" +
                    "不会取消作用域，也不会删除已打包的安装包",
                icon = Icons.Filled.RestartAlt,
                onClick = { confirmReset = true },
                dividerAbove = true,
                danger = true,
            )
        }

        Text(
            "版本信息与构建阶段在「总览」页底部。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            icon = {
                Icon(
                    Icons.Filled.DeleteSweep,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("重置所有设置？") },
            text = {
                Column {
                    Text("会被重置：")
                    Text("· 主题色、配色方案、悬浮窗外观与尺寸")
                    Text("· 终端默认模式、工作区路径、字号")
                    Text("· Hook 默认行为、自动 deoptimize、日志上限")
                    Text("· 悬浮窗实现方式（回到安全的内嵌式）及其风险确认")
                    Text("· Agent 的 Base URL、模型与已保存的 API Key")
                    Text(
                        "不会改动：",
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text("· LSPosed 作用域与悬浮窗目标应用")
                    Text("· 已生成的免 Root 安装包与签名密钥")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        onResetAll()
                    },
                ) { Text("重置") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("取消") }
            },
        )
    }
}
