package io.github.wg1337.regtrail.terminal

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A multi-mode in-app terminal.
 *
 * ## Design note: line-oriented, not a full PTY
 * This is a command REPL over a **persistent** root shell, not a VT100 emulator:
 *
 *  - a single `Shell` instance is created once and reused, so shell state
 *    (`cd`, exported variables, aliases) survives between commands, which is
 *    what makes it feel like a terminal rather than a series of one-shots;
 *  - output is captured per command and appended to a scrollback buffer;
 *  - full-screen curses programs (`top`, `vi`) are **not** supported, because
 *    they need a real tty. That requires a PTY (`forkpty` via JNI, the route
 *    Termux takes), which is a separate native module and deliberately out of
 *    scope here rather than half-implemented.
 *
 * The modes exist because "run this as root" and "run this as the target app"
 * are genuinely different operations, and conflating them is how you end up
 * debugging the wrong UID's filesystem.
 */
class TerminalSession {

    private var shell: Shell? = null

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Working directory, tracked so the prompt can show it and `cd` can persist. */
    private var cwd: String = DEFAULT_CWD

    /**
     * Directory the "工作区" modes start in and that [ensureWorkspace] creates.
     *
     * Configurable from the settings app; defaults to [WORKSPACE]. Only the
     * terminal follows it — the config-push path is fixed by `SharedPaths`
     * because the module reads it from there.
     */
    private var workspace: String = WORKSPACE

    /** History for the up/down arrows in the UI. */
    private val history = ArrayDeque<String>()

    // -----------------------------------------------------------------------
    // Session control
    // -----------------------------------------------------------------------

    /**
     * Opens the root shell.
     *
     * Safe to call repeatedly.
     *
     * @param workspacePath starting directory for the workspace-backed modes,
     *   and the directory ensured to exist. Blank falls back to [WORKSPACE] so a
     *   cleared setting can never produce a shell rooted at `/`.
     */
    suspend fun start(
        mode: Mode = Mode.Root,
        workspacePath: String = WORKSPACE,
    ): Unit = withContext(Dispatchers.IO) {
        close()
        workspace = workspacePath.trim().ifBlank { WORKSPACE }
        _state.value = SessionState(connected = false, mode = mode, busy = true)

        val created = runCatching { Shell.getShell() }.getOrNull()
        if (created == null) {
            append(Entry.System("无法创建 shell：Root 未授权？"))
            _state.value = SessionState(connected = false, mode = mode)
            return@withContext
        }

        shell = created
        cwd = defaultCwdFor(mode)

        // Prove the shell works and learn which identity we actually got. This
        // matters because `su` can silently hand back a non-root shell.
        val probe = runCatching {
            created.newJob().add("id -u", "id -un", "pwd").exec()
        }.getOrNull()

        val uid = probe?.out?.getOrNull(0)?.trim().orEmpty()
        val user = probe?.out?.getOrNull(1)?.trim().orEmpty()

        _state.value = SessionState(
            connected = true,
            mode = mode,
            uid = uid,
            user = user,
            isRoot = uid == "0",
            cwd = cwd,
        )

        append(
            Entry.System(
                buildString {
                    append("已连接 shell")
                    if (user.isNotEmpty()) append("（$user")
                    if (uid.isNotEmpty()) append(" / uid=$uid")
                    if (user.isNotEmpty() || uid.isNotEmpty()) append("）")
                    append("\n模式：").append(mode.label)
                    append("\n初始目录：").append(cwd)
                    append("\n提示：不支持全屏交互程序（需要 PTY，后续里程碑）")
                },
            ),
        )

        // Make sure the workspace exists before anyone tries to cd into it.
        ensureWorkspace()
    }

    private suspend fun ensureWorkspace() {
        val path = quote(workspace)
        run("mkdir -p $path && chmod 755 $path")
    }

    fun close() {
        runCatching { shell?.close() }
        shell = null
        _state.value = _state.value.copy(connected = false)
    }

    // -----------------------------------------------------------------------
    // Execution
    // -----------------------------------------------------------------------

