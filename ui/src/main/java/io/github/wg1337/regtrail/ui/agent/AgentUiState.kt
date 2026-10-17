package io.github.wg1337.regtrail.ui.agent

/**
 * Agent state as the UI sees it.
 *
 * Mirrors the app-side agent types rather than sharing them, for the same reason
 * as the terminal and debugger: `:ui` stays free of the HTTP client and the API
 * key handling.
 */

/** One message bubble in the conversation. */
data class AgentMessageUi(
    val role: Role,
    val text: String,
    /** Tool names the assistant asked for, shown as chips. */
    val toolCalls: List<String> = emptyList(),
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
