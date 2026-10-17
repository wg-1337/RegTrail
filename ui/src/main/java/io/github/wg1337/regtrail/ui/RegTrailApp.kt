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
import io.github.wg1337.regtrail.ui.screen.DebugScreen
import io.github.wg1337.regtrail.ui.screen.HomeScreen
import io.github.wg1337.regtrail.ui.screen.PatchScreen
import io.github.wg1337.regtrail.ui.screen.ScopeScreen
import io.github.wg1337.regtrail.ui.screen.SelfCheckScreen
import io.github.wg1337.regtrail.ui.screen.TerminalScreen
import io.github.wg1337.regtrail.ui.screen.settings.AgentSettingsScreen
import io.github.wg1337.regtrail.ui.screen.settings.AppearanceSettingsScreen
import io.github.wg1337.regtrail.ui.screen.settings.DebugSettingsScreen
import io.github.wg1337.regtrail.ui.screen.settings.OverlaySettingsScreen
import io.github.wg1337.regtrail.ui.screen.settings.SettingsScreen
import io.github.wg1337.regtrail.ui.screen.settings.TerminalSettingsScreen
import io.github.wg1337.regtrail.ui.state.AppSettings
import io.github.wg1337.regtrail.ui.state.ConfigStore
import io.github.wg1337.regtrail.ui.terminal.TerminalMode
import io.github.wg1337.regtrail.ui.terminal.TerminalUiState
import io.github.wg1337.regtrail.ui.theme.RegTrailTheme
import kotlinx.coroutines.delay
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
 *
 * The surface deliberately mirrors the four-tab information architecture: no
 * method here exists for "a page" — each one is a capability the settings pages
 * and the live surfaces both drive.
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

    /**
     * Opens a shell session.
     *
     * Both parameters are explicit because both are settings now: the identity
     * to run as (设置 → 终端 → 默认模式) and the directory to start in
     * (设置 → 终端 → 工作区路径). The UI passes the values it shows the user, so a
     * "reconnect" can never silently fall back to a mode the user did not pick.
     */
    fun openTerminal(mode: TerminalMode, workspacePath: String)
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
     * Snapshot of the packaging screen: patchable apps, produced APKs and
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

    // ---- Reset -------------------------------------------------------------

    /**
     * Clears the settings the platform owns: the overlay mechanism with its risk
     * acknowledgement, and the Agent credentials.
     *
     * The theme and [AppSettings] are *not* part of this contract — they live in
     * `ConfigStore`, which persists its own halves. Splitting it this way is what
     * keeps a single "reset" button honest: the UI knows exactly which values it
     * owns, and this method owns the rest. Framework scope and produced APKs are
     * deliberately out of scope (see `SettingsScreen`'s confirmation text).
     */
    fun resetPlatformSettings()
}

