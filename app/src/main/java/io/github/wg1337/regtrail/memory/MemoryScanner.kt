package io.github.wg1337.regtrail.memory

import android.util.Base64
import com.topjohnwu.superuser.Shell
import io.github.wg1337.regtrail.shared.bridge.SharedPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * Root-side memory search / patch for *another* process.
 *
 * ## Why every access goes through a root shell
 * Since Android 7 an unprivileged app cannot read another app's `/proc/<pid>/maps`
 * or `/proc/<pid>/mem`: both are gated on `ptrace_may_access()`, so a plain
 * `FileInputStream` fails with `EACCES` no matter what the file mode says. A
 * process holding `CAP_SYS_PTRACE` (i.e. the `su` shell) can, which is why the
 * bytes are fetched by `su` and handed back either as small shell output (`maps`)
 * or as files under [SharedPaths.OUT_DIR], which the app can then read directly
 * (that directory is already staged world-readable by `ConfigPublisher`).
 *
 * ## Operator, not worker
 * The shell never decides anything. Regions are selected here in Kotlin, so the
 * generated script is a flat list of `dd` invocations — no `awk` that has to be
 * re-read to be trusted, and no ROM-specific `ps`/`grep` output to parse.
 *
 * ## Protocol
 * - `listProcesses`: one loop printing `pid<TAB>uid<TAB>name`.
 * - `regions`: `head -c 2 MiB /proc/<pid>/maps`, parsed here.
 * - `scan` / `refine` / `read`: `dd if=/proc/<pid>/mem of=mem_<token>_<n>.bin`
 *   and the *file length* is authoritative ("this many bytes were really
 *   handed over"). That removes the ambiguity a single concatenated dump would
 *   have between a hole and a page the kernel refused.
 * - `write`: `printf` (octal escapes, never a quoting hazard) piped into
 *   `dd of=/proc/<pid>/mem`, then the same bytes are read back into
 *   `..._verify.bin` for the round-trip check.
 *
 * `dd` is always called with page-sized blocks for bulk reads, because
 * `bs`/`skip`/`count` are POSIX while `iflag=skip_bytes` is an extension some
 * toybox builds lack. Single-value reads/writes use `bs=1` and are tiny.
 *
 * ## Limits (all deliberate)
 * - 64 MiB per scan, 8 MiB per region: a game's `[anon:dalvik-main space]` can
 *   be a 256 MiB reservation, and pulling that through `dd` per scan would make
 *   the tool unusable.
 * - 1500 regions per scan, **smallest first**: Android's allocators hand out the
 *   low end of an arena first, so the bytes that hold a coin count are far more
 *   likely to be in a small region than in the tail of a giant one.
 * - 500 matches by default. Alignment is enforced for numeric types, so an
 *   `int` search does not report four overlapping hits per value.
 * - Every public entry point is bounded by a timeout: a wedged `su` can never
 *   hang the agent.
 */
internal class MemoryScanner {

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** All processes visible in `/proc`, sorted by pid. */
    suspend fun listProcesses(): List<ProcEntry> = guard("列出进程", LIST_TIMEOUT_MS) {
        listProcessesBlocking()
    }

    /** Memory map of [pid]. Readable regions only unless [readableOnly] is false. */
    suspend fun regions(pid: Int, readableOnly: Boolean = true): List<MemoryRegion> =
        guard("读取进程 $pid 的内存区域", REGION_TIMEOUT_MS) {
            fetchRegions(pid).filter { !readableOnly || it.isReadable }
        }

    /**
     * Searches readable, non-shared-library memory of [pid] for [query].
     *
     * The result carries the matches *and* how much was actually covered, because
     * "no match" and "no match in the 64 MiB we could afford" are different
     * answers and the caller must be able to tell them apart.
     */
    suspend fun scan(
        pid: Int,
        query: ScanQuery,
        maxResults: Int = DEFAULT_MAX_RESULTS,
    ): ScanOutcome = guard("搜索进程 $pid 的内存", SCAN_TIMEOUT_MS) {
        scanBlocking(pid, query, maxResults)
    }

    /**
     * The "next scan": re-reads only the addresses in [previous] and keeps the
     * ones whose value still equals [query].
     */
    suspend fun refine(
        pid: Int,
        previous: List<MemoryMatch>,
        query: ScanQuery,
    ): ScanOutcome = guard("复核进程 $pid 的内存", REFINE_TIMEOUT_MS) {
        refineBlocking(pid, previous, query)
    }

    /**
     * Writes [bytes] at [address] through `/proc/<pid>/mem`.
     *
     * @return true when the read-back matches exactly. A read-back that cannot be
     *   performed at all throws instead: "wrote and could not verify" must not be
     *   reported as "wrote".
     */
    suspend fun write(pid: Int, address: Long, bytes: ByteArray): Boolean =
        guard("写入进程 $pid 的内存", WRITE_TIMEOUT_MS) {
            writeBlocking(pid, address, bytes)
        }

    /**
     * Reads [size] bytes at [address]. Used to report what a failed [write] left
     * behind; not part of the search flow.
     */
    suspend fun read(pid: Int, address: Long, size: Int): ByteArray =
        guard("读取进程 $pid 的内存", READ_TIMEOUT_MS) {
            readBlocking(pid, address, size)
        }

    // -----------------------------------------------------------------------
    // Process list
    // -----------------------------------------------------------------------

    /**
     * Lists `/proc` children with their uid and command line.
     *
     * `cmdline` is NUL-separated and empty for kernel threads and zombies, hence
     * the `comm` fallback. `tr` converts the separators because a NUL cannot
     * survive command substitution; if `tr` were missing the empty result would
     * fall through to `comm` rather than producing a broken line.
     */
    private fun listProcessesBlocking(): List<ProcEntry> {
        val script = listOf(
            "for d in /proc/[0-9]*; do",
            "p=\${d#/proc/}",
            "u=\$(awk '/^Uid:/{print \$2; exit}' \"\$d/status\" 2>/dev/null)",
            "[ -n \"\$u\" ] || continue",
            "n=\$(tr '\\000' ' ' < \"\$d/cmdline\" 2>/dev/null)",
            "[ -n \"\$n\" ] || n=\$(cat \"\$d/comm\" 2>/dev/null)",
            "printf '%s\\t%s\\t%s\\n' \"\$p\" \"\$u\" \"\$n\"",
            "done",
        )
        val result = exec(script, "list")

        val entries = ArrayList<ProcEntry>()
        for (line in result.out) {
            val parts = line.split('\t')
            if (parts.size < 3) continue
            val pid = parts[0].trim().toIntOrNull() ?: continue
            val uid = parts[1].trim().toIntOrNull() ?: continue
            if (pid <= 0) continue
            val name = parts.subList(2, parts.size).joinToString("\t")
                .replace('\u0000', ' ')
                .trim()
                .take(96)
            entries += ProcEntry(pid, uid, name.ifEmpty { "?" })
        }

        if (entries.isEmpty()) {
            throw MemoryException(
                "读取 /proc 失败：" + describeShellFailure(result),
            )
        }
        return entries.sortedBy { it.pid }
    }

    // -----------------------------------------------------------------------
    // Regions
    // -----------------------------------------------------------------------

    private fun fetchRegions(pid: Int): List<MemoryRegion> {
        requirePid(pid)
        // Two lines, one round trip: the marker replaces a failing `cat`, because
        // `exit` would kill libsu's persistent shell.
        val script = listOf(
            "if [ -d /proc/$pid ]; then " +
                "head -c 2097152 /proc/$pid/maps 2>/dev/null; else echo $NO_PROC_MARKER; fi",
        )
        val result = exec(script, "maps")

        if (result.out.any { it.trim() == NO_PROC_MARKER }) {
            throw MemoryException("进程 $pid 不存在（/proc/$pid 已经消失，目标应用可能已退出）")
        }

        val regions = result.out.mapNotNull { parseMapLine(it) }
        if (regions.isEmpty()) {
            throw MemoryException(
                "读取 /proc/$pid/maps 失败：" + describeShellFailure(result),
            )
        }
        return regions
    }

    private fun parseMapLine(line: String): MemoryRegion? {
        val match = MAP_LINE.find(line.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull(16) ?: return null
        val end = match.groupValues[2].toLongOrNull(16) ?: return null
        if (end <= start) return null
        return MemoryRegion(
            start = start,
            end = end,
            permissions = match.groupValues[3],
            path = match.groupValues[7].trim(),
        )
    }

    /**
     * True for regions worth searching.
     *
     * Shared libraries, the framework's compiled code and device mappings are
     * excluded: they are enormous, usually read-only and never hold an app's
     * counters. Anonymous mappings (`[anon:…]`, heap, stack) and the app's own
     * file mappings are kept.
     */
    private fun MemoryRegion.isScannable(): Boolean {
        if (!isReadable || size <= 0L) return false
        val label = path
        if (label.isEmpty()) return true

        val lower = label.lowercase()
        if (lower.startsWith("[vvar") || lower.startsWith("[vsyscall") || lower.startsWith("[vectors")) {
            return false
        }
        if (lower.startsWith("/dev/") || lower.contains("dmabuf") || lower.contains("/kgsl")) {
            return false
        }
        if (lower.startsWith("/system/") || lower.startsWith("/apex/") || lower.startsWith("/vendor/") ||
            lower.startsWith("/product/") || lower.startsWith("/odm/") || lower.startsWith("/data/app/") ||
            lower.startsWith("/data/dalvik-cache/") || lower.startsWith("/memfd:")
        ) {
            return false
        }
        return !(lower.endsWith(".so") || lower.endsWith(".odex") || lower.endsWith(".vdex") ||
            lower.endsWith(".oat") || lower.endsWith(".art") || lower.endsWith(".apk") ||
            lower.endsWith(".jar") || lower.endsWith(".dex"))
    }

    // -----------------------------------------------------------------------
    // Scan
    // -----------------------------------------------------------------------

    private fun scanBlocking(pid: Int, query: ScanQuery, maxResults: Int): ScanOutcome {
        requirePid(pid)
        val needle = query.bytes
        if (needle.isEmpty()) throw MemoryException("搜索值不能为空")
        val limit = maxResults.coerceIn(1, MAX_MATCHES_HARD)

        val all = fetchRegions(pid)
        val scannable = all.filter { it.isScannable() }
        if (scannable.isEmpty()) {
            throw MemoryException(
                "进程 $pid 没有可扫描的可读内存区域" +
                    "（共 ${all.size} 个区域，可能全部是共享库、设备映射或不可读）",
            )
        }

        val token = nextToken()
        val errPath = "${SharedPaths.OUT_DIR}/mem_${token}.err"
        val dumps = ArrayList<RegionDump>()
        var truncated = false
        var planned = 0L

        // Budget first, then the script: a scan may read at most MAX_TOTAL_BYTES
        // and spawn at most MAX_REGIONS_PER_SCAN `dd` processes. Walking the
        // size-ascending list here (instead of cutting to N regions up front)
        // means a process with thousands of 4 KiB mappings still spends the byte
        // budget on as many of them as possible.
        for (region in scannable.sortedWith(compareBy({ it.size }, { it.start }))) {
            if (dumps.size >= MAX_REGIONS_PER_SCAN) {
                truncated = true
                break
            }
            val left = MAX_TOTAL_BYTES - planned
            if (left < PAGE_SIZE) {
                truncated = true
                break
            }
            val lead = (region.start % PAGE_SIZE).toInt()
            val want = minOf(region.size, REGION_BYTES_CAP, left)
            if (want <= 0L) continue
            if (want < region.size) truncated = true

            val blocks = (lead + want + PAGE_SIZE - 1) / PAGE_SIZE
            if (blocks <= 0L) continue

            dumps += RegionDump(
                region = region,
                file = File(SharedPaths.OUT_DIR, "mem_${token}_${dumps.size}.bin"),
                lead = lead,
                blocks = blocks,
            )
            planned += blocks * PAGE_SIZE
        }

        if (dumps.isEmpty()) {
            throw MemoryException("没有可读取的内存区域（进程 $pid 的地址空间可能在扫描期间发生了变化）")
        }

        val script = ArrayList<String>(preamble(errPath))
        for (dump in dumps) {
            script += "\$DD if=/proc/$pid/mem of=${dump.file.absolutePath} bs=$PAGE_SIZE " +
                "skip=${dump.region.start / PAGE_SIZE} count=${dump.blocks} 2>>$errPath"
        }
        script += "chmod 644 ${SharedPaths.OUT_DIR}/mem_${token}_*.bin 2>/dev/null"

        val result = exec(script, "scan")
        val shellErr = result.err.joinToString("\n")

        val matches = ArrayList<MemoryMatch>()
        var bytesRead = 0L
        var regionsRead = 0
        var emptyFiles = 0
        val readFailures = ArrayList<String>()
        var limitReached = false

        for (dump in dumps) {
            val length = runCatching { dump.file.length() }.getOrDefault(0L)
            if (length <= 0L) {
                emptyFiles++
                continue
            }
            val available = length - dump.lead
            val usable = minOf(available, dump.region.size)
            if (usable <= 0L) {
                emptyFiles++
                continue
            }

            val data = try {
                readPortion(dump.file, dump.lead.toLong(), usable.toInt())
            } catch (t: Throwable) {
                readFailures += "${dump.file.name}: ${t.message ?: t::class.java.simpleName}"
                continue
            }
            if (data == null || data.isEmpty()) {
                emptyFiles++
                continue
            }

            regionsRead++
            bytesRead += data.size
            if (scanBuffer(data, dump.region.start, dump.region, query, limit, matches)) {
                limitReached = true
                break
            }
        }

        val errText = readTextFile(errPath)
        if (regionsRead == 0) {
            throw MemoryException(
                buildString {
                    append("无法从 /proc/$pid/mem 读取任何数据。")
                    when {
                        readFailures.isNotEmpty() ->
                            append("应用读不到 shell 写出的临时文件（").append(readFailures.first()).append("）")
                        errText.isNotEmpty() ->
                            append("dd 报错：").append(firstLines(errText, 3))
                        shellErr.isNotBlank() ->
                            append("shell 报错：").append(firstLines(shellErr, 3))
                        else ->
                            append("dd 没有产出任何字节（地址空间已变化或进程已退出）")
                    }
                    append("。若报错为 Permission denied / EPERM，说明 su 所在 SELinux 域不允许 ptrace 该进程。")
                },
            )
        }

        val notes = ArrayList<String>()
        if (errText.isNotEmpty()) notes += "部分区域读取失败（dd）：" + firstLines(errText, 2)
        if (shellErr.isNotBlank()) notes += "shell：" + firstLines(shellErr, 2)
        if (readFailures.isNotEmpty()) {
            notes += "应用读不到 ${readFailures.size} 个临时文件（" + readFailures.first() + "）"
        }
        if (emptyFiles > 0) notes += "$emptyFiles 个区域没有读到数据"
        if (limitReached) notes += "命中数已达上限 $limit，提前停止（结果可能不全）"
        if (truncated) {
            notes += "读取范围被截断：单次最多 ${humanBytes(MAX_TOTAL_BYTES)}、" +
                "单区域最多 ${humanBytes(REGION_BYTES_CAP)}、最多 $MAX_REGIONS_PER_SCAN 个区域"
        }

        return ScanOutcome(
            matches = matches.sortedBy { it.address },
            truncated = truncated || limitReached,
            bytesRead = bytesRead,
            regionsRead = regionsRead,
            notes = notes,
        )
    }

    /**
     * Scans one buffer, reporting only addresses aligned to the value size for
     * the numeric types. Alignment is what keeps an `int` search from reporting
     * the same value at `addr`, `addr+1`, `addr+2` and `addr+3`.
     *
     * @return true when [limit] was reached (the caller should stop).
     */
    private fun scanBuffer(
        data: ByteArray,
        baseAddress: Long,
        region: MemoryRegion,
        query: ScanQuery,
        limit: Int,
        out: MutableList<MemoryMatch>,
    ): Boolean {
        val needle = query.bytes
        val size = needle.size
        if (size == 0 || data.size < size) return false

        val step = if (query.align) size else 1
        var offset = 0
        if (query.align) {
            val remainder = (baseAddress % size).toInt()
            if (remainder != 0) offset = size - remainder
        }

        while (offset + size <= data.size) {
            var hit = true
            for (k in 0 until size) {
                if (data[offset + k] != needle[k]) {
                    hit = false
                    break
                }
            }
            if (hit) {
                out += MemoryMatch(
                    address = baseAddress + offset,
                    size = size,
                    preview = hexBytes(regionBytes(data, offset)),
                    region = region.label,
                )
                if (out.size >= limit) return true
            }
            offset += step
        }
        return false
    }

    private fun regionBytes(data: ByteArray, offset: Int): ByteArray {
        val end = minOf(offset + CONTEXT_BYTES, data.size)
        return data.copyOfRange(offset, end)
    }

    // -----------------------------------------------------------------------
    // Refine (second / next scan)
    // -----------------------------------------------------------------------

    /**
     * Re-reads the candidate addresses page by page.
     *
     * Pages (not addresses) are the unit of work: five hundred candidates
     * typically collapse into a few dozen pages, and consecutive pages are
     * coalesced into one `dd` each. The whole operation is still one `su` job.
     */
    private fun refineBlocking(
        pid: Int,
        previous: List<MemoryMatch>,
        query: ScanQuery,
    ): ScanOutcome {
        requirePid(pid)
        val size = query.size
        if (size <= 0) throw MemoryException("搜索值不能为空")
        if (previous.isEmpty()) {
            return ScanOutcome(emptyList(), false, 0L, 0, listOf("没有可复核的地址"))
        }

        val candidates = previous.filter { it.address > 0L }.take(MAX_MATCHES_HARD)
        var truncated = candidates.size < previous.size

        val pages = sortedSetOf<Long>()
        for (match in candidates) {
            val page = pageStart(match.address)
            pages += page
            if ((match.address - page) + size > PAGE_SIZE) pages += page + PAGE_SIZE
        }
        if (pages.isEmpty()) throw MemoryException("没有可复核的有效地址")

        val runs = ArrayList<PageRun>()
        var runStart = -1L
        var runPages = 0L
        for (page in pages) {
            when {
                runStart < 0L -> {
                    runStart = page
                    runPages = 1L
                }
                page == runStart + runPages * PAGE_SIZE -> runPages++
                else -> {
                    runs += PageRun(runStart, runPages)
                    runStart = page
                    runPages = 1L
                }
            }
        }
        if (runStart >= 0L) runs += PageRun(runStart, runPages)
        if (runs.size > MAX_PAGE_RUNS) {
            truncated = true
            runs.subList(MAX_PAGE_RUNS, runs.size).clear()
        }

        val token = nextToken()
        val errPath = "${SharedPaths.OUT_DIR}/mem_${token}.err"
        val script = ArrayList<String>(preamble(errPath))
        val files = ArrayList<File>(runs.size)
        for ((index, run) in runs.withIndex()) {
            val file = File(SharedPaths.OUT_DIR, "mem_${token}_r$index.bin")
            script += "\$DD if=/proc/$pid/mem of=${file.absolutePath} bs=$PAGE_SIZE " +
                "skip=${run.startPage / PAGE_SIZE} count=${run.pageCount} 2>>$errPath"
            files += file
        }
        script += "chmod 644 ${SharedPaths.OUT_DIR}/mem_${token}_r*.bin 2>/dev/null"

        val result = exec(script, "refine")
        val errText = readTextFile(errPath)

        val kept = ArrayList<MemoryMatch>()
        var checked = 0
        var unreadable = 0
        var bytesRead = 0L

        for (match in candidates) {
            val runIndex = indexOfRun(runs, pageStart(match.address))
            if (runIndex < 0) {
                unreadable++
                continue
            }
            val run = runs[runIndex]
            val offset = match.address - run.startPage
            val actual = try {
                readPortion(files[runIndex], offset, size)
            } catch (_: Throwable) {
                null
            }
            checked++
            if (actual == null || actual.size != size) {
                unreadable++
                continue
            }
            bytesRead += size
            if (actual.contentEquals(query.bytes)) {
                kept += MemoryMatch(
                    address = match.address,
                    size = size,
                    preview = hexBytes(actual),
                    region = match.region,
                )
            }
        }

        val notes = ArrayList<String>()
        if (errText.isNotEmpty()) notes += "部分页读取失败（dd）：" + firstLines(errText, 2)
        if (result.err.isNotEmpty()) {
            notes += "shell：" + firstLines(result.err.joinToString("\n"), 2)
        }
        if (truncated) {
            notes += "复核范围被截断（地址数上限 $MAX_MATCHES_HARD，页段上限 $MAX_PAGE_RUNS）"
        }
        if (unreadable > 0) notes += "$unreadable 个地址读不到（页面已释放或进程已退出）"
        if (checked == 0) {
            throw MemoryException(
                "无法复核任何地址：" + if (errText.isNotEmpty()) {
                    firstLines(errText, 3)
                } else {
                    "临时文件缺失或应用无权限读取 ${SharedPaths.OUT_DIR}"
                },
            )
        }

        return ScanOutcome(
            matches = kept,
            truncated = truncated,
            bytesRead = bytesRead,
            regionsRead = runs.size,
            notes = notes,
        )
    }

    private fun indexOfRun(runs: List<PageRun>, page: Long): Int {
        for (index in runs.indices) {
            val run = runs[index]
            if (page >= run.startPage && page < run.startPage + run.pageCount * PAGE_SIZE) return index
        }
        return -1
    }

    // -----------------------------------------------------------------------
    // Write / read one value
    // -----------------------------------------------------------------------

    /**
     * Writes one value and reads it back.
     *
     * ## Why the payload travels as base64
     * Most values contain NUL bytes (`100` as `int32` is `64 00 00 00`), and a
     * NUL cannot be handed to the shell through a command line without an
     * escape sequence whose exact behaviour differs between the mksh builtin,
     * toybox `printf` and busybox. Base64 is ASCII, so the payload crosses the
     * shell as plain text and is decoded by the same
     * `base64 -d || toybox base64 -d` chain this project already uses to stage
     * its config snapshot. A decoder that is missing leaves an empty payload,
     * which is reported instead of silently writing nothing.
     */
    private fun writeBlocking(pid: Int, address: Long, bytes: ByteArray): Boolean {
        requirePid(pid)
        if (address < PAGE_SIZE) throw MemoryException("地址无效：${formatMemoryAddress(address)}")
        if (bytes.isEmpty()) throw MemoryException("要写入的字节为空")
        if (bytes.size > MAX_WRITE_BYTES) {
            throw MemoryException("单次最多写入 $MAX_WRITE_BYTES 字节，当前 ${bytes.size} 字节")
        }

        val token = nextToken()
        val dir = SharedPaths.OUT_DIR
        val errPath = "$dir/mem_${token}_w.err"
        val payloadPath = "$dir/mem_${token}_w.bin"
        val payloadB64Path = "$dir/mem_${token}_w.b64"
        val verifyPath = "$dir/mem_${token}_verify.bin"
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

        val script = ArrayList<String>(preamble(errPath))
        script += "printf '%s' '$b64' > $payloadB64Path 2>>$errPath"
        script += "base64 -d $payloadB64Path > $payloadPath 2>/dev/null || " +
            "toybox base64 -d $payloadB64Path > $payloadPath 2>/dev/null"
        script += "chmod 644 $payloadPath $payloadB64Path 2>/dev/null"
        script += "if [ -s $payloadPath ]; then \$DD if=$payloadPath of=/proc/$pid/mem bs=1 " +
            "seek=$address conv=notrunc 2>>$errPath && echo $WRITE_OK_MARKER || echo $WRITE_FAIL_MARKER; " +
            "else echo $PAYLOAD_FAIL_MARKER; fi"
        script += "\$DD if=/proc/$pid/mem of=$verifyPath bs=1 skip=$address " +
            "count=${bytes.size} 2>>$errPath"
        script += "chmod 644 $verifyPath 2>/dev/null"
        script += "rm -f $payloadB64Path 2>/dev/null"

        val result = exec(script, "write")
        val errText = readTextFile(errPath)
        val wrote = result.out.any { it.trim() == WRITE_OK_MARKER }
        val payloadMissing = result.out.any { it.trim() == PAYLOAD_FAIL_MARKER }

        if (!wrote) {
            val reason = when {
                payloadMissing -> "base64 解码失败（设备上没有可用的 base64 解码器）"
                errText.isNotEmpty() -> firstLines(errText, 3)
                else -> "dd 返回非零（地址未映射或该页不可写）"
            }
            throw MemoryException("写入 /proc/$pid/mem 失败：$reason")
        }

        val readBack = runCatching { File(verifyPath).readBytes() }.getOrNull()
        if (readBack == null || readBack.isEmpty()) {
            throw MemoryException(
                "写入已执行，但无法回读 ${formatMemoryAddress(address)} 校验：" +
                    if (errText.isNotEmpty()) {
                        firstLines(errText, 3)
                    } else {
                        "临时文件 $verifyPath 不存在或应用无法读取"
                    },
            )
        }
        return readBack.contentEquals(bytes)
    }

    private fun readBlocking(pid: Int, address: Long, size: Int): ByteArray {
        requirePid(pid)
        if (address < PAGE_SIZE) throw MemoryException("地址无效：${formatMemoryAddress(address)}")
        val count = size.coerceIn(1, MAX_WRITE_BYTES)

        val token = nextToken()
        val errPath = "${SharedPaths.OUT_DIR}/mem_${token}_r.err"
        val path = "${SharedPaths.OUT_DIR}/mem_${token}_read.bin"

        val script = ArrayList<String>(preamble(errPath))
        script += "\$DD if=/proc/$pid/mem of=$path bs=1 skip=$address count=$count 2>>$errPath"
        script += "chmod 644 $path 2>/dev/null"

        exec(script, "read")
        val errText = readTextFile(errPath)
        val data = runCatching { File(path).readBytes() }.getOrNull()
        if (data == null || data.isEmpty()) {
            throw MemoryException(
                "读取 ${formatMemoryAddress(address)} 失败：" + if (errText.isNotEmpty()) {
                    firstLines(errText, 3)
                } else {
                    "临时文件 $path 不存在或应用无法读取"
                },
            )
        }
        return data
    }

    // -----------------------------------------------------------------------
    // Shell plumbing
    // -----------------------------------------------------------------------

    /**
     * Runs a blocking operation on [Dispatchers.IO] and gives up after
     * [timeoutMs].
     *
     * `su` communication is blocking by nature (libsu writes a script and waits
     * for the shell prompt), so a cancellation cannot interrupt it. The work is
     * therefore started in its own scope and merely *awaited* with a timeout:
     * the caller always returns in bounded time even if the shell never answers.
     * The orphaned job finishes on its own later.
     */
    private suspend fun <T : Any> guard(label: String, timeoutMs: Long, block: () -> T): T =
        withContext(Dispatchers.IO) {
            val worker = CoroutineScope(Dispatchers.IO).async { block() }
            val value = withTimeoutOrNull(timeoutMs) { worker.await() }
            if (value == null) {
                worker.cancel()
                throw MemoryException(
                    "$label 超时（${timeoutMs / 1000} 秒）：su 没有响应，或目标进程的地址空间过大",
                )
            }
            value
        }

    /**
     * Returns a shell that is *definitely* root.
     *
     * The cached shell is reused when it is already root, so this cannot disturb
     * a terminal session that owns it. Otherwise a fresh shell is built — the
     * non-root one is left alone on purpose (closing it would kill whatever else
     * is using it, and `ConfigPublisher` already covers the rebuild-after-grant
     * case for its own path).
     */
    private fun rootShellOrThrow(): Shell {
        val cached = rootShell
        if (cached != null && cached.isRoot && cached.isAlive) return cached

        val shared = runCatching { Shell.getShell() }.getOrNull()
        if (shared != null && shared.isRoot && shared.isAlive) {
            rootShell = shared
            return shared
        }

        synchronized(shellLock) {
            val again = rootShell
            if (again != null && again.isRoot && again.isAlive) return again

            val fresh = runCatching {
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER)
                    .setTimeout(20)
                    .build()
            }.getOrNull()

            if (fresh == null || !fresh.isRoot || !fresh.isAlive) {
                throw MemoryException(
                    "无法获取 Root shell：su 被拒绝或不可用（内存读写必须经过 root shell）",
                )
            }
            rootShell = fresh
            return fresh
        }
    }

    private fun exec(script: List<String>, tag: String): Shell.Result {
        val shell = rootShellOrThrow()
        return runCatching { shell.newJob().add(*script.toTypedArray()).exec() }
            .getOrElse { cause ->
                throw MemoryException(
                    "Root 命令执行失败（$tag）：" + (cause.message ?: cause::class.java.simpleName),
                )
            }
    }

    /**
     * Common first lines: directory permissions plus a `dd` that is looked up
     * once, because a ROM without the toybox `dd` applet only has it behind
     * `toybox`.
     *
     * `chmod 755` on the staging directory is what makes the output readable by
     * this (unprivileged) app without another root round trip.
     */
    private fun preamble(errPath: String): List<String> = listOf(
        "mkdir -p ${SharedPaths.BASE_DIR} ${SharedPaths.OUT_DIR} 2>/dev/null",
        "chmod 755 ${SharedPaths.BASE_DIR} 2>/dev/null",
        "chmod 755 ${SharedPaths.OUT_DIR} 2>/dev/null",
        "rm -f ${SharedPaths.OUT_DIR}/mem_*.bin ${SharedPaths.OUT_DIR}/mem_*.err 2>/dev/null",
        "if command -v dd >/dev/null 2>&1; then DD=dd; else DD='toybox dd'; fi",
    )

    private fun describeShellFailure(result: Shell.Result): String {
        val err = result.err.joinToString("; ").take(300)
        return when {
            err.isNotBlank() -> err
            result.out.isNotEmpty() -> "输出无法解析（前 120 字符：${result.out.take(3).joinToString(" | ").take(120)}）"
            else -> "shell 没有任何输出（su 可能已失效）"
        }
    }

    private fun nextToken(): String =
        "m${System.currentTimeMillis() % 1_000_000}_${opSeq.incrementAndGet()}"

    private fun requirePid(pid: Int) {
        if (pid <= 0) throw MemoryException("pid 无效：$pid")
    }

    private fun pageStart(address: Long): Long = address - (address % PAGE_SIZE)

    private fun readPortion(file: File, offset: Long, length: Int): ByteArray? {
        if (length <= 0) return null
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buffer = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = raf.read(buffer, read, length - read)
                if (n <= 0) break
                read += n
            }
            return if (read == 0) null else if (read == length) buffer else buffer.copyOf(read)
        }
    }

    private fun readTextFile(path: String, maxChars: Int = 3000): String {
        val file = File(path)
        return runCatching {
            if (!file.exists() || file.length() == 0L) {
                ""
            } else {
                file.readText(Charsets.UTF_8).take(maxChars).trim()
            }
        }.getOrDefault("")
    }

    private fun firstLines(text: String, count: Int): String =
        text.lineSequence().filter { it.isNotBlank() }.take(count).joinToString("; ")

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    @Volatile
    private var rootShell: Shell? = null

    private val shellLock = Any()

    private val opSeq = AtomicLong(0)

    private data class RegionDump(
        val region: MemoryRegion,
        val file: File,
        /** Bytes to skip at the start of [file] because [MemoryRegion.start] was not page aligned. */
        val lead: Int,
        /** `dd count=` for this region, in pages. */
        val blocks: Long,
    )

    private data class PageRun(val startPage: Long, val pageCount: Long)

    private companion object {
        const val PAGE_SIZE = 4096L
        const val CONTEXT_BYTES = 16

        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        const val REGION_BYTES_CAP = 8L * 1024 * 1024
        const val MAX_REGIONS_PER_SCAN = 1500
        const val MAX_MATCHES_HARD = 1000
        const val DEFAULT_MAX_RESULTS = 500
        const val MAX_PAGE_RUNS = 512
        const val MAX_WRITE_BYTES = 256

        const val NO_PROC_MARKER = "__REGTRAIL_NO_PROC__"
        const val WRITE_OK_MARKER = "__REGTRAIL_WRITE_OK__"
        const val WRITE_FAIL_MARKER = "__REGTRAIL_WRITE_FAIL__"
        const val PAYLOAD_FAIL_MARKER = "__REGTRAIL_PAYLOAD_FAIL__"

        const val LIST_TIMEOUT_MS = 60_000L
        const val REGION_TIMEOUT_MS = 30_000L
        const val SCAN_TIMEOUT_MS = 120_000L
        const val REFINE_TIMEOUT_MS = 60_000L
        const val READ_TIMEOUT_MS = 30_000L
        const val WRITE_TIMEOUT_MS = 30_000L

        /**
         * `start-end perms offset dev inode [path]`; the path may itself contain
         * spaces, so it is captured as everything after the inode.
         */
        val MAP_LINE = Regex(
            "^([0-9a-fA-F]+)-([0-9a-fA-F]+)\\s+(\\S{4})\\s+([0-9a-fA-F]+)\\s+(\\S+)\\s+(\\d+)\\s*(.*)$",
        )
    }
}

