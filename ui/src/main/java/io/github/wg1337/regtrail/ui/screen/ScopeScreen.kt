package io.github.wg1337.regtrail.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.wg1337.regtrail.shared.model.CheckStatus
import io.github.wg1337.regtrail.shared.model.ScopeUiState
import io.github.wg1337.regtrail.ui.component.TargetAppRow

/**
 * "Targets & scope" screen.
 *
 * RegTrail is LSPosed-only, so this is the single place the user decides what
 * gets injected, in two independent layers:
 *
 *  1. **Framework scope** — the apps the framework loads the module into at all.
 *     RegTrail asks for this through the framework's own prompt; it is also
 *     editable by hand in the LSPosed manager, and both stay in sync.
 *  2. **`system` scope** — required for the permission-free floating window,
 *     because the bypass hooks `PhoneWindowManager` inside system_server.
 *  3. **Per-app overlay checkbox** — a RegTrail-level filter written into the
 *     config snapshot. Scope means "may load here"; the checkbox means
 *     "do show the window here".
 */
@Composable
fun ScopeScreen(
    state: ScopeUiState,
    onRefresh: () -> Unit,
    onGrant: (List<String>) -> Unit,
    onRevoke: (List<String>) -> Unit,
    onToggleScopeTarget: (String) -> Unit,
    onToggleOverlayTarget: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var onlySelected by remember { mutableStateOf(false) }

    val overlayTargets = state.overlayTargets
    val filtered = remember(state.apps, query, onlySelected, overlayTargets) {
        state.apps.asSequence()
            .filter { app ->
                query.isBlank() ||
                    app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            }
            .filter { app -> !onlySelected || app.packageName in overlayTargets }
            .toList()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("目标与作用域", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "作用域决定模块被加载到哪些应用；悬浮窗开关决定哪些应用显示窗口",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = onRefresh) { Text("刷新") }
                }
            }
        }

        // ---- Framework binding -------------------------------------------
        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (state.bound) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (state.bound) "已连接 LSPosed 框架" else "未连接到 LSPosed 框架",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (state.bound) {
                        KeyValue("框架", state.frameworkName ?: "—")
                        KeyValue("版本", state.frameworkVersion ?: "—")
                        KeyValue("API", state.apiVersion.toString())
                        KeyValue(
                            "system 作用域",
                            if (state.hasSystemScope) "已授权" else "未授权",
                            if (state.hasSystemScope) CheckStatus.Ok else CheckStatus.Failed,
                        )
                    } else {
                        Text(
                            "请在 LSPosed 管理器中启用「RegTrail」模块，然后重启设备或软重启框架。" +
                                "模块已正确声明，管理器应能直接列出它。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        // ---- system scope: only when the risky mechanism is selected --------
        if (state.showSystemScopePrompt) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "「免权限悬浮窗」需要 system 作用域",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "你选择了需要 Hook system_server 的实现方式。" +
                                "它需要 RegTrail 被加载进 system_server，因此必须先授权 system 作用域，" +
                                "并在后重启设备后才会生效。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "如果你改主意了，可以在「外观 → 注入悬浮窗」里切回默认的「内嵌式」，" +
                                "那种方式完全不需要 system 作用域。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { onGrant(listOf("system")) },
                            enabled = !state.busy,
                        ) { Text("申请 system 作用域") }
                    }
                }
            }
        }

        // ---- Batch actions -------------------------------------------------
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("批量操作", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onGrant(state.pendingGrant.toList()) },
                            enabled = !state.busy && state.pendingGrant.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("申请已勾选 (${state.pendingGrant.size})")
                        }
                        OutlinedButton(
                            onClick = { onRevoke(state.scope) },
                            enabled = !state.busy && state.scope.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("全部移除")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "框架会弹出一次通知确认，批准即对整批生效（1 分钟内有效）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.scope.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("当前作用域 (${state.scope.size})", style = MaterialTheme.typography.labelMedium)
                        Text(
                            state.scope.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ---- Result message ------------------------------------------------
        state.message?.let { msg ->
            item {
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.messageIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }

        // ---- App list ------------------------------------------------------
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索应用") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = onlySelected,
                    onClick = { onlySelected = !onlySelected },
                    label = { Text("仅已选") },
                )
            }
        }

        if (state.apps.isEmpty()) {
            item {
                Text(
                    if (state.busy) "正在读取应用列表…" else "没有可注入的应用",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        items(filtered, key = { it.packageName }) { app ->
            TargetAppRow(
                app = app,
                inScope = app.packageName in state.scope,
                scopePending = app.packageName in state.pendingGrant,
                overlayEnabled = app.packageName in overlayTargets,
                onToggleScope = { onToggleScopeTarget(app.packageName) },
                onToggleOverlay = { onToggleOverlayTarget(app.packageName) },
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
