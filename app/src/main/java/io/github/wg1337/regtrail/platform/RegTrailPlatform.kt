package io.github.wg1337.regtrail.platform

import android.content.Context
import android.util.Log
import io.github.wg1337.regtrail.BuildConfig
import io.github.wg1337.regtrail.agent.AgentConfig
import io.github.wg1337.regtrail.agent.AgentMessage
import io.github.wg1337.regtrail.agent.AgentSession
import io.github.wg1337.regtrail.agent.FileTools
import io.github.wg1337.regtrail.bridge.ModuleBridgeClient
import io.github.wg1337.regtrail.config.ConfigPublisher
import io.github.wg1337.regtrail.data.ScriptStore
import io.github.wg1337.regtrail.data.ThemeConfigStore
import io.github.wg1337.regtrail.detect.EnvironmentDetector
import io.github.wg1337.regtrail.patch.PatchRecord
import io.github.wg1337.regtrail.patch.PatchRepository
import io.github.wg1337.regtrail.patch.PatchStage
import io.github.wg1337.regtrail.patch.PatchTarget
import io.github.wg1337.regtrail.root.RootShell
import io.github.wg1337.regtrail.scope.AppCatalog
import io.github.wg1337.regtrail.scope.ScopeManager
import io.github.wg1337.regtrail.shared.bridge.SharedPaths
import io.github.wg1337.regtrail.shared.model.ModuleConfigSnapshot
import io.github.wg1337.regtrail.shared.model.OverlayMechanism
import io.github.wg1337.regtrail.shared.model.ScriptAction
import io.github.wg1337.regtrail.shared.model.ScriptSpec
import io.github.wg1337.regtrail.shared.model.ScriptTrigger
import io.github.wg1337.regtrail.shared.model.ScopeUiState
import io.github.wg1337.regtrail.shared.model.SelfCheckReport
import io.github.wg1337.regtrail.shared.model.ThinkingLevel
import io.github.wg1337.regtrail.shared.model.ThinkingWire
import io.github.wg1337.regtrail.terminal.TerminalSession
import io.github.wg1337.regtrail.ui.PlatformBridge
import io.github.wg1337.regtrail.ui.RootStateUi
import io.github.wg1337.regtrail.ui.agent.AgentConfigUi
import io.github.wg1337.regtrail.ui.agent.AgentMessageUi
import io.github.wg1337.regtrail.ui.agent.AgentToolStepUi
import io.github.wg1337.regtrail.ui.agent.AgentUiState
import io.github.wg1337.regtrail.ui.debug.ClassItemUi
import io.github.wg1337.regtrail.ui.debug.DebugMessageUi
import io.github.wg1337.regtrail.ui.debug.DebugUiState
import io.github.wg1337.regtrail.ui.debug.HookItemUi
import io.github.wg1337.regtrail.ui.debug.MethodItemUi
import io.github.wg1337.regtrail.ui.patch.PatchRecordUi
import io.github.wg1337.regtrail.ui.patch.PatchTargetUi
import io.github.wg1337.regtrail.ui.patch.PatchUiState
import io.github.wg1337.regtrail.ui.script.ScriptActionUi
import io.github.wg1337.regtrail.ui.script.ScriptDraftUi
import io.github.wg1337.regtrail.ui.script.ScriptEntryUi
import io.github.wg1337.regtrail.ui.script.ScriptUiState
import io.github.wg1337.regtrail.ui.script.ScriptVersionUi
import io.github.wg1337.regtrail.ui.terminal.TerminalEntry
import io.github.wg1337.regtrail.ui.terminal.TerminalMode
import io.github.wg1337.regtrail.ui.terminal.TerminalUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * The `:app` implementation of the UI's platform contract.
 *
 * Owns every piece of real machinery behind the interface: environment
 * detection, LSPosed scope negotiation, config publishing, the terminal, and the
 * bridge that drives the in-process debugger.
 *
 * State is exposed as [StateFlow] so a screen can observe it without polling,
 * while the synchronous `*State()` accessors satisfy the simpler pull-based
 * contract the UI also uses.
 */
