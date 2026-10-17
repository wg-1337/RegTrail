package io.github.wg1337.regtrail.xposed.mem

import io.github.wg1337.regtrail.xposed.RegTrailLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Server-side state for the 内存 tab: search sessions and their result sets.
 *
 * ## Why the results live here and not in the page
 * A refine ("再次扫描") is a *set operation over the previous hits*: the user
 * changes the value in the app and asks which of the previous addresses now
 * holds the new value. Keeping the previous set in the page would mean shipping
 * up to 500 addresses through the JS bridge on every step, and a page reload
 * (which the WebView does on its own schedule after a low-memory kill) would
 * silently lose the whole search. The module's process is the only place that
 * outlives the page.
 *
 * ## Session lifetime
 * At most [MAX_SESSIONS] sessions are kept, oldest dropped first. A memory search
 * is a short investigation, not a document: keeping an unbounded history would
 * pin lists of addresses in the host app's heap for as long as it runs.
 *
 * Every method returns the `data` object of the bridge envelope, or throws
 * [InProcessMemory.MemException] with a Chinese sentence. Nothing here decides
 * *what* to log: [InProcessMemory] already logs counts (never values).
 */
internal object MemorySessions {

    /** How many concurrent searches the UI may keep around. */
    const val MAX_SESSIONS = 8

    /** Default page size handed to the panel when it does not ask for one. */
    const val DEFAULT_MAX_RESULTS = InProcessMemory.DEFAULT_MAX_RESULTS

    private val lock = Any()
    private val nextId = AtomicInteger(1)
    private val sessions = LinkedHashMap<Int, Session>()

    /** The most recent search, or null when 内存 was never used in this process. */
    fun current(): Int? = synchronized(lock) { sessions.keys.lastOrNull() }

    /**
     * First scan: searches the whole address space and starts a session.
     *
     * @throws InProcessMemory.MemException on any failure, including a cancelled
     *   scan — the bridge turns it into `{"ok":false,"error":…}`.
     */
    fun scan(
        typeRaw: String?,
        value: String?,
        writableOnly: Boolean,
        maxResults: Int,
        shouldStop: () -> Boolean,
    ): JsonObject {
        val kind = InProcessMemory.ValueKind.parse(typeRaw)
            ?: throw InProcessMemory.MemException(
                "未知的搜索类型「${typeRaw.orEmpty()}」，可选：" +
                    InProcessMemory.ValueKind.WIRE_NAMES.joinToString(" / "),
            )
        val needleText = value?.trim().orEmpty()
        if (needleText.isEmpty()) {
            throw InProcessMemory.MemException("请先输入要搜索的值")
        }

        val scan = InProcessMemory.scan(
            kind = kind,
            rawValue = needleText,
            writableOnly = writableOnly,
            maxResults = maxResults,
            shouldStop = shouldStop,
        )

        val id = nextId.getAndIncrement()
        synchronized(lock) {
            sessions[id] = Session(
                id = id,
                kind = kind,
                query = needleText,
                matches = scan.matches,
                writableOnly = writableOnly,
            )
            while (sessions.size > MAX_SESSIONS) {
                val oldest = sessions.keys.first()
                sessions.remove(oldest)
            }
        }
        return scanJson(id, kind, needleText, scan)
    }

    /**
     * Next scan: filters [sessionId] down to the addresses that now hold [value].
     *
     * The type may be repeated by the caller (a page that lost the session type)
     * but the session's own type wins when both are given and disagree: a refine
     * that changes the width of the compared value would report nonsense.
     */
    fun refine(
        sessionId: Int,
        value: String?,
        typeRaw: String?,
        maxResults: Int,
        shouldStop: () -> Boolean,
    ): JsonObject {
        val session = synchronized(lock) { sessions[sessionId] }
            ?: throw InProcessMemory.MemException(
                "搜索会话 $sessionId 已不存在（可能已被回收）；请先做一次首次扫描",
            )
        val needleText = value?.trim().orEmpty()
        if (needleText.isEmpty()) {
            throw InProcessMemory.MemException("请输入数值变化后的新值")
        }
        val kind = session.kind.also {
            // A caller-supplied type is only honoured when it matches.
            val requested = InProcessMemory.ValueKind.parse(typeRaw)
            if (requested != null && requested != it) {
                RegTrailLog.d("mem: refine ignored type ${requested.wire}, session uses ${it.wire}")
            }
        }

        val scan = InProcessMemory.refine(
            previous = session.matches,
            kind = kind,
            rawValue = needleText,
            maxResults = maxResults,
            shouldStop = shouldStop,
        )

        synchronized(lock) {
            sessions[sessionId] = session.copy(matches = scan.matches, query = needleText)
        }
        return scanJson(sessionId, kind, needleText, scan)
    }

