package io.github.wg1337.regtrail.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.wg1337.regtrail.ui.agent.AgentConfigUi

/** Chinese labels for `ThinkingLevel`'s enum names. */
private val THINKING_LABELS = mapOf(
    "Off" to "关闭",
    "Low" to "低",
    "High" to "高",
    "Max" to "最高",
)

/** One sentence per level, kept next to the labels so they cannot drift. */
private val THINKING_HINTS = mapOf(
    "Off" to "不思考直接回答：最快最省，复杂分析容易漏线索。",
    "Low" to "少量思考（reasoning_effort=low）：适合明确的单步问题。",
    "High" to "默认档（reasoning_effort=high）：先思考再回答，适合定位类/字段。",
    "Max" to "思考最充分（reasoning_effort=max）：最慢最贵，适合多步排查。",
)

/** Chinese labels for `ThinkingWire`'s enum names. */
private val WIRE_LABELS = mapOf(
    "Auto" to "自动",
    "Always" to "始终发送",
    "Never" to "从不发送",
)

/**
 * Agent model configuration, moved off the chat screen.
 *
 * It used to be a panel toggled by a gear icon inside 对话, which meant the only
 * way to find "where do I put the API key" was to already know it was behind
 * that gear. The chat screen keeps a single call-to-action when nothing is
 * configured; the fields live here.
 *
 * ## What is deliberately not shown
 * The stored key is never rendered, not even masked with its real length: the
 * field starts empty and blank means "leave the stored key alone". That is also
 * why [AgentConfigUi] carries `hasApiKey` instead of the key itself — the
 * platform never hands the secret to the UI, so it cannot end up in a log line
 * or a screenshot.
 */
@Composable
fun AgentSettingsScreen(
    config: AgentConfigUi,
    onSave: (
        baseUrl: String,
        apiKey: String,
        model: String,
        rounds: Int,
        thinking: String,
        thinkingWire: String,
    ) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var baseUrl by remember(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var apiKey by remember { mutableStateOf("") }
    var model by remember(config.model) { mutableStateOf(config.model) }
    var rounds by remember(config.maxToolRounds) { mutableIntStateOf(config.maxToolRounds) }
    var thinking by remember(config.thinking) { mutableStateOf(config.thinking) }
    var thinkingWire by remember(config.thinkingWire) { mutableStateOf(config.thinkingWire) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SubPageHeader(
            title = "模型配置",
            subtitle = "OpenAI 兼容接口：Base URL、API Key、模型名与工具轮数",
            onBack = onBack,
        )

        SettingsCard(
            title = "接口",
            description = "任何兼容 /chat/completions 的服务都可以；Base URL 需要包含 /v1。",
        ) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                placeholder = { Text("https://api.openai.com/v1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = {
                    Text(if (config.hasApiKey) "API Key（已保存，留空则不修改）" else "API Key")
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (config.hasApiKey) {
                    "Key 状态：已保存（界面不回显、不写日志）"
                } else {
                    "Key 状态：未设置 —— 没有 Key 时对话不可用"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (config.hasApiKey) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )

            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SettingsCard(
            title = "工具轮数",
            description = "每次提问最多允许模型调用几轮工具；轮数越多，能查的东西越多，也越慢越贵。",
        ) {
            // The stored value may predate this list (or have been set through an
            // older build), so it is merged in rather than silently showing a
            // selection-less chip row.
            val choices = remember(rounds) { (listOf(4, 8, 12, 16) + rounds).distinct().sorted() }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { option ->
                    FilterChip(
                        selected = rounds == option,
                        onClick = { rounds = option },
                        label = { Text("$option 轮") },
                    )
                }
            }
        }

        // ---- 思考等级 ------------------------------------------------------
        //
        // The two controls mirror the DeepSeek API's own two parameters, and the
        // labels say so, because "思考等级" alone would leave the user guessing
        // whether it costs extra or whether it changes the request at all.
        SettingsCard(
            title = "思考等级",
            description = "DeepSeek 思考模式：模型先输出 reasoning_content 再回答。" +
                "等级对应请求里的 reasoning_effort（low / high / max）；关闭则发送 " +
                "thinking.type=disabled。",
        ) {
            val levels = listOf("Off", "Low", "High", "Max")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                levels.forEach { level ->
                    FilterChip(
                        selected = thinking == level,
                        onClick = { thinking = level },
                        label = { Text(THINKING_LABELS[level] ?: level) },
                    )
                }
            }
            Text(
                THINKING_HINTS[thinking] ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "思考模式下 temperature 不生效（接口会忽略它），所以开启时不再发送该参数。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))
            Text("是否发送这两个字段", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Auto", "Always", "Never").forEach { mode ->
                    FilterChip(
                        selected = thinkingWire == mode,
                        onClick = { thinkingWire = mode },
                        label = { Text(WIRE_LABELS[mode] ?: mode) },
                    )
                }
            }
            Text(
                when (thinkingWire) {
                    "Always" -> "总是发送：适用于自建的 DeepSeek 兼容网关"
                    "Never" -> "从不发送：官方 OpenAI 等不认识 thinking 字段的接口用这个"
                    else -> "自动：Base URL 含 deepseek 时发送，其它接口原样请求"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(
            onClick = {
                onSave(baseUrl.trim(), apiKey.trim(), model.trim(), rounds, thinking, thinkingWire)
                // Clear the field: the key is now stored, and leaving it on screen
                // would re-send the same secret on the next edit.
                apiKey = ""
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("保存配置") }

        Text(
            // Stated plainly because it is the one surprising thing about this
            // field: the in-window Agent runs inside the target process, so the key
            // has to travel there through the published config file, which is
            // world-readable on `/data/local/tmp`. Pretending the key never leaves
            // the settings app would be a lie the user could not check.
            "Key 保存在本机 SharedPreferences。为了让悬浮窗里的 Agent 也能调用模型，" +
                "它还会随配置下发到 /data/local/tmp/regtrail/config.json（该文件 0644，" +
                "同机应用可读）；它只会发往上面这个 Base URL，也不会写入日志。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
    }
}
