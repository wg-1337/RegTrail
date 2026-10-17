package io.github.wg1337.regtrail.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.wg1337.regtrail.shared.model.OverlayMechanism
import io.github.wg1337.regtrail.shared.model.ScopeUiState
import io.github.wg1337.regtrail.shared.model.SelfCheckReport
import io.github.wg1337.regtrail.ui.agent.AgentUiState
import io.github.wg1337.regtrail.ui.debug.DebugUiState
import io.github.wg1337.regtrail.ui.nav.Dest
import io.github.wg1337.regtrail.ui.patch.PatchUiState
import io.github.wg1337.regtrail.ui.screen.AgentScreen
import io.github.wg1337.regtrail.ui.screen.AppearanceScreen
import io.github.wg1337.regtrail.ui.screen.DebugScreen
import io.github.wg1337.regtrail.ui.screen.HomeScreen
import io.github.wg1337.regtrail.ui.screen.PatchScreen
import io.github.wg1337.regtrail.ui.screen.ScopeScreen
import io.github.wg1337.regtrail.ui.screen.SelfCheckScreen
import io.github.wg1337.regtrail.ui.screen.TerminalScreen
import io.github.wg1337.regtrail.ui.state.ConfigStore
import io.github.wg1337.regtrail.ui.terminal.TerminalMode
import io.github.wg1337.regtrail.ui.terminal.TerminalUiState
import io.github.wg1337.regtrail.ui.theme.RegTrailTheme
import kotlinx.coroutines.launch

/**
 * Root-access state as the UI sees it.
 *
 * [diagnosis] is raw probe output rather than a polished sentence on purpose: it
 * names which check returned what, which is what makes it actionable when the
 * answer is surprising (APatch and KernelSU both make libsu's own
 * `isAppGrantedRoot()` report false, and a user staring at that needs to know
 * why).
 */
data class RootStateUi(
    val granted: Boolean = false,
    val diagnosis: String = "",
)

/**
 * Everything `:ui` needs from the platform, injected by `:app`.
 *
 * Keeping this an interface is what lets `:ui` stay free of root access,
 * `PackageManager` enumeration, libsu and the LSPosed framework types.
 */
interface PlatformBridge {
    // ---- Environment -------------------------------------------------------
    suspend fun runSelfCheck(): SelfCheckReport

    /**
     * Monotonic revision of all platform state.
     *
     * The UI re-reads its snapshots whenever this changes. Polling a counter is
     * deliberate: the state is assembled from several sources (root probes,
     * framework binder, terminal, bridge) that change at unpredictable times, and
     * this keeps a single, obvious invalidation path instead of N parallel flows
     * that can each be forgotten.
     */
    fun stateRevision(): Long

    // ---- Root --------------------------------------------------------------
    fun rootState(): RootStateUi

    /** Triggers the su manager's prompt so the user can grant root. */
    fun requestRootAccess()

    // ---- Scope / targets ---------------------------------------------------
    fun scopeState(): ScopeUiState
    fun refreshScope()
    fun grantScope(packages: List<String>, onResult: (Boolean, String) -> Unit)
    fun revokeScope(packages: List<String>, onResult: (Boolean, String) -> Unit)
    fun toggleOverlayTarget(packageName: String)

    /** Adds/removes a package from the pending scope request. */
    fun toggleScopeTarget(packageName: String)

    /** Re-publishes the whole config snapshot to the module. */
    fun publishConfig()

    // ---- Terminal ----------------------------------------------------------
    fun terminalState(): TerminalUiState
    fun openTerminal(mode: TerminalMode)
    fun runTerminalCommand(command: String)
    fun clearTerminal()
    fun closeTerminal()

    // ---- Live debugger -----------------------------------------------------
    fun debugState(): DebugUiState
    fun refreshDebug()
    fun searchClasses(pattern: String)
    fun searchMethods(className: String, methodName: String)
    fun hookTrace(signature: String)
    fun hookReturn(signature: String, value: String, valueType: String)
    fun unhook(id: String)

    // ---- Overlay mechanism -------------------------------------------------
    fun overlayMechanism(): OverlayMechanism
    fun systemHookAcknowledged(): Boolean
    fun setOverlayMechanism(mechanism: OverlayMechanism, acknowledged: Boolean)

    // ---- Agent -------------------------------------------------------------
    fun agentState(): AgentUiState
    fun sendAgentMessage(text: String)
    fun resetAgent()
    fun saveAgentConfig(baseUrl: String, apiKey: String, model: String, rounds: Int)

