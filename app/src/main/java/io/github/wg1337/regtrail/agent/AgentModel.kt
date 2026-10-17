package io.github.wg1337.regtrail.agent

import io.github.wg1337.regtrail.shared.model.ThinkingLevel
import io.github.wg1337.regtrail.shared.model.ThinkingWire
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
    /**
     * 思考等级 — how hard the model may think before answering.
     *
     * Turns into the two documented request fields (`thinking.type` and
     * `reasoning_effort`); see [ThinkingLevel] and
     * `OpenAiCompatibleClient.buildBody`. Defaulted to the API's own default so
     * a config written before this field existed keeps behaving identically.
     */
    val thinking: ThinkingLevel = ThinkingLevel.DEFAULT,
    /**
     * Whether the request carries the two thinking fields at all.
     *
     * See [ThinkingWire]: [ThinkingWire.Auto] sends them only for a DeepSeek
     * Base URL, which is what keeps an untouched OpenAI-pointing install working
     * exactly as before while a DeepSeek user gets thinking on by default.
     */
    val thinkingWire: ThinkingWire = ThinkingWire.Auto,
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

/**
 * One tool call made by the assistant, as the UI shows it.
 *
 * ## Why the UI needs this instead of the raw transcript
 * The model's tool call is `{name, arguments: "{…json…}"}` and its result is
 * another JSON object. Rendering those verbatim gives the user a wall of JSON
 * per step, which is unreadable and — worse — trains them to stop looking. So
 * every step carries a **one-line** summary ([result]) that is enough to see
 * what happened, and the full text is only revealed when the row is expanded.
 *
 * The fields are already truncated when the step is built (see
 * `AgentSession.describeStep`), so a 200 KB class dump cannot bloat the UI state.
 */
data class AgentToolStep(
    val name: String,
    /** Arguments as sent, truncated for display. */
    val arguments: String = "",
    /** First line / short summary of the result. */
    val result: String = "",
    /** False when the tool (or the transport) reported a failure. */
    val ok: Boolean = true,
) {
    /** The collapsed one-liner: `tool · summary`. */
    val summary: String
        get() = if (result.isBlank()) name else "$name · $result"
}