// ---------------------------------------------------------------------------
// Model
// ---------------------------------------------------------------------------

/** One row of `/proc` as the agent sees it. */
internal data class ProcEntry(val pid: Int, val uid: Int, val name: String) {
    /** Android gives real apps uids from 10000 up (secondary users add 100000s). */
    val isApp: Boolean get() = uid >= 10_000
}

/** One line of `/proc/<pid>/maps`. */
internal data class MemoryRegion(
    val start: Long,
    val end: Long,
    /** `rw-p` style permission string. */
    val permissions: String,
    /** Mapped file, or an `[anon:…]`/`[stack]` style label, or empty. */
    val path: String,
) {
    val size: Long get() = end - start

    val isReadable: Boolean get() = permissions.startsWith("r")

    val isWritable: Boolean get() = permissions.length > 1 && permissions[1] == 'w'

    val isExecutable: Boolean get() = permissions.length > 2 && permissions[2] == 'x'

    /** Short label for display: the path, or `[anon]`. */
    val label: String get() = path.ifEmpty { "[anon]" }
}

/** One candidate address. */
internal data class MemoryMatch(
    val address: Long,
    val size: Int,
    /** Up to 16 bytes starting at [address], hex, for a human sanity check. */
    val preview: String,
    /** Region label this address was found in (empty when only the address is known). */
    val region: String = "",
)

