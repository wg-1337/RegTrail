package io.github.wg1337.regtrail.xposed.mem

import io.github.wg1337.regtrail.xposed.RegTrailLog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Memory search and patching **inside the injected process**, with no root.
 *
 * ## Why this can work without root at all
 * The kernel's `/proc/self/mem` is a window onto the calling process's own
 * address space. `mm_access()` in `fs/proc/base.c` short-circuits the
 * ptrace/`PTRACE_MODE_ATTACH_FSCREDS` permission check when the target is
 * `current` itself, so a process may always read — and, for a writable mapping,
 * write — its own memory. There is no SELinux transition involved, no `su`, no
 * `dd` and no `/system` binary to depend on.
 *
 * That is the whole reason this class exists next to the settings-side
 * `memory/MemoryScanner.kt`: that one must reach *another* process's memory, so
 * it has no choice but to go through a root shell (`dd` on `/proc/<pid>/mem`).
 * This one is sitting right next to the memory it wants and uses plain
 * `RandomAccessFile` I/O. The two differ in transport only; the search protocol
 * (type names, alignment, refine semantics, budgets) is deliberately identical
 * so a user who learned the search flow in the settings app gets the same
 * behaviour in the floating window.
 *
 * ## Why not just reuse the settings-side scanner
 * Sharing the code would drag the root-shell stack (libsu, process listing,
 * `dd`, file spooling) into every target process that loads the module. The
 * module is loaded into third-party apps whose dex method-reference pools are
 * frequently full, so the injected side stays as small as it can be. The
 * protocol is what both sides share; the transport is not.
 *
 * ## Failure model
 * Every method either returns a value or throws [MemException] with a Chinese
 * message meant for the user. Nothing here logs a scan value, an address or a
 * byte pattern at info level: the whole point of a memory search is that the
 * value is the secret (a session token, a score, a balance).
 */
internal object InProcessMemory {

    // -----------------------------------------------------------------------
    // Budgets
    // -----------------------------------------------------------------------

    /**
     * Largest single-region read.
     *
     * 8 MiB matches the settings-side scanner. The biggest useful regions (a
     * Dalvik heap, a 4 MiB dex mapping) fit; a multi-hundred-megabyte anonymous
     * mapping made by a game engine does not stall a scan.
     */
    const val MAX_REGION_BYTES = 8L * 1024 * 1024

    /**
     * Total bytes one scan may read.
     *
     * 64 MiB is ~15 ms of `pread` on a mid-range phone but up to a few hundred
     * milliseconds on a slow one, which is why the bridge runs the scan on a
     * background coroutine and answers the page from a job instead of blocking
     * the WebView's bridge thread for the whole read.
     */
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    /**
     * Total bytes one scan may read when the transport is the reflection-based
     * `sun.misc.Unsafe` one.
     *
     * That path is one JNI call per 8 bytes instead of one `pread` per page, so 64
     * MiB would take seconds and read as a hang. 16 MiB still covers the app's
     * Dalvik heap sections where a counter or a flag lives, and the reply says the
     * budget was reduced so the number is never a mystery.
     */
    const val MAX_TOTAL_BYTES_UNSAFE_ELEMENTWISE = 16L * 1024 * 1024

    /** Cap on regions visited per scan, so a pathological maps file cannot spin. */
    const val MAX_REGIONS = 2000

    /** How many hits one scan reports. A phone panel cannot usefully render more. */
    const val DEFAULT_MAX_RESULTS = 500

    /** Absolute ceiling for `maxResults`, mirroring the settings-side scanner. */
    const val HARD_MAX_RESULTS = 1000

    /** Bytes of context shown per hit, as hex. */
    const val CONTEXT_BYTES = 16

    /** Largest single write, mirroring `MemoryScanner.MAX_WRITE_BYTES`. */
    const val MAX_WRITE_BYTES = 256

    private const val PAGE_BYTES = 4096

    /** `/proc/self/maps` line: `start-end perms offset dev inode [path]`. */
    private val MAP_LINE = Regex(
        "^([0-9a-fA-F]+)-([0-9a-fA-F]+)\\s+(\\S{4})\\s+([0-9a-fA-F]+)\\s+(\\S+)\\s+(\\d+)\\s*(.*)$",
    )

    /** Raised for anything the user should read as a sentence, not a stack trace. */
    class MemException(message: String) : Exception(message)

    // -----------------------------------------------------------------------
    // Types
    // -----------------------------------------------------------------------

    /**
     * The value types a search may use.
     *
     * Identical wire names to the settings-side `ScanQuery`, including the
     * aliases, because the Agent's memory tools and the panel's type selector
     * must accept exactly the same vocabulary.
     */
    enum class ValueKind(val wire: String) {
        INT32("int32"),
        INT64("int64"),
        FLOAT32("float32"),
        FLOAT64("float64"),
        UTF8("utf8");

        val numeric: Boolean get() = this != UTF8

