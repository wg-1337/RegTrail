package io.github.wg1337.regtrail.xposed.mem

import io.github.wg1337.regtrail.xposed.RegTrailLog
import java.io.RandomAccessFile
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One way to reach **this** process's own bytes.
 *
 * ## Why this is an interface and not just `RandomAccessFile`
 * `/proc/self/mem` is the obvious transport: the kernel short-circuits the ptrace
 * permission check when the target is the caller, so a process may always read and
 * (for a writable mapping) write its own memory. Measured on the test device
 * (HUAWEI AGS5Z-AL00, Android 12 / SDK 31, `untrusted_app_27`):
 *
 * ```
 * java.io.FileNotFoundException: /proc/self/mem: open failed: EACCES (Permission denied)
 *   at libcore.io.IoBridge.open
 *   at java.io.RandomAccessFile.<init>
 * ```
 *
 * `open()` itself is refused — not a read at an unmapped page, not a budget
 * problem — while `/proc/self/maps` stays readable. So on a ROM like that one the
 * only transport left that does not need root is `sun.misc.Unsafe`, which reads and
 * writes the process's own address space through JNI with no syscall and therefore
 * no file permission to check. [UnsafeTransport] implements it.
 *
 * ## What the caller must know
 * - [readInto] is **one page per call** and reports failure instead of throwing:
 *   a mapping listed in `/proc/self/maps` can still have holes, and one bad page
 *   must not abort a whole scan.
 * - [close] is idempotent; every operation opens and closes its transport so a
 *   long-lived file descriptor cannot leak into the host app.
 */
internal interface MemTransport {

    /** Short name for the log and the 诊断 tab: `proc-mem` or `unsafe`. */
    val name: String

    /** One line of detail for the 诊断 output (which entry points are live). */
    fun describe(): String

    /**
     * Total bytes one scan may read through this transport.
     *
     * The reflection-based transport is one JNI call per 8 bytes — roughly two
     * orders of magnitude slower than `pread` — so its budget is smaller. Honest
     * truncation with a reported number beats an eight-second scan that looks
     * hung.
     */
    val maxTotalBytes: Long

    /** Copies exactly [length] bytes from [address] into `dest[destOffset…]`. */
    fun readInto(address: Long, dest: ByteArray, destOffset: Int, length: Int): Boolean

    /** Reads [length] bytes at [address], or null when the page is not readable. */
    fun read(address: Long, length: Int): ByteArray?

    /** Writes [bytes] at [address]; returns true when the kernel accepted them. */
    fun write(address: Long, bytes: ByteArray): Boolean

    fun close()
}

/**
 * `/proc/self/mem` through `RandomAccessFile`.
 *
 * Preferred whenever it opens: `pread` on a page costs ~100 ns, the bytes are
 * guaranteed to be the live ones, and a failed read is a normal `IOException`
 * rather than a fault.
 */
internal class ProcMemTransport(private val file: RandomAccessFile) : MemTransport {

    override val name: String = "proc-mem"

    override fun describe(): String = "/proc/self/mem (RandomAccessFile)"

    override val maxTotalBytes: Long = InProcessMemory.MAX_TOTAL_BYTES

    override fun readInto(address: Long, dest: ByteArray, destOffset: Int, length: Int): Boolean =
        runCatching {
            file.seek(address)
            file.readFully(dest, destOffset, length)
            true
        }.getOrDefault(false)

    override fun read(address: Long, length: Int): ByteArray? =
        runCatching {
            val buffer = ByteArray(length)
            file.seek(address)
            file.readFully(buffer)
            buffer
        }.getOrNull()

    override fun write(address: Long, bytes: ByteArray): Boolean = runCatching {
        file.seek(address)
        file.write(bytes)
        true
    }.getOrDefault(false)

    override fun close() {
        runCatching { file.close() }
    }

    companion object {
        /**
         * Opens the file, or explains why it could not be opened.
         *
         * @return the transport, or null; [reason] receives the errno text so the
         *   诊断 report can say *EACCES* rather than "failed".
         */
        fun open(reason: StringBuilder): ProcMemTransport? = try {
            ProcMemTransport(RandomAccessFile("/proc/self/mem", "r"))
        } catch (t: Throwable) {
            reason.append(t.javaClass.simpleName).append(": ")
            reason.append(t.message ?: "no message")
            null
        }

        /** Same, but for the read-write handle a write needs. */
        fun openWrite(reason: StringBuilder): ProcMemTransport? = try {
            ProcMemTransport(RandomAccessFile("/proc/self/mem", "rw"))
        } catch (t: Throwable) {
            reason.append(t.javaClass.simpleName).append(": ")
            reason.append(t.message ?: "no message")
            null
        }
    }
}