/** What one scan pass actually managed to look at. */
internal data class ScanOutcome(
    val matches: List<MemoryMatch>,
    /** True when the byte/region/match budget stopped the pass early. */
    val truncated: Boolean,
    val bytesRead: Long,
    val regionsRead: Int,
    /** Human-readable caveats: dd errors, unreadable regions, budget hits. */
    val notes: List<String>,
)

/**
 * A typed search value.
 *
 * ## Why the bytes are computed here
 * The shell only ever compares nothing — matching happens in Kotlin on bytes
 * that were read from the process. Encoding the needle once keeps the hot loop
 * to a byte comparison and makes [refine] trivially re-usable: it compares fresh
 * bytes against the same needle.
 *
 * ## Endianness
 * Little-endian, i.e. the native layout on every Android ABI this app runs on.
 * Integer literals also accept `0x…`, which is what an address dump shows.
 */
internal sealed class ScanQuery {

    abstract val typeName: String

    /** Exact bytes to find, little-endian for the numeric types. */
    abstract val bytes: ByteArray

    /** Numeric types only match at naturally aligned addresses by default. */
    open val align: Boolean get() = true

    val size: Int get() = bytes.size

    class Int32(val value: Int) : ScanQuery() {
        override val typeName: String = "int32"
        override val bytes: ByteArray = littleEndian(4) { putInt(value) }
    }

