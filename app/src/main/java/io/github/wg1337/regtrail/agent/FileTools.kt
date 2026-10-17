package io.github.wg1337.regtrail.agent

import io.github.wg1337.regtrail.util.AtomicFiles
import java.io.File

/**
 * `read_file` / `write_file`, restricted to a small set of roots.
 *
 * ## The two things this has to get right
 *
 * **1. A path is never silently rewritten.** A path containing `..` is
 * *rejected*, not normalised: "the file you asked for is outside the sandbox" is
 * information the caller needs, whereas quietly turning
 * `/data/local/tmp/regtrail/../../system/build.prop` into something else is how
 * a model ends up believing it edited one file while it edited another. After
 * the `..` check the path is canonicalised (symlinks included) and re-checked
 * against the roots, so a symlink pointing out of the sandbox is refused too.
 *
 * **2. Reads and writes are whole-file and atomic.** Writes go through
 * [AtomicFiles] (temp file + rename), so an interrupted write cannot leave a
 * truncated file — which for the script store would mean a lost version.
 *
 * ## Where the work happens
 * The settings app's own uid, with a **root fallback**: the workspace directory
 * `/data/local/tmp/regtrail` is created by the root shell as `root:root 0755`,
 * so the app can read it but not create files in it. Rather than telling the
 * user "the allowed roots are all read-only", a write that fails with EACCES is
 * retried through [RootChannel] — the app already holds root, and this is the
 * one feature where that is the whole point.
 */