/** One rendered message in the conversation. */
data class AgentMessage(
    val role: Role,
    val text: String,
    /** Tool names the assistant asked for, for display. */
    val toolCalls: List<String> = emptyList(),
    /**
     * The ordered tool calls of this turn, each collapsible in the UI.
     *
     * Empty for every non-assistant message and for an assistant turn that
     * answered without calling anything.
     */
    val toolSteps: List<AgentToolStep> = emptyList(),
    /**
     * The model's `reasoning_content`, when it returned one.
     *
     * Kept separate from [text] on purpose: the reasoning is *not* the answer,
     * and folding a page of deliberation into the chat bubble is how a
     * three-line answer becomes unreadable. The UI shows it as a collapsible
     * block that starts collapsed.
     */
    val reasoning: String? = null,
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

    /**
     * The `actions` array, as a JSON schema fragment.
     *
     * Shared by `script_create` and `script_update` **and** by the in-overlay
     * catalogue (it literally interpolates this string), because there must be
     * exactly one description of what an action can be. It is `ScriptAction`'s
     * shape and nothing more: `kind` is the enum the engine dispatches on, and
     * the other fields are the ones each kind reads.
     */
    val SCRIPT_ACTIONS_SCHEMA: String = """
        {"type":"array","description":"按顺序执行的动作。kind 决定哪些字段有意义：Trace/SetReturn/SetArg 对 className#memberName(signature) 挂 Hook；GetField/SetField 直接读写字段；CallMethod 反射调用。","items":{"type":"object","properties":{"kind":{"type":"string","enum":["Trace","SetReturn","SetArg","GetField","SetField","CallMethod"],"description":"Trace=记录调用；SetReturn=改返回值；SetArg=改第 1 个参数；GetField=读字段并记录；SetField=写字段；CallMethod=调用方法"},"className":{"type":"string","description":"完整类名，如 com.example.Login"},"memberName":{"type":"string","description":"方法名或字段名；构造函数写 <init>"},"signature":{"type":"string","description":"参数类型列表，如 (java.lang.String,int)；留空=第一个同名方法"},"value":{"type":"string","description":"SetReturn / SetField / SetArg 的字面量"},"valueType":{"type":"string","enum":["Auto","Boolean","Int","Long","Float","Double","String","Null"],"description":"按此类型解析 value，默认 Auto"},"receiver":{"type":"string","description":"CallMethod 的接收者表达式，可空"}},"required":["kind"]}}
    """.trimIndent()

    /** Trigger names, reused by `script_create` and `script_update`. */
    private val TRIGGER_SCHEMA: String =
        """{"type":"string","enum":["AppAttach","AppOnCreate","ActivityOnCreate","ActivityOnResume","ClassLoaded","MethodEnter","Manual"],"description":"何时执行：Manual=窗口按钮/script_run 手动触发；AppAttach=attachBaseContext；AppOnCreate=Application.onCreate；Activity*=匹配 triggerTarget 的 Activity；ClassLoaded=匹配 triggerTarget 的类加载；MethodEnter=triggerMethod 首个被调用的方法"}}"""

    /** Path rules, quoted in both file tools so the model does not try `/system`. */
    private val FILE_ROOTS_NOTE: String =
        "允许的根目录：RegTrail 自己的 files / cache / 外部 files 目录，以及工作区 /data/local/tmp/regtrail。" +
            "这些之外的路径（例如 /system、/data/data/其它应用）一律拒绝；" +
            "含 \"..\" 的路径直接报错，不会被静默改写。"

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
        AgentTool(
            name = "mem_list_processes",
            description = "列出设备上所有进程的 pid、uid 与命令行。内存搜索的第一步：先从这里找到目标应用的 pid。",
            parameters = """{"type":"object","properties":{}}""",
        ),
        AgentTool(
            name = "mem_regions",
            description = "列出某个进程可读的内存区域：起止地址、权限、大小、路径。" +
                "名字里带 [anon:…] 的匿名区域才最可能存放应用自己的数据。",
            parameters = """
                {"type":"object","properties":{
                  "pid":{"type":"integer","description":"进程号"}
                },"required":["pid"]}
            """.trimIndent(),
        ),
        AgentTool(
            name = "mem_scan",
            description = "在目标进程内存里按类型搜索一个值（例如游戏的金币数）。" +
                "只扫描可读的私有/匿名内存，单次最多读取 64 MB；数值类型按大小对齐。" +
                "返回命中的地址，命中数默认上限 500。",
            parameters = """
                {"type":"object","properties":{
                  "pid":{"type":"integer","description":"进程号"},
                  "type":{"type":"string","enum":["int32","int64","float32","float64","utf8"]},
                  "value":{"type":"string","description":"要搜索的值，如 100"},
                  "maxResults":{"type":"integer","description":"最多返回多少条命中，默认 500"}
                },"required":["pid","type","value"]}
            """.trimIndent(),
        ),
        AgentTool(
            name = "mem_refine",
            description = "二次扫描（缩小范围）：重新读取 addresses 里的地址，只保留当前值仍等于 value 的。" +
                "典型用法：mem_scan 搜当前值 → 在应用里让数值变化 → mem_refine 用新值过滤。" +
                "addresses 是十六进制地址字符串数组。",
            parameters = """
                {"type":"object","properties":{
                  "pid":{"type":"integer","description":"进程号"},
                  "type":{"type":"string","enum":["int32","int64","float32","float64","utf8"]},
                  "value":{"type":"string","description":"数值变化后的新值"},
                  "addresses":{"type":"array","items":{"type":"string"},"description":"上一次命中得到的十六进制地址"}
                },"required":["pid","type","value","addresses"]}
            """.trimIndent(),
        ),
        AgentTool(
            name = "mem_write",
            description = "把目标进程某个地址的值改成 value（按 type 编码），写入后自动回读校验。" +
                "找到唯一地址后再用，写错地址会破坏目标进程状态。",
            parameters = """
                {"type":"object","properties":{
                  "pid":{"type":"integer","description":"进程号"},
                  "address":{"type":"string","description":"十六进制地址，如 0x7f12345000"},
                  "type":{"type":"string","enum":["int32","int64","float32","float64","utf8"]},
                  "value":{"type":"string","description":"要写入的新值"}
                },"required":["pid","address","type","value"]}
            """.trimIndent(),
            destructive = true,
        ),
        // ---- Script library ------------------------------------------------
        //
        // Scripts are the *durable* form of a debug session: everything the
        // interactive tools can do once, a script does again at a trigger, on
        // every launch, without the user being present. The tools below are
        // therefore not "another feature" — they are how a session that worked
        // survives the session.
        AgentTool(
            name = "script_list",
            description = "列出本机的脚本库（每个脚本的 id、名称、触发时机、动作数、版本数、当前版本）。",
            parameters = """
                {"type":"object","properties":{
                  "pattern":{"type":"string","description":"名称/id 子串过滤，可空"}
                }}
            """.trimIndent(),
        ),
        AgentTool(
            name = "script_get",
            description = "读取一个脚本的当前版本全文（触发时机 + 动作列表）。",
            parameters = """
                {"type":"object","properties":{
                  "id":{"type":"string","description":"脚本 id，来自 script_list"}
                },"required":["id"]}
            """.trimIndent(),
        ),
        AgentTool(
            name = "script_create",
            description = "新建一个脚本并保存到脚本库（自动生成 id 与 v0001）。" +
                "把你刚刚验证成功的一串操作写进来，它就会在指定时机自动重放。",
            parameters = """
                {"type":"object","properties":{
                  "name":{"type":"string","description":"脚本名，如 登录返回值改写"},
                  "description":{"type":"string","description":"一句话说明这个脚本解决什么问题"},
                  "enabled":{"type":"boolean","description":"默认 true"},
                  "trigger":$TRIGGER_SCHEMA,
                  "triggerTarget":{"type":"string","description":"Activity*/ClassLoaded 的类名（子串匹配）"},
                  "triggerMethod":{"type":"string","description":"MethodEnter 用，如 com.example.Foo#bar"},
                  "actions":$SCRIPT_ACTIONS_SCHEMA
                },"required":["name","trigger","actions"]}
            """.trimIndent(),
            destructive = true,
        ),
        AgentTool(
            name = "script_update",
            description = "更新一个脚本：只传要改的字段即可。每次保存都会追加一个新版本，旧版本永远可回滚。",
            parameters = """
                {"type":"object","properties":{
                  "id":{"type":"string","description":"脚本 id"},
                  "name":{"type":"string"},
                  "description":{"type":"string"},
                  "enabled":{"type":"boolean"},
                  "trigger":$TRIGGER_SCHEMA,
                  "triggerTarget":{"type":"string"},
                  "triggerMethod":{"type":"string"},
                  "actions":$SCRIPT_ACTIONS_SCHEMA
                },"required":["id"]}
            """.trimIndent(),
            destructive = true,
        ),
        AgentTool(
            name = "script_run",
            description = "立即在目标进程里手动执行一个脚本（trigger 为 Manual 的脚本就是这样用的）。" +
                "执行前会自动把脚本库重新下发到目标进程，所以刚创建/修改的脚本可以直接跑。",
            parameters = """
                {"type":"object","properties":{
                  "id":{"type":"string","description":"脚本 id"}
                },"required":["id"]}
            """.trimIndent(),
            destructive = true,
        ),
        AgentTool(
            name = "script_versions",
            description = "列出一个脚本的版本历史（版本号、保存时间、动作数），最新在前。",
            parameters = """
                {"type":"object","properties":{
                  "id":{"type":"string","description":"脚本 id"}
                },"required":["id"]}
            """.trimIndent(),
        ),
        AgentTool(
            name = "script_restore",
            description = "把某个历史版本恢复成新的当前版本（不会删掉中间版本）。",
            parameters = """
                {"type":"object","properties":{
                  "id":{"type":"string","description":"脚本 id"},
                  "version":{"type":"integer","description":"要恢复的版本号，来自 script_versions"}
                },"required":["id","version"]}
            """.trimIndent(),
            destructive = true,
        ),
        // ---- Files ---------------------------------------------------------
        AgentTool(
            name = "read_file",
            description = "在设置端读一个文本文件，返回带行号的内容。可以用 startLine/endLine 只读一段。" +
                FILE_ROOTS_NOTE,
            parameters = """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"绝对路径，或在允许根目录内的相对路径"},
                  "startLine":{"type":"integer","description":"起始行，1-based，含"},
                  "endLine":{"type":"integer","description":"结束行，1-based，含；省略则等于 startLine"}
                },"required":["path"]}
            """.trimIndent(),
        ),
        AgentTool(
            name = "write_file",
            description = "在设置端写一个文本文件（原子替换：先写临时文件再改名）。" +
                "给了 startLine/endLine 就只替换这一段、其余行原样保留；mode=append 追加到末尾；" +
                "mode=overwrite 覆盖整个文件。行号越界会直接报错，不会截断。" +
                FILE_ROOTS_NOTE,
            parameters = """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"绝对路径，或在允许根目录内的相对路径"},
                  "content":{"type":"string","description":"要写入的文本"},
                  "startLine":{"type":"integer","description":"替换起始行，1-based，含"},
                  "endLine":{"type":"integer","description":"替换结束行，1-based，含；省略则等于 startLine"},
                  "mode":{"type":"string","enum":["range","append","overwrite"],"description":"默认：给了行号=range，否则 overwrite"}
                },"required":["path","content"]}
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
        - 以 Root 直接读写目标进程的内存：列进程、看内存区域、按类型搜值、二次扫描缩小范围、改写内存
        - 管理本机**脚本库**：列出、读取、创建、更新、查看版本、恢复版本，并立即在目标进程里手动执行
        - 在设置端读写文本文件（read_file / write_file，支持按行范围替换或追加）

        你**不能**做的事情（不要尝试，也不要承诺）：
        - 读取或修改 CPU 寄存器。Java 层 Hook 看不到寄存器，这需要 native 插桩。
        - 在目标进程内直接执行 shell（shell 工具运行在 RegTrail 设置端）。
        - 对未启动或未授权作用域的应用做任何操作。

        工作方式：
        - 先用 list_classes / find_class 摸清结构，再决定是否挂 Hook。
        - 挂 Hook 会改变目标应用行为，属于破坏性操作，说明清楚再做。
        - 要找某个数值（金币、血量、等级），走这条路径：
          mem_list_processes 找到目标 pid → mem_scan 搜当前值 →
          在应用里让这个数值变化 → mem_refine 用新值过滤，重复到只剩少数地址 →
          mem_write 写入新值。
        - 内存搜索只覆盖可读的私有/匿名区域，单次最多 64 MB；结果被截断时说明还有区域没扫到，
          换更小的 type 或直接搜更具体的值通常比反复全量扫更快。
        - 数值按大小对齐（int32 只报 4 字节对齐的地址），所以一个值一般只有少量候选。
        - 写内存会改变目标应用状态，属于破坏性操作，说明清楚再做。
        - 回答用中文，简洁、具体，引用真实的类名、方法签名与地址。
        - 如果工具返回错误，把错误原因如实说明，不要猜测。

        脚本是调试会话的持久形态（重要）：
        - 你在交互里做对了一次的事情（找到类、挂上 Hook、改对返回值、写对内存），
          只要用户可能想**再来一次**，就用 script_create 记成脚本 ——
          否则关掉目标应用后这次调试就白做了。
        - 什么时候该记：① 用户说"每次启动都要这样"；② 你刚验证某个 Hook/改写确实有效；
          ③ 同一串操作你已经做过第二遍。什么时候不该记：只读的探索（list_classes 之类）不用记。
        - trigger 选得越早，用户越省事：能 AppAttach 就别 Manual；只在某个界面才有意义的用
          ActivityOnResume + triggerTarget。
        - 记完可以立刻用 script_run 跑一遍验证；失败就 script_update，历史版本永远能 script_restore。
        - 一个可照抄的例子（把"登录接口永远返回成功"做成每次启动自动生效）：

          script_create {
            "name": "登录永远成功",
            "description": "App 启动后把 LoginManager.checkLogin 的返回值改成 true",
            "trigger": "AppOnCreate",
            "actions": [
              {"kind":"Trace","className":"com.example.app.LoginManager",
               "memberName":"checkLogin","signature":"(java.lang.String)"},
              {"kind":"SetReturn","className":"com.example.app.LoginManager",
               "memberName":"checkLogin","signature":"(java.lang.String)",
               "value":"true","valueType":"Boolean"}
            ]
          }

          然后 script_run {"id":"<返回的 id>"} 在当前进程里先试一次。

        文件工具（read_file / write_file）：
        - 只允许在 RegTrail 自己的目录和工作区 /data/local/tmp/regtrail 内操作；
          /system、/data/data/其他应用一律拒绝，含 ".." 的路径直接报错。
        - 改已有文件请用 startLine/endLine 只替换目标几行，不要整份覆盖后祈祷没丢内容；
          追加用 mode=append。行号越界会报错，先 read_file 看清总行数再写。
    """.trimIndent()
}