class RegTrailPlatform(
    private val context: Context,
    private val themeStore: ThemeConfigStore,
) : PlatformBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val scopeManager = ScopeManager(context)
    private val catalog = AppCatalog(context)
    private val publisher = ConfigPublisher(context)
    private val bridge = ModuleBridgeClient(context)
    private val terminal = TerminalSession()

    /**
     * The script library: `filesDir/scripts/<id>/vNNNN.json` + a `current`
     * pointer, with exports next door in `filesDir/script-exports`.
     *
     * Constructed eagerly (it only builds [java.io.File] handles; nothing is read
     * until the first call) so every entry point — publisher, Agent tools, UI —
     * shares one instance and therefore one lock.
     */
    private val scriptStore = ScriptStore(
        root = java.io.File(context.filesDir, ScriptStore.LIBRARY_DIR_NAME),
        exportDir = java.io.File(context.filesDir, ScriptStore.EXPORT_DIR_NAME),
    )

    /**
     * Root-shell file access, used by [fileTools] only when the app's own uid
     * cannot do the job.
     *
     * The workspace `/data/local/tmp/regtrail` is created by the root shell as
     * `root:root 0755`: readable by anyone, writable only by root. Without this
     * fallback every `write_file` into the workspace would fail with EACCES,
     * which would make the tool useless for the one directory the Agent is most
     * likely to want.
     *
     * The text travels base64-encoded so no quoting, newline or unicode hazard
     * can reach the shell, and the write is `tmp` + `mv -f` so a failure cannot
     * truncate an existing file. The size cap exists because Linux limits a
     * single `execve` argument to ~128 KB (`MAX_ARG_STRLEN`), and base64 is 4/3
     * of the payload.
     */
    private val rootFileChannel: FileTools.RootChannel = object : FileTools.RootChannel {

        override suspend fun read(path: String): String? =
            RootShell.run("cat '$path'", timeoutSeconds = 20).getOrNull()

        override suspend fun write(path: String, text: String): Boolean {
            if (text.length > ROOT_WRITE_CHARS) return false

            val b64 = android.util.Base64.encodeToString(
                text.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP,
            )
            val dir = path.substringBeforeLast('/', "")
            val command = buildString {
                if (dir.isNotEmpty()) {
                    append("mkdir -p '").append(dir).append("' && ")
                }
                append("printf '%s' '").append(b64).append("' | base64 -d > '")
                append(path).append(".tmp'")
                append(" && mv -f '").append(path).append(".tmp' '").append(path).append("'")
                append(" && chmod 644 '").append(path).append("'")
            }
            return RootShell.run(command, timeoutSeconds = 30).isSuccess
        }
    }

    /**
     * File access for the Agent's `read_file` / `write_file`.
     *
     * Roots are the app's own directories plus the workspace. Writes that the
     * app's uid cannot perform (the workspace is created `root:root 0755`) fall
     * back to the root shell — see [rootFileChannel].
     */
    private val fileTools: FileTools = FileTools(
        roots = buildList {
            add(context.filesDir)
            add(context.cacheDir)
            context.getExternalFilesDir(null)?.let { add(it) }
            add(java.io.File(SharedPaths.BASE_DIR))
            themeStore.loadSettings().terminalWorkspacePath
                .takeIf { it.startsWith("/") && it.length > 1 && !it.any { ch -> ch.isWhitespace() } }
                ?.let { add(java.io.File(it)) }
        }.distinct(),
        defaultRoot = java.io.File(SharedPaths.BASE_DIR),
        rootChannel = rootFileChannel,
    )

    private val _scopeState = MutableStateFlow(ScopeUiState(busy = true))
    val scopeStateFlow: StateFlow<ScopeUiState> = _scopeState.asStateFlow()

    private val _terminalState = MutableStateFlow(TerminalUiState())
    val terminalStateFlow: StateFlow<TerminalUiState> = _terminalState.asStateFlow()

    private val _debugState = MutableStateFlow(DebugUiState())
    val debugStateFlow: StateFlow<DebugUiState> = _debugState.asStateFlow()

    private val _agentState = MutableStateFlow(AgentUiState())
    val agentStateFlow: StateFlow<AgentUiState> = _agentState.asStateFlow()

    private val _scriptState = MutableStateFlow(ScriptUiState())
    val scriptStateFlow: StateFlow<ScriptUiState> = _scriptState.asStateFlow()

    /**
     * Monotonic counter bumped whenever any platform state changes.
     *
     * The UI uses it to re-read the (synchronous) state snapshots. Without this,
     * the Compose layer kept whatever it read at first composition: the "no root"
     * banner stayed visible after root was granted, and scope/hook results only
     * appeared after leaving and re-entering a screen.
     */
    private val _stateRevision = MutableStateFlow(0L)
    val stateRevisionFlow: StateFlow<Long> = _stateRevision.asStateFlow()

    override fun stateRevision(): Long = _stateRevision.value

    /** Call after any state mutation that the UI should observe. */
    private fun bumpRevision() {
        _stateRevision.value = _stateRevision.value + 1
    }

    /** Last config-publish outcome, so a failure is never silent. */
    private val _configPublishResult = MutableStateFlow<String?>(null)
    private val _configPublishOk = MutableStateFlow(false)

    /** Lazily created so a bad config cannot break app startup. */
    private var agentSession: AgentSession? = null

    /** RegTrail-level overlay filter (the per-app checkbox in the UI). */
    private val overlayTargets = linkedSetOf<String>()

    private val pendingGrant = linkedSetOf<String>()

    private var appsLoaded = false
    private var catalogCache: List<io.github.wg1337.regtrail.shared.model.TargetApp> = emptyList()

    fun start() {
        Log.i(TAG, "platform.start() begin")

        // Registering with the framework is the most likely thing to throw: it
        // touches the XposedService ContentProvider, which only exists once the
        // module has been enabled and the framework restarted. A failure here
        // must not prevent the rest of startup.
        try {
            scopeManager.register()
            Log.i(TAG, "scopeManager.register() ok")
        } catch (t: Throwable) {
            Log.e(TAG, "scopeManager.register() failed", t)
            _startupDiagnostics.value = _startupDiagnostics.value +
                "register ✗ ${t.message ?: t::class.java.simpleName}"
        }

        scope.launch {
            Log.i(TAG, "startup coroutine entered")
            // Every startup step is isolated. Previously they were a single
            // sequence, so one failure (a dead framework binder, a denied su)
            // silently aborted the rest — and a config that never got published
            // looked exactly like "the overlay doesn't work".
            step("loadCatalog") { loadCatalog() }
            step("refreshScope") { refreshScope() }
            step("loadOverlayTargets") {
                overlayTargets.addAll(themeStore.loadOverlayTargets())
            }
            step("pushAgentConfig") { pushAgentConfig() }
            step("publishConfig") { publishConfigInternal(showMessage = false) }
            // After publishing, re-sample root so the home screen's root banner
            // reflects the state that was actually achieved this session.
            step("rootState") { refreshRootStateInternal() }
            step("observeTerminal") { observeTerminal() }
            Log.i(TAG, "startup complete; publishOk=${_configPublishOk.value}")
        }

        // Terminal startup is separate: it blocks on the su prompt, and a user
        // who ignores that prompt must not stall config publishing.
        scope.launch {
            step("openTerminal") {
                // Honour the mode and workspace the user configured; both
                // default to the values this call used to hard-code.
                val settings = themeStore.loadSettings()
                terminal.start(settings.terminalDefaultMode.toAppMode(), settings.terminalWorkspacePath)
                pushTerminal()
            }
        }

        Log.i(TAG, "platform.start() end (coroutines queued)")
    }

    /**
     * Runs one startup step, logging failures instead of propagating them.
     *
     * Also records the failure in the diagnostics the UI shows, so a user can
     * report "which step failed" without needing logcat.
     */
    private suspend fun step(name: String, body: suspend () -> Unit) {
        try {
            body()
            Log.i(TAG, "startup step ok: $name")
            _startupDiagnostics.value = _startupDiagnostics.value + "$name ✓"
        } catch (t: Throwable) {
            Log.e(TAG, "startup step FAILED: $name", t)
            _startupDiagnostics.value = _startupDiagnostics.value +
                "$name ✗ ${t.message ?: t::class.java.simpleName}"
        }
    }

    /** Per-step startup outcomes, shown on the home screen. */
    private val _startupDiagnostics = MutableStateFlow<List<String>>(emptyList())
    val startupDiagnostics: StateFlow<List<String>> = _startupDiagnostics.asStateFlow()

    // -----------------------------------------------------------------------
    // Environment
    // -----------------------------------------------------------------------

    override suspend fun runSelfCheck(): SelfCheckReport = EnvironmentDetector.run()

    // -----------------------------------------------------------------------
    // Scope
    // -----------------------------------------------------------------------

    override fun scopeState(): ScopeUiState = _scopeState.value.copy(
        pendingGrant = pendingGrant.toSet(),
        overlayTargets = overlayTargets.toSet(),
    )

    /**
     * Publishes the current scope/target state to the UI and bumps the revision.
     *
     * All mutations funnel through here so the UI cannot miss a change.
     */
    private fun publishScopeState(next: ScopeUiState) {
        _scopeState.value = next
        bumpRevision()
    }

    override fun refreshScope() {
        scopeManager.refresh()
        val framework = scopeManager.state.value
        _scopeState.value = scopeState().copy(
            bound = framework.bound,
            frameworkName = framework.frameworkName,
            frameworkVersion = framework.frameworkVersion,
            apiVersion = framework.apiVersion,
            scope = framework.scope,
            apps = catalogCache,
            busy = !appsLoaded,
            // system scope is only a requirement for the risky mechanism.
            requiresSystemScope = themeStore.loadOverlayMechanism() ==
                OverlayMechanism.ApplicationOverlay &&
                themeStore.loadSystemHookAcknowledged(),
        )
        bumpRevision()
    }

    override fun grantScope(packages: List<String>, onResult: (Boolean, String) -> Unit) {
        if (packages.isEmpty()) {
            onResult(false, "没有需要申请的包名")
            return
        }
        setScopeBusy(true)
        scopeManager.grantScope(packages) { ok, message ->
            if (ok) pendingGrant.removeAll(packages.toSet())
            setScopeMessage(message, isError = !ok)
            refreshScope()
            setScopeBusy(false)
            onResult(ok, message)
        }
    }

    override fun revokeScope(packages: List<String>, onResult: (Boolean, String) -> Unit) {
        if (packages.isEmpty()) {
            onResult(false, "作用域为空")
            return
        }
        setScopeBusy(true)
        scopeManager.revokeScope(packages) { ok, message ->
            setScopeMessage(message, isError = !ok)
            refreshScope()
            setScopeBusy(false)
            onResult(ok, message)
        }
    }

    override fun toggleOverlayTarget(packageName: String) {
        if (!overlayTargets.remove(packageName)) overlayTargets.add(packageName)
        themeStore.saveOverlayTargets(overlayTargets)
        _scopeState.value = scopeState()
        bumpRevision()
    }

    override fun toggleScopeTarget(packageName: String) {
        if (!pendingGrant.remove(packageName)) pendingGrant.add(packageName)
        // Republish the state object so `pendingGrant` (which lives outside the
        // flow's own copy) is reflected in the snapshot the UI reads.
        _scopeState.value = scopeState()
        bumpRevision()
    }

    override fun publishConfig() {
        scope.launch { publishConfigInternal(showMessage = true) }
    }

    // -----------------------------------------------------------------------
    // Terminal
    // -----------------------------------------------------------------------

    override fun terminalState(): TerminalUiState = _terminalState.value

    override fun openTerminal(mode: TerminalMode, workspacePath: String) {
        scope.launch {
            terminal.start(mode.toAppMode(), workspacePath)
            pushTerminal()
        }
    }

    override fun runTerminalCommand(command: String) {
        scope.launch {
            pushTerminal()
            terminal.run(command)
            pushTerminal()
        }
    }

    override fun clearTerminal() {
        terminal.clear()
        pushTerminal()
    }

    override fun closeTerminal() {
        terminal.close()
        pushTerminal()
    }

    private fun observeTerminal() {
        scope.launch {
            terminal.entries.collect { pushTerminal() }
        }
        scope.launch {
            terminal.state.collect { pushTerminal() }
        }
    }

    private fun pushTerminal() {
        val session = terminal.state.value
        _terminalState.value = TerminalUiState(
            connected = session.connected,
            mode = session.mode.toUiMode(),
            uid = session.uid,
            user = session.user,
            isRoot = session.isRoot,
            cwd = session.cwd,
            busy = session.busy,
            entries = terminal.entries.value.map { it.toUiEntry() },
        )
        bumpRevision()
    }

    // -----------------------------------------------------------------------
    // Live debugger
    // -----------------------------------------------------------------------

    override fun debugState(): DebugUiState = _debugState.value

    override fun refreshDebug() {
        scope.launch {
            setDebugBusy(true)
            val loaded = bridge.isModuleLoaded()
            if (!loaded) {
                _debugState.value = _debugState.value.copy(
                    attached = false,
                    targetPackage = null,
                    targetProcess = null,
                    busy = false,
                )
                addDebugMessage("没有目标进程响应。请先启动一个已授权并勾选悬浮窗的应用。")
                return@launch
            }

            bridge.status().fold(
                onSuccess = { status -> applyStatus(status) },
                onFailure = { addDebugMessage(it.message ?: "读取状态失败", isError = true) },
            )
            refreshHooks()
            setDebugBusy(false)
        }
    }

    private fun applyStatus(status: JsonObject) {
        _debugState.value = _debugState.value.copy(
            attached = status.bool("moduleLoaded") ?: false,
            targetPackage = status.str("package"),
            targetProcess = status.str("processName"),
            frameworkLabel = status.str("framework"),
            hookCount = status.int("hooks") ?: 0,
            instanceCount = status.int("instances") ?: 0,
            activityCount = status.int("activities") ?: 0,
        )
    }

    override fun searchClasses(pattern: String) {
        if (pattern.isBlank()) return
        scope.launch {
            setDebugBusy(true)
            bridge.tool("list_classes", buildJsonObject {
                put("pattern", JsonPrimitive(pattern))
                put("limit", JsonPrimitive(120))
            }).fold(
                onSuccess = { data ->
                    val names = (data["classes"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        .orEmpty()
                    val total = data.int("total") ?: names.size
                    _debugState.value = _debugState.value.copy(
                        classQuery = pattern,
                        classes = names.map { ClassItemUi(it) },
                        methods = emptyList(),
                    )
                    addDebugMessage("匹配到 ${names.size} 个类（目标进程已加载 $total 个）")
                },
                onFailure = { addDebugMessage(it.message ?: "搜索失败", isError = true) },
            )
            setDebugBusy(false)
        }
    }

    override fun searchMethods(className: String, methodName: String) {
        if (className.isBlank()) return
        scope.launch {
            setDebugBusy(true)
            // The module side exposes class search; method enumeration reuses it
            // through dump_class, which returns the member list for one class.
            bridge.tool("find_class", buildJsonObject {
                put("name", JsonPrimitive(className))
            }).fold(
                onSuccess = { data ->
                    val methods = (data["methods"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        .orEmpty()
                        .filter { methodName.isBlank() || it.contains(methodName, true) }
                        .map { text ->
                            MethodItemUi(
                                signature = "$className:: $text",
                                className = className,
                                methodName = text.substringAfterLast(' ').substringBefore('('),
                                paramTypes = emptyList(),
                                returnType = text.substringBeforeLast(' ').substringAfterLast(' '),
                                isStatic = text.contains(" static "),
                            )
                        }
                    _debugState.value = _debugState.value.copy(
                        methodQuery = methodName,
                        methods = methods,
                        classes = emptyList(),
                    )
                    addDebugMessage("$className 匹配到 ${methods.size} 个成员")
                },
                onFailure = { addDebugMessage(it.message ?: "查找失败", isError = true) },
            )
            setDebugBusy(false)
        }
    }

    override fun hookTrace(signature: String) {
        scope.launch {
            setDebugBusy(true)
            bridge.tool("hook_method", buildJsonObject {
                put("signature", JsonPrimitive(signature))
                put("mode", JsonPrimitive("trace"))
            }).fold(
                onSuccess = { data ->
                    addDebugMessage("已挂 Hook: ${data.str("id")} → ${data.str("target")}")
                    refreshHooks()
                },
                onFailure = { addDebugMessage(it.message ?: "挂 Hook 失败", isError = true) },
            )
            setDebugBusy(false)
        }
    }

    override fun hookReturn(signature: String, value: String, valueType: String) {
        scope.launch {
            setDebugBusy(true)
            bridge.tool("hook_method", buildJsonObject {
                put("signature", JsonPrimitive(signature))
                put("mode", JsonPrimitive("return"))
                put("value", JsonPrimitive(value))
                put("valueType", JsonPrimitive(valueType))
            }).fold(
                onSuccess = { data ->
                    addDebugMessage(
                        "已改写返回值: ${data.str("id")} → $value",
                    )
                    refreshHooks()
                },
                onFailure = { addDebugMessage(it.message ?: "改写失败", isError = true) },
            )
            setDebugBusy(false)
        }
    }

    override fun unhook(id: String) {
        scope.launch {
            bridge.tool("unhook", buildJsonObject { put("id", JsonPrimitive(id)) })
                .fold(
                    onSuccess = {
                        addDebugMessage("已移除 Hook: $id")
                        refreshHooks()
                    },
                    onFailure = { addDebugMessage(it.message ?: "移除失败", isError = true) },
                )
        }
    }

    private suspend fun refreshHooks() {
        bridge.tool("list_hooks").fold(
            onSuccess = { data ->
                val hooks = (data["hooks"] as? JsonArray)?.mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    HookItemUi(
                        id = obj.str("id").orEmpty(),
                        target = obj.str("target").orEmpty(),
                        label = obj.str("label").orEmpty(),
                        calls = obj.str("calls")?.toLongOrNull() ?: 0L,
                        trace = obj.str("trace") == "true",
                        returnOverride = obj.str("returnOverride"),
                    )
                }.orEmpty()

                _debugState.value = _debugState.value.copy(
                    hooks = hooks,
                    hookCount = hooks.size,
                )
            },
            onFailure = { /* hooks are optional; silence */ },
        )
    }

    private fun addDebugMessage(text: String, isError: Boolean = false) {
        val current = _debugState.value.messages
        // The ring-buffer size is a user setting ("日志上限"). Read it here rather
        // than caching it so the setting takes effect for the very next line,
        // without the settings page having to push anything into the platform.
        val limit = themeStore.loadSettings().debugLogLimit.coerceAtLeast(1)
        val next = (current + DebugMessageUi(text, isError)).takeLast(limit)
        _debugState.value = _debugState.value.copy(messages = next)
        bumpRevision()
    }

    private fun setDebugBusy(value: Boolean) {
        _debugState.value = _debugState.value.copy(busy = value)
        bumpRevision()
    }

    // -----------------------------------------------------------------------
    // Agent
    // -----------------------------------------------------------------------

    override fun agentState(): AgentUiState = _agentState.value

    override fun sendAgentMessage(text: String) {
        scope.launch {
            _agentState.value = _agentState.value.copy(running = true, lastError = null)
            refreshAgentTarget()

            val session = agentSession ?: createAgentSession()
            session.send(text)
            _agentState.value = _agentState.value.copy(running = false)
        }
    }

    override fun resetAgent() {
        agentSession?.reset()
        _agentState.value = _agentState.value.copy(messages = emptyList(), lastError = null)
    }

    override fun saveAgentConfig(
        baseUrl: String,
        apiKey: String,
        model: String,
        rounds: Int,
        thinking: String,
        thinkingWire: String,
    ) {
        val existing = themeStore.loadAgentConfig()
        val updated = existing.copy(
            baseUrl = baseUrl.ifBlank { AgentConfig.DEFAULT_BASE_URL },
            model = model.ifBlank { AgentConfig.DEFAULT_MODEL },
            maxToolRounds = rounds.coerceIn(1, 30),
            thinking = ThinkingLevel.fromWire(thinking),
            thinkingWire = ThinkingWire.fromWire(thinkingWire),
        )
        // null keeps the previously stored key, so the field can be left blank
        // on subsequent edits without wiping the credential.
        themeStore.saveAgentConfig(updated, apiKey.takeIf { it.isNotBlank() })

        // Force the next turn to pick up the new configuration.
        agentSession = null
        pushAgentConfig()
        // The in-window Agent reads the level from the published config, so a
        // change here only reaches it after a republish.
        scope.launch { publishConfigInternal(showMessage = false) }
    }

    private fun createAgentSession(): AgentSession {
        val session = AgentSession(
            configProvider = { themeStore.loadAgentConfig() },
            bridge = bridge,
            scripts = scriptStore,
            files = fileTools,
            // A script the Agent just created is only in the library; the target
            // process runs whatever `config.json` says, so every library mutation
            // is followed by a publish. Failures are already reported by the
            // publish path (root missing, etc.).
            onScriptsChanged = { publishConfigInternal(showMessage = false) },
            onMessage = { message ->
                val current = _agentState.value.messages
                val next = (current + AgentMessageUi(
                    role = when (message.role) {
                        AgentMessage.Role.User -> AgentMessageUi.Role.User
                        AgentMessage.Role.Assistant -> AgentMessageUi.Role.Assistant
                        AgentMessage.Role.Tool -> AgentMessageUi.Role.Tool
                        AgentMessage.Role.System -> AgentMessageUi.Role.System
                    },
                    text = message.text,
                    toolCalls = message.toolCalls,
                    toolSteps = message.toolSteps.map { step ->
                        AgentToolStepUi(
                            name = step.name,
                            arguments = step.arguments,
                            result = step.result,
                            ok = step.ok,
                        )
                    },
                    reasoning = message.reasoning,
                )).takeLast(400)
                _agentState.value = _agentState.value.copy(messages = next)
                // Without this the screen only refreshed when the whole turn
                // finished, so a slow tool looked like a frozen conversation.
                bumpRevision()
            },
        )
        agentSession = session
        return session
    }

    private suspend fun refreshAgentTarget() {
        val loaded = bridge.isModuleLoaded()
        val status = if (loaded) bridge.status().getOrNull() else null
        _agentState.value = _agentState.value.copy(
            targetAttached = loaded,
            targetPackage = status?.str("package"),
            config = themeStore.loadAgentConfig().toUi(),
        )
    }

    private fun pushAgentConfig() {
        _agentState.value = _agentState.value.copy(
            config = themeStore.loadAgentConfig().toUi(),
        )
    }

    // -----------------------------------------------------------------------
    // Script library
    // -----------------------------------------------------------------------

    override fun scriptState(): ScriptUiState = _scriptState.value

    /**
     * Re-reads the whole library from disk.
     *
     * Always a full rescan rather than an incremental patch: the library is a
     * handful of small files, and "the list disagrees with the disk" is a class
     * of bug that only exists when a cache is trusted.
     */
    override fun refreshScripts() {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { scriptStore.list() } }
            result.fold(
                onSuccess = { entries ->
                    _scriptState.value = _scriptState.value.copy(
                        entries = entries.map { it.toUi() },
                        busy = false,
                    )
                },
                onFailure = { t ->
                    _scriptState.value = _scriptState.value.copy(
                        busy = false,
                        message = "读取脚本库失败：${t.message ?: t::class.java.simpleName}",
                        messageIsError = true,
                    )
                },
            )
            bumpRevision()
        }
    }

    override fun editScript(id: String?, startNew: Boolean) {
        scope.launch {
            if (startNew || id.isNullOrBlank()) {
                _scriptState.value = _scriptState.value.copy(
                    draft = ScriptDraftUi(),
                    versions = emptyList(),
                    message = null,
                    messageIsError = false,
                )
                bumpRevision()
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val spec = scriptStore.load(id)
                        ?: throw IllegalStateException("脚本不存在：$id")
                    spec to scriptStore.versions(id)
                }
            }
            result.fold(
                onSuccess = { (spec, versions) ->
                    _scriptState.value = _scriptState.value.copy(
                        draft = spec.toDraft(),
                        versions = versions.map { ScriptVersionUi(it.version, it.savedAt, it.actionCount) },
                        message = null,
                        messageIsError = false,
                    )
                },
                onFailure = { t ->
                    _scriptState.value = _scriptState.value.copy(
                        message = t.message ?: "读取脚本失败",
                        messageIsError = true,
                    )
                },
            )
            bumpRevision()
        }
    }

    override fun closeScriptEditor() {
        _scriptState.value = _scriptState.value.copy(
            draft = null,
            versions = emptyList(),
            message = null,
            messageIsError = false,
        )
        bumpRevision()
    }

    /**
     * Creates or saves the current draft.
     *
     * The draft is a *form*: every field is a string, and a blank one means "not
     * set" rather than "set to empty". Conversion to [ScriptAction] happens here
     * so the UI never has to know the enum names beyond the labels it shows.
     */
    override fun saveScript(draft: ScriptDraftUi) {
        scope.launch {
            val spec = runCatching { draft.toSpec() }
                .getOrElse { t ->
                    _scriptState.value = _scriptState.value.copy(
                        message = t.message ?: "脚本无法保存",
                        messageIsError = true,
                    )
                    bumpRevision()
                    return@launch
                }

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (draft.isNew) scriptStore.create(spec) else scriptStore.update(draft.id, spec)
                }
            }

            result.fold(
                onSuccess = { saved ->
                    val entries = withContext(Dispatchers.IO) {
                        runCatching { scriptStore.list() }.getOrDefault(emptyList())
                    }
                    _scriptState.value = _scriptState.value.copy(
                        entries = entries.map { it.toUi() },
                        draft = saved.toDraft(),
                        versions = withContext(Dispatchers.IO) {
                            runCatching { scriptStore.versions(saved.id) }.getOrDefault(emptyList())
                        }.map { ScriptVersionUi(it.version, it.savedAt, it.actionCount) },
                        message = "${if (draft.isNew) "已创建" else "已保存"} v${saved.version}",
                        messageIsError = false,
                    )
                    // The injected side runs the published list, so a save that
                    // did not republish would silently not take effect.
                    publishConfigInternal(showMessage = false)
                },
                onFailure = { t ->
                    _scriptState.value = _scriptState.value.copy(
                        message = "保存失败：${t.message ?: t::class.java.simpleName}",
                        messageIsError = true,
                    )
                },
            )
            bumpRevision()
        }
    }

    override fun duplicateScript(id: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { scriptStore.duplicate(id) }
            }
            result.fold(
                onSuccess = { copy ->
                    val entries = withContext(Dispatchers.IO) {
                        runCatching { scriptStore.list() }.getOrDefault(emptyList())
                    }
                    _scriptState.value = _scriptState.value.copy(
                        entries = entries.map { it.toUi() },
                        message = "已复制为「${copy.name}」（v${copy.version}）",
                        messageIsError = false,
                    )
                    publishConfigInternal(showMessage = false)
                },
                onFailure = { t ->
                    _scriptState.value = _scriptState.value.copy(
                        message = "复制失败：${t.message ?: t::class.java.simpleName}",
                        messageIsError = true,
                    )
                },
            )
            bumpRevision()
        }
    }

    override fun deleteScript(id: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { scriptStore.delete(id) }
            }
            result.fold(
                onSuccess = { removed ->
                    val entries = withContext(Dispatchers.IO) {
                        runCatching { scriptStore.list() }.getOrDefault(emptyList())
                    }
                    val current = _scriptState.value
                    _scriptState.value = current.copy(
                        entries = entries.map { it.toUi() },
                        // Deleting the script being edited would leave the editor
                        // showing a ghost, so the draft goes too.
                        draft = current.draft?.takeIf { it.id != id },
                        versions = if (current.draft?.id == id) emptyList() else current.versions,
                        message = if (removed) "已删除脚本" else "脚本已经不存在",
                        messageIsError = !removed,
                    )
                    publishConfigInternal(showMessage = false)
                },
                onFailure = { t ->
                    _scriptState.value = _scriptState.value.copy(
                        message = "删除失败：${t.message ?: t::class.java.simpleName}",
                        messageIsError = true,
                    )
                },
            )
            bumpRevision()
        }
    }

    override fun restoreScriptVersion(id: String, version: Int) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { scriptStore.restore(id, version) }
            }
            result.fold(
                onSuccess = { restored ->
                    val entries = withContext(Dispatchers.IO) {
                        runCatching { scriptStore.list() }.getOrDefault(emptyList())
                    }
                    _scriptState.value = _scriptState.value.copy(
                        entries = entries.map { it.toUi() },
                        draft = restored.toDraft(),
                        versions = withContext(Dispatchers.IO) {
                            runCatching { scriptStore.versions(id) }.getOrDefault(emptyList())
                        }.map { ScriptVersionUi(it.version, it.savedAt, it.actionCount) },
                        message = "已把 v$version 恢复成 v${restored.version}（历史都还在）",
                        messageIsError = false,
                    )
                    publishConfigInternal(showMessage = false)
                },
                onFailure = { t ->
                    _scriptState.value = _scriptState.value.copy(
                        message = "恢复失败：${t.message ?: t::class.java.simpleName}",
                        messageIsError = true,
                    )
                },
            )
            bumpRevision()
        }
    }

    override fun exportScripts() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { scriptStore.exportBundle() }
            }
            result.fold(
                onSuccess = { file ->
                    _scriptState.value = _scriptState.value.copy(
                        exportPath = file.absolutePath,
                        message = "已导出 ${file.length()} 字节到 ${file.absolutePath}",
                        messageIsError = false,
                    )
                },
                onFailure = { t ->
                    _scriptState.value = _scriptState.value.copy(
                        message = "导出失败：${t.message ?: t::class.java.simpleName}",
                        messageIsError = true,
                    )
                },
            )
            bumpRevision()
        }
    }

    override fun importScripts(json: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { scriptStore.importBundle(json) }
            }
            result.fold(
                onSuccess = { imported ->
                    val entries = withContext(Dispatchers.IO) {
                        runCatching { scriptStore.list() }.getOrDefault(emptyList())
                    }
                    _scriptState.value = _scriptState.value.copy(
                        entries = entries.map { it.toUi() },
                        message = buildString {
                            append("已导入 ").append(imported.imported).append(" 个脚本")
                            if (imported.renamed > 0) {
                                append("（其中 ").append(imported.renamed)
                                append(" 个与原 id 冲突，已改用新 id，原脚本未被覆盖）")
                            }
                            if (imported.skipped > 0) {
                                append("；跳过 ").append(imported.skipped).append(" 个空脚本")
                            }
                        },
                        messageIsError = false,
                    )
                    publishConfigInternal(showMessage = false)
                },
                onFailure = { t ->
                    _scriptState.value = _scriptState.value.copy(
                        message = "导入失败：${t.message ?: t::class.java.simpleName}",
                        messageIsError = true,
                    )
                },
            )
            bumpRevision()
        }
    }

    /** Clears just the transient message line above the list. */
    override fun clearScriptMessage() {
        _scriptState.value = _scriptState.value.copy(message = null, messageIsError = false)
        bumpRevision()
    }

    // -----------------------------------------------------------------------
    // Persistent injection (P6)
    // -----------------------------------------------------------------------

    private val patchRepository = PatchRepository(context)

    private val _patchState = MutableStateFlow(PatchUiState())
    val patchStateFlow: StateFlow<PatchUiState> = _patchState.asStateFlow()

    override fun patchState(): PatchUiState = _patchState.value

    /**
     * Re-enumerates installed apps and re-reads the produced-APK records.
     *
     * Runs on `Dispatchers.IO`: enumerating ~150 packages through `PackageManager`
     * plus stat-ing each APK is fast but not free, and doing it on the main thread
     * on screen entry was visible as a stutter on the scope screen already.
     */
    override fun refreshPatchTargets() {
        if (_patchState.value.loading) return
        _patchState.value = _patchState.value.copy(loading = true)
        bumpRevision()

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Triple(
                        patchRepository.listTargets(),
                        patchRepository.loadRecords(),
                        patchRepository.signerFingerprint(),
                    )
                }
            }

            result.fold(
                onSuccess = { (targets, records, fingerprint) ->
                    _patchState.value = _patchState.value.copy(
                        loading = false,
                        targets = targets.map { it.toUi() },
                        records = records.map { it.toUi() },
                        signerFingerprint = fingerprint,
                    )
                },
                onFailure = { t ->
                    _patchState.value = _patchState.value.copy(
                        loading = false,
                        message = "读取应用列表失败：${t.message ?: t::class.java.simpleName}",
                        messageIsError = true,
                    )
                },
            )
            bumpRevision()
        }
    }

    /**
     * Runs the patcher in the background and publishes progress as it goes.
     *
     * The stage callback fires on the IO thread; publishing it straight into the
     * `StateFlow` (plus a revision bump) is what lets the screen show "正在改写
     * AndroidManifest.xml" while the work is still happening, instead of a spinner
     * that could equally mean "hung".
     */
    override fun generatePatchedApk(packageName: String) {
        if (_patchState.value.busy) return

        val label = _patchState.value.targets.firstOrNull { it.packageName == packageName }?.label
        _patchState.value = _patchState.value.copy(
            busy = true,
            activePackage = packageName,
            activeLabel = label,
            stage = PatchStage.Resolve.label,
            message = null,
            messageIsError = false,
        )
        bumpRevision()

        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    patchRepository.patch(packageName) { stage, detail ->
                        _patchState.value = _patchState.value.copy(
                            stage = if (detail.isNullOrBlank()) stage.label else "${stage.label}：$detail",
                        )
                        bumpRevision()
                    }
                }
            }

            outcome.fold(
                onSuccess = { record ->
                    val records = withContext(Dispatchers.IO) {
                        runCatching { patchRepository.loadRecords() }.getOrDefault(emptyList())
                    }
                    _patchState.value = _patchState.value.copy(
                        busy = false,
                        activePackage = null,
                        activeLabel = null,
                        stage = null,
                        records = records.map { it.toUi() },
                        signerFingerprint = record.signerSha256,
                        message = "已生成：${record.path}",
                        messageIsError = false,
                    )
                },
                onFailure = { t ->
                    Log.e(TAG, "patch failed for $packageName", t)
                    _patchState.value = _patchState.value.copy(
                        busy = false,
                        activePackage = null,
                        activeLabel = null,
                        stage = null,
                        message = "打包失败：${t.message ?: t::class.java.simpleName}",
                        messageIsError = true,
                    )
                },
            )
            bumpRevision()
        }
    }

    override fun deletePatchedApk(path: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    patchRepository.deleteRecord(path) to patchRepository.loadRecords()
                }
            }
            result.fold(
                onSuccess = { (deleted, records) ->
                    _patchState.value = _patchState.value.copy(
                        records = records.map { it.toUi() },
                        message = if (deleted) "已删除补丁文件" else "文件已不存在，已从记录中移除",
                        messageIsError = false,
                    )
                },
                onFailure = { t ->
                    _patchState.value = _patchState.value.copy(
                        message = "删除失败：${t.message ?: t::class.java.simpleName}",
                        messageIsError = true,
                    )
                },
            )
            bumpRevision()
        }
    }

    // -----------------------------------------------------------------------
    // Root
    // -----------------------------------------------------------------------

    private val _rootState = MutableStateFlow(RootStateUi())
    val rootStateFlow: StateFlow<RootStateUi> = _rootState.asStateFlow()

    override fun rootState(): RootStateUi = _rootState.value

    /**
     * Requests root explicitly so the manager shows its prompt.
     *
     * Called from the UI rather than only implicitly at startup. An implicit
     * request that the user never notices looks identical to "the app is broken",
     * and it also risks caching a non-root shell before the grant lands — which
     * is exactly what happened here: the terminal asked for root during startup,
     * APatch's prompt went unanswered, and libsu cached the unprivileged shell so
     * every later attempt returned it.
     */
    override fun requestRootAccess() {
        scope.launch {
            Log.i(TAG, "explicit root request")

            // Drop any cached unprivileged shell first, so the rebuild really
            // re-issues the su request instead of handing back the stale one.
            runCatching { com.topjohnwu.superuser.Shell.getShell().close() }

            val shell = withContext(Dispatchers.IO) { publisher.obtainRootShellForUi() }
            val diagnosis = withContext(Dispatchers.IO) { publisher.rootDiagnosis() }

            Log.i(TAG, "explicit root request result: granted=${shell != null}")
            _rootState.value = RootStateUi(granted = shell != null, diagnosis = diagnosis)

            if (shell != null) publishConfigInternal(showMessage = false)
        }
    }

    /** Re-reads the root diagnosis without requesting anything. */
    fun refreshRootState() {
        scope.launch { refreshRootStateInternal() }
    }

    private suspend fun refreshRootStateInternal() {
        val diagnosis = withContext(Dispatchers.IO) { publisher.rootDiagnosis() }
        // `granted` must reflect a real root shell, not whether the config
        // happened to publish. Deriving it from the publish flag made the
        // "no root" banner stick even after root was granted, because at startup
        // the state was sampled before the publish ran.
        val hasRoot = withContext(Dispatchers.IO) { publisher.obtainRootShellForUi() != null }
        _rootState.value = RootStateUi(granted = hasRoot, diagnosis = diagnosis)
        bumpRevision()
    }

    // -----------------------------------------------------------------------
    // Wiring helpers
    // -----------------------------------------------------------------------

    private suspend fun loadCatalog() {
        if (appsLoaded) return
        catalogCache = withContext(Dispatchers.IO) { catalog.load() }
        appsLoaded = true
        _scopeState.value = scopeState().copy(apps = catalogCache, busy = false)
    }

    private suspend fun publishConfigInternal(showMessage: Boolean) {
        val theme = themeStore.load()
        val settings = themeStore.loadSettings()
        val mechanism = themeStore.loadOverlayMechanism()
        val acknowledged = themeStore.loadSystemHookAcknowledged()
        // Published so the floating window's Agent tab can call the model from
        // inside the target process. Read through the one store that owns the key
        // so the settings screens and the module can never disagree about it.
        val agent = themeStore.loadAgentConfig()

        // The library is on disk, so this is real I/O. `publishConfigInternal` is
        // called from the main-thread scope (and from Compose callbacks), so the
        // read is moved off it rather than trusted to be fast.
        val scripts = withContext(Dispatchers.IO) {
            runCatching { scriptStore.publishable() }.getOrDefault(emptyList())
        }

        val snapshot = ModuleConfigSnapshot(
            // The "随目标应用启动时显示悬浮窗" switch. Off means the module is
            // still loaded (the debugger keeps working) but creates no window in
            // any target — see `shouldAttach`.
            enabled = settings.overlayAutoAttach,
            // Only the apps the user explicitly ticked. The old "empty means all
            // scoped apps" reading was ambiguous and is gone: an empty list now
            // means no overlay anywhere, and attachAllScoped is the explicit way
            // to say "everywhere".
            targetPackages = overlayTargets.toList(),
            attachAllScoped = false,
            overlayMechanism = mechanism,
            systemHookAcknowledged = acknowledged,
            overlayPrimary = theme.overlayPrimary,
            overlayFontScale = theme.overlayFontScale,
            overlayAlpha = theme.overlayAlpha,
            overlayCornerDp = theme.overlayCornerDp,
            overlayDefaultX = theme.overlayDefaultX,
            overlayDefaultY = theme.overlayDefaultY,
            // The published size is the *starting* size for every target app. A
            // size the user dragged inside one app is remembered there and wins,
            // until these two values change — publishing a new default is how the
            // user resets a window they sized badly.
            overlayWidthDp = theme.overlayWidthDp,
            overlayHeightDp = theme.overlayHeightDp,
            // Consumed by `DebugEngine.installHook`: whether to deoptimize the
            // target method before hooking it.
            autoDeoptimize = settings.debugAutoDeoptimize,
            // The Agent endpoint. `agentApiKey` is the one secret in this file;
            // see the KDoc on the model for why it has to travel here (the window's
            // Agent runs inside the target process) and what that costs
            // (/data/local/tmp is world-readable). It is redacted before the
            // WebView ever sees the config.
            agentBaseUrl = agent.baseUrl,
            agentModel = agent.model,
            agentApiKey = agent.apiKey,
            agentMaxRounds = agent.maxToolRounds,
            // 思考等级 + 是否发送思考参数。 The in-window Agent builds its own
            // request inside the target process, so both choices have to travel
            // with the snapshot; empty strings mean "the module's default", which
            // is what an older config.json gets.
            agentThinking = agent.thinking.wire,
            agentThinkingWire = agent.thinkingWire.wire,
            // The current version of every script in the library. `ScriptSpec`
            // itself is unchanged, so a module build from before the library
            // existed simply ignores this list.
            scripts = scripts,
            // `mem`, `debug` and `agent` are the tabs the panel gained; the page hides
            // any tab this list does not name, so a stale publish means "no 内存 tab".
            panels = listOf("status", "log", "tools", "mem", "debug", "agent"),
            revision = System.currentTimeMillis(),
        )

        val result = publisher.publish(snapshot)

        // Always record the outcome: a silently failed publish was exactly the
        // kind of failure that looks like "the overlay just doesn't work".
        _configPublishResult.value = result.fold(
            onSuccess = {
                "配置已下发 · ${overlayTargets.size} 个悬浮窗目标 · 方式=${mechanism.name}"
            },
            onFailure = { "配置下发失败：${it.message ?: "未知原因"}" },
        )
        _configPublishOk.value = result.isSuccess

        result.fold(
            onSuccess = {
                Log.i(TAG, "config published: targets=${overlayTargets.size} mechanism=$mechanism")
            },
            onFailure = { t ->
                // Log the whole throwable: the message alone has repeatedly been
                // the difference between "su was denied" and "the shell exists
                // but is not root".
                Log.e(TAG, "config publish FAILED", t)
                scope.launch {
                    Log.e(TAG, "root diagnosis: ${publisher.rootDiagnosis()}")
                }
            },
        )

        if (showMessage) {
            setScopeMessage(_configPublishResult.value, isError = !result.isSuccess)
        }
    }

    /** Last config-publish outcome, for diagnostics on the home screen. */
    val configPublishResult: StateFlow<String?> = _configPublishResult.asStateFlow()

    val configPublishOk: StateFlow<Boolean> = _configPublishOk.asStateFlow()

    // ---- Overlay mechanism -------------------------------------------------

    override fun overlayMechanism(): OverlayMechanism = themeStore.loadOverlayMechanism()

    override fun systemHookAcknowledged(): Boolean = themeStore.loadSystemHookAcknowledged()

    override fun setOverlayMechanism(mechanism: OverlayMechanism, acknowledged: Boolean) {
        themeStore.saveOverlayMechanism(mechanism)
        themeStore.saveSystemHookAcknowledged(acknowledged)
        scope.launch { publishConfigInternal(showMessage = true) }
    }

    override fun resetPlatformSettings() {
        // Only what the platform owns. The theme and `AppSettings` are reset by
        // `ConfigStore` through its own persist callbacks, and the framework
        // scope plus produced APKs are deliberately left alone: they describe
        // what the user is inspecting, not how the app is configured.
        themeStore.saveOverlayMechanism(OverlayMechanism.DecorView)
        // Cleared together with the mechanism on purpose: the dangerous hook must
        // be opted into again, never silently re-armed by a reset.
        themeStore.saveSystemHookAcknowledged(false)
        themeStore.clearAgentConfig()

        // Drop the cached session so the next turn re-reads the cleared config
        // instead of keeping a client built from the old key.
        agentSession = null
        pushAgentConfig()
        refreshScope()
        scope.launch { publishConfigInternal(showMessage = true) }
    }

    private fun setScopeBusy(value: Boolean) {
        _scopeState.value = _scopeState.value.copy(busy = value)
        bumpRevision()
    }

    private fun setScopeMessage(message: String?, isError: Boolean) {
        _scopeState.value = _scopeState.value.copy(
            message = message,
            messageIsError = isError,
        )
        bumpRevision()
    }

    val modulePackage: String get() = BuildConfig.APPLICATION_ID

    suspend fun readBackConfig(): Result<ModuleConfigSnapshot> = publisher.readBack()
}

