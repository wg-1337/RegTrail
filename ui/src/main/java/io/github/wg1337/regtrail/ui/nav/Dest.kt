package io.github.wg1337.regtrail.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Every destination in the app, and the four of them that own a bottom-bar tab.
 *
 * ## Why the bar shrank to four
 * The previous bar carried six working screens (总览/目标/终端/调试/Agent/打包) and
 * pushed 外观 and 自检 onto a hidden side path. Two things went wrong with that:
 *
 *  - The bar mixed *live work surfaces* (terminal, debugger, packager) with the
 *    *configuration* of those surfaces, so the app read as a pile of pages
 *    rather than as one tool with settings.
 *  - Configuration that lived on a hidden path became unfindable — the colour
 *    picker in particular, which is why it is now two rows deep inside 设置 → 外观
 *    instead of behind a tab on an unlisted screen.
 *
 * The bar therefore carries only the four things that answer "what am I doing?",
 * and everything configuration-shaped lives under [Settings]. Live surfaces did
 * not disappear: [Terminal], [Debug] and [Patch] are sub-pages of their own
 * settings group and are also one tap away from [Home]'s quick actions.
 *
 * [ownedBy] is what keeps the bar honest while a sub-page is open: a sub-page
 * still highlights the tab it belongs to, so the four tabs keep acting as the
 * app's map instead of going blank.
 */
enum class Dest(
    val route: String,
    val label: String,
    val icon: ImageVector,
    /** True for the four destinations that render a bottom-bar tab. */
    val inBottomBar: Boolean = false,
    /**
     * The tab that owns this destination, for highlighting only.
     *
     * `null` on the tabs themselves and on [Home], which owns itself. References
     * to earlier enum entries are legal, so a sub-page can point at [Settings]
     * without a lookup table.
     */
    val ownedBy: Dest? = null,
) {
    // ---- Tabs ------------------------------------------------------------

    /** Readiness at a glance plus one-tap access to every live surface. */
    Home("home", "总览", Icons.Filled.Dashboard, inBottomBar = true),

    /** Which apps the module loads into, and which show the window. */
    Scope("scope", "目标", Icons.Filled.Shield, inBottomBar = true),

    /**
     * The single home for configuration.
     *
     * Grouped by the thing being configured (外观 / 悬浮窗 / 终端 / 调试 / Agent /
     * 打包 / 环境 / 高级), so a setting can be found by thinking about what it
     * affects rather than by remembering which page used to hold it.
     */
    Settings("settings", "设置", Icons.Filled.Tune, inBottomBar = true),

    /** The conversational tool loop. */
    Agent("agent", "Agent", Icons.Filled.AutoAwesome, inBottomBar = true),

    // ---- Settings sub-pages ---------------------------------------------

    /** Theme colours (both pickers), palette style, overlay visuals. */
    Appearance("appearance", "外观", Icons.Filled.Palette, ownedBy = Settings),

    /** Floating-window mechanism, targets and auto-attach behaviour. */
    Overlay("overlay", "悬浮窗", Icons.Filled.Layers, ownedBy = Settings),

    /** Terminal mode, workspace directory and font size. */
    TerminalSettings("terminal-settings", "终端设置", Icons.Filled.Terminal, ownedBy = Settings),

    /** Hook defaults, deoptimize policy and log limit. */
    DebugSettings("debug-settings", "调试设置", Icons.Filled.BugReport, ownedBy = Settings),

    /** Base URL, API key, model and tool-round budget for the Agent. */
    AgentSettings("agent-settings", "模型配置", Icons.Filled.SmartToy, ownedBy = Settings),

    /** Root/LSPosed/device self-check. */
    SelfCheck("selfcheck", "环境自检", Icons.Filled.Build, ownedBy = Settings),

    // ---- Live surfaces (formerly tabs) ----------------------------------

    /**
     * The live terminal.
     *
     * `ownedBy = null` on purpose: it is a tool, reachable from 总览's quick
     * actions and from 设置 → 终端, so highlighting one of those tabs while it is
     * open would claim an ownership that does not exist.
     */
    Terminal("terminal", "终端", Icons.Filled.Terminal),

    /** The live debugger. A tool, like [Terminal] — hence no owning tab. */
    Debug("debug", "实时调试", Icons.Filled.BugReport),

    /**
     * Persistent injection. Produces a file, so it stays a first-class screen
     * rather than a settings row; no tab owns it for the same reason as [Terminal].
     */
    Patch("patch", "打包", Icons.Filled.Inventory2),
}
