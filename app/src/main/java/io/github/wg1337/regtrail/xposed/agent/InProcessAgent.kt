package io.github.wg1337.regtrail.xposed.agent

import io.github.wg1337.regtrail.agent.AgentConfig
import io.github.wg1337.regtrail.agent.AgentException
import io.github.wg1337.regtrail.agent.AgentTool
import io.github.wg1337.regtrail.agent.OpenAiCompatibleClient
import io.github.wg1337.regtrail.shared.bridge.BridgeApi
import io.github.wg1337.regtrail.xposed.RegTrailLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Agent, running **inside the target process**.
 *
 * ## What this is
 * A small ReAct loop over an OpenAI-compatible chat-completions endpoint:
 *
 * ```
 * user turn ─▶ model ─┬─ final answer ─────────────────────────────▶ reply
 *                     └─ tool_calls ─▶ execute in-process tool ─┐
 *                                                              │
 *                        ◀── tool results appended ────────────┘
 * ```
 *
 * The tools are the ones that already exist in this process
 * (`xposed.bridge.AgentTools`: class/hook/field/method/Activity/View inspection,
 * plus the in-process memory tools). That is the whole point: class loaders,
 * live instance graphs, real field values and this process's own memory only
 * exist here, so an Agent that runs *anywhere else* can only ever guess.
 *
 * ## Why not reuse the settings-app AgentSession
 * `AgentSession` drives the target through a ContentProvider and its tool set
 * includes root-shell tools (`run_shell`, `mem_scan` by pid). Inside the target
 * process a shell tool would have to fork `su`, which most hosts neither have nor
 * should; and the pid-based memory tools must not be used to read *another*
 * process. The loop mechanics, the model client and the wire protocol are shared
 * (this reuses [OpenAiCompatibleClient]); only the tool catalogue and the system
 * prompt differ, and both are stated here.
 *
 * ## Safety
 *  - A destructive tool (anything that writes to the target) is **not** executed
 *    unless the user ticked 「允许破坏性操作」 in the panel for this session. The
 *    model is told so in the tool result, which makes it explain the situation
 *    rather than retry.
 *  - Conversation state is capped; a long session drops its oldest turns instead
 *    of growing the host app's heap forever.
 *  - The API key is never logged and never leaves this class.
 */
internal object InProcessAgent {

    /** One finished turn: the final text plus the tool steps that led to it. */
    data class Outcome(val reply: String, val steps: List<String>)

    /** Keeps a long chat from pinning an unbounded message list in the host heap. */
    private const val MAX_HISTORY_MESSAGES = 28

    /** Hard cap on tool rounds per turn, whatever the config says. */
    private const val MAX_ROUNDS = 10

    /** Longest tool result fed back to the model, in characters. */
    private const val MAX_TOOL_CHARS = 4000

    private val prettyJson = Json { prettyPrint = true; encodeDefaults = false }

    private val history = ArrayDeque<JsonObject>()

    /** How many messages the current conversation holds (shown in the UI). */
    fun pending(): Int = synchronized(history) { history.size }

    /** Forgets the conversation; the next turn starts from the system prompt. */
    fun reset() {
        synchronized(history) { history.clear() }
    }

