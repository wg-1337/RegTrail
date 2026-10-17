package io.github.wg1337.regtrail.agent

import io.github.wg1337.regtrail.bridge.ModuleBridgeClient
import io.github.wg1337.regtrail.data.ScriptStore
import io.github.wg1337.regtrail.memory.MemoryException
import io.github.wg1337.regtrail.memory.MemoryMatch
import io.github.wg1337.regtrail.memory.MemoryScanner
import io.github.wg1337.regtrail.memory.ScanQuery
import io.github.wg1337.regtrail.memory.formatMemoryAddress
import io.github.wg1337.regtrail.memory.hexBytes
import io.github.wg1337.regtrail.memory.humanBytes
import io.github.wg1337.regtrail.memory.parseMemoryAddress
import io.github.wg1337.regtrail.root.RootShell
import io.github.wg1337.regtrail.shared.model.ScriptAction
import io.github.wg1337.regtrail.shared.model.ScriptSpec
import io.github.wg1337.regtrail.shared.model.ScriptTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

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
 * Four places, and the split matters:
 *  - **Class/field/hook/script-execution tools** are dispatched to the *target
 *    process* through [ModuleBridgeClient], because that is the only place the
 *    objects exist.
 *  - **`run_shell`** runs on the *settings side* via root, because a shell inside
 *    a third-party process would be both surprising and far more fragile.
 *  - **`mem_*` memory tools** also run on the settings side, as root, against
 *    `/proc/<pid>/mem`. They do not need the module to be loaded, only a pid.
 *  - **`script_*` library tools and `read_file`/`write_file`** run here, against
 *    the on-disk library and the sandboxed roots: the library belongs to this
 *    app, so the target process has no business writing it.
 *
 * The system prompt tells the model this, so it does not misread a shell result
 * as the target's own environment.
 */