class FileTools(
    roots: List<File>,
    /** Where a *relative* path is resolved; the first root by default. */
    defaultRoot: File? = null,
    /** Optional root-shell escape hatch for paths the app's uid cannot touch. */
    private val rootChannel: RootChannel? = null,
) {

    /** A path or range the model must fix; the message is shown verbatim. */
    class FileToolException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    /**
     * Direct file access through the root shell.
     *
     * Narrow on purpose: two operations, both whole-file, both by absolute path.
     * An interface rather than a `RootShell` call so [FileTools] stays testable
     * and so the shell quoting lives in exactly one place.
     */
    interface RootChannel {
        /** Whole file as UTF-8, or null when it cannot be read. */
        suspend fun read(path: String): String?

        /** Whole file, atomically; true when it landed. */
        suspend fun write(path: String, text: String): Boolean
    }

    private val roots: List<File> = roots.map { it.absoluteFile }

    private val defaultRoot: File = defaultRoot?.absoluteFile ?: roots.firstOrNull() ?: File(".")

    /** The roots as one sentence, for the error message and the tool description. */
    fun rootsReport(): String = roots.joinToString("、") { it.path }

    // -----------------------------------------------------------------------
    // read_file
    // -----------------------------------------------------------------------

    /**
     * Reads a file, optionally only [startLine]..[endLine] (1-based, inclusive).
     *
     * The answer always carries a header with the file's **total** line count so
     * a caller that asked for the wrong range can see why it got nothing useful.
     */
    suspend fun readFile(path: String, startLine: Int?, endLine: Int?): String {
        val file = resolve(path)
        val raw = readText(file)

        val text = if (raw.length > MAX_READ_CHARS) raw.take(MAX_READ_CHARS) else raw
        val truncatedBytes = raw.length > MAX_READ_CHARS
        val total = LineRangeEdit.doc(text).size

        // `startLine` alone means "that one line"; `endLine` alone means "from the
        // top through that line"; neither means "the whole file".
        val from = startLine
        val to = endLine
        val range: LineRangeEdit.Range? = if (from == null && to == null) {
            null
        } else {
            val start = from ?: 1
            LineRangeEdit.Range(start, to ?: start)
        }

        return buildString {
            append("文件 ").append(file.path).append('\n')
            append("共 ").append(total).append(" 行")
            if (range != null) {
                append("；本次只读 ").append(range.startLine).append("..").append(range.endLine)
            } else {
                append("；本次读全部")
            }
            if (truncatedBytes) {
                append("（文件较大，只读取了前 ").append(MAX_READ_CHARS).append(" 个字符；")
                append("请用 startLine/endLine 读后面的部分）")
            }
            append('\n')
            append(LineRangeEdit.numbered(text, range))
        }
    }

    // -----------------------------------------------------------------------
    // write_file
    // -----------------------------------------------------------------------

    /**
     * Writes [content] into [path].
     *
     * Modes, and what each does to the rest of the file:
     *  - `range` (the default when a line range is given) — replace
     *    `startLine..endLine` inclusive, keep everything else;
     *  - `append` — add after the last line;
     *  - `overwrite` (the default otherwise) — replace the whole file.
     */
    suspend fun writeFile(
        path: String,
        content: String,
        startLine: Int?,
        endLine: Int?,
        mode: String?,
    ): String {
        val file = resolve(path)

        val resolved = when {
            !mode.isNullOrBlank() -> mode.trim().lowercase()
            startLine != null || endLine != null -> "range"
            else -> "overwrite"
        }

        val before = if (file.isFile || rootChannel != null) {
            runCatching { readText(file) }.getOrDefault("")
        } else {
            ""
        }
        val beforeLines = LineRangeEdit.doc(before).size

        val after = when (resolved) {
            "append" -> LineRangeEdit.append(before, content)

            "overwrite", "create" -> content

            "range", "replace" -> {
                val start = startLine
                    ?: throw FileToolException(
                        "mode=range 需要 startLine（1-based，含首尾）；" +
                            "想整份覆盖用 mode=overwrite，想追加用 mode=append",
                    )
                val end = endLine ?: start
                LineRangeEdit.replaceRange(before, LineRangeEdit.Range(start, end), content)
            }

            else -> throw FileToolException(
                "未知 mode：$mode（可用 range / append / overwrite）",
            )
        }

        writeText(file, after)

        val afterLines = LineRangeEdit.doc(after).size
        return buildString {
            append("已写入 ").append(file.path)
            append("（原子替换）\n")
            append("模式 ").append(resolved)
            if (resolved == "range" || resolved == "replace") {
                append("：替换 ").append(startLine).append("..").append(endLine ?: startLine)
            }
            append("；").append(beforeLines).append(" 行 → ").append(afterLines).append(" 行")
        }
    }

    // -----------------------------------------------------------------------
    // Path sandbox
    // -----------------------------------------------------------------------

    /**
     * Turns [path] into a [File] that is provably inside one of the roots.
     *
     * @throws FileToolException for an empty path, a traversal, or a path outside
     *   every root — including via a symlink.
     */
    fun resolve(path: String): File {
        val raw = path.trim()
        if (raw.isEmpty()) throw FileToolException("path 不能为空")
        if (raw.any { it == '\u0000' || it == '\n' || it == '\r' }) {
            throw FileToolException("path 含有 NUL 或换行，拒绝执行")
        }
        // Single quotes would let a path break out of the root-shell quoting, and
        // a legitimate path never needs them.
        if (raw.contains('\'')) throw FileToolException("path 不能包含单引号")

        val segments = raw.replace('\\', '/').split('/')
        if (segments.any { it == ".." }) {
            throw FileToolException(
                "拒绝访问：路径包含 \"..\"，不允许跳出允许的根目录。" +
                    "允许的根目录：${rootsReport()}",
            )
        }

        // "Starts with a separator" counts as absolute even on a JVM where
        // `File.isAbsolute` says otherwise (Windows needs a drive letter). The
        // tool's contract is a POSIX path — `/system/build.prop` on an Android
        // device — so it must be treated as absolute here too; resolving it
        // *relative to* the workspace is what would make `/system` land inside
        // the sandbox and quietly succeed.
        val looksAbsolute = raw.startsWith("/") || raw.startsWith("\\") || File(raw).isAbsolute
        val candidate = if (looksAbsolute) File(raw) else File(defaultRoot, raw)
        val canonical = runCatching { candidate.canonicalFile }.getOrElse { cause ->
            throw FileToolException(
                "无法解析路径 ${candidate.path}：${cause.message ?: "未知原因"}",
            )
        }

        val inside = roots.any { root ->
            val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: root
            canonical == canonicalRoot || canonical.path.startsWith(canonicalRoot.path + File.separator)
        }

        if (!inside) {
            throw FileToolException(
                "拒绝访问：${canonical.path} 不在允许的根目录内。" +
                    "允许的根目录：${rootsReport()}",
            )
        }
        return canonical
    }

    // -----------------------------------------------------------------------
    // I/O with the root fallback
    // -----------------------------------------------------------------------

    private suspend fun readText(file: File): String {
        val direct = runCatching {
            if (file.isFile) file.readText(Charsets.UTF_8) else null
        }.getOrNull()
        if (direct != null) return direct

        rootChannel?.read(file.path)?.let { return it }

        throw FileToolException(
            if (file.exists()) {
                "读取失败：没有权限读取 ${file.path}（Root 不可用或未授权）"
            } else {
                "文件不存在：${file.path}"
            },
        )
    }

    private suspend fun writeText(file: File, text: String) {
        val direct = runCatching { AtomicFiles.writeText(file, text) }
        if (direct.isSuccess) return

        if (rootChannel != null && rootChannel.write(file.path, text)) return

        throw FileToolException(
            "写入失败：${direct.exceptionOrNull()?.message ?: "未知原因"}" +
                if (rootChannel == null) "" else "（Root 回退也失败）",
        )
    }

    private companion object {
        /**
         * How much of a file one read returns.
         *
         * 256 K characters is already far more than a model should be asked to
         * reason over; the cap turns "read the whole APK's smali" into a pointer
         * at `startLine/endLine` instead of a spent context window.
         */
        const val MAX_READ_CHARS = 256 * 1024
    }
}
