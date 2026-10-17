package io.github.wg1337.regtrail.agent

import kotlinx.serialization.Serializable

/**
 * Configuration for the Agent's model endpoint.
 *
 * Only the **OpenAI-compatible chat-completions shape** is supported. That is a
 * deliberate single-protocol choice rather than a limitation in practice: the
 * same request/response format is spoken by OpenAI, OpenRouter, Together, Groq,
 * vLLM, Ollama's OpenAI endpoint, and most self-hosted gateways. Anything else
 * (Anthropic messages, Gemini generateContent) would need a second codepath for
 * no functional gain here.
 */
@Serializable
data class AgentConfig(
    /** Base URL *including* `/v1`, e.g. `https://api.openai.com/v1`. */
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    /** Hard cap on one request, in seconds. */
    val timeoutSeconds: Int = 60,
    /**
     * Maximum tool-calling rounds per user turn.
     *
     * Bounded on purpose: a model that keeps calling tools without converging
     * would otherwise spend the user's tokens indefinitely.
     */
    val maxToolRounds: Int = 8,
    /** Stop and ask before running a tool that mutates the target. */
    val confirmDestructive: Boolean = true,
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
    }
}

/**
 * A tool the model may call.
 *
 * Schemas are hand-written rather than generated: the set is small, the
 * descriptions are part of the prompt's quality, and hand-writing them keeps the
 * JSON exactly in the shape each provider expects.
 */
data class AgentTool(
    val name: String,
    val description: String,
    val parameters: String,
    /** True when running it changes the target's behaviour. */
    val destructive: Boolean = false,
)

/** One rendered message in the conversation. */
data class AgentMessage(
    val role: Role,
    val text: String,
    /** Tool calls made by the assistant, for display. */
    val toolCalls: List<String> = emptyList(),
) {
    enum class Role {
        User,
        Assistant,
        /** A tool result fed back to the model. */
        Tool,
        /** RegTrail's own notices (errors, limits hit). */
        System,
    }
}

/**
 * The tools exposed to the model.
 *
 * These deliberately mirror the in-process tool surface implemented by the
 * module (see `AgentTools`), because the model's job is to *drive* that surface,
 * not to reimplement it.
 */
object AgentTools {

    val ALL: List<AgentTool> = listOf(
        AgentTool(
            name = "list_classes",
            description = "列出目标进程中已加载的类。可用 pattern 做子串过滤。",
            parameters = """
                {"type":"object","properties":{
                  "pattern":{"type":"string","description":"类名子串，可为空"},
                  "limit":{"type":"integer","description":"最多返回数量，默认 100"}
                }}
            """.trimIndent(),
        ),
        AgentTool(
            name = "find_class",
            description = "按完整类名获取一个类的成员（方法、字段、父类、接口）。",
            parameters = """
                {"type":"object","properties":{
                  "name":{"type":"string","description":"完整类名，如 com.example.Foo"}
                },"required":["name"]}
            """.trimIndent(),
        ),
        AgentTool(
            name = "list_activities",
            description = "列出目标进程中当前存活的 Activity，以及窗口标题。",
            parameters = """{"type":"object","properties":{}}""",
        ),
        AgentTool(
            name = "dump_view_tree",
            description = "导出当前 Activity 的 View 树，含 id、尺寸与文本内容。",
            parameters = """
                {"type":"object","properties":{
                  "maxDepth":{"type":"integer","description":"最大深度，默认 12"}
                }}
            """.trimIndent(),
        ),
        AgentTool(
            name = "hook_method",
            description = "对目标方法挂 Hook。mode=trace 记录每次调用的参数与返回值；" +
                "mode=return 则把返回值强制改成 value。原方法仍会执行，副作用保留。",
            parameters = """
                {"type":"object","properties":{
                  "signature":{"type":"string","description":"com.example.Foo#bar(int,java.lang.String)"},
                  "mode":{"type":"string","enum":["trace","return"]},
                  "value":{"type":"string","description":"mode=return 时的返回值字面量"}
                },"required":["signature","mode"]}
            """.trimIndent(),
            destructive = true,
        ),
        AgentTool(
            name = "list_hooks",
            description = "列出当前已安装的 Hook 及其命中次数。",
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
            name = "set_field",
            description = "写入一个静态字段的值。",
            parameters = """
                {"type":"object","properties":{
                  "className":{"type":"string"},
                  "fieldName":{"type":"string"},
                  "value":{"type":"string","description":"字面量，如 true / 42 / 文本"}
                },"required":["className","fieldName","value"]}
            """.trimIndent(),
            destructive = true,
        ),
        AgentTool(
            name = "call_method",
            description = "调用一个静态方法并返回结果。args 为字符串参数数组，会按目标类型转换。",
            parameters = """
                {"type":"object","properties":{
                  "signature":{"type":"string","description":"com.example.Foo#bar(int)"},
                  "args":{"type":"array","items":{"type":"string"}}
                },"required":["signature"]}
            """.trimIndent(),
            destructive = true,
        ),
        AgentTool(
            name = "run_shell",
            description = "在设备上以 Root 执行 shell 命令（在 RegTrail 设置端执行，" +
                "不是目标进程内）。适合 ls / cat / dumpsys / logcat 等。",
            parameters = """
                {"type":"object","properties":{
                  "command":{"type":"string"}
                },"required":["command"]}
            """.trimIndent(),
            destructive = true,
        ),
    )

    /** Looks up a tool by the name the model used. */
    fun byName(name: String): AgentTool? = ALL.firstOrNull { it.name == name }

    /**
     * The system prompt.
     *
     * States the capability boundary explicitly, because a model that believes it
     * can read CPU registers will keep trying and waste the user's budget.
     */
    val SYSTEM_PROMPT: String = """
        你是 RegTrail 内置的安卓逆向分析助手。你可以调用工具来检查一个正在运行的目标应用。

        你的能力范围：
        - 枚举已加载的类、查看类的成员、读取静态字段
        - 在当前 Activity 上查看 View 树
        - 对 Java 方法挂 Hook：观察参数与返回值，或强制改写返回值
        - 以 Root 执行 shell 命令

        你**不能**做的事情（不要尝试，也不要承诺）：
        - 读取或修改 CPU 寄存器。Java 层 Hook 看不到寄存器，这需要 native 插桩。
        - 在目标进程内直接执行 shell（shell 工具运行在 RegTrail 设置端）。
        - 对未启动或未授权作用域的应用做任何操作。

        工作方式：
        - 先用 list_classes / find_class 摸清结构，再决定是否挂 Hook。
        - 挂 Hook 会改变目标应用行为，属于破坏性操作，说明清楚再做。
        - 回答用中文，简洁、具体，引用真实的类名与方法签名。
        - 如果工具返回错误，把错误原因如实说明，不要猜测。
    """.trimIndent()
}
