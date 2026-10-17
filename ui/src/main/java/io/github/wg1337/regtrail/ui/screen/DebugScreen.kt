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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.wg1337.regtrail.shared.model.CheckStatus
import io.github.wg1337.regtrail.ui.debug.DebugUiState
import io.github.wg1337.regtrail.ui.screen.settings.SubPageHeader
import io.github.wg1337.regtrail.ui.state.HookMode

/**
 * The live debugger screen.
 *
 * The honest framing is built into the copy: this instruments **Java methods**.
 * Arguments and return values are visible and rewritable; CPU registers are not,
 * and never will be through this mechanism — that needs native instrumentation.
 * Saying so up front prevents the feature being judged against a promise it never
 * made.
 *
 * Reached from 设置 → 调试 (and from 总览's quick actions) rather than from a
 * bottom-bar tab: it is a work surface, and the tab budget is now spent on the
 * four destinations people navigate between. [defaultHookMode] is the value from
 * 设置 → 调试 → Hook 默认行为 and only decides which of the two per-method actions
 * is the one-tap primary button.
 */
@Composable
fun DebugScreen(
    state: DebugUiState,
    defaultHookMode: HookMode,
    onRefresh: () -> Unit,
    onSearchClasses: (String) -> Unit,
    onSearchMethods: (String, String) -> Unit,
    onHookTrace: (String) -> Unit,
    onHookReturn: (String, String, String) -> Unit,
    onUnhook: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var className by remember { mutableStateOf(state.classQuery) }
    var methodName by remember { mutableStateOf(state.methodQuery) }
    var returnValue by remember { mutableStateOf("") }
    var returnSignature by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            SubPageHeader(
                title = "实时调试",
                subtitle = "对目标进程内的方法挂 Hook，可观察参数与返回值，并可改写返回值",
                onBack = onBack,
                trailing = {
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = onRefresh) { Text("刷新") }
                    }
                },
            )
        }

        // ---- Attachment ---------------------------------------------------
        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (state.attached) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (state.attached) "已连接目标进程" else "未连接目标进程",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (state.attached) {
                        KeyValue("目标包名", state.targetPackage ?: "—")
                        KeyValue("进程", state.targetProcess ?: "—")
                        KeyValue("框架", state.frameworkLabel ?: "—")
                        KeyValue(
                            "Hook / 实例 / Activity",
                            "${state.hookCount} / ${state.instanceCount} / ${state.activityCount}",
                        )
                    } else {
                        Text(
                            "请先启动一个已授权作用域、且勾选了悬浮窗的目标应用，" +
                                "模块在该进程内加载后才能接受调试指令。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        // ---- Capability boundary ------------------------------------------
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("能力边界", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    KeyValue("可读/可改参数与返回值", "支持", CheckStatus.Ok)
                    KeyValue("可挂载已加载的类", "支持（含去内联）", CheckStatus.Ok)
                    KeyValue("CPU 寄存器读写", "不支持（需 native 插桩）", CheckStatus.Warning)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "寄存器级调试需要 Dobby/Frida 一类 native 方案，不属于 Java Hook 的能力范围。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---- Search --------------------------------------------------------
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("查找方法", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = className,
                        onValueChange = { className = it },
                        label = { Text("类名（支持片段匹配）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = methodName,
                        onValueChange = { methodName = it },
                        label = { Text("方法名（可留空）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onSearchClasses(className) },
                            enabled = !state.busy && state.attached && className.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) { Text("搜类") }
                        OutlinedButton(
                            onClick = { onSearchMethods(className, methodName) },
                            enabled = !state.busy && state.attached && className.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) { Text("搜方法") }
                    }
                }
            }
        }

        // ---- Installed hooks ------------------------------------------------
        if (state.hooks.isNotEmpty()) {
            item {
                Text("已安装 Hook (${state.hooks.size})", style = MaterialTheme.typography.titleSmall)
            }
            items(state.hooks, key = { it.id }) { hook ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    hook.target,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "调用 ${hook.calls} 次" +
                                        if (hook.returnOverride != null) {
                                            " · 返回值强制为 ${hook.returnOverride}"
                                        } else if (hook.trace) {
                                            " · 记录调用"
                                        } else {
                                            ""
                                        },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onUnhook(hook.id) }) { Text("移除") }
                        }
                    }
                }
            }
        }

        // ---- Method results -------------------------------------------------
        if (state.methods.isNotEmpty()) {
            item {
                Text("匹配到 ${state.methods.size} 个方法", style = MaterialTheme.typography.titleSmall)
            }
            items(state.methods, key = { it.signature }) { method ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            method.signature,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Which of the two actions is primary comes from
                            // 设置 → 调试 → Hook 默认行为; the other one stays one
                            // tap away so the default never removes an option.
                            val defaultTrace = defaultHookMode == HookMode.Trace
                            Button(
                                onClick = {
                                    if (defaultTrace) {
                                        onHookTrace(method.signature)
                                    } else {
                                        returnSignature = method.signature
                                        returnValue = defaultLiteralFor(method.returnType)
                                    }
                                },
                                enabled = !state.busy,
                            ) {
                                Text(if (defaultTrace) "跟踪调用（默认）" else "改返回值（默认）")
                            }
                            OutlinedButton(
                                onClick = {
                                    if (defaultTrace) {
                                        returnSignature = method.signature
                                        returnValue = defaultLiteralFor(method.returnType)
                                    } else {
                                        onHookTrace(method.signature)
                                    }
                                },
                                enabled = !state.busy,
                            ) { Text(if (defaultTrace) "改返回值" else "跟踪调用") }
                        }
                    }
                }
            }
        }

        // ---- Return-value editor ---------------------------------------------
        if (returnSignature.isNotEmpty()) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("改写返回值", style = MaterialTheme.typography.titleSmall)
                        Text(
                            returnSignature,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = returnValue,
                            onValueChange = { returnValue = it },
                            label = { Text("返回值字面量（null / true / 42 / 文本）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "原方法仍会执行，因此副作用保留；仅返回值被替换。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    onHookReturn(returnSignature, returnValue, "auto")
                                    returnSignature = ""
                                },
                                enabled = !state.busy,
                                modifier = Modifier.weight(1f),
                            ) { Text("安装") }
                            TextButton(onClick = { returnSignature = "" }) { Text("取消") }
                        }
                    }
                }
            }
        }

        // ---- Classes ---------------------------------------------------------
        if (state.classes.isNotEmpty()) {
            item {
                Text("匹配到 ${state.classes.size} 个类", style = MaterialTheme.typography.titleSmall)
            }
            items(state.classes.take(60), key = { it.name }) { cls ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp)),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        cls.name,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // ---- Messages --------------------------------------------------------
        if (state.messages.isNotEmpty()) {
            // The list is already bounded by the platform (设置 → 调试 → 日志上限),
            // so it is rendered whole instead of being truncated a second time
            // with a different, invisible limit.
            item { Text("日志 (${state.messages.size})", style = MaterialTheme.typography.titleSmall) }
            items(state.messages) { message ->
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (message.isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** A plausible starting literal for a given return type, to reduce typing. */
private fun defaultLiteralFor(returnType: String): String = when (returnType) {
    "boolean" -> "true"
    "void" -> ""
    "int", "long", "short", "byte" -> "0"
    "float", "double" -> "0"
    "java.lang.String" -> ""
    else -> "null"
}