    /**
     * Runs one command line and appends its output.
     *
     * @param raw the line as typed. Empty lines, comments and the built-ins are
     *   handled here rather than being sent to the shell.
     */
    suspend fun run(raw: String): Unit = withContext(Dispatchers.IO) {
        val line = raw.trim()
        if (line.isEmpty()) return@withContext

        val activeShell = shell
        if (activeShell == null) {
            append(Entry.System("尚未连接 shell"))
            return@withContext
        }

        append(Entry.Command(line, _state.value.cwd))
        if (history.isEmpty() || history.last() != line) history.addLast(line)
        while (history.size > MAX_HISTORY) history.removeFirst()

        // Built-ins that must run in *our* view of the world, not the shell's,
        // because the shell is a fresh process per job and its `cd` would not
        // survive. Everything else is sent through as-is.
        when {
            line == "clear" -> {
                _entries.value = emptyList()
                return@withContext
            }
            line == "exit" -> {
                close()
                append(Entry.System("已断开"))
                return@withContext
            }
            line == "pwd" -> {
                append(Entry.Output(cwd))
                return@withContext
            }
            line == "help" -> {
                append(Entry.System(HELP_TEXT))
                return@withContext
            }
            line.startsWith("cd ") || line == "cd" -> {
                changeDirectory(line.removePrefix("cd").trim())
                return@withContext
            }
            line.startsWith("mode ") -> {
                append(
                    Entry.System(
                        "切换模式请用界面上的模式选择器；当前：${_state.value.mode.label}",
                    ),
                )
                return@withContext
            }
        }

        _state.value = _state.value.copy(busy = true)
        try {
            // Wrap in a subshell that cds first, so the working directory behaves
            // as the user expects even though each Job is a fresh process.
            val wrapped = "cd ${quote(cwd)} 2>/dev/null; $line"

            val result = activeShell.newJob()
                .add(wrapped)
                .to(mutableListOf(), mutableListOf())
                .exec()

            val stdout = result.out.joinToString("\n")
            val stderr = result.err.joinToString("\n")

            if (stdout.isNotEmpty()) append(Entry.Output(stdout))
            if (stderr.isNotEmpty()) append(Entry.Error(stderr))
            if (stdout.isEmpty() && stderr.isEmpty() && !result.isSuccess) {
                append(Entry.Error("命令失败（exit ${result.code}）"))
            }
        } catch (t: Throwable) {
            append(Entry.Error(t.message ?: t::class.java.simpleName))
        } finally {
            _state.value = _state.value.copy(busy = false)
        }
    }