        /** Encodes the user's text, or null when it does not parse for this type. */
        fun encode(raw: String): ByteArray? = when (this) {
            INT32 -> parseIntText(raw)?.let { bytes -> le(4) { putInt(bytes) } }
            INT64 -> parseLongText(raw)?.let { value -> le(8) { putLong(value) } }
            FLOAT32 -> raw.trim().toFloatOrNull()
                ?.takeIf { !it.isNaN() }
                ?.let { value -> le(4) { putInt(value.toRawBits()) } }
            FLOAT64 -> raw.trim().toDoubleOrNull()
                ?.takeIf { !it.isNaN() }
                ?.let { value -> le(8) { putLong(value.toRawBits()) } }
            // An empty needle would match every address in the address space.
            UTF8 -> raw.toByteArray(Charsets.UTF_8).takeIf { it.isNotEmpty() }
        }

        /** Renders the bytes at one address the way the result list displays it. */
        fun decode(bytes: ByteArray): String = runCatching {
            when (this) {
                INT32 -> ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toString()
                INT64 -> ByteBuffer.wrap(bytes, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long.toString()
                FLOAT32 ->
                    ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).float.toString()
                FLOAT64 ->
                    ByteBuffer.wrap(bytes, 0, 8).order(ByteOrder.LITTLE_ENDIAN).double.toString()
                // A UTF-8 hit is by definition exactly the needle, so echoing the
                // bytes is both correct and cheaper than re-decoding a region.
                UTF8 -> String(bytes, Charsets.UTF_8)
            }
        }.getOrDefault("?")

        companion object {
            /** Names the panel's selector and the Agent tools accept. */
            val WIRE_NAMES: List<String> = entries.map { it.wire }

            /**
             * Parses a wire name or one of its common aliases.
             *
             * The alias list is copied from the settings-side `ScanQuery.parse`
             * on purpose: a user who types `float` in one place must not have to
             * type `float32` in the other.
             */
            fun parse(raw: String?): ValueKind? = when (raw?.trim()?.lowercase()) {
                "int", "int32", "i32", "dword" -> INT32
                "long", "int64", "i64", "qword" -> INT64
                "float", "float32", "f32" -> FLOAT32
                "double", "float64", "f64" -> FLOAT64
                "utf8", "utf-8", "string", "str" -> UTF8
                else -> null
            }
        }
    }

    /** One readable mapping from `/proc/self/maps`. */
    data class Region(
        val start: Long,
        val end: Long,
        /** `rw-p` style permission string. */
        val perms: String,
        /** Mapped file, or an `[anon:…]`/`[heap]` label, or empty. */
        val path: String,
    ) {
        val size: Long get() = end - start
        val readable: Boolean get() = perms.startsWith("r")
        val writable: Boolean get() = perms.length > 1 && perms[1] == 'w'

        /** Short label for display: the path, or `[anon]`. */
        val label: String get() = path.ifEmpty { "[anon]" }

        fun contains(address: Long): Boolean = address >= start && address < end
    }

    /** One hit. [value] is the value *as currently readable* at [address]. */
    data class Match(
        val address: Long,
        val value: String,
        /** Up to [CONTEXT_BYTES] bytes at the address, hex, for a sanity check. */
        val preview: String,
        val region: String,
    )

    /** What one scan pass managed to look at, and what it found. */
    data class Scan(
        val matches: List<Match>,
        /** True when a byte/region/result budget stopped the pass early. */
        val truncated: Boolean,
        val bytesRead: Long,
        val regionsRead: Int,
        val regionsSkipped: Int,
        val totalRegions: Int,
        val note: String,
        /** Wall-clock duration of the pass, in milliseconds. */
        val elapsedMs: Long = 0L,
        /** Which transport actually produced the bytes (`proc-mem` / `unsafe`). */
        val transport: String = "",
        /** Regions the filter kept, out of [totalRegions]. */
        val regionsKept: Int = 0,
        /** Pages that could not be read (unmapped holes inside a live region). */
        val pageFailures: Int = 0,
    )

    /** The outcome of one write, including what was there before. */
    data class WriteOutcome(
        val address: Long,
        val written: ByteArray,
        val original: ByteArray?,
        /** Read back after writing: never report success without checking. */
        val verified: Boolean,
        val region: Region?,
    )

    /** A modified address, with enough context for the UI to offer 「还原」. */
    data class Modified(
        val address: Long,
        val originalHex: String,
        val currentHex: String,
        /** True when the current bytes already equal the remembered original. */
        val reverted: Boolean,
    )

    // -----------------------------------------------------------------------
    // Region bookkeeping
    // -----------------------------------------------------------------------