    /** Writes one value, remembering the original so 「还原」 is possible. */
    fun write(addressRaw: String?, typeRaw: String?, value: String?): JsonObject {
        val address = InProcessMemory.parseAddress(addressRaw)
            ?: throw InProcessMemory.MemException("地址无效：${addressRaw.orEmpty()}")
        val kind = InProcessMemory.ValueKind.parse(typeRaw)
            ?: throw InProcessMemory.MemException("未知的写入类型「${typeRaw.orEmpty()}」")
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) throw InProcessMemory.MemException("请输入要写入的值")

        val outcome = InProcessMemory.write(address, kind, text)
        if (!outcome.verified) {
            throw InProcessMemory.MemException(
                "写入后回读不一致（${InProcessMemory.hexAddress(address)}），" +
                    "该页可能是写时复制或已被目标应用改回",
            )
        }

        return buildJsonObject {
            put("addr", JsonPrimitive(InProcessMemory.hexAddress(address)))
            put("type", JsonPrimitive(kind.wire))
            // The *decoded* value, not the text the user typed: "0x10" and "16"
            // are the same write, and the row must show what memory now holds.
            put("value", JsonPrimitive(kind.decode(outcome.written)))
            put("verified", JsonPrimitive(true))
            put(
                "original",
                JsonPrimitive(outcome.original?.let { hexText(it) } ?: ""),
            )
            put("bytes", JsonPrimitive(outcome.written.size))
            put("region", JsonPrimitive(outcome.region?.label ?: ""))
            put("perms", JsonPrimitive(outcome.region?.perms ?: ""))
            put("modifiedCount", JsonPrimitive(InProcessMemory.modifiedCount))

            // The result lists of every live session are now stale for this
            // address. Refreshing the stored value is what makes the row show the
            // new number without a rescan.
            refreshSessions(
                address = address,
                newValue = kind.decode(outcome.written),
                newPreview = hexText(outcome.written),
            )
        }
    }

    /** Restores one address, or all of them when [addressRaw] is null/blank. */
    fun revert(addressRaw: String?): JsonObject {
        val text = addressRaw?.trim().orEmpty()
        if (text.isEmpty() || text == "all") {
            val count = InProcessMemory.revertAll()
            refreshAllSessions()
            return buildJsonObject {
                put("reverted", JsonPrimitive(count))
                put("all", JsonPrimitive(true))
                put("modifiedCount", JsonPrimitive(InProcessMemory.modifiedCount))
            }
        }

        val address = InProcessMemory.parseAddress(text)
            ?: throw InProcessMemory.MemException("地址无效：$text")
        // Which session knows this address decides how to *render* the restored
        // bytes. Without a session the bytes are still restored; the panel just
        // gets hex instead of a number.
        val kind = synchronized(lock) {
            sessions.values.firstOrNull { session ->
                session.matches.any { it.address == address }
            }?.kind
        }
        val restored = InProcessMemory.revert(address)
        refreshAllSessions()
        return buildJsonObject {
            put("addr", JsonPrimitive(InProcessMemory.hexAddress(address)))
            put("reverted", JsonPrimitive(1))
            put("bytes", JsonPrimitive(restored.size))
            put("value", JsonPrimitive(kind?.decode(restored) ?: hexText(restored)))
            put("modifiedCount", JsonPrimitive(InProcessMemory.modifiedCount))
        }
    }

    /** Session list plus every address this process has modified. */
    fun describe(): JsonObject {
        val snapshot = synchronized(lock) { sessions.values.toList() }
        return buildJsonObject {
            put("count", JsonPrimitive(snapshot.size))
            put("current", JsonPrimitive(current() ?: 0))
            put("sessions", buildJsonArray {
                snapshot.forEach { session ->
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive(session.id))
                            put("type", JsonPrimitive(session.kind.wire))
                            put("query", JsonPrimitive(session.query))
                            put("count", JsonPrimitive(session.matches.size))
                            put("writableOnly", JsonPrimitive(session.writableOnly))
                        },
                    )
                }
            })
            put("modifiedCount", JsonPrimitive(InProcessMemory.modifiedCount))
            put("modified", buildJsonArray {
                InProcessMemory.modified().forEach { entry ->
                    add(
                        buildJsonObject {
                            put("addr", JsonPrimitive(InProcessMemory.hexAddress(entry.address)))
                            put("original", JsonPrimitive(entry.originalHex))
                            put("current", JsonPrimitive(entry.currentHex))
                            put("reverted", JsonPrimitive(entry.reverted))
                        },
                    )
                }
            })
        }
    }

    /**
     * Drops every search session but **keeps** the recorded original bytes.
     *
     * Destroying the originals here would silently delete the user's only undo for
     * addresses they have already written to, which is a data-loss bug wearing a
     * "clean up" label. Reverting stays possible until the process ends or the
     * window is torn down ([forget]).
     */
    fun clear(): JsonObject {
        val dropped = synchronized(lock) {
            val count = sessions.size
            sessions.clear()
            count
        }
        return buildJsonObject {
            put("sessions", JsonPrimitive(dropped))
            put("modified", JsonPrimitive(InProcessMemory.modifiedCount))
        }
    }

    /** Forgets everything, without touching memory. Used when the window detaches. */
    fun forget() {
        synchronized(lock) { sessions.clear() }
        InProcessMemory.forgetOriginals()
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private data class Session(
        val id: Int,
        val kind: InProcessMemory.ValueKind,
        val query: String,
        val matches: List<InProcessMemory.Match>,
        val writableOnly: Boolean,
    )

    /** One session's results, in the shape the page renders. */
    private fun scanJson(
        id: Int,
        kind: InProcessMemory.ValueKind,
        query: String,
        scan: InProcessMemory.Scan,
    ): JsonObject = buildJsonObject {
        put("session", JsonPrimitive(id))
        put("type", JsonPrimitive(kind.wire))
        put("value", JsonPrimitive(query))
        put("count", JsonPrimitive(scan.matches.size))
        put("truncated", JsonPrimitive(scan.truncated))
        put("note", JsonPrimitive(scan.note))
        put("bytesRead", JsonPrimitive(scan.bytesRead))
        put("regionsRead", JsonPrimitive(scan.regionsRead))
        put("regionsTotal", JsonPrimitive(scan.totalRegions))
        put("modifiedCount", JsonPrimitive(InProcessMemory.modifiedCount))
        put("results", buildJsonArray {
            scan.matches.forEach { match ->
                add(
                    buildJsonObject {
                        put("addr", JsonPrimitive(InProcessMemory.hexAddress(match.address)))
                        put("value", JsonPrimitive(match.value))
                        put("preview", JsonPrimitive(match.preview))
                        put("region", JsonPrimitive(match.region))
                    },
                )
            }
        })
    }

    /**
     * Replaces the cached value of [address] in every session.
     *
     * Called after a write so the row the user just edited shows the new value
     * instead of contradicting the success message. A session that does not
     * contain the address is untouched.
     */
    private fun refreshSessions(address: Long, newValue: String, newPreview: String) {
        synchronized(lock) {
            if (sessions.isEmpty()) return
            for ((id, session) in sessions.entries.toList()) {
                if (session.matches.none { it.address == address }) continue
                sessions[id] = session.copy(
                    matches = session.matches.map { match ->
                        if (match.address != address) {
                            match
                        } else {
                            match.copy(value = newValue, preview = newPreview)
                        }
                    },
                )
            }
        }
    }

    /**
     * Re-reads every session's addresses after a revert.
     *
     * A revert restores bytes the sessions' cached values were overwritten from,
     * so each session must show what memory holds *now*. The read width comes from
     * the session's own type, not from the cached text: a float's rendered length
     * has nothing to do with its width in memory.
     */
    private fun refreshAllSessions() {
        val snapshot = synchronized(lock) { sessions.values.toList() }
        if (snapshot.isEmpty()) return

        for (session in snapshot) {
            val width = when (session.kind) {
                InProcessMemory.ValueKind.INT32, InProcessMemory.ValueKind.FLOAT32 -> 4
                InProcessMemory.ValueKind.INT64, InProcessMemory.ValueKind.FLOAT64 -> 8
                InProcessMemory.ValueKind.UTF8 -> session.query.toByteArray(Charsets.UTF_8).size
            }
            val updated = session.matches.map { match ->
                val bytes = InProcessMemory.read(match.address, width)
                if (bytes == null) match else match.copy(value = session.kind.decode(bytes))
            }
            synchronized(lock) {
                if (sessions[session.id] != null) {
                    sessions[session.id] = session.copy(matches = updated)
                }
            }
        }
    }

    private fun hexText(bytes: ByteArray): String = bytes.joinToString(" ") { byte ->
        val value = byte.toInt() and 0xFF
        val text = value.toString(16)
        if (text.length == 1) "0$text" else text
    }
}
