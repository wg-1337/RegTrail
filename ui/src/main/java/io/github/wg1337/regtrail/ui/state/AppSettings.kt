package io.github.wg1337.regtrail.ui.state

import io.github.wg1337.regtrail.shared.bridge.SharedPaths
import io.github.wg1337.regtrail.ui.terminal.TerminalMode

/**
 * Behavioural settings — everything that is *not* a colour.
 *
 * ## Why this is separate from `ThemeConfig`
 * `ThemeConfig` is a `shared` model that is serialized and published to the
 * injected module as a whole. Most of the knobs here are consumed only by the
 * settings app (terminal font size, log ring-buffer size) and publishing them
 * would mean every future field is a wire-format decision. Only the two fields
 * the module really reads are copied into `ModuleConfigSnapshot` on publish —
 * see [overlayAutoAttach] and [debugAutoDeoptimize].
 *
 * ## Why every field has a default
 * The values are persisted as individual `SharedPreferences` keys. A key that
 * did not exist in an older build decodes to the default here, so upgrading
 * never resets a setting the user did change: the defaults below are the values
 * the previous hard-coded behaviour used.
 */
data class AppSettings(
    /**
     * Whether the floating window appears by itself when a target app starts.
     *
     * Published as `ModuleConfigSnapshot.enabled`. Off means "attached
     * nowhere": the module keeps running and the debugger keeps working, but no
     * window is created in any target — which is why the switch is described as
     * a visibility switch, not as an on/off for injection.
     */
    val overlayAutoAttach: Boolean = true,

    /**
     * Shell identity used when a terminal session is opened.
     *
     * Applies to the *next* session; the terminal screen's own mode chips switch
     * the live session without touching this.
     */
    val terminalDefaultMode: TerminalMode = TerminalMode.Root,

    /**
     * Directory RegTrail creates and uses as the terminal's starting point.
     *
     * Only the terminal follows this. The config push path is fixed at
     * [SharedPaths.BASE_DIR] because the module reads it from there; moving that
     * would silently break config delivery, so it is deliberately not editable.
     */
    val terminalWorkspacePath: String = SharedPaths.BASE_DIR,

    /**
     * Scrollback font size of the terminal, in sp.
     *
     * The old hard-coded 13 sp is the default, so an untouched install renders
     * exactly as before.
     */
    val terminalFontSizeSp: Int = 13,

    /**
     * What the debugger does when a method is hooked through its primary action.
     *
     * Not published: the module needs an explicit mode on every hook call, and
     * the secondary button still offers the other mode per hook. This setting
     * only decides which of the two is the one-tap default.
     */
    val hookDefaultMode: HookMode = HookMode.Trace,

    /**
     * Whether the module deoptimizes a method before hooking it.
     *
     * Published as `ModuleConfigSnapshot.autoDeoptimize`. On (the historical
     * behaviour) is what makes hooks fire on methods the JIT already inlined;
     * off trades that for not forcing a recompile of the target method.
     */
    val debugAutoDeoptimize: Boolean = true,

    /**
     * How many debugger log lines the settings app keeps.
     *
     * Bounds the in-memory ring buffer behind the debugger and the log section
     * on the live debug screen; the module still writes its own log file.
     */
    val debugLogLimit: Int = DEFAULT_DEBUG_LOG_LIMIT,
) {
    companion object {
        /** Default debugger log ring-buffer size, in lines. */
        const val DEFAULT_DEBUG_LOG_LIMIT = 200

        /** Font sizes offered in the terminal settings page. */
        val TERMINAL_FONT_SP_CHOICES = listOf(11, 13, 15, 17)

        /** Log limits offered in the debug settings page. */
        val DEBUG_LOG_LIMIT_CHOICES = listOf(60, 120, 200, 500)

        /** Allowed range for [terminalWorkspacePath]; anything else is a typo. */
        fun isValidWorkspacePath(path: String): Boolean {
            val trimmed = path.trim()
            return trimmed.startsWith("/") &&
                trimmed.length > 1 &&
                !trimmed.any { it.isWhitespace() || it == '\u0000' }
        }
    }
}

/**
 * What a freshly installed hook does.
 *
 * Mirrors the two `mode` values the module's `hook_method` tool accepts for
 * routine work (the third, `throw`, is only reachable through the Agent).
 */
enum class HookMode(
    val label: String,
    val description: String,
) {
    /** Log every call with its arguments and return value. */
    Trace("记录调用", "记录每次调用的参数与返回值，不改变应用行为"),

    /**
     * Replace the return value with a literal the user types.
     *
     * The original method still runs, so its side effects are kept; only the
     * value handed back to the caller changes.
     */
    Return("改写返回值", "把返回值换成指定字面量，原方法仍会执行"),
}
