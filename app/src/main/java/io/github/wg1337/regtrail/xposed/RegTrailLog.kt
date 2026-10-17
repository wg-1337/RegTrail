package io.github.wg1337.regtrail.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.wg1337.regtrail.shared.bridge.SharedPaths
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Module-side logging.
 *
 * Every line goes to three places: the framework log (visible via the LSPosed
 * manager and `logcat -s RegTrail`), an append-only spool file that the settings
 * app can tail, and a small in-memory ring buffer that the floating window's
 * log panel renders.
 *
 * File writes happen on a dedicated daemon thread. The module runs inside a
 * *target app's* main thread during startup, so logging must never block and
 * must never throw.
 */
internal object RegTrailLog {

    private const val TAG = "RegTrail"
    private const val RING_SIZE = 400
    private const val QUEUE_SIZE = 512
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val ring = ArrayDeque<String>(RING_SIZE)
    private val lock = Any()

    private val queue = ArrayBlockingQueue<String>(QUEUE_SIZE)

    private val started = AtomicBoolean(false)

    @Volatile
    private var module: XposedModule? = null

    /** Called once from the module entry point. */
    fun bind(module: XposedModule) {
        this.module = module
        startWriter()
    }

    fun d(message: String) = write(Log.DEBUG, message, null)
    fun i(message: String) = write(Log.INFO, message, null)
    fun w(message: String, t: Throwable? = null) = write(Log.WARN, message, t)
    fun e(message: String, t: Throwable? = null) = write(Log.ERROR, message, t)

    fun write(priority: Int, message: String, throwable: Throwable?) {
        val line = buildString {
            append(stamp.format(Date()))
            append(' ')
            append(priorityChar(priority))
            append(' ')
            append(message)
            if (throwable != null) {
                append(" | ")
                append(stackTrace(throwable))
            }
        }

        // 1) ring buffer for the UI
        synchronized(lock) {
            ring.addLast(line)
            while (ring.size > RING_SIZE) ring.removeFirst()
        }

        // 2) framework log
        runCatching {
            val m = module
            if (m != null) {
                m.log(priority, TAG, message, throwable)
            } else {
                Log.println(priority, TAG, message)
            }
        }

        // 3) spool file, off-thread
        if (queue.offer(line).not()) {
            // Queue full: drop the oldest so the newest (most useful) survives.
            queue.poll()
            queue.offer(line)
        }
    }

    /** Snapshot of recent lines for the overlay log panel. */
    fun recent(limit: Int = 200): List<String> = synchronized(lock) {
        if (ring.size <= limit) ring.toList() else ring.toList().takeLast(limit)
    }

    private fun startWriter() {
        if (!started.compareAndSet(false, true)) return
        val t = Thread({ writerLoop() }, "regtrail-log")
        t.isDaemon = true
        t.start()
    }

    private fun writerLoop() {
        val file = File(SharedPaths.LOG_FILE)
        while (true) {
            try {
                val line = queue.poll(1, TimeUnit.SECONDS) ?: continue
                runCatching {
                    file.parentFile?.mkdirs()
                    // Cheap rotation: if the file got big, start over.
                    if (file.exists() && file.length() > MAX_FILE_BYTES) {
                        file.delete()
                    }
                    FileWriter(file, true).use { w ->
                        w.append(line).append('\n')
                    }
                }
            } catch (_: InterruptedException) {
                return
            } catch (_: Throwable) {
                // Never let the writer die: the module would lose all logging.
            }
        }
    }

    private fun priorityChar(priority: Int): Char = when (priority) {
        Log.VERBOSE -> 'V'
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        Log.ERROR -> 'E'
        Log.ASSERT -> 'A'
        else -> '?'
    }

    private fun stackTrace(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString().lineSequence().take(6).joinToString(" / ")
    }
}
