package io.github.wg1337.regtrail.xposed.debug

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.wg1337.regtrail.xposed.RegTrailLog
import io.github.wg1337.regtrail.xposed.overlay.ModuleConfig
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The live debugger that runs inside a target process.
 *
 * ## What this can and cannot do
 * Everything here is *Java-level* instrumentation via the framework's
 * `hook(Executable)`. That means:
 *
 *  - ✅ read arguments, replace arguments, force a return value, throw instead
 *    of returning, trace calls, count hits;
 *  - ✅ hook methods of classes that are already loaded (`deoptimize` handles
 *    the inlining case);
 *  - ❌ **never** read or write CPU registers. Java hooks cannot see them by
 *    construction, and no amount of reflection changes that. Register-level
 *    work needs native instrumentation (Dobby/Frida), which is a separate,
 *    opt-in concern.
 *
 * That boundary is stated in the UI as well, so nobody is promised something
 * the platform cannot deliver.
 *
 * ## Shape
 * A hook is identified by a user-visible [HookSpec.id]. Installing one stores a
 * [HookState] holding the live `HookHandle` plus the mutable behaviour, so the
 * UI can retune a hook (change the forced return value) without unhooking and
 * rehooking — which matters when the target method is called in a tight loop.
 */
internal object DebugEngine {

    private lateinit var module: XposedModule

    /**
     * Config reader, so "deoptimize before hooking" honours the published
     * `autoDeoptimize` switch.
     *
     * Built once at [attach] rather than per hook install: `ModuleConfig`
     * caches the parsed snapshot and only re-`stat`s the file, so holding one
     * instance is both cheaper and the same object the rest of the module uses.
     */
    private var config: ModuleConfig? = null

    private val hooks = ConcurrentHashMap<String, HookState>()

    private val hookSeq = AtomicLong(0)

    /** Classes already offered to the UI, memoised per classloader. */
    private val classCache = ConcurrentHashMap<ClassLoader, List<Class<*>>>()

    fun attach(module: XposedModule) {
        this.module = module
        this.config = ModuleConfig(module)
    }

    val isAttached: Boolean get() = ::module.isInitialized

    // -----------------------------------------------------------------------
    // Class discovery
    // -----------------------------------------------------------------------

    /**
     * Every class the target's classloader has loaded so far.
     *
     * There is no public API for this, so the usual trick is reflection into
     * the classloader's internal `pathList`/`dexElements`. It is best-effort by
     * design: any failure yields a partial list rather than an exception,
     * because a debugger that crashes the app it is inspecting is useless.
     */
    fun loadedClasses(loader: ClassLoader): List<Class<*>> =
        classCache.getOrPut(loader) {
            runCatching { collectLoadedClasses(loader) }.getOrElse {
                RegTrailLog.w("could not enumerate loaded classes", it)
                emptyList()
            }
        }

    /** Drops the memoised class list; call after the app loads more code. */
    fun invalidateClassCache(loader: ClassLoader? = null) {
        if (loader == null) classCache.clear() else classCache.remove(loader)
    }

    private fun collectLoadedClasses(loader: ClassLoader): List<Class<*>> {
        val out = LinkedHashSet<Class<*>>()

        // BaseDexClassLoader.pathList.dexElements[].dexFile.entries()
        val baseDex = Class.forName("dalvik.system.BaseDexClassLoader")
        val pathListField = baseDex.getDeclaredField("pathList").apply { isAccessible = true }
        val pathList = pathListField.get(loader) ?: return emptyList()

        val dexElementsField =
            pathList.javaClass.getDeclaredField("dexElements").apply { isAccessible = true }
        val elements = dexElementsField.get(pathList) as? Array<*> ?: return emptyList()

        for (element in elements) {
            if (element == null) continue
            val dexFile = runCatching {
                element.javaClass.getDeclaredField("dexFile").apply { isAccessible = true }
                    .get(element)
            }.getOrNull() ?: continue

            // DexFile.entries() returns an Enumeration<String> of class names.
            val entries = runCatching {
                dexFile.javaClass.getMethod("entries").invoke(dexFile) as? java.util.Enumeration<*>
            }.getOrNull() ?: continue

            while (entries.hasMoreElements()) {
                val name = entries.nextElement() as? String ?: continue
                runCatching { Class.forName(name, false, loader) }
                    .getOrNull()
                    ?.let { out.add(it) }
            }
        }
        return out.toList()
    }

    /**
     * Methods matching a query.
     *
     * @param classNamePattern substring or simple glob (`*`) against the FQCN.
     * @param methodPattern substring against the method name; null matches all.
     * @param limit hard cap so a broad query cannot stall the target's UI thread.
     */
    fun findMethods(
        loader: ClassLoader,
        classNamePattern: String,
        methodPattern: String? = null,
        includeConstructors: Boolean = false,
        limit: Int = 200,
    ): List<MethodRef> {
        val out = ArrayList<MethodRef>()
        val needle = classNamePattern.removeSurrounding("*")

        for (cls in loadedClasses(loader)) {
            if (out.size >= limit) break
            if (!cls.name.contains(needle, ignoreCase = true)) continue

            for (m in cls.declaredMethods) {
                if (out.size >= limit) break
                if (methodPattern != null && !m.name.contains(methodPattern, ignoreCase = true)) {
                    continue
                }
                out.add(MethodRef.of(cls, m))
            }
            if (includeConstructors) {
                for (c in cls.declaredConstructors) {
                    if (out.size >= limit) break
                    out.add(MethodRef.ofConstructor(cls, c))
                }
            }
        }
        return out
    }