/**
 * Root composable: theme + navigation + the working screens.
 *
 * ## Structure
 * Four bottom-bar tabs — 总览 / 目标 / 设置 / Agent — and every other destination
 * as a sub-page pushed on top of 总览. The bar therefore answers "what am I
 * doing", and 设置 answers "where is that option"; the two questions the previous
 * six-tab layout mixed together.
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
    var overlayMechanism by remember { mutableStateOf(platform.overlayMechanism()) }
    var systemHookAcknowledged by remember { mutableStateOf(platform.systemHookAcknowledged()) }
    var revision by remember { mutableStateOf(platform.stateRevision()) }
    val scope = rememberCoroutineScope()

    fun syncAll() {
        scopeState = platform.scopeState()
        terminalState = platform.terminalState()
        debugState = platform.debugState()
        agentState = platform.agentState()
        rootState = platform.rootState()
        patchState = platform.patchState()
        // Mechanism and acknowledgement are plain preferences rather than part of
        // the snapshots, so they are re-read here too; otherwise a reset would
        // leave the radio group showing the old selection.
        overlayMechanism = platform.overlayMechanism()
        systemHookAcknowledged = platform.systemHookAcknowledged()
    }

    // Re-read every snapshot whenever the platform reports a change. Without
    // this the screens showed whatever they read at first composition, so a
    // granted root permission or an accepted scope prompt never appeared until
    // the user navigated away and back.
    LaunchedEffect(Unit) {
        while (true) {
            val current = platform.stateRevision()
            if (current != revision) {
                revision = current
                syncAll()
            }
            delay(250)
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

    LaunchedEffect(Unit) {
        platform.refreshScope()
        syncAll()
        if (report == null) refreshSelfCheck()
        // The shell itself is opened by the platform at startup with the
        // configured mode and workspace. Opening it here as well — as this used
        // to do, hard-coded to Root — would race that and quietly override the
        // setting.
        terminalState = platform.terminalState()
    }

    val theme = store.theme
    val settings = store.settings

    /**
     * Applies a settings edit and re-publishes only when the module cares.
     *
     * A terminal font size or a log limit changes nothing the module can see, and
     * a re-publish is a root shell round trip plus a file write; doing it for
     * every slider tick would be waste. The two fields that are part of the
     * published snapshot — `overlayAutoAttach` and `debugAutoDeoptimize` — do
     * trigger one.
     */
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val before = store.settings
        store.updateSettings(transform)
        val after = store.settings
        if (before.overlayAutoAttach != after.overlayAutoAttach ||
            before.debugAutoDeoptimize != after.debugAutoDeoptimize
        ) {
            platform.publishConfig()
        }
    }

    RegTrailTheme(config = theme) {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentDest = Dest.entries
            .firstOrNull { it.route == backStackEntry?.destination?.route }
            ?: Dest.Home
        // Sub-pages highlight the tab they belong to, so the bar keeps acting as
        // the app's map even three levels down.
        val selectedTab = currentDest.ownedBy ?: currentDest

        /**
         * The one navigation primitive.
         *
         * A **tab** resets the stack to "总览 + that tab", so tapping a tab from a
         * sub-page always lands on the tab itself instead of restoring whatever
         * sub-page was left open (the trap `saveState/restoreState` sets when a
         * graph has no per-tab nested stacks).
         *
         * A **sub-page** is pushed on top of whatever opened it, so 设置 → 外观
         * keeps 设置 underneath and Back returns to the hub rather than jumping to
         * 总览, and 终端设置 → 打开终端 stacks naturally.
         */
        fun goTo(dest: Dest) {
            // Entering a screen re-reads the platform state it renders, so it is
            // never stale after a prompt, a hook edit or a patch run.
            when (dest) {
                Dest.Scope, Dest.Overlay -> {
                    platform.refreshScope()
                    scopeState = platform.scopeState()
                }
                Dest.Debug, Dest.DebugSettings -> {
                    platform.refreshDebug()
                    debugState = platform.debugState()
                }
                Dest.Agent, Dest.AgentSettings -> {
                    agentState = platform.agentState()
                }
                Dest.Terminal, Dest.TerminalSettings -> {
                    terminalState = platform.terminalState()
                }
                Dest.Patch -> {
                    // Enumerating installed apps is the slow part of this screen,
                    // so refresh on entry rather than on every recomposition.
                    platform.refreshPatchTargets()
                    patchState = platform.patchState()
                }
                else -> Unit
            }

            navController.navigate(dest.route) {
                if (dest.inBottomBar) {
                    popUpTo(Dest.Home.route) { inclusive = false }
                }
                launchSingleTop = true
            }
        }

        fun back() {
            navController.popBackStack()
        }

        fun openTerminalScreen() {
            terminalState = platform.terminalState()
            goTo(Dest.Terminal)
        }

        Scaffold(
            bottomBar = {
                NavigationBar {
                    Dest.entries.filter { it.inBottomBar }.forEach { dest ->
                        NavigationBarItem(
                            selected = selectedTab == dest,
                            onClick = {
                                if (dest == Dest.Home && currentDest == Dest.Home) {
                                    // Already home: re-read instead of pushing a
                                    // second copy of the start destination.
                                    syncAll()
                                } else {
                                    goTo(dest)
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
                        onGoToScope = { goTo(Dest.Scope) },
                        onGoToTerminal = { openTerminalScreen() },
                        onGoToDebug = { goTo(Dest.Debug) },
                        onGoToPatch = { goTo(Dest.Patch) },
                        onGoToSelfCheck = { goTo(Dest.SelfCheck) },
                        onGoToSettings = { goTo(Dest.Settings) },
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

                // ---- Settings hub -------------------------------------------
                composable(Dest.Settings.route) {
                    SettingsScreen(
                        theme = theme,
                        settings = settings,
                        scopeState = scopeState,
                        debugState = debugState,
                        patchState = patchState,
                        agentState = agentState,
                        overlayMechanism = overlayMechanism,
                        systemHookAcknowledged = systemHookAcknowledged,
                        onThemeChange = { transform ->
                            store.updateTheme(transform)
                            platform.publishConfig()
                        },
                        onSettingsChange = { transform -> updateSettings(transform) },
                        onResetAll = {
                            // Order matters: persist the two UI-owned halves
                            // first, then let the platform reset its own plus
                            // re-publish from the freshly stored values.
                            store.resetAll()
                            platform.resetPlatformSettings()
                            syncAll()
                        },
                        onNavigate = { dest -> goTo(dest) },
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
                        onOpenSettings = { goTo(Dest.AgentSettings) },
                    )
                }

                // ---- Settings sub-pages -------------------------------------
                composable(Dest.Appearance.route) {
                    AppearanceSettingsScreen(
                        config = theme,
                        onChange = { transform ->
                            store.updateTheme(transform)
                            platform.publishConfig()
                        },
                        onReset = {
                            store.resetTheme()
                            platform.publishConfig()
                        },
                        onBack = { back() },
                    )
                }

                composable(Dest.Overlay.route) {
                    OverlaySettingsScreen(
                        mechanism = overlayMechanism,
                        acknowledged = systemHookAcknowledged,
                        settings = settings,
                        overlayTargets = scopeState.overlayTargets,
                        apps = scopeState.apps,
                        onMechanismChange = { mechanism, acknowledged ->
                            // Apply optimistically: the platform writes the
                            // preference synchronously and publishes asynchronously,
                            // and the radio group must not lag behind its own tap.
                            platform.setOverlayMechanism(mechanism, acknowledged)
                            overlayMechanism = mechanism
                            systemHookAcknowledged = acknowledged
                            // `requiresSystemScope` is derived from the mechanism
                            // inside the scope snapshot, not from the preference.
                            // Without this re-read the hub's system-scope row kept
                            // describing the previously selected mechanism.
                            platform.refreshScope()
                            scopeState = platform.scopeState()
                        },
                        onSettingsChange = { transform -> updateSettings(transform) },
                        onOpenScope = { goTo(Dest.Scope) },
                        onBack = { back() },
                    )
                }

                composable(Dest.TerminalSettings.route) {
                    TerminalSettingsScreen(
                        settings = settings,
                        onChange = { transform -> updateSettings(transform) },
                        onOpenTerminal = { openTerminalScreen() },
                        onBack = { back() },
                    )
                }

                composable(Dest.DebugSettings.route) {
                    DebugSettingsScreen(
                        settings = settings,
                        debugState = debugState,
                        onChange = { transform -> updateSettings(transform) },
                        onOpenDebugger = { goTo(Dest.Debug) },
                        onBack = { back() },
                    )
                }

                composable(Dest.AgentSettings.route) {
                    AgentSettingsScreen(
                        config = agentState.config,
                        onSave = { baseUrl, apiKey, model, rounds ->
                            platform.saveAgentConfig(baseUrl, apiKey, model, rounds)
                            agentState = platform.agentState()
                        },
                        onBack = { back() },
                    )
                }

                composable(Dest.SelfCheck.route) {
                    SelfCheckScreen(
                        report = report,
                        running = running,
                        onRerun = { refreshSelfCheck() },
                        onBack = { back() },
                    )
                }

                // ---- Live surfaces ------------------------------------------
                composable(Dest.Terminal.route) {
                    TerminalScreen(
                        state = terminalState,
                        fontSizeSp = settings.terminalFontSizeSp,
                        onCommand = { command ->
                            platform.runTerminalCommand(command)
                            terminalState = platform.terminalState()
                        },
                        onModeChange = { mode ->
                            // The mode chips change the *live* session; the default
                            // mode in settings only decides what the next session
                            // starts as.
                            platform.openTerminal(mode, settings.terminalWorkspacePath)
                            terminalState = platform.terminalState()
                        },
                        onClear = {
                            platform.clearTerminal()
                            terminalState = platform.terminalState()
                        },
                        onReconnect = {
                            platform.openTerminal(
                                terminalState.mode,
                                settings.terminalWorkspacePath,
                            )
                            terminalState = platform.terminalState()
                        },
                        onBack = { back() },
                    )
                }

                composable(Dest.Debug.route) {
                    DebugScreen(
                        state = debugState,
                        defaultHookMode = settings.hookDefaultMode,
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
                        onBack = { back() },
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
                        onBack = { back() },
                    )
                }
            }
        }
    }
}
