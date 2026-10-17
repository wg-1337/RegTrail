package io.github.wg1337.regtrail.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Destinations in the bottom navigation bar.
 *
 * The bar carries the six screens that belong to the *working loop* (overview ->
 * pick targets -> terminal -> debugger -> agent -> package). Six is the ceiling:
 * Material's navigation bar starts truncating labels on narrow phones at seven, so
 * anything added later has to displace something rather than extend the bar.
 * Appearance and the environment self-check are setup-time concerns, reached from
 * the overview screen.
 */
enum class Dest(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val inBottomBar: Boolean = true,
) {
    Home("home", "总览", Icons.Filled.Dashboard),
    Scope("scope", "目标", Icons.Filled.Shield),
    Terminal("terminal", "终端", Icons.Filled.Terminal),
    Debug("debug", "调试", Icons.Filled.BugReport),
    Agent("agent", "Agent", Icons.Filled.AutoAwesome),
    Patch("patch", "打包", Icons.Filled.Inventory2),

    // Reached from the overview screen; not primary tabs.
    Appearance("appearance", "外观", Icons.Filled.Palette, inBottomBar = false),
    SelfCheck("selfcheck", "自检", Icons.Filled.Build, inBottomBar = false),
}