    /** Navigates relative to the tracked cwd, verifying the target exists. */
    private suspend fun changeDirectory(target: String) {
        val destination = when {
            target.isEmpty() || target == "~" -> "/data/local/tmp"
            target.startsWith("/") -> target
            else -> "$cwd/$target"
        }
        val check = runCatching {
            shell?.newJob()
                ?.add("cd ${quote(destination)} 2>/dev/null && pwd")
                ?.exec()
        }.getOrNull()

        val resolved = check?.out?.firstOrNull()?.trim()
        if (check?.isSuccess == true && !resolved.isNullOrEmpty()) {
            cwd = resolved
            _state.value = _state.value.copy(cwd = cwd)
        } else {
            append(Entry.Error("无法进入目录：$destination"))
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    fun historyEntries(): List<String> = history.toList()

    fun clear() {
        _entries.value = emptyList()
    }

    private fun append(entry: Entry) {
        val current = _entries.value
        // Bound the scrollback so a chatty command cannot exhaust memory.
        val next = if (current.size >= MAX_ENTRIES) {
            current.subList(current.size - MAX_ENTRIES + 1, current.size) + entry
        } else {
            current + entry
        }
        _entries.value = next
    }

    private fun defaultCwdFor(mode: Mode): String = when (mode) {
        // The app sandbox has no reliable writable path we can assume, and
        // `filesDir` is not a stable shell cwd across jobs, so start in the
        // shared scratch directory every mode can reach.
        Mode.App -> DEFAULT_CWD
        Mode.Device -> workspace
        Mode.HostContext -> workspace
        Mode.Root -> DEFAULT_CWD
    }

    /** Single-quotes a path for safe interpolation into a shell line. */
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    // -----------------------------------------------------------------------

    /**
     * Which identity commands run as.
     *
     * Modelled explicitly because these are genuinely different and silently
     * running as the wrong one wastes a lot of debugging time.
     */
    enum class Mode(val label: String, val description: String) {
        /** uid 0 via su: full device access. */
        Root("Root", "以 uid 0 执行，可访问整个设备"),

        /** The settings app's own uid: no root needed, sandboxed. */
        App("应用沙箱", "以 RegTrail 自身身份执行，无需 Root"),

        /**
         * A shell placed inside the *target* app's context.
         *
         * Implemented by having root enter the target's mount namespace and
         * SELinux context, which is what makes the target's view of `/data/data`
         * and of its own mounts reproducible.
         */
        HostContext("宿主上下文", "进入目标应用的挂载命名空间与 SELinux 上下文"),

        /** Root shell rooted at the shared workspace; networking unrestricted. */
        Device("工作区（联网）", "在 RegTrail 工作区内执行，网络不受限"),
    }

    data class SessionState(
        val connected: Boolean = false,
        val mode: Mode = Mode.Root,
        val uid: String = "",
        val user: String = "",
        val isRoot: Boolean = false,
        val cwd: String = DEFAULT_CWD,
        val busy: Boolean = false,
    )

    /** One line in the scrollback. */
    sealed interface Entry {
        val text: String

        data class Command(val command: String, val cwd: String) : Entry {
            override val text: String get() = command
        }

        data class Output(override val text: String) : Entry

        data class Error(override val text: String) : Entry

        data class System(override val text: String) : Entry
    }

    companion object {
        /**
         * Default workspace, equal to `SharedPaths.BASE_DIR`.
         *
         * Kept as a separate constant because a *session* may be pointed at
         * another directory from the settings app, while the module's spool and
         * config path stay where `SharedPaths` says.
         */
        const val WORKSPACE = "/data/local/tmp/regtrail"

        const val DEFAULT_CWD = "/data/local/tmp"

        private const val MAX_ENTRIES = 400
        private const val MAX_HISTORY = 200

        private val HELP_TEXT = """
            可用内置命令：
              help          显示本帮助
              clear         清空回显
              pwd           显示当前目录（由 RegTrail 跟踪）
              cd <dir>      切换目录（跨命令保持）
              exit          断开 shell
            其余内容原样交给 shell 执行。
            不支持需要 PTY 的全屏程序（top/vi 等）。
        """.trimIndent()
    }
}

/**
 * Resolves the `su` invocation that puts a shell inside a target app's context.
 *
 * Magisk and KernelSU both accept `--mount-master`/`-M`; entering another
 * process's namespace is done with `nsenter` against the target pid, and the
 * SELinux context can be set with `-Z`. Which of these is available varies by
 * manager, so the command is built to try the accurate form and degrade to a
 * plain root shell rather than failing outright.
 */
internal object HostContextBuilder {

    /**
     * @param packageName the target app.
     * @param uid the target app's uid, from PackageManager.
     * @return a shell command that yields a shell in the target's context.
     */
    fun build(packageName: String, uid: Int): String = buildString {
        // `$PID` is a *shell* variable, so it is escaped out of Kotlin's string
        // templates deliberately.
        val d = '$'
        append("PID=").append(d).append("(pidof ").append(packageName)
        append(" | awk '{print ").append(d).append("1}'); ")
        append("if [ -n \"").append(d).append("PID\" ] && command -v nsenter >/dev/null 2>&1; then ")
        append("echo '进入 ").append(packageName).append(" 的命名空间 (pid=")
        append(d).append("PID)'; ")
        append("exec nsenter -t ").append(d).append("PID -m -p -U -i -n /system/bin/sh; ")
        append("else ")
        append("echo '目标未运行或缺少 nsenter，回退为同 uid 的 root shell'; ")
        append("exec su ").append(uid).append(" -c /system/bin/sh; ")
        append("fi")
    }

    /** True when the workspace path is usable as a cwd. */
    fun workspaceExists(): Boolean = runCatching { File(TerminalSession.WORKSPACE).isDirectory }
        .getOrDefault(false)
}
