package io.github.wg1337.regtrail.ui.debug

/**
 * Live-debugger state as the UI sees it.
 *
 * Mirrors the app-side debugger types for the same reason the terminal does:
 * `:ui` should not depend on the module/framework classes.
 */

/** A method the debugger can hook. */
data class MethodItemUi(
    val signature: String,
    val className: String,
    val methodName: String,
    val paramTypes: List<String>,
    val returnType: String,
    val isStatic: Boolean,
)

/** A hook currently installed in a target process. */
data class HookItemUi(
    val id: String,
    val target: String,
    val label: String,
    val calls: Long,
    val trace: Boolean,
    val returnOverride: String?,
)

/** One class from the target's loaded set. */
data class ClassItemUi(
    val name: String,
)

/** Result of a tool call, shown as a log line in the debugger. */
data class DebugMessageUi(
    val text: String,
    val isError: Boolean = false,
)

/**
 * Full debugger state for one frame.
 *
 * [attached] means "a target process answered the bridge", which is a stronger
 * statement than "the module is installed": it proves the module is loaded *and*
 * that we can talk to it right now.
 */
data class DebugUiState(
    val attached: Boolean = false,
    /** Which target process answered, e.g. `com.example.app`. */
    val targetPackage: String? = null,
    val targetProcess: String? = null,
    val frameworkLabel: String? = null,
    val hookCount: Int = 0,
    val instanceCount: Int = 0,
    val activityCount: Int = 0,

    val classQuery: String = "",
    val methodQuery: String = "",
    val classes: List<ClassItemUi> = emptyList(),
    val methods: List<MethodItemUi> = emptyList(),
    val hooks: List<HookItemUi> = emptyList(),
    val messages: List<DebugMessageUi> = emptyList(),

    val busy: Boolean = false,
    val lastError: String? = null,
)