    // ---- Persistent injection (P6) -----------------------------------------
    /**
     * Snapshot of the no-root packaging screen: patchable apps, produced APKs and
     * whatever the patcher is doing right now.
     *
     * Like every other `*State()` accessor this is a synchronous pull; the UI
     * re-reads it whenever [stateRevision] changes.
     */
    fun patchState(): PatchUiState

    /** Re-enumerates installed apps; cheap enough to run on every screen entry. */
    fun refreshPatchTargets()

    /**
     * Patches [packageName] and writes a signed APK.
     *
     * Asynchronous on purpose: the caller is a Compose click handler. Progress and
     * the result arrive through [patchState].
     */
    fun generatePatchedApk(packageName: String)

    /** Deletes a produced APK and forgets it. */
    fun deletePatchedApk(path: String)
}

/**
 * Root composable: theme + navigation + the working screens.
 */
@Composable
fun RegTrailApp(
    store: ConfigStore,
    platform: PlatformBridge,
) {
    var report by remember { mutableStateOf<SelfCheckReport?>(null) }
    var running by remember { mutableStateOf(false) }
    var scopeState by remember { mutableStateOf(platform.scopeState()) }
    var terminalState by remember { mutableStateOf(platform.terminalState()) }
    var debugState by remember { mutableStateOf(platform.debugState()) }
    var agentState by remember { mutableStateOf(platform.agentState()) }
    var rootState by remember { mutableStateOf(platform.rootState()) }
    var patchState by remember { mutableStateOf(platform.patchState()) }
    var revision by remember { mutableStateOf(platform.stateRevision()) }
    val scope = rememberCoroutineScope()

    // Re-read every snapshot whenever the platform reports a change. Without
    // this the screens showed whatever they read at first composition, so a
    // granted root permission or an accepted scope prompt never appeared until
    // the user navigated away and back.
    LaunchedEffect(Unit) {
        while (true) {
            val current = platform.stateRevision()
            if (current != revision) {
                revision = current
                scopeState = platform.scopeState()
                terminalState = platform.terminalState()
                debugState = platform.debugState()
                agentState = platform.agentState()
                rootState = platform.rootState()
                patchState = platform.patchState()
            }
            kotlinx.coroutines.delay(250)
        }
    }

    fun refreshSelfCheck() {
        if (running) return
        scope.launch {
            running = true
            try {
                report = platform.runSelfCheck()
            } finally {
                running = false
            }
        }
    }

    fun syncAll() {
        scopeState = platform.scopeState()
        terminalState = platform.terminalState()
        debugState = platform.debugState()
        agentState = platform.agentState()
        rootState = platform.rootState()
        patchState = platform.patchState()
    }

    LaunchedEffect(Unit) {
        platform.refreshScope()
        syncAll()
        if (report == null) refreshSelfCheck()
        // The terminal is useful immediately; open it in the background so the
        // first command does not pay the shell-startup cost.
        platform.openTerminal(TerminalMode.Root)
    }

    RegTrailTheme(config = store.theme) {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route ?: Dest.Home.route

        Scaffold(
            bottomBar = {
                NavigationBar {
                    Dest.entries.filter { it.inBottomBar }.forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = {
                                if (currentRoute != dest.route) {
                                    navController.navigate(dest.route) {
                                        popUpTo(Dest.Home.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                                // Entering a live screen re-reads platform state so
                                // it is never stale after a prompt or a hook edit.
                                when (dest) {
                                    Dest.Scope -> {
                                        platform.refreshScope()
                                        scopeState = platform.scopeState()
                                    }
                                    Dest.Debug -> {
                                        platform.refreshDebug()
                                        debugState = platform.debugState()
                                    }
                                    Dest.Agent -> {
                                        agentState = platform.agentState()
                                    }
                                    Dest.Terminal -> {
                                        terminalState = platform.terminalState()
                                    }
                                    Dest.Patch -> {
                                        // Enumerating installed apps is the slow
                                        // part of this screen, so refresh on entry
                                        // rather than on every recomposition.
                                        platform.refreshPatchTargets()
                                        patchState = platform.patchState()
                                    }
                                    else -> Unit
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Dest.Home.route,
                modifier = Modifier.padding(padding),
            ) {
                composable(Dest.Home.route) {
                    HomeScreen(
                        report = report,
                        scopeState = scopeState,
                        debugState = debugState,
                        rootState = rootState,
                        onGoToScope = {
                            navController.navigate(Dest.Scope.route) { launchSingleTop = true }
                        },
                        onGoToSelfCheck = {
                            navController.navigate(Dest.SelfCheck.route) { launchSingleTop = true }
                        },
                        onGoToDebug = {
                            platform.refreshDebug()
                            debugState = platform.debugState()
                            navController.navigate(Dest.Debug.route) { launchSingleTop = true }
                        },
                        onRequestRoot = {
                            platform.requestRootAccess()
                            rootState = platform.rootState()
                        },
                    )
                }

                composable(Dest.Scope.route) {
                    ScopeScreen(
                        state = scopeState,
                        onRefresh = {
                            platform.refreshScope()
                            scopeState = platform.scopeState()
                        },
                        onGrant = { pkgs ->
                            platform.grantScope(pkgs) { _, _ ->
                                scopeState = platform.scopeState()
                            }
                            scopeState = platform.scopeState()
                        },
                        onRevoke = { pkgs ->
                            platform.revokeScope(pkgs) { _, _ ->
                                scopeState = platform.scopeState()
                            }
                            scopeState = platform.scopeState()
                        },
                        onToggleScopeTarget = { pkg ->
                            platform.toggleScopeTarget(pkg)
                            scopeState = platform.scopeState()
                        },
                        onToggleOverlayTarget = { pkg ->
                            platform.toggleOverlayTarget(pkg)
                            scopeState = platform.scopeState()
                            platform.publishConfig()
                        },
                    )
                }

                composable(Dest.Terminal.route) {
                    TerminalScreen(
                        state = terminalState,
                        onCommand = { command ->
                            platform.runTerminalCommand(command)
                            terminalState = platform.terminalState()
                        },
                        onModeChange = { mode ->
                            platform.openTerminal(mode)
                            terminalState = platform.terminalState()
                        },
                        onClear = {
                            platform.clearTerminal()
                            terminalState = platform.terminalState()
                        },
                        onReconnect = {
                            platform.openTerminal(terminalState.mode)
                            terminalState = platform.terminalState()
                        },
                    )
                }

                composable(Dest.Debug.route) {
                    DebugScreen(
                        state = debugState,
                        onRefresh = {
                            platform.refreshDebug()
                            debugState = platform.debugState()
                        },
                        onSearchClasses = { pattern ->
                            platform.searchClasses(pattern)
                            debugState = platform.debugState()
                        },
                        onSearchMethods = { cls, method ->
                            platform.searchMethods(cls, method)
                            debugState = platform.debugState()
                        },
                        onHookTrace = { signature ->
                            platform.hookTrace(signature)
                            debugState = platform.debugState()
                        },
                        onHookReturn = { signature, value, type ->
                            platform.hookReturn(signature, value, type)
                            debugState = platform.debugState()
                        },
                        onUnhook = { id ->
                            platform.unhook(id)
                            debugState = platform.debugState()
                        },
                    )
                }

                composable(Dest.Agent.route) {
                    AgentScreen(
                        state = agentState,
                        onSend = { text ->
                            platform.sendAgentMessage(text)
                            agentState = platform.agentState()
                        },
                        onReset = {
                            platform.resetAgent()
                            agentState = platform.agentState()
                        },
                        onSaveConfig = { baseUrl, apiKey, model, rounds ->
                            platform.saveAgentConfig(baseUrl, apiKey, model, rounds)
                            agentState = platform.agentState()
                        },
                    )
                }

                composable(Dest.Patch.route) {
                    PatchScreen(
                        state = patchState,
                        onRefresh = {
                            platform.refreshPatchTargets()
                            patchState = platform.patchState()
                        },
                        onGenerate = { packageName ->
                            platform.generatePatchedApk(packageName)
                            patchState = platform.patchState()
                        },
                        onDeleteRecord = { path ->
                            platform.deletePatchedApk(path)
                            patchState = platform.patchState()
                        },
                    )
                }

                composable(Dest.Appearance.route) {
                    AppearanceScreen(
                        config = store.theme,
                        overlayMechanism = platform.overlayMechanism(),
                        systemHookAcknowledged = platform.systemHookAcknowledged(),
                        onMechanismChange = { mechanism, acknowledged ->
                            platform.setOverlayMechanism(mechanism, acknowledged)
                        },
                        onChange = { transform ->
                            store.updateTheme(transform)
                            platform.publishConfig()
                        },
                        onReset = {
                            store.resetTheme()
                            platform.publishConfig()
                        },
                    )
                }

                composable(Dest.SelfCheck.route) {
                    SelfCheckScreen(
                        report = report,
                        running = running,
                        onRerun = { refreshSelfCheck() },
                    )
                }
            }
        }
    }
}