    /**
     * Runs one user turn to completion.
     *
     * @param config endpoint + limits, published by the settings app.
     * @param prompt the user's message.
     * @param allowDestructive when false, tools that mutate the target are refused
     *   with an explanation instead of failing the whole turn.
     * @param execute runs one tool in this process (the controller supplies this
     *   so this class never needs a Context, a class loader or the reflection
     *   surface).
     * @param shouldStop polled between rounds and between tool calls; a cancelled
     *   job stops before spending another model request.
     * @param onStep publishes a progress line for the panel.
     * @throws AgentException on any transport/model failure, with a Chinese
     *   message the panel can show verbatim.
     */
    suspend fun chat(
        config: AgentConfig,
        prompt: String,
        allowDestructive: Boolean,
        execute: (String, JsonObject) -> BridgeApi.Response,
        shouldStop: () -> Boolean = { false },
        onStep: (String) -> Unit = {},
    ): Outcome {
        if (!config.isConfigured) {
            throw AgentException("未配置模型：请先在设置端填写 Base URL、模型名与 API Key")
        }
        if (prompt.isBlank()) throw AgentException("请输入要分析的内容")

        val tools = toolCatalog()
        val client = OpenAiCompatibleClient(config)
        val steps = ArrayList<String>()
        val rounds = config.maxToolRounds.coerceIn(1, MAX_ROUNDS)

        synchronized(history) {
            if (history.isEmpty()) {
                history.addLast(OpenAiCompatibleClient.system(SYSTEM_PROMPT))
            }
            history.addLast(OpenAiCompatibleClient.message("user", prompt))
            trim()
        }

        var reply = ""
        for (round in 1..rounds) {
            if (shouldStop()) throw AgentException("已取消")
            if (round > 1) onStep("第 $round 轮：继续分析…")

            val messages = synchronized(history) { history.toList() }
            val completion = client.complete(messages, tools).getOrElse { throw it }

            if (completion.content.isNotBlank()) reply = completion.content

            if (completion.toolCalls.isEmpty()) {
                synchronized(history) {
                    if (completion.content.isNotBlank()) {
                        history.addLast(
                            OpenAiCompatibleClient.message("assistant", completion.content),
                        )
                    }
                    trim()
                }
                return Outcome(reply, steps)
            }

            // The assistant message that *requested* the calls must be echoed back
            // verbatim: every provider matches `tool_call_id` against it.
            synchronized(history) {
                history.addLast(completion.rawMessage)
                trim()
            }

            for (call in completion.toolCalls) {
                if (shouldStop()) throw AgentException("已取消")
                val tool = tools.firstOrNull { it.name == call.name }
                val text = when {
                    tool == null -> "错误：未知工具 ${call.name}"
                    tool.destructive && !allowDestructive ->
                        "错误：${call.name} 会修改目标应用，需要用户在 Agent 面板勾选" +
                            "「允许破坏性操作」后才能执行。请向用户说明你要做什么，等他开启。"
                    else -> {
                        onStep("第 $round 轮：${call.name}")
                        val response = runCatching { execute(call.name, call.arguments) }
                            .getOrElse {
                                BridgeApi.Response(
                                    ok = false,
                                    error = it.message ?: it::class.java.simpleName,
                                )
                            }
                        val rendered = renderToolResult(response)
                        steps += "${call.name} → ${rendered.lineSequence().first().take(160)}"
                        rendered
                    }
                }
                synchronized(history) {
                    history.addLast(OpenAiCompatibleClient.toolResult(call.id, text))
                    trim()
                }
            }
        }

        RegTrailLog.d("agent: tool round limit ($rounds) reached")
        return Outcome(
            reply.ifBlank {
                "（已达到 $rounds 轮工具调用上限，仍没有得出最终结论。可以继续追问，" +
                    "或者把问题拆得更小：先定位类，再看字段，最后才决定要不要改写。）"
            },
            steps,
        )
    }

    /**
     * Renders one tool response for the model *and* for the console.
     *
     * The model pays for every character, so short scalar results are inlined and
     * structured ones are pretty-printed with a hard cap. This is also the exact
     * text the 工具控制台 shows, which makes "what did the model actually see" a
     * question that can be answered from the panel.
     */
    fun renderToolResult(response: BridgeApi.Response, limit: Int = MAX_TOOL_CHARS): String {
        if (!response.ok) return "错误：${response.error ?: "工具执行失败"}"
        val data = response.data
        val text = when {
            data is JsonObject -> singleValue(data)
                ?: runCatching {
                    prettyJson.encodeToString(JsonElement.serializer(), data)
                }.getOrElse { data.toString() }
            else -> data.toString()
        }
        return if (text.length <= limit) {
            text
        } else {
            text.take(limit) + "…（已截断，原长 ${text.length}）"
        }
    }

    /** A one-field result (`value`/`result`/`tree`) is nicer un-quoted. */
    private fun singleValue(data: JsonObject): String? {
        for (key in listOf("tree", "value", "result", "removed", "id")) {
            val element = data[key] ?: continue
            val text = (element as? JsonPrimitive)?.contentOrNull ?: continue
            if (text.isNotEmpty()) return text
        }
        return null
    }

    /**
     * Drops the oldest turns, keeping the system prompt first.
     *
     * A `tool` message whose parent assistant message was just dropped would be
     * rejected by every OpenAI-compatible endpoint ("tool_call_id not found"), so
     * leading tool messages are dropped as well.
     */
    private fun trim() {
        if (history.size <= MAX_HISTORY_MESSAGES) return

        val system = history.firstOrNull()?.takeIf { it.role() == "system" }
        if (system != null) history.removeFirst()
        while (history.size > MAX_HISTORY_MESSAGES - 1 && history.isNotEmpty()) {
            history.removeFirst()
        }
        while (history.isNotEmpty() && history.first().role() == "tool") {
            history.removeFirst()
        }
        if (system != null) history.addFirst(system)
    }

