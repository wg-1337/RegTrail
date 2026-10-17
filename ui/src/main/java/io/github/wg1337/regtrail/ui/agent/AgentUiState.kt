package io.github.wg1337.regtrail.ui.agent

/**
 * Agent state as the UI sees it.
 *
 * Mirrors the app-side agent types rather than sharing them, for the same reason
 * as the terminal and debugger: `:ui` stays free of the HTTP client and the API
 * key handling.
 */

/**
 * One tool call made by the assistant, as a collapsed row.
 *
 * The fields are already truncated by the session (see `AgentSession.preview` /
 * `summarize`), so expanding a row shows a bounded amount of text even when the
 * tool returned a 200 KB class dump.
 */
data class AgentToolStepUi(
    val name: String,
    val arguments: String = "",
    val result: String = "",
    val ok: Boolean = true,
) {
    /** The collapsed one-liner: `tool · summary`. */
    val summary: String get() = if (result.isBlank()) name else "$name · $result"
}

/** One message bubble in the conversation. */
data class AgentMessageUi(
    val role: Role,
    val text: String,
    /** Tool names the assistant asked for, shown as chips. */
    val toolCalls: List<String> = emptyList(),
    /** Ordered, individually collapsible tool calls of this turn. */
    val toolSteps: List<AgentToolStepUi> = emptyList(),
    /** `reasoning_content`, shown in a collapsible block that starts closed. */
    val reasoning: String? = null,
) {
    enum class Role {
        User,
        Assistant,
        Tool,
        System,
    }
}

/** Non-secret view of the agent configuration. */
data class AgentConfigUi(
    val baseUrl: String = "",
    val model: String = "",
    val hasApiKey: Boolean = false,
    val maxToolRounds: Int = 8,
    /** `ThinkingLevel` enum name: `Off` / `Low` / `High` / `Max`. */
    val thinking: String = "High",
    /** `ThinkingWire` enum name: `Auto` / `Always` / `Never`. */
    val thinkingWire: String = "Auto",
) {
    val isConfigured: Boolean
        get() = hasApiKey && baseUrl.isNotBlank() && model.isNotBlank()
}

/** Full agent state for one frame. */
data class AgentUiState(
    val messages: List<AgentMessageUi> = emptyList(),
    val config: AgentConfigUi = AgentConfigUi(),
    val running: Boolean = false,
    /** True when a target process is currently answering the bridge. */
    val targetAttached: Boolean = false,
    val targetPackage: String? = null,
    val lastError: String? = null,
)