class AgentSession(
    private val configProvider: () -> AgentConfig,
    private val bridge: ModuleBridgeClient,
    /** The on-disk script library owned by this app. */
    private val scripts: ScriptStore,
    /** Sandboxed file access for `read_file` / `write_file`. */
    private val files: FileTools,
    /**
     * Re-publishes `config.json` so the injected side sees the library change.
     *
     * A `script_create` that only wrote a file would leave the target process
     * running the *previous* script list, which makes "created it, then ran it"
     * fail for a reason the user cannot see. `script_run` therefore publishes
     * first and executes second.
     */
    private val onScriptsChanged: suspend () -> Unit = {},
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
            // own tool_calls array on the next round (required by the protocol) —
            // and so `reasoning_content` is echoed back, which the DeepSeek guide
            // *requires* on every later request once `tools` is present ("若您的
            // 代码中未正确回传 reasoning_content，API 会返回 400 报错").
            history += completion.rawMessage

            if (completion.toolCalls.isEmpty()) {
                val text = completion.content.ifBlank { "（模型没有返回内容）" }
                onMessage(
                    AgentMessage(
                        AgentMessage.Role.Assistant,
                        text,
                        reasoning = completion.reasoning.takeIf { it.isNotBlank() },
                    ),
                )
                return@withContext
            }

            // One tool-call round: run them in order, collect a *summarised* step
            // per call, and publish the round as a single assistant message.
            //
            // The UI renders each step collapsed to one line. Emitting the raw
            // arguments and the raw JSON result as separate chat messages — which
            // is what this used to do — produced a wall of JSON that made the
            // conversation unreadable and the actual answer hard to find.
            val steps = ArrayList<AgentToolStep>(completion.toolCalls.size)

            for (call in completion.toolCalls) {
                val tool = AgentTools.byName(call.name)
                if (tool == null) {
                    val payload = errJson("未知工具 ${call.name}")
                    history += OpenAiCompatibleClient.toolResult(call.id, payload)
                    steps += AgentToolStep(
                        name = call.name,
                        arguments = preview(call.rawArguments),
                        result = "未知工具",
                        ok = false,
                    )
                    continue
                }

                toolCalls += call.name
                val result = executeTool(tool, call.arguments)
                steps += AgentToolStep(
                    name = call.name,
                    arguments = preview(call.rawArguments),
                    result = summarize(result),
                    ok = !looksLikeFailure(result),
                )
                history += OpenAiCompatibleClient.toolResult(call.id, result)
            }

            onMessage(
                AgentMessage(
                    AgentMessage.Role.Assistant,
                    completion.content,
                    toolCalls = steps.map { it.name },
                    toolSteps = steps,
                    reasoning = completion.reasoning.takeIf { it.isNotBlank() },
                ),
            )
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
    // Dispatch
    // -----------------------------------------------------------------------

    private suspend fun executeTool(tool: AgentTool, args: JsonObject): String = try {
        when {
            tool.name == "run_shell" -> runShell(args)
            tool.name.startsWith("mem_") -> runMemoryTool(tool.name, args)
            tool.name.startsWith("script_") -> runScriptTool(tool.name, args)
            tool.name == "read_file" || tool.name == "write_file" -> runFileTool(tool.name, args)
            else -> runInTarget(tool.name, args)
        }
    } catch (t: Throwable) {
        errJson(t.message ?: t::class.java.simpleName)
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
            return errJson("目标进程未加载模块：请先启动一个已授权作用域并勾选悬浮窗的应用")
        }
        val result = bridge.tool(name, args)
        return result.fold(
            onSuccess = { data -> OpenAiCompatibleClient.summarize(data) },
            onFailure = { error -> errJson(error.message ?: "工具执行失败") },
        )
    }

    /** Runs a root shell command on the settings side. */
    private suspend fun runShell(args: JsonObject): String {
        val command = args.strArg("command")
            ?: return errJson("缺少 command 参数")

        val result = RootShell.run(command, timeoutSeconds = 25)
        return result.fold(
            onSuccess = { output ->
                okJson { put("output", output.take(MAX_SHELL_OUTPUT)) }
            },
            onFailure = { error ->
                errJson(error.message ?: "命令执行失败")
            },
        )
    }

    // -----------------------------------------------------------------------
    // Script library tools
    // -----------------------------------------------------------------------

    /**
     * The `script_*` tools: the durable form of a debug session.
     *
     * Everything except `script_run` executes here; `script_run` republishes and
     * then asks the *target process* to execute the script, because an action is
     * a reflection call against that process's classes.
     */
    private suspend fun runScriptTool(name: String, args: JsonObject): String = when (name) {
        "script_list" -> scriptList(args)
        "script_get" -> scriptGet(args)
        "script_create" -> scriptCreate(args)
        "script_update" -> scriptUpdate(args)
        "script_run" -> scriptRun(args)
        "script_versions" -> scriptVersions(args)
        "script_restore" -> scriptRestore(args)
        else -> errJson("未知的脚本工具 $name")
    }

    private fun scriptList(args: JsonObject): String {
        val pattern = args.strArg("pattern").orEmpty()
        val entries = scripts.list().filter {
            pattern.isBlank() ||
                it.name.contains(pattern, ignoreCase = true) ||
                it.id.contains(pattern, ignoreCase = true)
        }

        return okJson {
            put("count", entries.size)
            put(
                "scripts",
                buildJsonArray {
                    entries.forEach { entry ->
                        add(
                            buildJsonObject {
                                put("id", entry.id)
                                put("name", entry.name)
                                put("enabled", entry.enabled)
                                put("trigger", entry.trigger.name)
                                put("actions", entry.actionCount)
                                put("versions", entry.versionCount)
                                put("currentVersion", entry.currentVersion)
                                put("updatedAt", entry.updatedAt)
                                if (entry.description.isNotBlank()) {
                                    put("description", entry.description)
                                }
                            },
                        )
                    }
                },
            )
        }
    }

    private fun scriptGet(args: JsonObject): String {
        val id = args.strArg("id") ?: return errJson("script_get 需要 id")
        val spec = scripts.load(id) ?: return errJson("没有这个脚本：$id")
        return okJson {
            put("script", specToJson(spec))
            put(
                "versions",
                buildJsonArray {
                    scripts.versions(id).forEach { info ->
                        add(
                            buildJsonObject {
                                put("version", info.version)
                                put("savedAt", info.savedAt)
                                put("actions", info.actionCount)
                            },
                        )
                    }
                },
            )
        }
    }

    private suspend fun scriptCreate(args: JsonObject): String {
        val name = args.strArg("name")?.takeIf { it.isNotBlank() }
            ?: return errJson("script_create 需要 name")
        val actions = parseActions(args["actions"])
        if (actions.isEmpty()) {
            return errJson(
                "script_create 需要至少一个 action（kind 取 Trace/SetReturn/SetArg/GetField/SetField/CallMethod）",
            )
        }

        val spec = ScriptSpec(
            id = "",
            name = name,
            enabled = args.boolArg("enabled") ?: true,
            trigger = parseTrigger(args.strArg("trigger")),
            triggerTarget = args.strArg("triggerTarget"),
            triggerMethod = args.strArg("triggerMethod"),
            actions = actions,
            description = args.strArg("description").orEmpty(),
        )

        val saved = scripts.create(spec)
        onScriptsChanged()
        return okJson {
            put("created", saved.id)
            put("name", saved.name)
            put("trigger", saved.trigger.name)
            put("actions", saved.actions.size)
            put("version", saved.version)
            put("next", "已经下发到目标进程；用 script_run 立刻验证一次")
        }
    }

    private suspend fun scriptUpdate(args: JsonObject): String {
        val id = args.strArg("id") ?: return errJson("script_update 需要 id")
        val current = scripts.load(id) ?: return errJson("没有这个脚本：$id")

        // Only what was sent changes; everything else keeps its stored value, so
        // "enable this script" is one field rather than a full re-write that
        // could silently drop actions the model did not re-send.
        val actions = args["actions"]?.let { parseActions(it) }
        val updated = current.copy(
            name = args.strArg("name") ?: current.name,
            description = args.strArg("description") ?: current.description,
            enabled = args.boolArg("enabled") ?: current.enabled,
            trigger = args.strArg("trigger")?.let { parseTrigger(it) } ?: current.trigger,
            triggerTarget = args.strArg("triggerTarget") ?: current.triggerTarget,
            triggerMethod = args.strArg("triggerMethod") ?: current.triggerMethod,
            actions = actions ?: current.actions,
        )

        val saved = scripts.update(id, updated)
        onScriptsChanged()
        return okJson {
            put("id", saved.id)
            put("version", saved.version)
            put("actions", saved.actions.size)
            put("history", scripts.versions(id).size)
        }
    }

    private suspend fun scriptRun(args: JsonObject): String {
        val id = args.strArg("id") ?: return errJson("script_run 需要 id")
        val local = scripts.load(id) ?: return errJson("没有这个脚本：$id")

        // Publish first: a script created or edited a second ago is only in the
        // library, and the target process runs whatever `config.json` says.
        onScriptsChanged()

        if (!bridge.isModuleLoaded()) {
            return errJson("目标进程未加载模块：请先启动一个已授权作用域并勾选悬浮窗的应用")
        }

        val result = bridge.tool("script_run", buildJsonObject { put("id", JsonPrimitive(id)) })
        return result.fold(
            onSuccess = { data ->
                val message = data.strOrNull("result").orEmpty()
                okJson {
                    put("id", id)
                    put("name", local.name)
                    put("version", local.version)
                    put("result", message)
                }
            },
            onFailure = { error -> errJson(error.message ?: "执行失败") },
        )
    }

    private fun scriptVersions(args: JsonObject): String {
        val id = args.strArg("id") ?: return errJson("script_versions 需要 id")
        val versions = scripts.versions(id)
        if (versions.isEmpty()) return errJson("没有这个脚本：$id")

        return okJson {
            put("id", id)
            put("count", versions.size)
            put(
                "versions",
                buildJsonArray {
                    versions.forEach { info ->
                        add(
                            buildJsonObject {
                                put("version", info.version)
                                put("savedAt", info.savedAt)
                                put("name", info.name)
                                put("actions", info.actionCount)
                            },
                        )
                    }
                },
            )
        }
    }

    private suspend fun scriptRestore(args: JsonObject): String {
        val id = args.strArg("id") ?: return errJson("script_restore 需要 id")
        val version = args.intArg("version")
            ?: return errJson("script_restore 需要 version（整数，来自 script_versions）")

        val saved = try {
            scripts.restore(id, version)
        } catch (t: Throwable) {
            return errJson(t.message ?: "恢复失败")
        }
        onScriptsChanged()
        return okJson {
            put("id", id)
            put("restoredFrom", version)
            put("version", saved.version)
            put("actions", saved.actions.size)
        }
    }

    // -----------------------------------------------------------------------
    // File tools
    // -----------------------------------------------------------------------

    private suspend fun runFileTool(name: String, args: JsonObject): String {
        val path = args.strArg("path") ?: return errJson("$name 需要 path")
        return try {
            when (name) {
                "read_file" -> files.readFile(
                    path = path,
                    startLine = args.intArg("startLine"),
                    endLine = args.intArg("endLine"),
                )

                "write_file" -> files.writeFile(
                    path = path,
                    content = args.strArg("content").orEmpty(),
                    startLine = args.intArg("startLine"),
                    endLine = args.intArg("endLine"),
                    mode = args.strArg("mode"),
                )

                else -> errJson("未知的文件工具 $name")
            }
        } catch (e: FileTools.FileToolException) {
            errJson(e.message ?: "文件操作失败")
        } catch (e: LineRangeEdit.RangeException) {
            errJson(e.message ?: "行范围非法")
        }
    }

    // -----------------------------------------------------------------------
    // Script argument parsing
    // -----------------------------------------------------------------------

    /**
     * Turns the model's `actions` array into [ScriptAction]s.
     *
     * Unknown `kind`s are dropped rather than guessed: a `SetReturn` that the
     * engine reads as a `Trace` would look like it worked while changing
     * nothing, which is the single most expensive failure mode for a script.
     */
    private fun parseActions(element: JsonElement?): List<ScriptAction> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val kind = obj.strOrNull("kind")
                ?.let { raw -> ScriptAction.Kind.entries.firstOrNull { it.name == raw } }
                ?: return@mapNotNull null
            ScriptAction(
                kind = kind,
                className = obj.strOrNull("className"),
                memberName = obj.strOrNull("memberName"),
                signature = obj.strOrNull("signature"),
                value = obj.strOrNull("value"),
                valueType = obj.strOrNull("valueType")
                    ?.let { raw -> ScriptAction.ValueType.entries.firstOrNull { it.name == raw } }
                    ?: ScriptAction.ValueType.Auto,
                receiver = obj.strOrNull("receiver"),
            )
        }
    }

    private fun parseTrigger(raw: String?): ScriptTrigger =
        raw?.let { text -> ScriptTrigger.entries.firstOrNull { it.name == text } }
            ?: ScriptTrigger.Manual

    /** The `ScriptSpec` shape, for `script_get`'s answer. */
    private fun specToJson(spec: ScriptSpec): JsonObject = buildJsonObject {
        put("id", spec.id)
        put("name", spec.name)
        put("enabled", spec.enabled)
        put("trigger", spec.trigger.name)
        spec.triggerTarget?.let { put("triggerTarget", it) }
        spec.triggerMethod?.let { put("triggerMethod", it) }
        put("version", spec.version)
        if (spec.description.isNotBlank()) put("description", spec.description)
        put(
            "actions",
            buildJsonArray {
                spec.actions.forEach { action ->
                    add(
                        buildJsonObject {
                            put("kind", action.kind.name)
                            action.className?.let { put("className", it) }
                            action.memberName?.let { put("memberName", it) }
                            action.signature?.let { put("signature", it) }
                            action.value?.let { put("value", it) }
                            put("valueType", action.valueType.name)
                            action.receiver?.let { put("receiver", it) }
                        },
                    )
                }
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

    private fun JsonObject.boolArg(key: String): Boolean? {
        val text = (this[key] as? JsonPrimitive)?.contentOrNull?.trim() ?: return null
        return text == "true" || text == "1"
    }

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

    /**
     * A tool result, squeezed onto one line for the collapsed step row.
     *
     * The full text is not kept here: it is already in the transcript that goes
     * back to the model, and duplicating a 200 KB class dump into the UI state
     * would make every recomposition pay for it.
     */
    private fun summarize(result: String): String {
        val oneLine = result.replace(WHITESPACE, " ").trim()
        return if (oneLine.length <= SUMMARY_CHARS) {
            oneLine
        } else {
            oneLine.take(SUMMARY_CHARS) + "…（共 ${result.length} 字符）"
        }
    }

    /** Arguments, truncated for the collapsed row (the model sent the full JSON). */
    private fun preview(raw: String): String {
        val oneLine = raw.replace(WHITESPACE, " ").trim()
        return if (oneLine.length <= ARG_CHARS) oneLine else oneLine.take(ARG_CHARS) + "…"
    }

    /**
     * Best-effort failure detection for the *display* flag only.
     *
     * The tool results in this class are either `{"ok":false,…}` envelopes or
     * Chinese sentences starting with 错误, so this is exact for the shapes it
     * actually sees; a mis-detected flag only changes a colour, never behaviour.
     */
    private fun looksLikeFailure(result: String): Boolean {
        val text = result.trimStart()
        return text.startsWith("{\"ok\":false") ||
            text.startsWith("错误") ||
            text.contains("\"ok\":false")
    }

    private fun okJson(build: JsonObjectBuilder.() -> Unit): String =
        buildJsonObject {
            put("ok", true)
            build()
        }.toString()

    private fun errJson(message: String): String = buildJsonObject {
        put("ok", false)
        put("error", message)
    }.toString()

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

        /** Width of the collapsed step summary line. */
        const val SUMMARY_CHARS = 160

        /** Width of the collapsed step's argument preview. */
        const val ARG_CHARS = 220

        val WHITESPACE = Regex("\\s+")
    }
}

/** Reads a string field out of a tool-argument object. */
private fun JsonObject.strOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
