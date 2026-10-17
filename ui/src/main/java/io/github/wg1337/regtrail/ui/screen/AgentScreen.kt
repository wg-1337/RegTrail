package io.github.wg1337.regtrail.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.wg1337.regtrail.ui.agent.AgentMessageUi
import io.github.wg1337.regtrail.ui.agent.AgentUiState

/**
 * The Agent chat screen.
 *
 * Two things are surfaced loudly because they shape what the user should expect:
 *  - **whether a target is attached** — without it, every tool call fails, and
 *    the model would otherwise blame itself;
 *  - **the capability boundary** — the system prompt already refuses register
 *    work, and the UI says so too, so nobody reads a failed attempt as a bug.
 *
 * Configuration is *not* here any more. The panel used to be behind a gear icon,
 * which made "where do I put my API key" undiscoverable; it now lives in
 * 设置 → Agent, and this screen points at it when nothing is configured.
 */
@Composable
fun AgentScreen(
    state: AgentUiState,
    onSend: (String) -> Unit,
    onReset: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    fun submit() {
        val text = input.trim()
        if (text.isEmpty() || state.running) return
        input = ""
        onSend(text)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        // ---- Header -------------------------------------------------------
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Agent", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        when {
                            !state.config.isConfigured -> "未配置模型"
                            state.targetAttached ->
                                "已连接 ${state.targetPackage ?: "目标进程"}"
                            else -> "无目标进程（工具调用会失败）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            !state.config.isConfigured -> MaterialTheme.colorScheme.error
                            state.targetAttached -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                TextButton(onClick = onReset) { Text("新会话") }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "模型配置")
                }
            }
        }

        // ---- Configuration call-to-action ----------------------------------
        // Only when it is actionable: a permanent banner would be noise once the
        // model is configured (the header already says whether it is).
        if (!state.config.isConfigured) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("还没有可用的模型", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "填好 Base URL、API Key 与模型名后才能开始对话。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenSettings) { Text("填写模型配置") }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ---- Conversation ---------------------------------------------------
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (state.messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Text(
                            "问点什么吧",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "例如：这个应用有哪些可疑的网络请求类？把某个校验方法的返回值改成 true。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.messages) { message -> MessageBubble(message) }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }

        // ---- Input ----------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("向 Agent 提问…") },
                enabled = !state.running,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            Spacer(Modifier.width(8.dp))
            if (state.running) {
                Box(
                    modifier = Modifier.size(52.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            } else {
                IconButton(
                    onClick = { submit() },
                    enabled = input.isNotBlank(),
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: AgentMessageUi) {
    val (container, content) = when (message.role) {
        AgentMessageUi.Role.User ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        AgentMessageUi.Role.Assistant ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        AgentMessageUi.Role.Tool ->
            MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.onSurfaceVariant
        AgentMessageUi.Role.System ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }

    val alignment = if (message.role == AgentMessageUi.Role.User) {
        Alignment.CenterEnd
    } else {
        Alignment.CenterStart
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = container,
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(
                    when (message.role) {
                        AgentMessageUi.Role.User -> "你"
                        AgentMessageUi.Role.Assistant -> "Agent"
                        AgentMessageUi.Role.Tool -> "工具"
                        AgentMessageUi.Role.System -> "系统"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = content.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    message.text,
                    style = if (message.role == AgentMessageUi.Role.Tool) {
                        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = content,
                )
            }
        }
    }
}

/**
 * A one-line capability note reused elsewhere in the app.
 *
 * Kept next to the chat because it is the sentence the chat's empty state and
 * the Agent settings page both need to stay honest about.
 */
internal const val AGENT_CAPABILITY_NOTE =
    "Agent 可检查类/字段/View 树、挂方法 Hook、读写静态字段、以 Root 执行命令；" +
        "不能读取 CPU 寄存器。"