// ---------------------------------------------------------------------------
// Cross-module state translation
// ---------------------------------------------------------------------------

private fun TerminalMode.toAppMode(): TerminalSession.Mode = when (this) {
    TerminalMode.Root -> TerminalSession.Mode.Root
    TerminalMode.App -> TerminalSession.Mode.App
    TerminalMode.HostContext -> TerminalSession.Mode.HostContext
    TerminalMode.Device -> TerminalSession.Mode.Device
}

private fun TerminalSession.Mode.toUiMode(): TerminalMode = when (this) {
    TerminalSession.Mode.Root -> TerminalMode.Root
    TerminalSession.Mode.App -> TerminalMode.App
    TerminalSession.Mode.HostContext -> TerminalMode.HostContext
    TerminalSession.Mode.Device -> TerminalMode.Device
}

private fun TerminalSession.Entry.toUiEntry(): TerminalEntry = when (this) {
    is TerminalSession.Entry.Command -> TerminalEntry(text, TerminalEntry.Kind.Command)
    is TerminalSession.Entry.Output -> TerminalEntry(text, TerminalEntry.Kind.Output)
    is TerminalSession.Entry.Error -> TerminalEntry(text, TerminalEntry.Kind.Error)
    is TerminalSession.Entry.System -> TerminalEntry(text, TerminalEntry.Kind.System)
}

