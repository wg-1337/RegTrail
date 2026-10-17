package io.github.wg1337.regtrail.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.wg1337.regtrail.shared.model.CheckStatus
import io.github.wg1337.regtrail.shared.model.ScopeUiState
import io.github.wg1337.regtrail.shared.model.SelfCheckReport
import io.github.wg1337.regtrail.ui.RootStateUi
import io.github.wg1337.regtrail.ui.debug.DebugUiState

/**
 * 总览 — readiness at a glance, plus the app's launcher pad.
 *
 * ## Why the quick actions exist
 * The bottom bar only has four tabs now (总览 / 目标 / 设置 / Agent). Every live
 * surface that lost its tab — 终端, 实时调试, 打包 — is reached from here in one
 * tap, which is the constraint the restructure had to keep: nothing removed from
 * the bar may become harder to reach.
 *
 * The card also names 设置 explicitly. The colour picker was unfindable before
 * precisely because nothing on this screen pointed at the page that held it.
 */
@Composable
fun HomeScreen(
    report: SelfCheckReport?,
    scopeState: ScopeUiState,
    debugState: DebugUiState,
    rootState: RootStateUi,
    onGoToScope: () -> Unit,
    onGoToTerminal: () -> Unit,
    onGoToDebug: () -> Unit,
    onGoToPatch: () -> Unit,
    onGoToSelfCheck: () -> Unit,
    onGoToSettings: () -> Unit,
    onRequestRoot: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("RegTrail", style = MaterialTheme.typography.headlineMedium)
        Text(
            "安卓逆向与实时分析工作台",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- Root gate ------------------------------------------------------
        // Shown first and loudly. Without root the module's config is never
        // published, which makes every downstream feature look broken; the cause
        // is invisible unless it is stated here.
        if (!rootState.granted) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("尚未获得 Root 权限", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "没有 Root 就无法把配置下发给注入端，悬浮窗、调试与 Agent 都会失效。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onRequestRoot, modifier = Modifier.fillMaxWidth()) {
                        Text("请求 Root 权限")
                    }
                    if (rootState.diagnosis.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            rootState.diagnosis,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ---- Readiness at a glance ----------------------------------------
        val ready = report?.ready == true
        val scopeCount = scopeState.scope.size

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                // Colour encodes health only. "Configured but no target chosen
                // yet" is normal progress, not a failure, so it must not be red.
                containerColor = when {
                    report == null -> MaterialTheme.colorScheme.surfaceVariant
                    !ready -> MaterialTheme.colorScheme.errorContainer
                    scopeCount == 0 -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.primaryContainer
                },
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    when {
                        report == null -> "正在检测运行环境…"
                        !ready -> "环境未就绪"
                        scopeCount == 0 -> "环境就绪，尚未选择目标"
                        else -> "已就绪，已选择 $scopeCount 个目标"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        report == null -> "首次启动需要确认 Root 与 LSPosed 是否可用。"
                        !ready -> buildString {
                            if (!report.root.granted) append("· 未获得 Root 权限\n")
                            if (!report.lsposed.installed) append("· 未检测到 LSPosed\n")
                            if (report.lsposed.zygiskBlocking) {
                                append("· Zygisk 已禁用（LSPosed 依赖它）\n")
                            }
                        }.trim()
                        !scopeState.bound -> "模块已安装，但尚未连接到 LSPosed 框架。"
                        scopeCount == 0 -> "去「目标」页选择要注入的应用。"
                        // Only nag when the *selected* mechanism actually needs it.
                        // The default DecorView overlay needs no `system` scope, so
                        // telling every user to grant one both misleads them and
                        // pushes them at the one option that can stop the device
                        // from booting. Verified on device: this banner used to say
                        // "建议授权 system 作用域" while 注入方式 read "内嵌式（安全）".
                        scopeState.requiresSystemScope && !scopeState.hasSystemScope ->
                            "你选择了「免权限悬浮窗」，它需要先授权 system 作用域。"
                        else -> "悬浮窗会在目标应用启动时自动出现。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = onGoToScope, modifier = Modifier.fillMaxWidth()) {
                    Text("选择目标应用")
                }
            }
        }

        // ---- Quick actions -------------------------------------------------
        SectionCard("快捷入口") {
            Text(
                "主题色、悬浮窗、终端、调试与模型的全部设置都在「设置」页；" +
                    "下面这些是常用的工作界面。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickAction("终端", onGoToTerminal, Modifier.weight(1f))
                QuickAction("实时调试", onGoToDebug, Modifier.weight(1f))
                QuickAction("打包", onGoToPatch, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickAction("环境自检", onGoToSelfCheck, Modifier.weight(1f))
                QuickAction("设置", onGoToSettings, Modifier.weight(1f))
            }
        }

        // ---- Framework summary --------------------------------------------
        SectionCard("LSPosed 框架") {
            if (scopeState.bound) {
                KeyValue("框架", scopeState.frameworkName ?: "—")
                KeyValue("版本", scopeState.frameworkVersion ?: "—")
                KeyValue("API", scopeState.apiVersion.toString())
                KeyValue(
                    "作用域",
                    "${scopeCount} 个应用",
                    if (scopeCount > 0) CheckStatus.Ok else CheckStatus.Warning,
                )
                // Only surfaced when it actually matters. The default DecorView
                // mechanism needs no system scope, so showing it as a red failure
                // would push the user toward the bootloop-risk option for nothing.
                if (scopeState.requiresSystemScope) {
                    KeyValue(
                        "system",
                        if (scopeState.hasSystemScope) "已授权" else "未授权",
                        if (scopeState.hasSystemScope) CheckStatus.Ok else CheckStatus.Failed,
                    )
                }
                KeyValue(
                    "悬浮窗方式",
                    if (scopeState.requiresSystemScope) "免权限悬浮窗（需 system）" else "内嵌式（安全）",
                    CheckStatus.Info,
                )
            } else {
                Text(
                    "未连接。请在 LSPosed 管理器中启用 RegTrail 模块。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- Injection status ----------------------------------------------
        SectionCard("注入状态") {
            KeyValue(
                "目标进程",
                debugState.targetPackage ?: "无",
                if (debugState.attached) CheckStatus.Ok else CheckStatus.Warning,
            )
            KeyValue("已装 Hook", debugState.hookCount.toString())
            KeyValue("活动 Activity", debugState.activityCount.toString())
            if (!debugState.attached) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "启动一个已授权作用域且勾选了悬浮窗的目标应用后，这里会显示该进程。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- Roadmap ------------------------------------------------------
        SectionCard("后续里程碑") {
            RoadmapRow("P4", "Agent：工具链驱动的目标应用分析", pending = true)
            RoadmapRow("P5", "注入持久化：导出免 Root 增强安装包", pending = true)
        }

        SectionCard("当前构建") {
            KeyValue("阶段", "P1 注入闭环")
            KeyValue("界面", "AndroidX Compose + Material 3")
            KeyValue("框架目标", "LSPosed v2.2.x（现代 API 102）")
            KeyValue("悬浮窗", "WebView（注入体积最小）")
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/**
 * One equal-width button in the quick-action block.
 *
 * `FilledTonalButton` rather than a plain `Button`: the block has five of them and
 * filled buttons would compete with the readiness card's single primary action.
 */
@Composable
private fun QuickAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(label, maxLines = 1, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun RoadmapRow(badge: String, text: String, pending: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            badge,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (pending) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
internal fun KeyValue(
    key: String,
    value: String,
    status: CheckStatus? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            key,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = when (status) {
                CheckStatus.Ok -> MaterialTheme.colorScheme.primary
                CheckStatus.Warning -> MaterialTheme.colorScheme.tertiary
                CheckStatus.Failed -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
