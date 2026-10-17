package io.github.wg1337.regtrail.ui.script

/**
 * The script library as the UI sees it.
 *
 * Mirrors `ScriptStore`'s shapes instead of sharing them, for the same reason as
 * the Agent and debugger state: `:ui` must not link against the on-disk store,
 * and the two shapes are allowed to diverge (the store's `Entry` carries a
 * `ScriptTrigger` enum; the UI wants a name it can look a Chinese label up for).
 *
 * Everything here is **immutable and flat** so a screen can hold one snapshot in
 * `remember` and re-read it when the platform's revision changes.
 */

/** One row of the library list. */
data class ScriptEntryUi(
    val id: String,
    val name: String,
    val description: String = "",
    val enabled: Boolean = true,
    /** `ScriptTrigger` enum name; see [ScriptStrings.triggerLabel]. */
    val trigger: String = "Manual",
    val actionCount: Int = 0,
    val versionCount: Int = 0,
    val currentVersion: Int = 0,
    /** Save time of the current version, epoch millis. */
    val updatedAt: Long = 0L,
) {
    /** `手动 · 3 个动作 · v2 / 共 5 版` — one line, used by the list row. */
    val summary: String
        get() = buildString {
            append(ScriptStrings.triggerLabel(trigger))
            append(" · ").append(actionCount).append(" 个动作")
            if (currentVersion > 0) {
                append(" · v").append(currentVersion)
                if (versionCount > 1) append(" / 共 ").append(versionCount).append(" 版")
            }
        }
}

/** One row of a script's version history. */
data class ScriptVersionUi(
    val version: Int,
    val savedAt: Long,
    val actionCount: Int,
)

/**
 * One action being edited.
 *
 * All fields are strings: this is a *form*, and a number typed into a text field
 * is a string until it is valid. Conversion (and rejection) happens on the
 * platform side, where the `ScriptAction` enum lives.
 */
data class ScriptActionUi(
    val kind: String = "Trace",
    val className: String = "",
    val memberName: String = "",
    val signature: String = "",
    val value: String = "",
    val valueType: String = "Auto",
    val receiver: String = "",
) {
    /** `Trace com.example.Foo#bar(String)` — the collapsed one-liner. */
    val summary: String
        get() = buildString {
            append(kind)
            if (className.isNotBlank()) {
                append(' ').append(className.trim())
                if (memberName.isNotBlank()) append('#').append(memberName.trim())
                if (signature.isNotBlank()) append(signature.trim())
            }
            if (value.isNotBlank() && kind != "Trace") {
                append(" = ").append(value.trim())
            }
        }
}

/** A script being created or edited. `id` is blank for a new one. */
data class ScriptDraftUi(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val trigger: String = "Manual",
    val triggerTarget: String = "",
    val triggerMethod: String = "",
    val actions: List<ScriptActionUi> = emptyList(),
) {
    val isNew: Boolean get() = id.isBlank()
}

/** Full library state for one frame. */
data class ScriptUiState(
    val entries: List<ScriptEntryUi> = emptyList(),
    /** The script being edited, or null when the list is showing. */
    val draft: ScriptDraftUi? = null,
    /** History of [draft]'s script, newest first; empty for a new script. */
    val versions: List<ScriptVersionUi> = emptyList(),
    val message: String? = null,
    val messageIsError: Boolean = false,
    val busy: Boolean = false,
    /** Absolute path of the last bundle export, shown to the user. */
    val exportPath: String? = null,
)

/**
 * The enum names the model and the engine speak, with Chinese labels.
 *
 * Kept in one object so the library screen, the editor and the Agent tool schema
 * cannot drift: `ScriptAction.Kind.entries` on the platform side is the source of
 * truth, and a name that disappears there simply loses its label here.
 */
object ScriptStrings {

    /** `ScriptAction.Kind` values, in the order the editor offers them. */
    val ACTION_KINDS = listOf("Trace", "SetReturn", "SetArg", "GetField", "SetField", "CallMethod")

    /** `ScriptAction.ValueType` values. */
    val VALUE_TYPES = listOf("Auto", "Boolean", "Int", "Long", "Float", "Double", "String", "Null")

    /** `ScriptTrigger` values, in the order the editor offers them. */
    val TRIGGERS = listOf(
        "Manual",
        "AppAttach",
        "AppOnCreate",
        "ActivityOnCreate",
        "ActivityOnResume",
        "ClassLoaded",
        "MethodEnter",
    )

    fun triggerLabel(trigger: String): String = when (trigger) {
        "Manual" -> "手动"
        "AppAttach" -> "应用 attach"
        "AppOnCreate" -> "应用启动"
        "ActivityOnCreate" -> "Activity 创建"
        "ActivityOnResume" -> "Activity 恢复"
        "ClassLoaded" -> "类加载"
        "MethodEnter" -> "方法进入"
        else -> trigger
    }

    fun kindLabel(kind: String): String = when (kind) {
        "Trace" -> "记录调用"
        "SetReturn" -> "改写返回值"
        "SetArg" -> "改写参数"
        "GetField" -> "读字段"
        "SetField" -> "写字段"
        "CallMethod" -> "调用方法"
        else -> kind
    }

    /** One sentence per trigger, for the editor's hint line. */
    fun triggerHint(trigger: String): String = when (trigger) {
        "Manual" -> "只在悬浮窗「工具」页或 Agent 的 script_run 里执行"
        "AppAttach" -> "Application.attachBaseContext 之后立刻执行（最早的时机）"
        "AppOnCreate" -> "Application.onCreate 之后执行"
        "ActivityOnCreate" -> "匹配「目标类名」的 Activity 每次 onCreate 时执行"
        "ActivityOnResume" -> "匹配「目标类名」的 Activity 每次 onResume 时执行"
        "ClassLoaded" -> "「目标类名」对应的类第一次加载时执行"
        "MethodEnter" -> "「目标方法」第一次被调用时执行（写 com.example.Foo#bar）"
        else -> ""
    }

    /** True when the trigger uses [ScriptDraftUi.triggerTarget]. */
    fun usesTarget(trigger: String): Boolean =
        trigger == "ActivityOnCreate" || trigger == "ActivityOnResume" || trigger == "ClassLoaded"

    /** True when the trigger uses [ScriptDraftUi.triggerMethod]. */
    fun usesMethod(trigger: String): Boolean = trigger == "MethodEnter"
}