// ---------------------------------------------------------------------------
// JSON helpers
// ---------------------------------------------------------------------------

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()

private fun JsonObject.bool(key: String): Boolean? = str(key)?.toBooleanStrictOrNull()

/** Non-secret projection of the agent config for the UI. */
private fun AgentConfig.toUi(): AgentConfigUi = AgentConfigUi(
    baseUrl = baseUrl,
    model = model,
    hasApiKey = apiKey.isNotBlank(),
    maxToolRounds = maxToolRounds,
    thinking = thinking.name,
    thinkingWire = thinkingWire.name,
)

// ---------------------------------------------------------------------------
// Script library translation
// ---------------------------------------------------------------------------

private fun ScriptStore.Entry.toUi(): ScriptEntryUi = ScriptEntryUi(
    id = id,
    name = name,
    description = description,
    enabled = enabled,
    trigger = trigger.name,
    actionCount = actionCount,
    versionCount = versionCount,
    currentVersion = currentVersion,
    updatedAt = updatedAt,
)

private fun ScriptSpec.toDraft(): ScriptDraftUi = ScriptDraftUi(
    id = id,
    name = name,
    description = description,
    enabled = enabled,
    trigger = trigger.name,
    triggerTarget = triggerTarget.orEmpty(),
    triggerMethod = triggerMethod.orEmpty(),
    actions = actions.map { it.toUi() },
)