    /**
     * Reads and parses `/proc/self/maps`.
     *
     * A process can always read its own maps. Lines that do not parse are
     * dropped rather than treated as fatal: ROM-specific additions exist, and a
     * single weird line must not disable the feature.
     */
    fun regions(): List<Region> {
        val file = File("/proc/self/maps")
        if (!file.canRead()) {
            throw MemException("无法读取 /proc/self/maps（当前进程不允许访问自己的内存映射表）")
        }
        val out = ArrayList<Region>(256)
        runCatching {
            file.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val match = MAP_LINE.find(line.trim()) ?: return@forEach
                    val start = match.groupValues[1].toLongOrNull(16) ?: return@forEach
                    val end = match.groupValues[2].toLongOrNull(16) ?: return@forEach
                    if (end <= start) return@forEach
                    out += Region(
                        start = start,
                        end = end,
                        perms = match.groupValues[3],
                        path = match.groupValues[7].trim(),
                    )
                }
            }
        }.onFailure {
            throw MemException("读取 /proc/self/maps 失败：${it.message ?: it::class.java.simpleName}")
        }
        return out
    }

    /**
     * True when this mapping is worth searching.
     *
     * ## What is deliberately included
     * With [writableOnly] false the scan covers **anonymous/heap/stack** mappings
     * *and* the app's own code mappings (`base.apk`, `*.oat`, `*.vdex`, `*.art`,
     * `*.dex`). Real targets put constants in code — a config flag, a default
     * price, a hard-coded currency rate — and a "writable only" default silently
     * hides exactly those, which is why that toggle defaults to **off**.
     *
     * ## What is excluded
     * The kernel's own oddities (`[vvar]`/`[vsyscall]`/`[vectors]`: no useful data,
     * sometimes unreadable even though mapped `r`), device/GPU mappings (reading
     * them can block or fault), the framework/apex/vendor images and every shared
     * library. Those are hundreds of megabytes of code shared by every process on
     * the device: an app's counters are never in them, and including them would
     * spend the whole 64 MiB budget before reaching the app's heap.
     */
    fun Region.isScannable(writableOnly: Boolean): Boolean {
        if (!readable || size <= 0L) return false
        if (writableOnly && !writable) return false

        val label = path
        if (label.isEmpty()) return true
        val lower = label.lowercase()

        if (lower.startsWith("[vvar") || lower.startsWith("[vsyscall") ||
            lower.startsWith("[vectors") || lower.startsWith("[vdso")
        ) {
            return false
        }

        if (lower.startsWith("/dev/") || lower.contains("dmabuf") ||
            lower.contains("/kgsl") || lower.contains("/gpu")
        ) {
            return false
        }

        if (lower.startsWith("/system/") || lower.startsWith("/system_ext/") ||
            lower.startsWith("/apex/") || lower.startsWith("/vendor/") ||
            lower.startsWith("/product/") || lower.startsWith("/odm/") ||
            lower.startsWith("/data/dalvik-cache/") || lower.startsWith("/memfd:")
        ) {
            return false
        }

        // Shared libraries are excluded, but the app's own dex/oat/vdex/art/apk
        // mappings are kept — see the KDoc above.
        return !lower.endsWith(".so")
    }

    /** Regions that pass the filter, in the order a scan visits them. */
    fun scannableRegions(writableOnly: Boolean): List<Region> =
        regions().filter { it.isScannable(writableOnly) }
            // Smallest first: a process has thousands of 4 KiB anonymous
            // mappings, and those are where an app's own counters live. Sorting by
            // size means a budget stop still covered the useful part of the
            // address space instead of one huge engine heap.
            .sortedWith(compareBy({ it.size }, { it.start }))
            .take(MAX_REGIONS)

    // -----------------------------------------------------------------------
    // Scan
    // -----------------------------------------------------------------------

    /**
     * First pass: find [needle] everywhere it appears in the process's memory.
     *
     * @param shouldStop polled between regions *and* inside the buffer loop of a
     *   large region, so cancelling a 64 MiB scan takes milliseconds rather than
     *   a full pass.
     */
    fun scan(
        kind: ValueKind,
        rawValue: String,
        writableOnly: Boolean,
        maxResults: Int = DEFAULT_MAX_RESULTS,
        shouldStop: () -> Boolean = { false },
    ): Scan {
        val startedAt = System.nanoTime()
        val needle = kind.encode(rawValue)
            ?: throw MemException("无法把「$rawValue」解析为 ${kind.wire}")

        val all = regions()
        if (all.isEmpty()) throw MemException("当前进程没有可读的内存映射")
        val candidates = all.filter { it.isScannable(writableOnly) }
        if (candidates.isEmpty()) {
            throw MemException(
                "没有可扫描的内存区域（共 ${all.size} 个映射，可能全部是共享库或不可读）",
            )
        }

        val limit = maxResults.coerceIn(1, HARD_MAX_RESULTS)
        val ordered = candidates
            .sortedWith(compareBy({ it.size }, { it.start }))
            .take(MAX_REGIONS)

        val found = ArrayList<Match>(minOf(limit, 64))
        var bytesRead = 0L
        var regionsRead = 0
        var pageFailures = 0
        var truncated = ordered.size < candidates.size
        var stopped = false

        val transport = openTransport()
        // The budget depends on the transport: the reflection-based one is far slower.
        val budget = transport.maxTotalBytes.coerceAtMost(MAX_TOTAL_BYTES)
        try {
            for (region in ordered) {
                if (shouldStop()) {
                    stopped = true
                    break
                }
                val left = budget - bytesRead
                if (left < PAGE_BYTES) {
                    truncated = true
                    break
                }
                val want = minOf(region.size, MAX_REGION_BYTES, left)
                if (want < region.size) truncated = true

                val chunk = readRegion(transport, region.start, want) ?: continue
                pageFailures += chunk.failedPages
                if (chunk.data.isEmpty()) continue

                regionsRead++
                bytesRead += chunk.data.size

                if (
                    scanBuffer(
                        data = chunk.data,
                        baseAddress = region.start,
                        kind = kind,
                        needle = needle,
                        limit = limit,
                        out = found,
                        validPages = chunk.validPages,
                    )
                ) {
                    truncated = true
                    break
                }
            }
        } finally {
            transport.close()
        }

        if (regionsRead == 0) {
            throw MemException(
                "无法从 ${transport.name} 读取任何数据（尝试了 ${ordered.size} 个区域，" +
                    "失败页 $pageFailures）",
            )
        }
        if (stopped) throw MemException("扫描已取消")

        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L

        // Address-ascending output: deterministic across runs, which is what makes
        // "the third address is the one I want" reproducible.
        val matches = found.sortedBy { it.address }

        val note = buildString {
            append("读取 ").append(mib(bytesRead)).append(" / ")
            append(regionsRead).append(" 个区域")
            if (truncated) {
                append("；已达到预算上限（单次最多 ")
                append(mib(budget))
                append("），未扫完的地址空间需要靠「再次扫描」缩小范围")
            }
            if (pageFailures > 0) {
                append("；").append(pageFailures).append(" 个页面不可读（区域内的空洞，已跳过）")
            }
            append("；传输=").append(transport.name)
            append("；耗时=").append(elapsedMs).append(" ms")
            if (!writableOnly) append("；含只读代码段")
        }

        RegTrailLog.d(
            "mem: scan ${kind.wire} → ${matches.size} 命中 " +
                "($regionsRead 区域, ${mib(bytesRead)}, ${elapsedMs}ms, " +
                "transport=${transport.name}, truncated=$truncated, " +
                "pageFailures=$pageFailures, kept=${candidates.size}/${all.size})",
        )
        return Scan(
            matches = matches,
            truncated = truncated,
            bytesRead = bytesRead,
            regionsRead = regionsRead,
            regionsSkipped = all.size - candidates.size,
            totalRegions = all.size,
            note = note,
            elapsedMs = elapsedMs,
            transport = transport.name,
            regionsKept = candidates.size,
            pageFailures = pageFailures,
        )
    }

    /**
     * Next pass: keep only the previous hits whose *current* value still matches.
     *
     * ## Why this re-reads instead of comparing stored bytes
     * The stored bytes are from the moment of the first scan. Between then and now
     * the user changed the value in the app, which is the entire point of a
     * refine — comparing stale bytes would filter out exactly the address that
     * changed. Re-reading also notices freed/reused pages, whose read returns an
     * error or garbage that will not match.
     */
    fun refine(
        previous: List<Match>,
        kind: ValueKind,
        rawValue: String,
        maxResults: Int = DEFAULT_MAX_RESULTS,
        shouldStop: () -> Boolean = { false },
    ): Scan {
        val startedAt = System.nanoTime()
        val needle = kind.encode(rawValue)
            ?: throw MemException("无法把「$rawValue」解析为 ${kind.wire}")
        if (previous.isEmpty()) {
            return Scan(emptyList(), false, 0L, 0, 0, 0, "上一轮没有命中，先重新做首次扫描")
        }

        val limit = maxResults.coerceIn(1, HARD_MAX_RESULTS)
        val kept = ArrayList<Match>(minOf(previous.size, 64))
        var checked = 0
        var unreadable = 0
        val buffer = ByteArray(needle.size)

        val transport = openTransport()
        try {
            for (candidate in previous) {
                if (shouldStop()) throw MemException("扫描已取消")
                if (kept.size >= limit) break
                checked++

                if (!transport.readInto(candidate.address, buffer, 0, needle.size)) {
                    // The page was freed or remapped since the first pass. That is
                    // normal in a running app, not an error.
                    unreadable++
                    continue
                }
                if (!buffer.contentEquals(needle)) continue

                kept += candidate.copy(
                    value = kind.decode(buffer.copyOf()),
                    preview = hex(buffer, CONTEXT_BYTES),
                )
            }
        } finally {
            transport.close()
        }

        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        val note = buildString {
            append("复核 ").append(checked).append(" 个地址，保留 ")
            append(kept.size).append(" 个")
            if (unreadable > 0) append("；").append(unreadable).append(" 个地址已不可读（内存已释放）")
            if (checked < previous.size) append("；达到结果上限，未复核全部地址")
            append("；传输=").append(transport.name)
            append("；耗时=").append(elapsedMs).append(" ms")
        }

        RegTrailLog.d(
            "mem: refine ${kind.wire} → ${kept.size} 命中 " +
                "(checked=$checked, ${elapsedMs}ms, transport=${transport.name})",
        )
        return Scan(
            matches = kept.sortedBy { it.address },
            truncated = checked < previous.size,
            bytesRead = checked.toLong() * needle.size,
            regionsRead = checked,
            regionsSkipped = unreadable,
            totalRegions = previous.size,
            note = note,
            elapsedMs = elapsedMs,
            transport = transport.name,
            regionsKept = kept.size,
            pageFailures = unreadable,
        )
    }

    /**
     * Scans one buffer, reporting only naturally-aligned addresses for numeric
     * types.
     *
     * Alignment is what keeps an `int32` search from reporting the same value at
     * `addr`, `addr+1`, `addr+2` and `addr+3`; a JVM/ART field never starts
     * mid-word. Strings are exempt, because a `char[]`/`String` payload has no
     * alignment.
     *
     * @return true when [limit] was reached (the caller should stop).
     */
    private fun scanBuffer(
        data: ByteArray,
        baseAddress: Long,
        kind: ValueKind,
        needle: ByteArray,
        limit: Int,
        out: MutableList<Match>,
        validPages: BooleanArray? = null,
    ): Boolean {
        val size = needle.size
        if (size == 0 || data.size < size) return false

        val step = if (kind.numeric) size else 1
        var offset = 0
        if (kind.numeric) {
            val remainder = (baseAddress % size).toInt()
            if (remainder != 0) offset = size - remainder
        }

        val preview = ByteArray(size + CONTEXT_BYTES)
        while (offset + size <= data.size) {
            var hit = true
            for (k in 0 until size) {
                if (data[offset + k] != needle[k]) {
                    hit = false
                    break
                }
            }
            // A page that could not be read stays zero-filled, and a `0` needle
            // would otherwise "match" every byte of it. A hit therefore has to sit
            // entirely inside pages that were really read.
            if (hit && validPages != null) {
                if (!validPages[offset / PAGE_BYTES]) hit = false
                else if (!validPages[(offset + size - 1) / PAGE_BYTES]) hit = false
            }
            if (hit) {
                val contextLength = minOf(preview.size, data.size - offset)
                System.arraycopy(data, offset, preview, 0, contextLength)
                out += Match(
                    address = baseAddress + offset,
                    value = kind.decode(data.copyOfRange(offset, offset + size)),
                    preview = hex(preview, contextLength),
                    region = "",
                )
                if (out.size >= limit) return true
            }
            offset += step
        }
        return false
    }

    // -----------------------------------------------------------------------
    // Read / write
    // -----------------------------------------------------------------------

    /**
     * Reads [length] bytes at [address].
     *
     * Used by the bridge for a follow-up "what is the value now" query and by
     * [write] for its verification step.
     */
    fun read(address: Long, length: Int): ByteArray? {
        if (address <= 0L) return null
        val size = length.coerceIn(1, MAX_WRITE_BYTES)
        val transport = openTransport()
        return try {
            transport.read(address, size)
        } finally {
            transport.close()
        }
    }

    /**
     * Writes [rawValue] as [kind] at [address].
     *
     * A thin wrapper over [write] so the type-aware callers (the panel, the Agent)
     * do not each repeat the encoding.
     */
    fun write(address: Long, kind: ValueKind, rawValue: String): WriteOutcome {
        val bytes = kind.encode(rawValue)
            ?: throw MemException("无法把「$rawValue」解析为 ${kind.wire}")
        return write(address, bytes)
    }

    /**
     * Writes [bytes] at [address], remembering the bytes they replace.
     *
     * ## Steps, and why each one exists
     *  1. **Look the address up in `/proc/self/maps`.** A write into a
     *     non-writable mapping fails with `EIO` deep inside the kernel; checking
     *     first turns that into a sentence that says *why* ("该地址在只读代码段").
     *  2. **Read the old bytes** so 「还原」 is possible. A restore is the only
     *     safety net this feature can offer: the host app's state is otherwise
     *     impossible to roll back.
     *  3. **Write through `/proc/self/mem` opened `rw`.**
     *  4. **Read back and compare** ([WriteOutcome.verified]). A write that
     *     silently did nothing is the worst possible outcome for the user, and a
     *     `/proc/self/mem` write *can* be dropped by a copy-on-write page.
     *
     * The first original bytes for an address win: writing twice then reverting
     * must restore the value from before the *first* write.
     */
    fun write(address: Long, bytes: ByteArray): WriteOutcome {
        if (bytes.isEmpty() || bytes.size > MAX_WRITE_BYTES) throw MemException("写入内容过长")
        if (address <= 0L) throw MemException("地址无效")

        val region = runCatching { regions().firstOrNull { it.contains(address) } }.getOrNull()
            ?: throw MemException("地址 ${hexAddress(address)} 不在当前进程的任何映射内")
        if (!region.readable) throw MemException("地址所在区域不可读（${region.perms}）")
        if (!region.writable) {
            throw MemException(
                "地址所在区域不可写（${region.perms}，${region.label}）：" +
                    "内核会拒绝写入只读映射",
            )
        }

        val before = read(address, bytes.size)
            ?: throw MemException("无法读取地址 ${hexAddress(address)} 的原始内容")

        val transport = openWriteTransport()
        try {
            if (!transport.write(address, bytes)) {
                throw MemException(
                    "写入 ${hexAddress(address)} 失败：${transport.name} 拒绝了这次写",
                )
            }
        } finally {
            transport.close()
        }

        val after = read(address, bytes.size)
        val verified = after != null && after.contentEquals(bytes)

        // Remember the pristine bytes only once per address.
        originals.putIfAbsent(address, before)

        RegTrailLog.i(
            "mem: write ${hexAddress(address)} (${bytes.size} B) verified=$verified " +
                "transport=${transport.name}",
        )
        return WriteOutcome(
            address = address,
            written = bytes,
            original = before,
            verified = verified,
            region = region,
        )
    }

    /** Restores the bytes remembered for [address]. Returns the restored bytes. */
    fun revert(address: Long): ByteArray {
        val original = originals[address]
            ?: throw MemException("地址 ${hexAddress(address)} 没有记录过原始值，无法还原")
        val region = runCatching { regions().firstOrNull { it.contains(address) } }.getOrNull()
        if (region != null && !region.writable) {
            throw MemException("地址所在区域已变为不可写（${region.perms}），无法还原")
        }

        val transport = openWriteTransport()
        try {
            if (!transport.write(address, original)) {
                throw MemException("还原 ${hexAddress(address)} 失败：${transport.name} 拒绝了这次写")
            }
        } finally {
            transport.close()
        }

        val after = read(address, original.size)
        if (after == null || !after.contentEquals(original)) {
            throw MemException("还原 ${hexAddress(address)} 后回读不一致")
        }
        originals.remove(address)
        RegTrailLog.i("mem: revert ${hexAddress(address)} (${original.size} B)")
        return original
    }

    /** Restores every remembered address. Returns how many were restored. */
    fun revertAll(): Int {
        var count = 0
        for (address in originals.keys.toList()) {
            if (runCatching { revert(address) }.isSuccess) count++
        }
        return count
    }

    /** Addresses this process has written through the panel, and their state now. */
    fun modified(): List<Modified> = originals.entries
        .mapNotNull { entry ->
            runCatching {
                val current = read(entry.key, entry.value.size)
                Modified(
                    address = entry.key,
                    originalHex = hex(entry.value, entry.value.size),
                    currentHex = current?.let { hex(it, it.size) } ?: "?",
                    reverted = current != null && current.contentEquals(entry.value),
                )
            }.getOrNull()
        }
        .sortedBy { it.address }

    /** Forgets the remembered originals (used when the window detaches). */
    fun forgetOriginals() = originals.clear()

    val modifiedCount: Int get() = originals.size

    /**
     * First-write original bytes, keyed by address.
     *
     * `ConcurrentHashMap` rather than a synchronized map: reads (`modified()` on
     * the UI thread, `revert` from a bridge call) and writes (the agent on an IO
     * thread) can overlap and neither may ever block the host app's main thread.
     */
    private val originals = ConcurrentHashMap<Long, ByteArray>()

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Reads [want] bytes at [start], one 4 KiB page at a time.
     *
     * `readFully` over a whole region would give up at the first `EIO`, and a
     * mapping listed in `/proc/self/maps` really can contain a hole: the page can
     * be unmapped between the maps read and this read, and Android's Dalvik
     * mappings have guard pages inside them. Each page is therefore read
     * individually and a failure only costs that page — [ReadChunk.validPages] is
     * what keeps the zero-filled hole from producing false matches.
     */
    private fun readRegion(transport: MemTransport, start: Long, want: Long): ReadChunk? {
        if (want <= 0L) return null
        val size = want.toInt()
        val pageCount = (size + PAGE_BYTES - 1) / PAGE_BYTES
        val buffer = ByteArray(size)
        val valid = BooleanArray(pageCount)
        var failed = 0
        var lastGood = -1
        var offset = 0
        var page = 0
        while (page < pageCount) {
            val length = minOf(PAGE_BYTES, size - offset)
            val ok = runCatching {
                transport.readInto(start + offset, buffer, offset, length)
            }.getOrDefault(false)
            if (ok) {
                valid[page] = true
                lastGood = page
            } else {
                failed++
            }
            offset += length
            page++
        }
        if (lastGood < 0) return null

        // Drop the trailing run of unreadable pages: scanning zeros there would only
        // waste comparisons (and the validity map already blocks false hits).
        val endByte = minOf(size, (lastGood + 1) * PAGE_BYTES)
        return if (endByte == size) {
            ReadChunk(buffer, valid, failed)
        } else {
            ReadChunk(buffer.copyOf(endByte), valid.copyOf(lastGood + 1), failed)
        }
    }

    /** Bytes of one region, with the page-validity map [readRegion] built. */
    private class ReadChunk(
        val data: ByteArray,
        val validPages: BooleanArray,
        val failedPages: Int,
    )

    // -----------------------------------------------------------------------
    // Transport selection
    // -----------------------------------------------------------------------

    /**
     * Why `/proc/self/mem` could not be opened, once that is known.
     *
     * Cached so a scan does not pay for a failing `open()` on every call, and so the
     * log says it once instead of on every scan. A ROM does not gain the file back
     * while the process runs.
     */
    @Volatile
    private var procMemReadFailure: String? = null

    /** The read transport: `/proc/self/mem` when it opens, otherwise `Unsafe`. */
    private fun openTransport(): MemTransport {
        val known = procMemReadFailure
        if (known == null) {
            val reason = StringBuilder()
            ProcMemTransport.open(reason)?.let { return it }
            procMemReadFailure = reason.toString()
            RegTrailLog.w(
                "mem: /proc/self/mem 不可读（$reason），改用 sun.misc.Unsafe 传输",
            )
        }
        return UnsafeTransport.get() ?: throw MemException(
            "/proc/self/mem 无法打开（$known），且 sun.misc.Unsafe 不可用：" +
                (UnsafeTransport.unavailableReason() ?: "未知原因"),
        )
    }

    /** The write transport: `/proc/self/mem` opened `rw`, otherwise `Unsafe`. */
    private fun openWriteTransport(): MemTransport {
        val reason = StringBuilder()
        ProcMemTransport.openWrite(reason)?.let { return it }
        return UnsafeTransport.get() ?: throw MemException(
            "无法写入：/proc/self/mem 无法以读写方式打开（$reason），" +
                "且 sun.misc.Unsafe 不可用：" +
                (UnsafeTransport.unavailableReason() ?: "未知原因"),
        )
    }

    /**
     * A plain-text report of everything that decides whether the 内存 tab can work.
     *
     * This exists because "内存搜索不可用" is not a bug report: the answer lives in
     * four independent facts (does `maps` parse, does the filter keep anything, does
     * `/proc/self/mem` open for read and for write, and which transport survives),
     * and none of them are visible from the settings app. The 内存 tab's 「诊断」
     * button prints exactly this, and it is logged line by line so it survives in
     * the 日志 tab and in the framework log.
     */
    fun probeReport(): String {
        val out = StringBuilder()
        fun line(text: String) {
            out.append(text).append('\n')
        }

        line("=== RegTrail 内存诊断 ===")
        line("进程: ${processName()} pid=${android.os.Process.myPid()} " +
            "uid=${android.os.Process.myUid()}")
        line("SELinux: ${selinuxContext()}")
        line("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) " +
            "ABI=${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}")

        // 1) maps
        var all: List<Region> = emptyList()
        val mapsResult = runCatching { regions() }
        mapsResult.fold(
            onSuccess = { all = it },
            onFailure = { line("/proc/self/maps: 读取失败 — ${it.message}") },
        )
        if (all.isNotEmpty()) {
            val readable = all.count { it.readable }
            val writable = all.count { it.writable }
            line("/proc/self/maps: ${all.size} 个映射（可读 $readable / 可写 $writable）")
        }

        // 2) filter
        val keptAll = all.filter { it.isScannable(false) }
        val keptWritable = all.filter { it.isScannable(true) }
        line(
            "区域过滤: 全部 ${all.size} → 保留 ${keptAll.size}（不限可写）/ " +
                "${keptWritable.size}（只搜可写）",
        )
        val dropped = all.filter { !it.isScannable(false) }
        if (dropped.isNotEmpty()) {
            line("丢弃示例（共 ${dropped.size}）：")
            dropped.take(4).forEach { line("  - ${describeRegionDropped(it)}") }
        }
        if (keptAll.isNotEmpty()) {
            line("保留示例（共 ${keptAll.size}）：")
            keptAll.take(4).forEach {
                line("  - ${hexAddress(it.start)}-${hexAddress(it.end)} ${it.perms} ${it.label}")
            }
        }

        // 3) /proc/self/mem
        val readReason = StringBuilder()
        val readHandle = ProcMemTransport.open(readReason)
        line(
            "/proc/self/mem 打开(读): " +
                if (readHandle != null) "成功" else "失败 — $readReason",
        )
        val writeReason = StringBuilder()
        val writeHandle = ProcMemTransport.openWrite(writeReason)
        line(
            "/proc/self/mem 打开(读写): " +
                if (writeHandle != null) "成功" else "失败 — $writeReason",
        )

        // 4) one sample page read through every available transport
        val sample = keptAll.minByOrNull { it.size } ?: all.firstOrNull()
        val sampleLength = sample?.let { minOf(PAGE_BYTES.toLong(), it.size).toInt() } ?: PAGE_BYTES
        if (sample != null) {
            line(
                "样例区域: ${hexAddress(sample.start)}-${hexAddress(sample.end)} " +
                    "${sample.perms} ${sample.label}",
            )
            val procResult = readHandle?.read(sample.start, sampleLength)
            line(
                "  样例读取(proc-mem): " +
                    if (procResult != null) "成功 ${hex(procResult, 16)}" else "失败",
            )
            val unsafe = UnsafeTransport.get()
            if (unsafe == null) {
                line("  样例读取(unsafe): 不可用 — ${UnsafeTransport.unavailableReason() ?: "?"}")
            } else {
                val unsafeResult = runCatching { unsafe.read(sample.start, sampleLength) }.getOrNull()
                line(
                    "  样例读取(unsafe): " +
                        if (unsafeResult != null) {
                            "成功 ${hex(unsafeResult, 16)}"
                        } else {
                            "失败（所有读取方法都无法访问该页）"
                        },
                )
                line("  unsafe 实现: ${unsafe.describe()}")
            }
        } else {
            line("样例区域: 没有可用的映射")
        }

        // 5) the decision
        val chosen = when {
            readHandle != null && UnsafeTransport.get() != null -> "proc-mem（unsafe 也可用）"
            readHandle != null -> "proc-mem"
            UnsafeTransport.get() != null -> "unsafe（/proc/self/mem 被拒绝，已自动降级）"
            else -> "无可用传输：内存搜索与写入都不可用"
        }
        line("传输: $chosen")
        if (readHandle == null) {
            line(
                "说明: 本 ROM 的应用域不允许打开 /proc/self/mem（open 即返回 EACCES），" +
                    "这是系统限制，不是模块权限问题。Unsafe 传输直接读写本进程地址空间，" +
                    "不需要打开任何文件；扫描按 4 KiB 页读取，且只读 /proc/self/maps 报告的区间。",
            )
        }
        line("=== 诊断结束 ===")

        runCatching { readHandle?.close() }
        runCatching { writeHandle?.close() }
        return out.toString()
    }

    /** `/proc/self/maps` label plus why the filter rejected it. */
    private fun describeRegionDropped(region: Region): String {
        val label = region.label
        val lower = label.lowercase()
        val why = when {
            !region.readable -> "不可读（${region.perms}）"
            lower.endsWith(".so") -> "共享库"
            lower.startsWith("/memfd:") -> "memfd（JIT 缓存）"
            lower.startsWith("/data/dalvik-cache/") -> "dalvik-cache"
            lower.startsWith("/system/") || lower.startsWith("/apex/") ||
                lower.startsWith("/vendor/") || lower.startsWith("/product/") ||
                lower.startsWith("/system_ext/") || lower.startsWith("/odm/") -> "系统镜像"
            lower.startsWith("/dev/") -> "设备映射"
            lower.startsWith("[vvar") || lower.startsWith("[vdso") ||
                lower.startsWith("[vsyscall") || lower.startsWith("[vectors") -> "内核特殊段"
            else -> "其他"
        }
        return "${hexAddress(region.start)}-${hexAddress(region.end)} " +
            "${region.perms} $label — $why"
    }

    /** `/proc/self/cmdline` first token; the process name as the kernel sees it. */
    private fun processName(): String = runCatching {
        File("/proc/self/cmdline").readBytes()
            .takeWhile { it != 0.toByte() }
            .toByteArray()
            .toString(Charsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "?"

    /** The SELinux domain this process runs in; useful to compare across ROMs. */
    private fun selinuxContext(): String = runCatching {
        File("/proc/self/attr/current").readText().trim().trimEnd('\u0000')
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "不可读"

    /** `0x7f12345000`. */
    fun hexAddress(address: Long): String = "0x" + java.lang.Long.toHexString(address)

    /**
     * Parses an address the user or the model typed.
     *
     * Accepts `0x`-prefixed hex (what the UI displays), bare hex (what a dump
     * prints) and plain decimal. Bare hex is the ambiguous case: `123456` is read
     * as hex because a real address is never that small, and an address that small
     * would be rejected anyway.
     */
    fun parseAddress(raw: String?): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val body = text.removePrefix("0x").removePrefix("0X")
        val parsed = body.toLongOrNull(16) ?: return null
        return if (parsed > 0L) parsed else null
    }

    private fun hex(bytes: ByteArray, length: Int): String {
        if (length <= 0) return ""
        val builder = StringBuilder(length * 3)
        for (i in 0 until minOf(length, bytes.size)) {
            if (i > 0) builder.append(' ')
            val value = bytes[i].toInt() and 0xFF
            builder.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return builder.toString()
    }

    private fun mib(bytes: Long): String =
        if (bytes < 1024L * 1024L) "${bytes / 1024} KiB" else "${bytes / (1024L * 1024L)} MiB"

    private const val HEX = "0123456789abcdef"

    /** Little-endian scratch buffer, shared by every encoder. */
    private inline fun le(size: Int, body: ByteBuffer.() -> Unit): ByteArray {
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.body()
        return buffer.array()
    }

    /** Accepts decimal and `0x`-prefixed hex for an `int32`. */
    private fun parseIntText(raw: String): Int? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        val negative = text.startsWith("-")
        if (negative || text.startsWith("+")) text = text.substring(1).trim()
        if (text.startsWith("0x", true)) {
            val magnitude = text.substring(2).toLongOrNull(16) ?: return null
            if (magnitude > 0xFFFF_FFFFL) return null
            val wrapped = magnitude.toInt()
            return if (negative) -wrapped else wrapped
        }
        return (if (negative) "-$text" else text).toIntOrNull()
    }

    /** Accepts decimal and `0x`-prefixed hex for an `int64`. */
    private fun parseLongText(raw: String): Long? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        val negative = text.startsWith("-")
        if (negative || text.startsWith("+")) text = text.substring(1).trim()
        if (text.startsWith("0x", true)) {
            val magnitude = text.substring(2).toLongOrNull(16) ?: return null
            return if (negative) -magnitude else magnitude
        }
        return (if (negative) "-$text" else text).toLongOrNull()
    }
}
