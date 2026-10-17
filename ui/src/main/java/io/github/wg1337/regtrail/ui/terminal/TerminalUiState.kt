package io.github.wg1337.regtrail.ui.terminal

/**
 * Terminal state as the UI sees it.
 *
 * These types are deliberately separate from the app-side terminal
 * implementation: `:ui` must not depend on `:app` (or on libsu), and the UI only
 * ever needs a rendered line plus a handful of status fields. `:app` maps its
 * richer session onto this.
 */

/** One rendered line of scrollback. */
data class TerminalEntry(
    val text: String,
    val kind: Kind,
) {
    enum class Kind {
        /** The command the user typed. */
        Command,

        /** Standard output. */
        Output,

        /** Standard error, or a failure we generated. */
        Error,

        /** RegTrail's own messages (connection notices, help, errors). */
        System,
    }
}

/**
 * Which identity commands run as.
 *
 * Mirrors the app-side enum. Kept in sync manually rather than shared, because
 * sharing would drag the app's shell implementation into `:ui`.
 */
enum class TerminalMode(
    val label: String,
    val description: String,
) {
    Root("Root", "以 uid 0 执行，可访问整个设备"),
    App("应用沙箱", "以 RegTrail 自身身份执行，无需 Root"),
    HostContext("宿主上下文", "进入目标应用的挂载命名空间与 SELinux 上下文"),
    Device("工作区（联网）", "在 RegTrail 工作区内执行，网络不受限"),
}

/** Full terminal state for one frame. */
data class TerminalUiState(
    val connected: Boolean = false,
    val mode: TerminalMode = TerminalMode.Root,
    val uid: String = "",
    val user: String = "",
    val isRoot: Boolean = false,
    val cwd: String = "/data/local/tmp",
    val busy: Boolean = false,
    val entries: List<TerminalEntry> = emptyList(),
)