private fun ScriptAction.toUi(): ScriptActionUi = ScriptActionUi(
    kind = kind.name,
    className = className.orEmpty(),
    memberName = memberName.orEmpty(),
    signature = signature.orEmpty(),
    value = value.orEmpty(),
    valueType = valueType.name,
    receiver = receiver.orEmpty(),
)

/**
 * Turns the editor's string form back into a [ScriptSpec].
 *
 * @throws IllegalArgumentException with a message the editor shows verbatim —
 *   "第 2 个动作缺少类名" is actionable, a `valueOf` crash is not.
 */
private fun ScriptDraftUi.toSpec(): ScriptSpec {
    val cleanName = name.trim()
    if (cleanName.isEmpty()) throw IllegalArgumentException("脚本名不能为空")

    val triggerValue = ScriptTrigger.entries.firstOrNull { it.name == trigger }
        ?: throw IllegalArgumentException("未知的触发时机：$trigger")

    val mapped = actions.mapIndexed { index, action ->
        val position = index + 1
        val kind = ScriptAction.Kind.entries.firstOrNull { it.name == action.kind }
            ?: throw IllegalArgumentException("第 $position 个动作的 kind 未知：${action.kind}")
        val className = action.className.trim()
        if (className.isEmpty()) {
            throw IllegalArgumentException("第 $position 个动作缺少类名")
        }
        ScriptAction(
            kind = kind,
            className = className,
            memberName = action.memberName.trim().ifEmpty { null },
            signature = action.signature.trim().ifEmpty { null },
            value = action.value.takeIf { it.isNotBlank() },
            valueType = ScriptAction.ValueType.entries.firstOrNull { it.name == action.valueType }
                ?: ScriptAction.ValueType.Auto,
            receiver = action.receiver.trim().ifEmpty { null },
        )
    }

    if (mapped.isEmpty()) throw IllegalArgumentException("至少要有一个动作")

    return ScriptSpec(
        id = id,
        name = cleanName,
        enabled = enabled,
        trigger = triggerValue,
        triggerTarget = triggerTarget.trim().ifEmpty { null },
        triggerMethod = triggerMethod.trim().ifEmpty { null },
        actions = mapped,
        description = description.trim(),
    )
}