    /** Resolves an exact method by name and parameter type names. */
    fun resolveMethod(
        loader: ClassLoader,
        className: String,
        methodName: String,
        paramTypes: List<String>,
    ): Executable? {
        val cls = runCatching { Class.forName(className, false, loader) }.getOrNull() ?: return null
        val types = paramTypes.map { resolveType(it, loader) }.toTypedArray()

        if (methodName == "<init>") {
            return runCatching { cls.getDeclaredConstructor(*types) }.getOrNull()
        }
        if (methodName == "<clinit>") return null
        return runCatching { cls.getDeclaredMethod(methodName, *types) }.getOrNull()
    }

    private fun resolveType(name: String, loader: ClassLoader): Class<*> = when (name) {
        "boolean" -> Boolean::class.javaPrimitiveType!!
        "byte" -> Byte::class.javaPrimitiveType!!
        "char" -> Char::class.javaPrimitiveType!!
        "short" -> Short::class.javaPrimitiveType!!
        "int" -> Int::class.javaPrimitiveType!!
        "long" -> Long::class.javaPrimitiveType!!
        "float" -> Float::class.javaPrimitiveType!!
        "double" -> Double::class.javaPrimitiveType!!
        "void" -> Void.TYPE
        else -> Class.forName(name, false, loader)
    }

    // -----------------------------------------------------------------------
    // Hooking
    // -----------------------------------------------------------------------

    /**
     * Installs a hook. Returns the assigned id.
     *
     * @param behavior what the hook does when the method is called.
     */
    fun installHook(
        target: Executable,
        behavior: HookBehavior,
        label: String,
    ): Result<String> = runCatching {
        check(isAttached) { "DebugEngine not attached" }

        val id = behavior.id.ifBlank { "h${hookSeq.incrementAndGet()}" }
        if (hooks.containsKey(id)) {
            // Replacing is intentional: the UI re-applies a hook after editing it.
            hooks.remove(id)?.handle?.unhook()
        }

        // Deoptimize first: if the method was inlined into a caller, a hook would
        // otherwise never fire. Skippable from the settings app ("自动
        // deoptimize"), because forcing the recompile is measurable in a hot
        // method; an unreadable config keeps the historical behaviour (on).
        if (autoDeoptimize()) {
            runCatching { module.deoptimize(target) }
        }

        val state = HookState(id = id, target = target, label = label, behavior = behavior)

        val handle = module.hook(target)
            .setId(id)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            // PROTECTIVE: a throwing hook must not corrupt the target app.
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain -> state.onCall(chain) }

        state.handle = handle
        hooks[id] = state