    private fun JsonObject.role(): String? = this["role"]?.jsonPrimitive?.contentOrNull

    // -----------------------------------------------------------------------
    // Tool catalogue
    // -----------------------------------------------------------------------

    /**
     * The tools the model may call, all executed in this process.
     *
     * Schemas are hand-written for the same reason the settings-side catalogue is:
     * the descriptions are part of the prompt's quality, and hand-writing keeps
     * the JSON exactly in the shape providers expect.
     */
    fun toolCatalog(): List<AgentTool> = CATALOG

    /** Names only, for the panel's 「可用工具」 hint and the console's help. */
    fun toolNames(): List<String> = CATALOG.map { it.name }

    private val CATALOG: List<AgentTool> = listOf(
        AgentTool(
            name = "list_classes",
            description = "列出当前进程已加载的类。可用 pattern 做子串过滤（区分大小写不敏感）。",
            parameters = """
                {"type":"object","properties":{
                  "pattern":{"type":"string","description":"类名子串，可为空"},
                  "limit":{"type":"integer","description":"最多返回数量，默认 200"}
                }}
            """.trimIndent(),
        ),
        AgentTool(
            name = "find_class",
            description = "按完整类名获取一个类的父类、接口、方法与字段。",
            parameters = """
                {"type":"object","properties":{
                  "name":{"type":"string","description":"完整类名，如 com.example.Foo"}
                },"required":["name"]}
            """.trimIndent(),
        ),
        AgentTool(
            name = "dump_class",
            description = "同 find_class：导出一个类的结构。",
            parameters = """
                {"type":"object","properties":{
                  "name":{"type":"string","description":"完整类名"}
                },"required":["name"]}
            """.trimIndent(),
        ),
        AgentTool(
            name = "list_activities",
            description = "列出当前进程中存活的 Activity，以及它的窗口标题与是否处于前台。",
            parameters = """{"type":"object","properties":{}}""",
        ),
        AgentTool(
            name = "dump_view_tree",
            description = "导出当前 Activity 的 View 树，含类名、资源 id、尺寸与文本。",
            parameters = """
                {"type":"object","properties":{
                  "maxDepth":{"type":"integer","description":"最大深度，默认 12"}
                }}
            """.trimIndent(),
        ),
        AgentTool(
            name = "list_hooks",
            description = "列出本进程内已安装的 Hook 及命中次数。",
            parameters = """{"type":"object","properties":{}}""",
        ),
        AgentTool(
            name = "unhook",
            description = "移除一个已安装的 Hook。",
            parameters = """
                {"type":"object","properties":{
                  "id":{"type":"string","description":"Hook 标识，来自 list_hooks"}
                },"required":["id"]}
            """.trimIndent(),
            destructive = true,
        ),
        AgentTool(
            name = "get_static_field",
            description = "读取一个静态字段的值。",
            parameters = """
                {"type":"object","properties":{
                  "className":{"type":"string"},
                  "fieldName":{"type":"string"}
                },"required":["className","fieldName"]}
            """.trimIndent(),
        ),
        AgentTool(
            name = "get_field",
            description = "读取实例字段的值。实例字段需要 instance 句柄（本进程内的对象标识）。",
            parameters = """
                {"type":"object","properties":{
                  "className":{"type":"string"},
                  "fieldName":{"type":"string"},
                  "instance":{"type":"string","description":"实例句柄"}
                },"required":["className","fieldName","instance"]}
            """.trimIndent(),
        ),
        AgentTool(
            name = "set_field",
            description = "写入一个字段的值（字面量会按字段类型转换）。",
            parameters = """
                {"type":"object","properties":{
                  "className":{"type":"string"},
                  "fieldName":{"type":"string"},
                  "value":{"type":"string","description":"字面量，如 true / 42 / 文本"},
                  "instance":{"type":"string","description":"实例字段才需要"}
                },"required":["className","fieldName","value"]}
            """.trimIndent(),
            destructive = true,
        ),
        AgentTool(
            name = "call_method",
            description = "调用一个方法并返回结果。args 为字符串数组，会按参数类型转换。",
            parameters = """
                {"type":"object","properties":{
                  "signature":{"type":"string","description":"com.example.Foo#bar(int,java.lang.String)"},
                  "args":{"type":"array","items":{"type":"string"}},
                  "instance":{"type":"string","description":"实例方法才需要"}
                },"required":["signature"]}
            """.trimIndent(),
            destructive = true,
        ),
        AgentTool(
            name = "hook_method",
            description = "给目标方法挂 Hook。mode=trace 记录参数与返回值；mode=return 强制改写返回值。",
            parameters = """
                {"type":"object","properties":{
                  "signature":{"type":"string","description":"com.example.Foo#bar(int)"},
                  "mode":{"type":"string","enum":["trace","return","throw"]},
                  "value":{"type":"string","description":"mode=return 时的返回值字面量"},
                  "label":{"type":"string","description":"备注，便于在列表里辨认"}
                },"required":["signature","mode"]}
            """.trimIndent(),
            destructive = true,
        ),
        AgentTool(
            name = "mem_scan",
            description = "在本进程自己的内存里按类型搜索一个值（不需要 pid，也不需要 Root）。" +
                "type 取 int32/int64/float32/float64/utf8；数值按大小对齐。" +
                "返回会话号与命中地址，命中数默认上限 500。",
            parameters = """
                {"type":"object","properties":{
                  "type":{"type":"string","enum":["int32","int64","float32","float64","utf8"]},
                  "value":{"type":"string","description":"要搜索的值，如 100"},
                  "writableOnly":{"type":"boolean","description":"只搜可写内存，默认 false"},
                  "maxResults":{"type":"integer","description":"最多返回多少条命中"}
                },"required":["type","value"]}
            """.trimIndent(),
        ),
        AgentTool(
            name = "mem_refine",
            description = "二次扫描：用上一次的会话号复核地址，只保留当前值等于 value 的。" +
                "用法：mem_scan 搜当前值 → 在应用里让数值变化 → mem_refine 用新值过滤。",
            parameters = """
                {"type":"object","properties":{
                  "session":{"type":"integer","description":"mem_scan 返回的会话号"},
                  "value":{"type":"string","description":"数值变化后的新值"}
                },"required":["session","value"]}
            """.trimIndent(),
        ),
        AgentTool(
            name = "mem_write",
            description = "把本进程某个地址的值改成 value，写入后自动回读校验，并记录原始值以便还原。" +
                "写错地址会破坏目标应用状态，务必先用 mem_refine 缩到少数地址。",
            parameters = """
                {"type":"object","properties":{
                  "addr":{"type":"string","description":"十六进制地址，如 0x7f12345000"},
                  "type":{"type":"string","enum":["int32","int64","float32","float64","utf8"]},
                  "value":{"type":"string","description":"要写入的新值"}
                },"required":["addr","type","value"]}
            """.trimIndent(),
            destructive = true,
        ),
        AgentTool(
            name = "mem_revert",
            description = "把某个地址还原成写入前的原始值。不带 addr 时还原全部。",
            parameters = """
                {"type":"object","properties":{
                  "addr":{"type":"string","description":"十六进制地址，可空"}
                }}
            """.trimIndent(),
            destructive = true,
        ),
    )