// ---------------------------------------------------------------------------
// P6 — cross-module translation for the packaging screen
// ---------------------------------------------------------------------------

private fun PatchTarget.toUi(): PatchTargetUi = PatchTargetUi(
    packageName = packageName,
    label = label,
    isSystem = isSystem,
    minSdk = minSdk,
    targetSdk = targetSdk,
    apkBytes = apkBytes,
    splitCount = splitCount,
    minSdkWillBeRaised = minSdkWillBeRaised,
)

private fun PatchRecord.toUi(): PatchRecordUi = PatchRecordUi(
    packageName = packageName,
    label = label,
    path = path,
    bytes = bytes,
    createdAt = createdAt,
    injectedDexEntry = injectedDexEntry,
    signerSha256 = signerSha256,
    warnings = warnings,
)

/** Log tag for the settings-side diagnostics. */
private const val TAG = "RegTrail"

/**
 * Largest text the root file channel will hand to `su` in one command.
 *
 * Linux caps a single `execve` argument at `MAX_ARG_STRLEN` (128 KB) and the
 * payload is base64 (4/3 of its size) inside a `printf '%s' '…'` argument, so
 * 64 K characters of text is the honest ceiling with headroom to spare.
 */
private const val ROOT_WRITE_CHARS = 64 * 1024
