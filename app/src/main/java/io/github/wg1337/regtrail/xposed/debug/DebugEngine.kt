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

    /**
     * Methods a 调试 tab query matches, best-first and bounded in time.
     *
     * ## What the query may be
     *  - `Login` — a class-name fragment. Every class whose FQCN contains it
     *    contributes its declared methods (and its constructors), because "watch what
     *    this class does" is the common case.
     *  - `com.foo.Bar#save` — an explicit class and method fragment.
     *  - `save` — a method-name fragment across the loaded classes. This is the
     *    expensive one: it has to reflect over classes whose *name* did not match, so
     *    it stops after [METHOD_SCAN_MS] or [METHOD_SCAN_CLASSES], whichever comes
     *    first, and says so in the reply instead of blocking the panel.
     *
     * Argument types are returned as their real names (`int`, `java.lang.String`) so
     * the page can hand them straight back to [resolveMethod] — no lossy
     * `simpleName` round trip in between.
     */
    fun search(loader: ClassLoader, query: String, limit: Int = SEARCH_LIMIT): SearchResult {
        val text = query.trim()
        if (text.isEmpty()) return SearchResult(emptyList(), 0, false)

        val hash = text.indexOf('#')
        val classPart = if (hash >= 0) text.substring(0, hash).trim() else text
        val methodPart = if (hash >= 0) text.substring(hash + 1).trim() else ""

        val classes = loadedClasses(loader)
        val out = ArrayList<MethodRef>()
        val seen = HashSet<String>()

        // Pass 1: classes whose name matches the query.
        for (cls in classes) {
            if (out.size >= limit) break
            if (!cls.name.contains(classPart, ignoreCase = true)) continue
            collectMethods(cls, methodPart, limit, out, seen)
        }

        // Pass 2: method names, but only when the user is not asking for a whole
        // class and pass 1 found little. Bounded by time and by class count.
        val wantsMethodScan = methodPart.isNotEmpty() || out.size < limit / 3
        var timedOut = false
        if (wantsMethodScan && out.size < limit) {
            val deadline = System.nanoTime() + METHOD_SCAN_MS * 1_000_000L
            val needle = methodPart.ifEmpty { classPart }
            var scanned = 0
            for (cls in classes) {
                if (out.size >= limit) break
                if (needle.isEmpty()) break
                if (scanned >= METHOD_SCAN_CLASSES || System.nanoTime() > deadline) {
                    timedOut = true
                    break
                }
                scanned++
                collectMethods(cls, needle, limit, out, seen, constructors = false)
            }
        }

        return SearchResult(out, classes.size, timedOut)
    }

    /**
     * Adds one class's methods — **and its superclasses'** — to [out], skipping
     * duplicates.
     *
     * ## Why inherited methods must be included
     * `Class.getDeclaredMethods()` only returns what a class declares itself, and the
     * method a user most often wants to watch is inherited: an Activity's `onResume`
     * lives in a base class or in `android.app.Activity`, a Fragment's `onCreateView`
     * in `androidx.fragment.app.Fragment`. Without the walk, searching
     * `MainActivity#onResume` returned **zero** results for a method that plainly
     * exists and is plainly called — measured on the test device.
     *
     * The entry is labelled with the class that *declares* the method, so the page can
     * hand it straight back to [resolveMethod] and the hook is installed on the real
     * `Executable`.
     *
     * Constructors are included by default: "watch the constructor of this class" is
     * the usual way to see what an Activity is built with, and their parameter list
     * is what makes the overload picker meaningful. They belong to the matched class
     * only, so they are never collected from a superclass.
     */
    private fun collectMethods(
        cls: Class<*>,
        methodPattern: String,
        limit: Int,
        out: MutableList<MethodRef>,
        seen: MutableSet<String>,
        constructors: Boolean = true,
    ) {
        if (constructors) {
            val ctors = runCatching { cls.declaredConstructors }.getOrDefault(emptyArray())
            for (c in ctors) {
                if (out.size >= limit) return
                if (methodPattern.isNotEmpty() &&
                    !"<init>".contains(methodPattern, ignoreCase = true)
                ) {
                    continue
                }
                val ref = MethodRef.ofConstructor(cls, c)
                if (seen.add(ref.signature)) out.add(ref)
            }
        }

        var current: Class<*>? = cls
        var depth = 0
        while (current != null && depth < MAX_SUPER_DEPTH) {
            val methods = runCatching { current.declaredMethods }.getOrDefault(emptyArray())
            for (m in methods) {
                if (out.size >= limit) return
                if (methodPattern.isNotEmpty() &&
                    !m.name.contains(methodPattern, ignoreCase = true)
                ) {
                    continue
                }
                val ref = MethodRef.of(current, m)
                if (seen.add(ref.signature)) out.add(ref)
            }
            val superClass = current.superclass
            if (superClass == null || superClass == Any::class.java) break
            current = superClass
            depth++
        }
    }

    /** What a [search] matched, plus whether the scan stopped early. */
    data class SearchResult(
        val methods: List<MethodRef>,
        val classesScanned: Int,
        val truncated: Boolean,
    )

    /** Resolves an exact method by name and parameter type names. */
    fun resolveMethod(
        loader: ClassLoader,
        className: String,
        methodName: String,
        paramTypes: List<String>,
    ): Executable? {
        val cls = runCatching { Class.forName(className, false, loader) }.getOrNull() ?: return null
        val types = paramTypes.map { resolveType(it, loader) }.toTypedArray()

        if (methodName == "<clinit>") return null
        return resolveExecutable(cls, methodName, types)
    }

    /**
     * Finds a method on [cls] **or any of its superclasses**.
     *
     * `getDeclaredMethod` does not walk the hierarchy, so a strict lookup reports
     * "not found" for exactly the methods people watch first (`onResume`,
     * `onCreateView`, `setContentView`). The walk is bounded and stops before
     * `java.lang.Object`, so a search never turns up `toString`/`equals`.
     */
    private fun resolveExecutable(
        cls: Class<*>,
        methodName: String,
        types: Array<Class<*>>,
    ): Executable? {
        var current: Class<*>? = cls
        var depth = 0
        while (current != null && depth < MAX_SUPER_DEPTH) {
            if (methodName == "<init>") {
                return runCatching { current.getDeclaredConstructor(*types) }.getOrNull()
            }
            runCatching { current.getDeclaredMethod(methodName, *types) }
                .getOrNull()
                ?.let { return it }
            val superClass = current.superclass
            if (superClass == null || superClass == Any::class.java) break
            current = superClass
            depth++
        }
        return null
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

    // -----------------------------------------------------------------------
    // Recorded calls (the 调试 tab's live view)
    // -----------------------------------------------------------------------

    /**
     * Calls recorded for one hook, oldest first.
     *
     * @param since only entries with `seq > since` are returned, so the page can poll
     *   incrementally instead of re-downloading the ring every 700 ms.
     * @param limit hard cap on the reply size.
     * @return null when no hook has that id (a page that outlived an unhook).
     */
    fun calls(id: String, since: Long, limit: Int = MAX_CALLS_PER_HOOK): CallsPage? {
        val state = hooks[id] ?: return null
        return state.calls(since, limit)
    }

    /** Drops the recorded calls of one hook, keeping the hook installed. */
    fun clearCalls(id: String): Boolean = hooks[id]?.clearCalls() ?: false

    /** Drops every recorded call of every hook. */
    fun clearAllCalls() {
        hooks.values.forEach { it.clearCalls() }
    }

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
        /** True when this hook keeps a ring of call entries for the 调试 tab. */
        val recording: Boolean = false,
        /** Entries currently held in that ring. */
        val recorded: Int = 0,
    )

    /** One recorded invocation: the arguments and what came back. */
    data class CallEntry(
        val seq: Long,
        /** `System.currentTimeMillis()` at entry. */
        val atMillis: Long,
        val thread: String,
        /** Argument values, formatted, one string per parameter. */
        val args: List<String>,
        /** Return value as text, or null when the method returned void. */
        val result: String?,
        /** Exception class and message when the call threw, else null. */
        val threw: String?,
        val durationMs: Long,
    )

    /** A page of recorded calls plus the cursor the page should ask from next. */
    data class CallsPage(
        val entries: List<CallEntry>,
        val next: Long,
        val total: Int,
        /** Entries evicted because the ring was full, since the process started. */
        val dropped: Long,
        val hookCalls: Long,
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

        /**
         * Recorded calls, oldest first.
         *
         * `ArrayDeque` guarded by [callLock] rather than a concurrent collection: the
         * hook runs on whatever thread called the target method (frequently the main
         * thread of the host app), and a lock-free structure with the "drop the
         * oldest" policy this needs is more code than the contention justifies.
         */
        private val callLog = ArrayDeque<CallEntry>()

        private val callLock = Any()
        private val callSeq = AtomicLong(0)
        private val callDropped = AtomicLong(0)

        fun onCall(chain: XposedInterface.Chain): Any? {
            val n = calls.incrementAndGet()
            val b = behavior
            var proceeded = false

            // Only pay for formatting arguments when recording was asked for: this
            // code runs inside a hooked method, on the host app's own thread.
            val recording = b.record && shouldLog(n, b.recordEvery)
            val startedNs = if (recording) System.nanoTime() else 0L
            val argsText = if (recording) captureArgs(chain) else emptyList()

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
                        proceeded = true
                        if (argsChanged) chain.proceed(args.toTypedArray()) else chain.proceed()
                    }
                    if (b.trace) {
                        RegTrailLog.i("[$id] return forced → ${stringify(b.returnOverride)}")
                    }
                    if (recording) {
                        appendCall(
                            argsText = argsText,
                            result = stringify(b.returnOverride),
                            threw = null,
                            startedNs = startedNs,
                        )
                    }
                    return b.returnOverride
                }

                proceeded = true
                val result = if (argsChanged) {
                    chain.proceed(args.toTypedArray())
                } else {
                    chain.proceed()
                }

                if (b.trace && shouldLog(n, b.traceEvery)) {
                    RegTrailLog.i("[$id] returned ${stringify(result)}")
                }
                if (recording) {
                    appendCall(
                        argsText = argsText,
                        result = stringify(result),
                        threw = null,
                        startedNs = startedNs,
                    )
                }
                result
            } catch (t: Throwable) {
                if (proceeded) {
                    // The **target** threw. Record it and let the exception travel to
                    // the caller: calling the origin a second time here (which is what
                    // this used to do) would run the method's side effects twice — a
                    // debugger must never change the behaviour of the method it
                    // watches, and a method that throws is exactly where that matters.
                    if (recording) {
                        appendCall(
                            argsText = argsText,
                            result = null,
                            threw = "${t.javaClass.name}: ${t.message ?: ""}".trim(),
                            startedNs = startedNs,
                        )
                    }
                    throw t
                }
                RegTrailLog.w("[$id] hook error", t)
                // PROTECTIVE mode already contains this, but be explicit: never
                // let a debugger bug change the target's behaviour silently.
                return chain.proceed()
            }
        }

        /** Formats the live arguments for the 调试 tab; never throws. */
        private fun captureArgs(chain: XposedInterface.Chain): List<String> = runCatching {
            chain.args.map { truncate(stringify(it)) }
        }.getOrDefault(emptyList())

        private fun appendCall(
            argsText: List<String>,
            result: String?,
            threw: String?,
            startedNs: Long,
        ) {
            runCatching {
                val entry = CallEntry(
                    seq = callSeq.incrementAndGet(),
                    atMillis = System.currentTimeMillis(),
                    thread = Thread.currentThread().name,
                    args = argsText,
                    result = result?.let { truncate(it) },
                    threw = threw,
                    durationMs = (System.nanoTime() - startedNs) / 1_000_000L,
                )
                synchronized(callLock) {
                    callLog.addLast(entry)
                    while (callLog.size > MAX_CALLS_PER_HOOK) {
                        callLog.removeFirst()
                        callDropped.incrementAndGet()
                    }
                }
            }.onFailure {
                // Recording must never disturb the host app; swallowing here is the
                // whole point of the try/catch.
                RegTrailLog.w("[$id] cannot record call", it)
            }
        }

        fun calls(since: Long, limit: Int): CallsPage {
            val snapshot: List<CallEntry>
            val dropped: Long
            synchronized(callLock) {
                snapshot = if (since <= 0L) callLog.toList() else callLog.filter { it.seq > since }
                dropped = callDropped.get()
            }
            val page = if (snapshot.size > limit) snapshot.takeLast(limit) else snapshot
            return CallsPage(
                entries = page,
                next = page.lastOrNull()?.seq ?: since,
                total = snapshot.size,
                dropped = dropped,
                hookCalls = calls.get(),
            )
        }

        fun clearCalls(): Boolean {
            synchronized(callLock) { callLog.clear() }
            return true
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
            recording = behavior.record,
            recorded = synchronized(callLock) { callLog.size },
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

    /** Keeps one recorded value short enough to render in a 300 dp panel. */
    private fun truncate(text: String): String =
        if (text.length <= MAX_VALUE_CHARS) text else text.take(MAX_VALUE_CHARS) + "…"

    /**
     * How many calls one hook keeps for the 调试 tab.
     *
     * A hot method can run thousands of times per second; a ring this size is what
     * makes the feature safe to leave installed, and the page caps what it renders on
     * top of that.
     */
    const val MAX_CALLS_PER_HOOK = 200

    /** How long one recorded argument/return value may be. */
    private const val MAX_VALUE_CHARS = 160

    /** How many methods one search returns. */
    const val SEARCH_LIMIT = 60

    /**
     * Time budget for the expensive method-name pass of [search].
     *
     * The search runs on the JS bridge thread, which the panel needs to stay
     * responsive. 250 ms is invisible for a manual query and bounds the damage on a
     * process with thousands of loaded classes.
     */
    private const val METHOD_SCAN_MS = 250L

    /** How many classes the method-name pass may reflect over. */
    private const val METHOD_SCAN_CLASSES = 900

    /**
     * How many superclasses [collectMethods] / [resolveExecutable] walk.
     *
     * Deep enough for the real hierarchies in an Android app (Activity ← BaseActivity
     * ← FragmentActivity ← ComponentActivity ← …), shallow enough that a broad search
     * cannot spend its whole budget on `java.lang.Object`.
     */
    private const val MAX_SUPER_DEPTH = 8
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
     * Keep the arguments and the return value of each call for the 调试 tab.
     *
     * Separate from [trace]: tracing writes to the framework log, recording keeps the
     * values in a bounded ring the floating window can poll. A debugger that wants to
     * *see* calls live needs the latter without necessarily spamming the log.
     */
    val record: Boolean = false,
    /** Record every Nth call; 1 means every call. */
    val recordEvery: Long = 1,
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
