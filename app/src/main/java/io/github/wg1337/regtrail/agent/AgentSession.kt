package io.github.wg1337.regtrail.agent

import io.github.wg1337.regtrail.bridge.ModuleBridgeClient
import io.github.wg1337.regtrail.memory.MemoryException
import io.github.wg1337.regtrail.memory.MemoryMatch
import io.github.wg1337.regtrail.memory.MemoryScanner
import io.github.wg1337.regtrail.memory.ScanQuery
import io.github.wg1337.regtrail.memory.formatMemoryAddress
import io.github.wg1337.regtrail.memory.hexBytes
import io.github.wg1337.regtrail.memory.humanBytes
import io.github.wg1337.regtrail.memory.parseMemoryAddress
import io.github.wg1337.regtrail.root.RootShell
import io.github.wg1337.regtrail.shared.bridge.BridgeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
 * Three places, and the split matters:
 *  - **Class/field/hook tools** are dispatched to the *target process* through
 *    [ModuleBridgeClient], because that is the only place the objects exist.
 *  - **`run_shell`** runs on the *settings side* via root, because a shell inside
 *    a third-party process would be both surprising and far more fragile.
 *  - **`mem_*` memory tools** also run on the settings side, as root, against
 *    `/proc/<pid>/mem`. They do not need the module to be loaded, only a pid.
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

    /**
     * Root-side memory access. Stateless apart from its cached `su` shell, so one
     * instance serves the whole session.
     */
    private val memory = MemoryScanner()

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
        when {
            tool.name == "run_shell" -> runShell(args)
            tool.name.startsWith("mem_") -> runMemoryTool(tool.name, args)
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

    // -----------------------------------------------------------------------
    // Memory tools (settings side, as root)
    // -----------------------------------------------------------------------

    /**
     * Runs a `mem_*` tool.
     *
     * Unlike the class/field tools these do not need the target process to have
     * loaded the module: every byte is fetched or written through the root shell
     * against `/proc/<pid>/mem`, so they work on any process the su domain may
     * ptrace.
     *
     * Results are plain text rather than the JSON the in-process tools return,
     * because the model reads these directly and the useful part is the address
     * list. Failures keep the shell's own message (for example `dd: /proc/…:
     * Permission denied`) instead of a generic "failed".
     */
    private suspend fun runMemoryTool(name: String, args: JsonObject): String = try {
        when (name) {
            "mem_list_processes" -> memListProcesses()
            "mem_regions" -> memRegions(args)
            "mem_scan" -> memScan(args)
            "mem_refine" -> memRefine(args)
            "mem_write" -> memWrite(args)
            else -> "错误：未知的内存工具 $name"
        }
    } catch (e: MemoryException) {
        "错误：${e.message ?: "内存操作失败"}"
    }

    private suspend fun memListProcesses(): String {
        val entries = memory.listProcesses()
        if (entries.isEmpty()) return "没有从 /proc 列出任何进程"

        // Apps first: the model is looking for the target app, and a device has
        // far more kernel threads than apps, so a pid-ordered head would cut the
        // interesting rows off.
        val ordered = entries.filter { it.isApp } + entries.filterNot { it.isApp }
        val shown = ordered.take(MAX_PROCESS_LINES)

        return buildString {
            append("共 ").append(entries.size).append(" 个进程")
            if (entries.size > shown.size) {
                append("（只显示前 ").append(shown.size).append(" 个，应用进程优先）")
            }
            append("，格式 pid / uid / 命令行：\n")
            for (entry in shown) {
                append(entry.pid).append("  ").append(entry.uid).append("  ")
                append(entry.name.take(72)).append('\n')
            }
        }.trimEnd()
    }

    private suspend fun memRegions(args: JsonObject): String {
        val pid = args.intArg("pid") ?: return "错误：缺少 pid 参数（整数进程号）"
        val regions = memory.regions(pid)
        if (regions.isEmpty()) return "进程 $pid 没有可读的内存区域"

        val shown = regions.take(MAX_REGION_LINES)
        return buildString {
            append("进程 ").append(pid).append("：可读区域 ").append(regions.size).append(" 个")
            if (regions.size > shown.size) {
                append("（只显示前 ").append(shown.size).append(" 个）")
            }
            append("，格式 起止地址 / 权限 / 大小 / 路径：\n")
            for (region in shown) {
                append(formatMemoryAddress(region.start)).append('-')
                append(formatMemoryAddress(region.end))
                append(' ').append(region.permissions)
                append(' ').append(humanBytes(region.size))
                if (region.path.isNotEmpty()) append(' ').append(region.path.take(72))
                append('\n')
            }
        }.trimEnd()
    }

    private suspend fun memScan(args: JsonObject): String {
        val pid = args.intArg("pid") ?: return "错误：缺少 pid 参数（整数进程号）"
        val type = args.strArg("type")
            ?: return "错误：缺少 type 参数（${ScanQuery.TYPE_NAMES.joinToString("/")}）"
        val value = args.strArg("value") ?: return "错误：缺少 value 参数"
        val query = ScanQuery.parse(type, value)
            ?: return "错误：无法把 \"$value\" 解析为 $type（可用：${ScanQuery.TYPE_NAMES.joinToString("/")}）"
        val maxResults = args.intArg("maxResults") ?: DEFAULT_MAX_RESULTS

        val outcome = memory.scan(pid, query, maxResults)
        return buildString {
            append("pid=").append(pid).append(' ').append(query.typeName)
            append('=').append(value).append("（").append(query.size).append(" 字节）：命中 ")
            append(outcome.matches.size).append(" 个；读取 ")
            append(outcome.regionsRead).append(" 个区域 / ").append(humanBytes(outcome.bytesRead))
            if (outcome.truncated) append("（已截断）")
            append('\n')

            if (outcome.matches.isEmpty()) {
                append("没有命中。确认进程仍在运行、数值没变，或换 type（例如 float32）再试。\n")
            } else if (outcome.matches.size <= MAX_VERBOSE_MATCHES) {
                for (match in outcome.matches) {
                    append(formatMemoryAddress(match.address))
                    append("  ").append(match.preview)
                    if (match.region.isNotEmpty()) append("  ").append(match.region)
                    append('\n')
                }
            } else {
                append("地址（每行 8 个）：\n")
                outcome.matches.forEachIndexed { index, match ->
                    if (index > 0) append(if (index % 8 == 0) "\n" else ", ")
                    append(formatMemoryAddress(match.address))
                }
                append('\n')
            }

            for (note in outcome.notes) append("注：").append(note).append('\n')
            if (outcome.matches.isNotEmpty()) {
                append("下一步：在应用里让这个数值变化，再用 mem_refine 传入这些地址和新值过滤；")
                append("只剩少数地址后可用 mem_write 写入。")
            }
        }.trimEnd()
    }

    private suspend fun memRefine(args: JsonObject): String {
        val pid = args.intArg("pid") ?: return "错误：缺少 pid 参数（整数进程号）"
        val type = args.strArg("type")
            ?: return "错误：缺少 type 参数（${ScanQuery.TYPE_NAMES.joinToString("/")}）"
        val value = args.strArg("value") ?: return "错误：缺少 value 参数"
        val query = ScanQuery.parse(type, value)
            ?: return "错误：无法把 \"$value\" 解析为 $type（可用：${ScanQuery.TYPE_NAMES.joinToString("/")}）"

        val raw = args["addresses"] as? JsonArray
            ?: return "错误：缺少 addresses 参数（十六进制地址字符串的 JSON 数组）"
        val addresses = raw.mapNotNull { element ->
            val primitive = element as? JsonPrimitive ?: return@mapNotNull null
            val text = primitive.contentOrNull?.trim().orEmpty()
            if (text.isEmpty()) null
            else if (primitive.isString) parseMemoryAddress(text) else text.toLongOrNull()
        }.distinct()
        if (addresses.isEmpty()) {
            return "错误：addresses 里没有可解析的地址（示例：[\"0x7f12340000\"]）"
        }

        val previous = addresses.map { MemoryMatch(address = it, size = query.size, preview = "") }
        val outcome = memory.refine(pid, previous, query)

        return buildString {
            append("pid=").append(pid).append(' ').append(query.typeName).append('=').append(value)
            append("：复核 ").append(addresses.size).append(" 个地址，仍然命中 ")
            append(outcome.matches.size).append(" 个，丢弃 ")
            append(addresses.size - outcome.matches.size).append(" 个\n")
            for (match in outcome.matches) {
                append(formatMemoryAddress(match.address)).append("  ").append(match.preview).append('\n')
            }
            for (note in outcome.notes) append("注：").append(note).append('\n')
            if (outcome.matches.size <= 2) {
                append("范围已很小，可用 mem_write 写入新值（写错地址会破坏目标进程状态）。")
            }
        }.trimEnd()
    }

    private suspend fun memWrite(args: JsonObject): String {
        val pid = args.intArg("pid") ?: return "错误：缺少 pid 参数（整数进程号）"
        val address = args.addressArg("address")
            ?: return "错误：缺少或无法解析 address（十六进制字符串，如 \"0x7f12340000\"）"
        val type = args.strArg("type")
            ?: return "错误：缺少 type 参数（${ScanQuery.TYPE_NAMES.joinToString("/")}）"
        val value = args.strArg("value") ?: return "错误：缺少 value 参数"
        val query = ScanQuery.parse(type, value)
            ?: return "错误：无法把 \"$value\" 解析为 $type（可用：${ScanQuery.TYPE_NAMES.joinToString("/")}）"

        val verified = memory.write(pid, address, query.bytes)
        if (verified) {
            return "写入成功并已回读校验：pid=$pid ${formatMemoryAddress(address)} = $value" +
                "（${query.typeName}，${query.size} 字节）"
        }

        val actual = runCatching { memory.read(pid, address, query.size) }.getOrNull()
        return "写入未通过回读校验：pid=$pid ${formatMemoryAddress(address)} 现值为 " +
            (actual?.let { hexBytes(it) } ?: "（读取失败）") +
            "，期望 ${hexBytes(query.bytes)}。该地址可能只读、已释放，或写入被内核拒绝。"
    }

    // -----------------------------------------------------------------------
    // Tool argument helpers
    // -----------------------------------------------------------------------

    private fun JsonObject.strArg(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()

    private fun JsonObject.intArg(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()

    /**
     * Addresses arrive as `0x…` strings, but a model sometimes sends them as a
     * JSON number instead; a number is decimal, a string is hex, so a bare
     * `140234567890` is not silently read as hex digits.
     */
    private fun JsonObject.addressArg(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        val text = primitive.contentOrNull?.trim().orEmpty()
        if (text.isEmpty()) return null
        return if (primitive.isString) parseMemoryAddress(text) else text.toLongOrNull()
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

        /** A device has hundreds of processes; the tail is rarely the target. */
        const val MAX_PROCESS_LINES = 200

        /** A big app can have thousands of mappings. */
        const val MAX_REGION_LINES = 120

        /** Above this, matches are printed as a bare address list. */
        const val MAX_VERBOSE_MATCHES = 60

        /** Matches returned when the model does not say how many it wants. */
        const val DEFAULT_MAX_RESULTS = 500
    }
}