/**
 * `sun.misc.Unsafe` as a memory transport.
 *
 * ## Why this works when `/proc/self/mem` does not
 * Every method here JNI-calls straight into ART's `Unsafe` implementation, which
 * dereferences the address it is handed. There is no `open`, no LSM hook, no file
 * at all — so the SELinux rule that refuses `/proc/self/mem` has nothing to deny.
 * The same property is the risk: dereferencing an address that is **not** mapped
 * kills the process with SIGSEGV, which Java cannot catch. Callers therefore only
 * ever pass addresses taken from `/proc/self/maps`, one 4 KiB page at a time, and
 * accept that a page unmapped in the microseconds between the maps read and the
 * load is a crash. See `InProcessMemory.readRegion` for the mitigation.
 *
 * ## Entry points
 * `Unsafe` has two families of byte access and neither is in the SDK, so they are
 * resolved reflectively and the best available one is used:
 *
 *  - `copyMemory(long,Object,long,long)` / `copyMemory(Object,long,long,long)` —
 *    bulk, no allocation. Preferred.
 *  - `getLong(long)` / `putLong(long,long)` … — one call per 8 bytes, which is why
 *    this transport halves the scan budget.
 */
internal class UnsafeTransport private constructor(
    private val unsafe: Any,
    private val bulkRead: Method?,
    private val bulkWrite: Method?,
    private val getLong: Method?,
    private val getByte: Method?,
    private val putLong: Method?,
    private val putByte: Method?,
    private val arrayBaseOffset: Method?,
) : MemTransport {

    override val name: String = "unsafe"

    /** True when reads go through `copyMemory` rather than a per-8-byte call. */
    private val bulk: Boolean = bulkRead != null

    /** Scratch buffer for the per-element path; allocated once per transport. */
    private val elementBuffer: ByteBuffer = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())

    override fun describe(): String = buildString {
        append("sun.misc.Unsafe theUnsafe")
        append(if (bulk) " · copyMemory(批量)" else " · getLong(逐 8 字节)")
        append(" · getByte=").append(getByte != null)
        append(" · putByte=").append(putByte != null)
        append(" · bulkWrite=").append(bulkWrite != null)
    }

    override val maxTotalBytes: Long = if (bulk) {
        InProcessMemory.MAX_TOTAL_BYTES
    } else {
        InProcessMemory.MAX_TOTAL_BYTES_UNSAFE_ELEMENTWISE
    }

    override fun readInto(
        address: Long,
        dest: ByteArray,
        destOffset: Int,
        length: Int,
    ): Boolean {
        if (length <= 0) return false

        val bulkMethod = bulkRead
        val baseOffset = arrayBaseOffset
        if (bulkMethod != null && baseOffset != null) {
            return runCatching {
                val offset = (baseOffset.invoke(null) as Int).toLong() + destOffset
                bulkMethod.invoke(unsafe, address, dest, offset, length.toLong())
                true
            }.getOrDefault(false)
        }

        val longReader = getLong
        if (longReader != null) {
            return runCatching {
                val buffer = ByteBuffer.wrap(dest, destOffset, length).order(ByteOrder.nativeOrder())
                val whole = length / 8
                var at = address
                for (i in 0 until whole) {
                    buffer.putLong(longReader.invoke(unsafe, at) as Long)
                    at += 8
                }
                val tail = length - whole * 8
                if (tail > 0) {
                    elementBuffer.clear()
                    elementBuffer.putLong(longReader.invoke(unsafe, at) as Long)
                    System.arraycopy(
                        elementBuffer.array(),
                        0,
                        dest,
                        destOffset + whole * 8,
                        tail,
                    )
                }
                true
            }.getOrDefault(false)
        }

        val byteReader = getByte ?: return false
        return runCatching {
            for (i in 0 until length) {
                dest[destOffset + i] = byteReader.invoke(unsafe, address + i) as Byte
            }
            true
        }.getOrDefault(false)
    }

    override fun read(address: Long, length: Int): ByteArray? {
        if (length <= 0) return null
        val buffer = ByteArray(length)
        return if (readInto(address, buffer, 0, length)) buffer else null
    }

    override fun write(address: Long, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false

        val bulkMethod = bulkWrite
        val baseOffset = arrayBaseOffset
        if (bulkMethod != null && baseOffset != null) {
            return runCatching {
                val offset = (baseOffset.invoke(null) as Int).toLong()
                bulkMethod.invoke(unsafe, bytes, offset, address, bytes.size.toLong())
                true
            }.getOrDefault(false)
        }

        val longWriter = putLong
        if (longWriter != null) {
            return runCatching {
                val whole = bytes.size / 8
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
                var at = address
                for (i in 0 until whole) {
                    longWriter.invoke(unsafe, at, buffer.getLong(i * 8))
                    at += 8
                }
                var i = whole * 8
                while (i < bytes.size) {
                    putByteRequired().invoke(unsafe, address + i, bytes[i])
                    i++
                }
                true
            }.getOrDefault(false)
        }

        val byteWriter = putByte ?: return false
        return runCatching {
            for (i in bytes.indices) {
                byteWriter.invoke(unsafe, address + i, bytes[i])
            }
            true
        }.getOrDefault(false)
    }

    private fun putByteRequired(): Method =
        putByte ?: error("Unsafe has no putByte")

    override fun close() = Unit

    companion object {
        /** Cache of the resolved instance: reflection is not free and this is per-process. */
        @Volatile
        private var instance: UnsafeTransport? = null

        @Volatile
        private var failure: String? = null

        /** The transport, or null when this ROM does not expose `sun.misc.Unsafe`. */
        fun get(): UnsafeTransport? {
            instance?.let { return it }
            failure?.let { return null }
            return create()?.also { instance = it }
        }

        /** Why [get] returned null, for the 诊断 report. */
        fun unavailableReason(): String? = failure

        private fun create(): UnsafeTransport? = try {
            val cls = Class.forName("sun.misc.Unsafe")
            val field = runCatching { cls.getDeclaredField("theUnsafe") }
                .getOrElse { cls.getDeclaredField("THE_ONE") }
            field.isAccessible = true
            val unsafe = field.get(null) ?: throw IllegalStateException("theUnsafe is null")

            val arrayBaseOffset = methodOrNull(cls, "arrayBaseOffset", Class::class.java)

            // Bulk copy, in the argument order OpenJDK ships. Only the ones that
            // really exist are kept; the per-element methods are the fallback for a
            // build that has neither.
            val bulkRead = methodOrNull(
                cls,
                "copyMemory",
                Long::class.javaPrimitiveType!!,
                Any::class.java,
                Long::class.javaPrimitiveType!!,
                Long::class.javaPrimitiveType!!,
            )
            val bulkWrite = methodOrNull(
                cls,
                "copyMemory",
                Any::class.java,
                Long::class.javaPrimitiveType!!,
                Long::class.javaPrimitiveType!!,
                Long::class.javaPrimitiveType!!,
            )

            val getLong = methodOrNull(cls, "getLong", Long::class.javaPrimitiveType!!)
            val getByte = methodOrNull(cls, "getByte", Long::class.javaPrimitiveType!!)
            val putLong = methodOrNull(
                cls,
                "putLong",
                Long::class.javaPrimitiveType!!,
                Long::class.javaPrimitiveType!!,
            )
            val putByte = methodOrNull(
                cls,
                "putByte",
                Long::class.javaPrimitiveType!!,
                Byte::class.javaPrimitiveType!!,
            )

            if (getLong == null && getByte == null) {
                failure = "sun.misc.Unsafe 存在但没有可用的读取方法"
                RegTrailLog.w("mem: $failure")
                return null
            }

            RegTrailLog.i(
                "mem: Unsafe transport ready (bulkRead=${bulkRead != null}, " +
                    "bulkWrite=${bulkWrite != null}, getLong=${getLong != null}, " +
                    "getByte=${getByte != null})",
            )
            UnsafeTransport(
                unsafe = unsafe,
                bulkRead = bulkRead,
                bulkWrite = bulkWrite,
                getLong = getLong,
                getByte = getByte,
                putLong = putLong,
                putByte = putByte,
                arrayBaseOffset = arrayBaseOffset,
            )
        } catch (t: Throwable) {
            failure = "${t.javaClass.simpleName}: ${t.message}"
            RegTrailLog.w("mem: sun.misc.Unsafe unavailable", t)
            null
        }

        private fun methodOrNull(cls: Class<*>, name: String, vararg params: Class<*>): Method? =
            runCatching { cls.getMethod(name, *params).apply { isAccessible = true } }.getOrNull()
    }
}
