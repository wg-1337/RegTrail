package io.github.wg1337.regtrail.util

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Whole-file writes that never leave a half-written file behind.
 *
 * ## Why this exists
 * Two features write files the rest of the app then re-reads as authoritative:
 * the script library (`ScriptStore`) and the Agent's `write_file` tool. A plain
 * `File.writeText` truncates first and writes second, so a crash, a full disk or
 * a killed process in between leaves a truncated file — and for the script
 * library that means a *lost version*, which is exactly what the append-only
 * design is supposed to prevent.
 *
 * The write therefore goes to a sibling temp file and is then moved over the
 * target. `Files.move(…, ATOMIC_MOVE, REPLACE_EXISTING)` is a POSIX `rename(2)`
 * on Android (API 26+, and this app's `minSdk` is 27) *and* works on Windows,
 * where the JVM's `File.renameTo` refuses to overwrite an existing file — which
 * is what makes the same code path testable in a plain JVM unit test.
 *
 * If the platform refuses an atomic move, the fallback is delete-then-rename:
 * still never a partially written file, only a short window without one.
 */
object AtomicFiles {

    /** Suffix used for the temp file; deliberately not a recognised data name. */
    private const val TEMP_SUFFIX = ".tmp"

    /**
     * Replaces [target] with [text] atomically, creating parent directories.
     *
     * @throws IOException when the directory cannot be created or the move fails.
     */
    fun writeText(target: File, text: String) {
        val absolute = target.absoluteFile
        val dir = absolute.parentFile
            ?: throw IOException("路径没有父目录：${target.path}")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("无法创建目录 ${dir.path}")
        }

        val temp = File(dir, absolute.name + TEMP_SUFFIX)
        try {
            temp.writeText(text, Charsets.UTF_8)
            moveReplacing(temp, absolute)
        } finally {
            // Only fires when the move failed; a successful move leaves no temp.
            if (temp.exists()) runCatching { temp.delete() }
        }
    }

    /** Moves [from] onto [to], replacing it; atomic when the platform allows. */
    fun moveReplacing(from: File, to: File) {
        try {
            Files.move(
                from.toPath(),
                to.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            return
        } catch (_: Throwable) {
            // ATOMIC_MOVE is optional per platform; fall through to the portable
            // path rather than failing a write that would otherwise succeed.
        }

        if (to.exists() && !to.delete()) {
            throw IOException("无法覆盖已有文件 ${to.path}")
        }
        if (!from.renameTo(to)) {
            throw IOException("无法把 ${from.path} 移动到 ${to.path}")
        }
    }
}