    /**
     * The system prompt.
     *
     * It states the *in-process* boundary explicitly, because a model trained on
     * "adb shell + root" answers will otherwise keep proposing tools that do not
     * exist here, and the user reads those proposals as the feature being broken.
     */
    val SYSTEM_PROMPT: String = """
        你是 RegTrail 注入在目标应用进程里的分析助手。你运行在该应用**自己的进程内**，
        因此能看到它真实的类加载器、对象与内存，但不能使用 shell，也不能读写别的进程。

        你可以用的工具（都在当前进程内执行）：
        - list_classes / find_class / dump_class：摸清类结构
        - list_activities / dump_view_tree：看界面
        - get_static_field / get_field / set_field：读写字段
        - call_method：调用方法
        - hook_method / list_hooks / unhook：方法级调试
        - mem_scan / mem_refine / mem_write / mem_revert：在当前进程内存里搜索与改写数值
          （不需要 Root；写入会记录原始值并且可以还原）

        你**不能**做的事（不要承诺、不要尝试）：
        - 执行 shell 命令、读写其它进程的内存、读取 CPU 寄存器
        - 修改目标 APK 或重新签名
        - 在用户没有勾选「允许破坏性操作」时修改任何状态（工具会直接拒绝并告诉你）

        工作方式：
        - 先用类/字段工具定位，再决定是否 Hook 或写内存；每一步都要能解释理由。
        - 找某个数值的路径是：mem_scan 搜当前值 → 让用户在应用里改变它 →
          mem_refine 用新值过滤，重复到只剩少量地址 → 再考虑 mem_write。
        - 破坏性操作前先用一句话说明后果；写入后提醒用户可以用「还原」回滚。
        - 工具返回错误时如实说明原因，不要编造结果。
        - 用中文回答，简洁、具体，引用真实的类名、方法签名与地址。
    """.trimIndent()
}
