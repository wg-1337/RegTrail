package io.github.wg1337.regtrail.agent

import io.github.wg1337.regtrail.bridge.ModuleBridgeClient
import io.github.wg1337.regtrail.root.RootShell
import io.github.wg1337.regtrail.shared.bridge.BridgeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The ReAct-style loop that turns a user question into tool calls and an answer.
 *
 * ## Shape
 * ```
 * system prompt + history + user turn
 *   └─ model ─┬─ tool_calls ──▶ execute ──▶ append tool results ──┐
 *             │                                                   │
 *             └─ content ──▶ done                                │
 *             ▲───────────────────────────────────────────────────┘
 *         (bounded by maxToolRounds)
 * ```
 *
 * ## Where tools run
 * Two places, and the split matters:
 *  - **Class/field/hook tools** are dispatched to the *target process* through
 *    [ModuleBridgeClient], because that is the only place the objects exist.
 *  - **`run_shell`** runs on the *settings side* via root, because a shell inside
 *    a third-party process would be both surprising and far more fragile.
 *
 * The system prompt tells the model this, so it does not misread a shell result
 * as the target's own environment.
 */
class AgentSession(
    private val configProvider: () -> AgentConfig,
    private val bridge: ModuleBridgeClient,
    private val onMessage: (AgentMessage) -> Unit,
) {

    private val history = mutableListOf<JsonObject>()

    private val toolCalls = mutableListOf<String>()

    /** Sends a user turn and runs the loop until the model produces an answer. */
    suspend fun send(userText: String): Unit = withContext(Dispatchers.IO) {
        val config = configProvider()
        if (!config.isConfigured) {
            onMessage(
                AgentMessage(
                    AgentMessage.Role.System,
                    "尚未配置模型。请在下方填写 API Base URL、Key 与模型名。",
                ),
            )
            return@withContext
        }

        onMessage(AgentMessage(AgentMessage.Role.User, userText))

        // First turn seeds the conversation; later turns append.
        if (history.isEmpty()) {
            history += OpenAiCompatibleClient.system(AgentTools.SYSTEM_PROMPT)
        }
        history += OpenAiCompatibleClient.message("user", userText)

        val client = OpenAiCompatibleClient(config)

        var round = 0
        while (round < config.maxToolRounds) {
            round++

            val completion = client.complete(history, AgentTools.ALL).getOrElse { error ->
                onMessage(
                    AgentMessage(
                        AgentMessage.Role.System,
                        "请求模型失败：${error.message ?: "未知错误"}",
                    ),
                )
                return@withContext
            }

            // Re-append the assistant message verbatim so the provider sees its
            // own tool_calls array on the next round (required by the protocol).
            history += completion.rawMessage

            if (completion.toolCalls.isEmpty()) {
                val text = completion.content.ifBlank { "（模型没有返回内容）" }
                onMessage(AgentMessage(AgentMessage.Role.Assistant, text))
                return@withContext
            }

            if (completion.content.isNotBlank()) {
                onMessage(
                    AgentMessage(
                        AgentMessage.Role.Assistant,
                        completion.content,
                        toolCalls = completion.toolCalls.map { it.name },
                    ),
                )
            } else {
                onMessage(
                    AgentMessage(
                        AgentMessage.Role.Assistant,
                        "调用工具：" + completion.toolCalls.joinToString(", ") { it.name },
                        toolCalls = completion.toolCalls.map { it.name },
                    ),
                )
            }

            for (call in completion.toolCalls) {
                val tool = AgentTools.byName(call.name)
                if (tool == null) {
                    history += OpenAiCompatibleClient.toolResult(
                        call.id,
                        """{"ok":false,"error":"未知工具 ${call.name}"}""",
                    )
                    continue
                }

                toolCalls += call.name
                onMessage(
                    AgentMessage(
                        AgentMessage.Role.Tool,
                        describeCall(call),
                    ),
                )

                val result = executeTool(tool, call.arguments)
                onMessage(
                    AgentMessage(
                        AgentMessage.Role.Tool,
                        result,
                    ),
                )

                history += OpenAiCompatibleClient.toolResult(call.id, result)
            }
        }

        onMessage(
            AgentMessage(
                AgentMessage.Role.System,
                "已达到工具调用上限（${config.maxToolRounds} 轮），停止。可以继续追问，或提高上限。",
            ),
        )
    }

    /** Clears the conversation, keeping the session object alive. */
    fun reset() {
        history.clear()
        toolCalls.clear()
    }

    // -----------------------------------------------------------------------

    private suspend fun executeTool(tool: AgentTool, args: JsonObject): String = try {
        when (tool.name) {
            "run_shell" -> runShell(args)
            else -> runInTarget(tool.name, args)
        }
    } catch (t: Throwable) {
        """{"ok":false,"error":${quote(t.message ?: t::class.java.simpleName)}}"""
    }

    /**
     * Dispatches to the target process.
     *
     * The bridge already returns a JSON object; it is re-serialized through
     * [summarize] to bound its size, because a class dump can be large and an
     * oversized tool result wastes the model's context.
     */
    private suspend fun runInTarget(name: String, args: JsonObject): String {
        if (!bridge.isModuleLoaded()) {
            return """{"ok":false,"error":"目标进程未加载模块：请先启动一个已授权作用域并勾选悬浮窗的应用"}"""
        }
        val result = bridge.tool(name, args)
        return result.fold(
            onSuccess = { data -> OpenAiCompatibleClient.summarize(data) },
            onFailure = { error ->
                """{"ok":false,"error":${quote(error.message ?: "工具执行失败")}}"""
            },
        )
    }

    /** Runs a root shell command on the settings side. */
    private suspend fun runShell(args: JsonObject): String {
        val command = (args["command"] as? JsonPrimitive)?.content
            ?: return """{"ok":false,"error":"缺少 command 参数"}"""

        val result = RootShell.run(command, timeoutSeconds = 25)
        return result.fold(
            onSuccess = { output ->
                """{"ok":true,"output":${quote(output.take(MAX_SHELL_OUTPUT))}}"""
            },
            onFailure = { error ->
                """{"ok":false,"error":${quote(error.message ?: "命令执行失败")}}"""
            },
        )
    }

    private fun describeCall(call: OpenAiCompatibleClient.ToolCall): String = buildString {
        append("→ ").append(call.name)
        if (call.rawArguments.isNotBlank() && call.rawArguments != "{}") {
            append(' ').append(call.rawArguments.take(200))
        }
    }

    /** Minimal JSON string escaping, enough for our own error payloads. */
    private fun quote(text: String): String = buildString {
        append('"')
        for (ch in text) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) {
                    append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
        append('"')
    }

    private companion object {
        /** Keeps one shell result from swallowing the whole context window. */
        const val MAX_SHELL_OUTPUT = 6000
    }
}
