package io.github.wg1337.regtrail.xposed.bridge

import io.github.wg1337.regtrail.xposed.RegTrailLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Background work the WebView UI can start and then poll.
 *
 * ## Why the panel cannot simply wait for these
 * `window.RegTrailNative.post(action, payload)` is a *synchronous* call: the
 * `@JavascriptInterface` method blocks the WebView's JavaBridge thread until it
 * returns, and the page cannot run a single line of script until then. A memory
 * scan may read 64 MiB and an Agent turn may sit in a socket for a minute, so
 * blocking that thread freezes the panel — which is exactly the "the tool is
 * broken" experience this design avoids.
 *
 * The contract is therefore: **submit, answer fast, poll.**
 *
 *  - [submit] starts the work on `Dispatchers.IO` and returns a [Handle]
 *    immediately.
 *  - The bridge call waits a *short* while ([defaultWaitMs]) so the common case
 *    (a search over a few thousand regions, under a second) still comes back in
 *    one round trip, exactly as the protocol in docs describes.
 *  - If it is not done yet, the page gets `{"pending":true,"job":N}` and polls
 *    `jobPoll`, which doubles as the progress channel through [Handle.report].
 *
 * ## Why one lane per kind
 * Two scans at once would double the read budget (64 MiB each) and thrash the
 * host app's page cache for no gain, and two Agent turns would interleave their
 * tool calls on one conversation. [Semaphore]s serialize work *per lane* without
 * pinning threads, so a queued job is cheap.
 *
 * ## Why a cancelled job may still complete
 * A `HttpURLConnection` read cannot be interrupted from Java. Cancellation is
 * therefore cooperative: [Handle.cancelled] is polled by the memory scanner and
 * checked between Agent rounds, and a result that arrives after a cancel is
 * discarded rather than handed to a page that has moved on.
 */
internal object InProcessJobs {

    /** Which serialized lane a job runs in. */
    enum class Lane {
        /** Memory scans and refines: heavy, and budget-bound. */
        MEMORY,

        /** Agent turns: slow, network-bound. */
        AGENT,
    }

    /** How long a bridge call waits inline before answering `pending`. */
    const val defaultWaitMs = 900L

    /** How many finished jobs stay addressable by `jobPoll`. */
    private const val KEEP_FINISHED = 24

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lanes = mapOf(
        Lane.MEMORY to Semaphore(1),
        Lane.AGENT to Semaphore(1),
    )

    private val seq = AtomicInteger(1)
    private val lock = Any()
    private val jobs = LinkedHashMap<Int, Handle>()

    /**
     * One unit of background work.
     *
     * Deliberately mutable and lock-free for readers: the fields the UI reads
     * ([data], [error], [progress], [done]) are volatile, so `jobPoll` never waits
     * on the worker's lock.
     */
    class Handle internal constructor(val id: Int, val kind: String) {
        private val latch = CountDownLatch(1)

        @Volatile
        internal var coroutine: Job? = null

        /** Polled by cooperative workers; see the class KDoc. */
        @Volatile
        var cancelled: Boolean = false
            private set

        @Volatile
        var data: JsonObject? = null
            internal set

        @Volatile
        var error: String? = null
            internal set

        /** Current step, e.g. "第 2 轮：调用 list_classes". Shown by the page. */
        @Volatile
        var progress: String = ""
            internal set

        val done: Boolean get() = latch.count == 0L

        /** Wall-clock duration so far, for the "已运行 3 秒" line. */
        val elapsedMs: Long
            get() {
                val start = startedAt
                val end = if (done) finishedAt else System.currentTimeMillis()
                return (end - start).coerceAtLeast(0L)
            }

        @Volatile
        internal var startedAt: Long = System.currentTimeMillis()
        @Volatile
        internal var finishedAt: Long = 0L

        internal fun markStarted() {
            startedAt = System.currentTimeMillis()
        }

        /** Publishes a progress line for `jobPoll`. Never throws. */
        fun report(text: String) {
            progress = text
        }

        /** Cooperative cancellation: also asks the coroutine to stop. */
        fun cancel() {
            cancelled = true
            runCatching { coroutine?.cancel(CancellationException("cancelled")) }
        }

        internal fun complete() {
            finishedAt = System.currentTimeMillis()
            latch.countDown()
        }

        internal fun awaitLatch(timeoutMs: Long): Boolean =
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Starts [block] and returns its handle.
     *
     * @param kind short name shown in the UI and used to pick the lane
     *   (`memScan`, `memRefine`, `agentChat`, …).
     */
    fun submit(
        kind: String,
        lane: Lane,
        block: suspend (Handle) -> JsonObject,
    ): Handle {
        val handle = Handle(seq.getAndIncrement(), kind)
        synchronized(lock) {
            jobs[handle.id] = handle
            pruneLocked()
        }

        val gate = lanes.getValue(lane)
        handle.coroutine = scope.launch {
            try {
                gate.withPermit {
                    if (handle.cancelled) {
                        handle.error = "任务已取消"
                        return@withPermit
                    }
                    handle.markStarted()
                    handle.data = block(handle)
                }
            } catch (t: CancellationException) {
                handle.error = handle.error ?: "任务已取消"
            } catch (t: Throwable) {
                // Every failure becomes a sentence the UI can render. A stack trace
                // in the panel would be unreadable at 11 px in a 300 dp window, and
                // it would also print the values the user was searching for.
                RegTrailLog.w("job $kind failed", t)
                handle.error = t.message ?: t::class.java.simpleName
            } finally {
                if (handle.cancelled && handle.data != null && handle.error == null) {
                    // The worker finished after the user cancelled: do not hand a
                    // stale result to a page that has already moved on.
                    handle.data = null
                    handle.error = "任务已取消"
                }
                handle.complete()
            }
        }
        return handle
    }

    /** Blocking wait, used by the bridge call to answer inline when it can. */
    fun await(handle: Handle, timeoutMs: Long): Boolean =
        if (timeoutMs <= 0L) false else handle.awaitLatch(timeoutMs)

    fun find(id: Int): Handle? = synchronized(lock) { jobs[id] }

    fun cancel(id: Int): Boolean {
        val handle = find(id) ?: return false
        if (handle.done) return false
        handle.cancel()
        return true
    }

    /** Cancels everything in flight. Used when the window is hidden or detached. */
    fun cancelAll() {
        val snapshot = synchronized(lock) { jobs.values.toList() }
        snapshot.forEach { if (!it.done) it.cancel() }
    }

    /**
     * Drops the oldest *finished* jobs once the map grows past [KEEP_FINISHED].
     *
     * Only finished ones: dropping a running job would make its `jobPoll` answer
     * "任务不存在" while the work was still touching memory.
     */
    private fun pruneLocked() {
        if (jobs.size <= KEEP_FINISHED) return
        val finished = jobs.values.filter { it.done }.sortedBy { it.id }
        var excess = jobs.size - KEEP_FINISHED
        for (job in finished) {
            if (excess <= 0) break
            jobs.remove(job.id)
            excess--
        }
    }
}
