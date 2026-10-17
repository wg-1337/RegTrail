package io.github.wg1337.regtrail.xposed.debug

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A handle table for live objects in the target process.
 *
 * The settings app cannot hold a reference to an object living in another
 * process, and Binder cannot carry one. So the module keeps the objects and
 * hands out short string handles; subsequent tool calls (read this field, call
 * that method) reference the handle.
 *
 * Entries are deliberately **weak**: this table must never keep a target app's
 * Activity or model object alive. When the app drops its own reference, the
 * handle simply stops resolving, and callers get a clear error instead of a
 * leak inside someone else's process.
 */
internal object InstanceRegistry {

    private val table = ConcurrentHashMap<String, java.lang.ref.WeakReference<Any>>()

    private val seq = AtomicLong(0)

    /** Registers [value] under a fresh handle and returns it. */
    fun register(value: Any, prefix: String = "obj"): String {
        prune()
        val handle = "$prefix-${seq.incrementAndGet()}"
        table[handle] = java.lang.ref.WeakReference(value)
        return handle
    }

    fun put(handle: String, value: Any) {
        table[handle] = java.lang.ref.WeakReference(value)
    }

    fun get(handle: String): Any? {
        val ref = table[handle] ?: return null
        val value = ref.get()
        if (value == null) table.remove(handle)
        return value
    }

    fun contains(handle: String): Boolean = get(handle) != null

    fun release(handle: String) {
        table.remove(handle)
    }

    fun size(): Int {
        prune()
        return table.size
    }

    private fun prune() {
        table.entries.removeAll { it.value.get() == null }
    }
}

/**
 * Prebuilt [HookBehavior] factories.
 *
 * Separate from `DebugEngine` so the tool layer can construct behaviours without
 * knowing the sentinel semantics of "no override" (see `DebugEngine.NO_OVERRIDE`).
 */
internal object DebugHookBehavior {

    /** Log every [every]-th call. */
    fun trace(id: String = "", every: Long = 1L): HookBehavior = HookBehavior(
        id = id,
        trace = true,
        traceEvery = every.coerceAtLeast(1L),
    )

    /**
     * Force the method to return [value].
     *
     * The original still runs, so side effects are preserved: this is the
     * "what if this check said yes?" tool, not a way to silently skip work.
     * Passing `null` for [value] forces a null return, which is why the engine
     * uses an explicit sentinel rather than null to mean "unset".
     */
    fun forceReturn(
        id: String = "",
        value: String?,
        valueType: String = "auto",
        trace: Boolean = true,
    ): HookBehavior {
        val coerced: Any? = when (valueType.lowercase()) {
            "null" -> null
            "boolean" -> value.equals("true", true) || value == "1"
            "int" -> value?.toIntOrNull()
            "long" -> value?.toLongOrNull()
            "float" -> value?.toFloatOrNull()
            "double" -> value?.toDoubleOrNull()
            "string" -> value.orEmpty()
            else -> value
        }
        return HookBehavior(
            id = id,
            trace = trace,
            returnOverride = coerced,
        )
    }

    /** Replace one argument, keeping everything else. */
    fun replaceArg(
        id: String = "",
        index: Int,
        value: Any?,
        trace: Boolean = true,
    ): HookBehavior = HookBehavior(
        id = id,
        trace = trace,
        argOverrides = mapOf(index to value),
    )

    /** Throw instead of running the original. */
    fun throwInstead(id: String = "", throwable: Throwable): HookBehavior = HookBehavior(
        id = id,
        trace = true,
        throwInstead = throwable,
    )
}
