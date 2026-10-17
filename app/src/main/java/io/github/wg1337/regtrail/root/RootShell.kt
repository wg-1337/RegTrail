package io.github.wg1337.regtrail.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A minimal root shell helper for P0.
 *
 * ## Why not libsu yet
 * libsu (`com.github.topjohnwu.libsu`) is the right long-term answer — it is
 * JitPack-only (verified: latest 6.0.0, modules `core` / `service` / `nio`) and
 * it owns the tricky parts of talking to `su`. P0 deliberately avoids adding it
 * so the very first build stays dependency-light; it arrives in P3 with the
 * terminal. See `docs/PLAN.md` 2.5.
 *
 * ## Why no explicit PTY
 * Magisk's `su` implements:
 * ```
 * interactive |= !has_command;
 * ```
 * so a bare `su` (no `-c`) with a non-TTY stdin still starts an interactive
 * shell and then blocks forever waiting for input. Every command here is
 * therefore passed as `su -c "<cmd>"`, and stdin is closed immediately.
 *
 * KernelSU re-joins the arguments after `-c` with spaces, so the command is
 * always passed as a **single argument** to avoid re-splitting surprises.
 *
 * ## Why output is drained on another thread
 * Reading the process's stdout to EOF on the calling thread would block past
 * any timeout (the reader only returns when the child closes its pipe). We
 * therefore drain the stream on a dedicated thread and bound the *process*
 * wait with a real timeout, so a denied or wedged `su` cannot hang the UI.
 */
object RootShell {

    /** How long any single root invocation may take before we give up. */
    private const val DEFAULT_TIMEOUT_SECONDS = 12L

    private const val SU = "su"

    /**
     * Runs [command] as root.
     *
     * @return [Result.success] with combined stdout+stderr when `su` exited 0,
     *   otherwise [Result.failure] with a human-readable reason (denied, timed
     *   out, or a non-zero exit code).
     */
    suspend fun run(
        command: String,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    ): Result<String> = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            val pb = ProcessBuilder(SU, "-c", command)
            pb.redirectErrorStream(true)
            process = pb.start()

            // Close stdin so an accidentally-interactive su cannot wait on us.
            runCatching { process.outputStream.close() }

            val sink = AtomicReference("")
            val drainer = Thread({ sink.set(drain(process.inputStream)) }, "regtrail-su-drain")
            drainer.isDaemon = true
            drainer.start()

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                drainer.join(500)
                return@withContext Result.failure(
                    RootException("root command timed out after ${timeoutSeconds}s"),
                )
            }

            // The process has exited; the drainer will hit EOF almost immediately.
            drainer.join(1500)
            val text = sink.get().trimEnd()
            val exit = process.exitValue()

            if (exit == 0) {
                Result.success(text)
            } else {
                Result.failure(
                    RootException(
                        buildString {
                            append("su exited with code ").append(exit)
                            if (text.isNotEmpty()) append(": ").append(text.take(300))
                        },
                    ),
                )
            }
        } catch (t: Throwable) {
            process?.destroyForcibly()
            Result.failure(RootException(t.message ?: t::class.java.simpleName, t))
        }
    }

    /**
     * Convenience wrapper: does [command] as root but never distinguishes a
     * non-zero exit from success — used for "best effort" probes where a
     * missing tool is normal.
     */
    suspend fun runBestEffort(
        command: String,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    ): String? = run(command, timeoutSeconds).getOrNull()

    private fun drain(stream: InputStream): String {
        val sb = StringBuilder()
        return try {
            stream.bufferedReader().use { reader ->
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                    // Guard against a runaway command flooding memory.
                    if (sb.length > 512 * 1024) break
                }
            }
            sb.toString()
        } catch (_: Throwable) {
            sb.toString()
        }
    }
}

/** Raised when a root command could not be completed. */
class RootException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