    class Int64(val value: Long) : ScanQuery() {
        override val typeName: String = "int64"
        override val bytes: ByteArray = littleEndian(8) { putLong(value) }
    }

    class Float32(val value: Float) : ScanQuery() {
        override val typeName: String = "float32"
        override val bytes: ByteArray = littleEndian(4) { putInt(value.toRawBits()) }
    }

    class Float64(val value: Double) : ScanQuery() {
        override val typeName: String = "float64"
        override val bytes: ByteArray = littleEndian(8) { putLong(value.toRawBits()) }
    }

    class Utf8(val text: String) : ScanQuery() {
        override val typeName: String = "utf8"
        override val bytes: ByteArray = text.toByteArray(Charsets.UTF_8)

        /** A string can sit anywhere, including unaligned. */
        override val align: Boolean = false
    }

    companion object {
        /** The names accepted by [parse], also the enum shown to the model. */
        val TYPE_NAMES: List<String> = listOf("int32", "int64", "float32", "float64", "utf8")

        /** Builds a query from the tool's `type` + `value` strings, or null. */
        fun parse(type: String, value: String): ScanQuery? {
            val raw = value.trim()
            return when (type.trim().lowercase()) {
                "int", "int32", "i32", "dword" -> parseIntText(raw)?.let { Int32(it) }
                "long", "int64", "i64", "qword" -> parseLongText(raw)?.let { Int64(it) }
                "float", "float32", "f32" ->
                    raw.toFloatOrNull()?.takeIf { !it.isNaN() }?.let { Float32(it) }
                "double", "float64", "f64" ->
                    raw.toDoubleOrNull()?.takeIf { !it.isNaN() }?.let { Float64(it) }
                "utf8", "utf-8", "string", "str" -> raw.takeIf { it.isNotEmpty() }?.let { Utf8(it) }
                else -> null
            }
        }

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
}

/** Raised for every failure that has a user-readable reason. */
internal class MemoryException(message: String) : Exception(message)

// ---------------------------------------------------------------------------
// Formatting / parsing helpers shared with the tool layer
// ---------------------------------------------------------------------------

private const val HEX_DIGITS = "0123456789abcdef"

/** `0x` + 12 zero-padded hex digits, so a list of addresses lines up. */
internal fun formatMemoryAddress(address: Long): String =
    "0x" + address.toString(16).padStart(12, '0')

/** Lower-case hex bytes, space separated (`64 00 00 00`). */
internal fun hexBytes(bytes: ByteArray): String = buildString {
    for (index in bytes.indices) {
        if (index > 0) append(' ')
        val value = bytes[index].toInt() and 0xFF
        append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
    }
}

/** Parses `0x7f1234`, `7f1234` or a decimal string into an address. */
internal fun parseMemoryAddress(text: String): Long? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    var body = if (trimmed.startsWith("0x", true)) trimmed.substring(2) else trimmed
    body = body.replace("_", "").replace(" ", "")
    if (body.isEmpty()) return null
    if (body.any { it !in "0123456789abcdefABCDEF" }) return null
    return body.toLongOrNull(16)
}

/** Compact byte counts for tool output. */
internal fun humanBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)} MB"
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

private fun littleEndian(size: Int, put: ByteBuffer.() -> Unit): ByteArray =
    ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(put).array()