        RegTrailLog.i("hook installed: $id → ${describe(target)}")
        id
    }

    /**
     * The published `autoDeoptimize` switch, defaulting to `true`.
     *
     * Every failure path (config not readable yet, malformed JSON, module
     * attached without a config) resolves to the behaviour that shipped before
     * the switch existed, because "hook never fires" is a far worse failure
     * than "method was recompiled".
     */
    private fun autoDeoptimize(): Boolean = runCatching {
        config?.reload()?.autoDeoptimize ?: true
    }.getOrDefault(true)

    fun unhook(id: String): Boolean {
        val state = hooks.remove(id) ?: return false
        runCatching { state.handle?.unhook() }
        RegTrailLog.i("hook removed: $id")
        return true
    }

    fun unhookAll() {
        hooks.keys.toList().forEach { unhook(it) }
    }

    /** Retunes a live hook without rehooking. */
    fun updateBehavior(id: String, transform: (HookBehavior) -> HookBehavior): Boolean {
        val state = hooks[id] ?: return false
        state.behavior = transform(state.behavior)
        return true
    }

    fun snapshot(): List<HookInfo> = hooks.values.map { it.info() }

    fun describe(target: Executable): String {
        val params = target.parameterTypes.joinToString(",") { it.simpleName }
        val owner = target.declaringClass.name
        val name = if (target is Method) target.name else "<init>"
        return "$owner#$name($params)"
    }

    /** Reflectively reads a static field, used by the "dump class" tool. */
    fun describeClass(cls: Class<*>): Map<String, Any?> = mapOf(
        "name" to cls.name,
        "superclass" to cls.superclass?.name,
        "interfaces" to cls.interfaces.map { it.name },
        "isInterface" to cls.isInterface,
        "isEnum" to cls.isEnum,
        "modifiers" to Modifier.toString(cls.modifiers),
        "methods" to cls.declaredMethods.take(200).map { m ->
            "${Modifier.toString(m.modifiers)} ${m.returnType.simpleName} ${m.name}(" +
                m.parameterTypes.joinToString(",") { it.simpleName } + ")"
        },
        "fields" to cls.declaredFields.take(200).map { f ->
            "${Modifier.toString(f.modifiers)} ${f.type.simpleName} ${f.name}"
        },
    )

    /** Live hook record exposed to the UI. */
    data class HookInfo(
        val id: String,
        val target: String,
        val label: String,
        val calls: Long,
        val returnOverride: String?,
        val trace: Boolean,
    )

    private class HookState(
        val id: String,
        val target: Executable,
        val label: String,
        @Volatile var behavior: HookBehavior,
    ) {
        @Volatile
        var handle: XposedInterface.HookHandle? = null

        private val calls = AtomicLong(0)

        fun onCall(chain: XposedInterface.Chain): Any? {
            val n = calls.incrementAndGet()
            val b = behavior

            return try {
                if (b.trace && shouldLog(n, b.traceEvery)) {
                    RegTrailLog.i(
                        "[$id] ${describe(target)} args=" +
                            chain.args.joinToString(", ") { stringify(it) },
                    )
                }

                // Argument replacement happens before the original runs.
                val args = chain.args.toMutableList()
                var argsChanged = false
                for ((index, value) in b.argOverrides) {
                    if (index in args.indices) {
                        args[index] = value
                        argsChanged = true
                    }
                }

                // `returnOverride` uses NO_OVERRIDE as "not set" so that `null`
                // remains a legitimate forced value (a method returning null is
                // a common thing to fake).
                if (b.throwInstead != null) {
                    throw b.throwInstead
                }

                if (b.returnOverride !== NO_OVERRIDE) {
                    // Still run the original unless the caller asked to skip it:
                    // skipping changes side effects, which is usually not wanted
                    // for a pure "fake the return value" edit.
                    runCatching {
                        if (argsChanged) chain.proceed(args.toTypedArray()) else chain.proceed()
                    }
                    if (b.trace) {
                        RegTrailLog.i("[$id] return forced → ${stringify(b.returnOverride)}")
                    }
                    return b.returnOverride
                }

                val result = if (argsChanged) {
                    chain.proceed(args.toTypedArray())
                } else {
                    chain.proceed()
                }

                if (b.trace && shouldLog(n, b.traceEvery)) {
                    RegTrailLog.i("[$id] returned ${stringify(result)}")
                }
                result
            } catch (t: Throwable) {
                RegTrailLog.w("[$id] hook error", t)
                // PROTECTIVE mode already contains this, but be explicit: never
                // let a debugger bug change the target's behaviour silently.
                chain.proceed()
            }
        }

        private fun shouldLog(n: Long, every: Long): Boolean =
            every <= 1L || n % every == 0L

        fun info() = HookInfo(
            id = id,
            target = describe(target),
            label = label,
            calls = calls.get(),
            returnOverride = behavior.returnOverride
                ?.takeIf { it !== NO_OVERRIDE }
                ?.let { stringify(it) },
            trace = behavior.trace,
        )
    }

    /** Sentinel meaning "do not override the return value". */
    val NO_OVERRIDE: Any = object {
        override fun toString() = "<no override>"
    }

    private fun stringify(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"$value\""
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { stringify(it) }
        else -> runCatching { value.toString() }.getOrDefault("<unprintable>")
    }
}

/** Everything that controls how one hook behaves. */
internal data class HookBehavior(
    /** Stable id; blank asks the engine to assign one. */
    val id: String = "",
    /** Log each call (throttled by [traceEvery]). */
    val trace: Boolean = false,
    /** Log every Nth call; 1 means every call. */
    val traceEvery: Long = 1,
    /**
     * When set to anything other than [DebugEngine.NO_OVERRIDE], this value is
     * returned instead of the original result. `null` is a legitimate forced
     * value, which is why the sentinel exists rather than using null itself.
     */
    val returnOverride: Any? = DebugEngine.NO_OVERRIDE,
    /** Replacement arguments, keyed by index. */
    val argOverrides: Map<Int, Any?> = emptyMap(),
    /** When set, thrown instead of calling the original. */
    val throwInstead: Throwable? = null,
)

/** A hookable method, flattened for the wire/UI. */
internal data class MethodRef(
    val className: String,
    val methodName: String,
    val paramTypes: List<String>,
    val returnType: String,
    val isStatic: Boolean,
    val signature: String,
) {
    companion object {
        fun of(cls: Class<*>, m: Method) = MethodRef(
            className = cls.name,
            methodName = m.name,
            paramTypes = m.parameterTypes.map { it.name },
            returnType = m.returnType.name,
            isStatic = Modifier.isStatic(m.modifiers),
            signature = "${cls.name}#${m.name}(" +
                m.parameterTypes.joinToString(",") { it.name } + ")",
        )

        fun ofConstructor(cls: Class<*>, c: java.lang.reflect.Constructor<*>) = MethodRef(
            className = cls.name,
            methodName = "<init>",
            paramTypes = c.parameterTypes.map { it.name },
            returnType = "void",
            isStatic = false,
            signature = "${cls.name}#<init>(" +
                c.parameterTypes.joinToString(",") { it.name } + ")",
        )
    }
}
