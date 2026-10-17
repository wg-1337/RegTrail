package io.github.wg1337.regtrail.xposed.script

import android.app.Activity
import android.app.Application
import android.content.Context
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.wg1337.regtrail.shared.model.ScriptAction
import io.github.wg1337.regtrail.shared.model.ScriptSpec
import io.github.wg1337.regtrail.shared.model.ScriptTrigger
import io.github.wg1337.regtrail.xposed.RegTrailLog
import io.github.wg1337.regtrail.xposed.debug.DebugEngine
import io.github.wg1337.regtrail.xposed.debug.DebugHookBehavior
import io.github.wg1337.regtrail.xposed.overlay.ModuleConfig
import java.lang.reflect.Executable
import java.lang.reflect.Modifier

/**
 * Executes recorded debug scripts inside the target process.
 *
 * ## What a "script" is
 * A script is a named, ordered list of [ScriptAction]s plus a [ScriptTrigger]
 * that decides when it runs. This is the other half of feature ③: the debugger
 * produces the actions interactively, this engine replays them automatically.
 *
 * ## Why triggers are registered eagerly
 * Most useful triggers fire *before* the user could possibly tap anything —
 * `AppAttach`, `AppOnCreate`, `ClassLoaded`. So on boot the engine installs one
 * dispatcher hook per trigger kind, then looks up which scripts want it. That
 * keeps the hot path to a single map lookup instead of iterating every script on
 * every method call.
 *
 * ## Failure policy
 * A broken script must never break the host app, so every action is wrapped and
 * every hook runs in PROTECTIVE exception mode. A script that fails logs once and
 * is disabled, rather than spamming on every invocation.
 */
internal object ScriptEngine {

    private lateinit var module: XposedModule

    /** Scripts indexed by the trigger kind that should fire them. */
    private val byTrigger = HashMap<ScriptTrigger, MutableList<ScriptSpec>>()

    /** Scripts that failed, so they are not retried forever. */
    private val broken = HashSet<String>()

    /** ClassLoader of the target app, captured as soon as it is known. */
    @Volatile
    private var loader: ClassLoader? = null

    @Volatile
    private var configuredRevision: Long = -1L

    /** True once the class-load hook is installed big picture-wise. */
    @Volatile
    private var classHookInstalled = false

    // -----------------------------------------------------------------------
    // Boot
    // -----------------------------------------------------------------------

    /**
     * Reads the scripts out of the published config and installs trigger hooks.
     *
     * Idempotent per config revision: called on app attach and whenever the
     * settings app republishes.
     */
    fun boot(module: XposedModule, classLoader: ClassLoader, context: Context?) {
        this.module = module
        this.loader = classLoader

        val snapshot = ModuleConfig(module).reload()
        if (snapshot.revision == configuredRevision) return
        configuredRevision = snapshot.revision

        // Re-index. Old hook registrations stay alive but resolve to the new
        // script list, so editing a script does not need a reboot.
        //
        // Guarded because the floating window's 「列出脚本」 button reads this map
        // from the WebView's JS-bridge thread, which is *not* the thread that runs
        // this re-index. A concurrent HashMap read against a write can corrupt or
        // throw, and a throw here would take down injection.
        synchronized(byTrigger) {
            byTrigger.clear()
            broken.clear()

            val enabled = snapshot.raw.scripts.filter { it.enabled && it.id.isNotBlank() }
            enabled.forEach { script ->
                byTrigger.getOrPut(script.trigger) { mutableListOf() }.add(script)
            }

            RegTrailLog.i(
                "ScriptEngine: ${enabled.size} script(s) loaded " +
                    byTrigger.entries.joinToString { "${it.key}=${it.value.size}" },
            )
        }

        // Install the dispatchers this config needs.
        if (byTrigger.containsKey(ScriptTrigger.ClassLoaded)) installClassLoadedHook()
        if (byTrigger.containsKey(ScriptTrigger.MethodEnter)) installMethodEnterHooks()

        // Triggers that already passed still get a chance: app-level ones are
        // fired explicitly by OverlayController right after boot.
        fire(ScriptTrigger.AppAttach, context)
    }

    /** Called by the overlay controller once the target Application is created. */
    fun onApplicationCreated(context: Context) {
        fire(ScriptTrigger.AppOnCreate, context)
    }

    /** Called from the Activity lifecycle tracker. */
    fun onActivity(activity: Activity, created: Boolean) {
        val trigger = if (created) {
            ScriptTrigger.ActivityOnCreate
        } else {
            ScriptTrigger.ActivityOnResume
        }
        fire(trigger, activity, activityClass = activity.javaClass.name)
    }

    // -----------------------------------------------------------------------
    // Trigger dispatch
    // -----------------------------------------------------------------------

    private fun fire(
        trigger: ScriptTrigger,
        context: Context?,
        activityClass: String? = null,
        extra: Any? = null,
    ) {
        val scripts = byTrigger[trigger] ?: return
        val activeLoader = loader ?: return

        scripts.forEach { script ->
            if (script.id in broken) return@forEach

            // Activity triggers may target one specific Activity class.
            if (activityClass != null && !script.triggerTarget.isNullOrBlank()) {
                val target = script.triggerTarget
                if (!activityClass.contains(target!!, ignoreCase = true)) return@forEach
            }

            runCatching {
                execute(script, activeLoader, context, extra)
            }.onFailure { t ->
                broken += script.id
                RegTrailLog.w("ScriptEngine: script '${script.name}' failed, disabled", t)
            }
        }
    }

    /** Entry point for the floating window's "run script" button. */
    fun runManual(scriptId: String, context: Context?): String {
        val activeLoader = loader ?: return "尚未捕获目标 ClassLoader"
        // Copied under the same lock `boot()` writes with; see there.
        val script = synchronized(byTrigger) {
            byTrigger.values.flatten().firstOrNull { it.id == scriptId }
        } ?: return "未找到脚本 $scriptId"

        return runCatching {
            execute(script, activeLoader, context, null)
            "已执行 ${script.name}（${script.actions.size} 个动作）"
        }.getOrElse { t ->
            broken += script.id
            "执行失败：${t.message ?: t::class.java.simpleName}"
        }
    }

    /**
     * The scripts the floating window may offer a 「运行」 button for.
     *
     * Returns whole [ScriptSpec]s rather than bare ids because the tools tab
     * shows a *name* next to each button: an id alone is usually a UUID-like
     * string and tells the user nothing. `distinctBy` guards against a config
     * that lists the same script under two triggers.
     */
    fun scriptEntries(): List<ScriptSpec> = synchronized(byTrigger) {
        byTrigger.values.flatten().filter { it.enabled }.distinctBy { it.id }
    }

    val scriptIds: List<String> get() = scriptEntries().map { it.id }

    // -----------------------------------------------------------------------
    // Execution
    // -----------------------------------------------------------------------

    private fun execute(
        script: ScriptSpec,
        loader: ClassLoader,
        context: Context?,
        extra: Any?,
    ) {
        RegTrailLog.i("ScriptEngine: running '${script.name}' (${script.actions.size} actions)")
        script.actions.forEach { action ->
            runCatching { executeAction(action, loader, context, extra) }
                .onFailure { RegTrailLog.w("ScriptEngine: action ${action.kind} failed", it) }
        }
    }

    private fun executeAction(
        action: ScriptAction,
        loader: ClassLoader,
        context: Context?,
        extra: Any?,
    ) {
        val className = action.className ?: return
        val methodName = action.memberName

        when (action.kind) {
            // ---- Immediate actions ----------------------------------------
            ScriptAction.Kind.GetField -> {
                val cls = Class.forName(className, false, loader)
                val field = findField(cls, methodName ?: return) ?: return
                field.isAccessible = true
                val receiver = if (Modifier.isStatic(field.modifiers)) null else extra
                RegTrailLog.i(
                    "ScriptEngine: ${className}#${field.name} = ${safeToString(field.get(receiver))}",
                )
            }

            ScriptAction.Kind.SetField -> {
                val cls = Class.forName(className, false, loader)
                val field = findField(cls, methodName ?: return) ?: return
                field.isAccessible = true
                val value = coerce(action.value, field.type, action.valueType)
                val receiver = if (Modifier.isStatic(field.modifiers)) null else extra
                field.set(receiver, value)
                RegTrailLog.i("ScriptEngine: set ${className}#${field.name} = ${safeToString(value)}")
            }

            ScriptAction.Kind.CallMethod -> {
                val method = resolveExecutable(loader, action) ?: return
                method.isAccessible = true
                val params = arrayOfNulls<Any?>(method.parameterTypes.size)
                val receiver = if (Modifier.isStatic(method.modifiers)) null else extra
                val result = (method as? java.lang.reflect.Method)?.invoke(receiver, *params)
                RegTrailLog.i("ScriptEngine: ${describe(method)} → ${safeToString(result)}")
            }

            // ---- Hook-based actions ---------------------------------------
            ScriptAction.Kind.Trace,
            ScriptAction.Kind.SetReturn,
            ScriptAction.Kind.SetArg,
            -> {
                val target = resolveExecutable(loader, action) ?: return
                installActionHook(target, action)
            }
        }
    }

    private fun installActionHook(target: Executable, action: ScriptAction) {
        val behavior = when (action.kind) {
            ScriptAction.Kind.Trace -> DebugHookBehavior.trace(
                id = "script-${describe(target)}",
            )

            ScriptAction.Kind.SetReturn -> DebugHookBehavior.forceReturn(
                id = "script-${describe(target)}",
                value = action.value,
                valueType = action.valueType.name.lowercase(),
            )

            ScriptAction.Kind.SetArg -> DebugHookBehavior.replaceArg(
                id = "script-${describe(target)}",
                index = 0,
                value = coerce(
                    action.value,
                    target.parameterTypes.firstOrNull() ?: Any::class.java,
                    action.valueType,
                ),
            )

            else -> return
        }

        DebugEngine.installHook(
            target = target,
            behavior = behavior,
            label = "script:${action.kind}",
        ).onSuccess { RegTrailLog.i("ScriptEngine: hook installed for ${describe(target)}") }
            .onFailure { RegTrailLog.w("ScriptEngine: hook failed for ${describe(target)}", it) }
    }

    // -----------------------------------------------------------------------
    // Trigger hooks
    // -----------------------------------------------------------------------

    /**
     * Fires `ClassLoaded` scripts whenever a class is loaded.
     *
     * Hooks `ClassLoader.loadClass` once. Matching is done by the script's
     * `triggerTarget`, so a script only runs for the class it names.
     */
    private fun installClassLoadedHook() {
        if (classHookInstalled) return
        classHookInstalled = true

        runCatching {
            val loadClass = ClassLoader::class.java.getDeclaredMethod(
                "loadClass",
                String::class.java,
                Boolean::class.javaPrimitiveType,
            )

            module.hook(loadClass)
                .setId("regtrail.script.classloaded")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val name = chain.getArg(0) as? String
                    val result = chain.proceed()

                    if (name != null) {
                        val scripts = byTrigger[ScriptTrigger.ClassLoaded]
                        if (!scripts.isNullOrEmpty()) {
                            scripts.forEach { script ->
                                if (script.id in broken) return@forEach
                                val matches = script.triggerTarget
                                    ?.let { name.contains(it, ignoreCase = true) }
                                    ?: false
                                if (!matches) return@forEach
                                runCatching {
                                    execute(script, loader ?: return@forEach, null, null)
                                }.onFailure { broken += script.id }
                            }
                        }
                    }
                    result
                }
            RegTrailLog.i("ScriptEngine: ClassLoaded dispatcher installed")
        }.onFailure { RegTrailLog.w("ScriptEngine: cannot hook ClassLoader.loadClass", it) }
    }

    /** Hooks each method named by a `MethodEnter` script, once. */
    private fun installMethodEnterHooks() {
        byTrigger[ScriptTrigger.MethodEnter].orEmpty().forEach { script ->
            val signature = script.triggerMethod ?: return@forEach
            val activeLoader = loader ?: return@forEach

            runCatching {
                val parsed = parseSignature(signature) ?: error("无法解析 $signature")
                val target = DebugEngine.resolveMethod(
                    loader = activeLoader,
                    className = parsed.first,
                    methodName = parsed.second,
                    paramTypes = emptyList(),
                ) ?: error("找不到方法 $signature")

                module.hook(target)
                    .setId("regtrail.script.enter.${script.id}")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        // `thisObject` is not exposed by the framework Chain, so
                        // scripts that need an instance receiver should use the
                        // `receiver` field / a static hook instead.
                        if (script.id !in broken) {
                            runCatching { execute(script, activeLoader, null, null) }
                                .onFailure { broken += script.id }
                        }
                        result
                    }
                RegTrailLog.i("ScriptEngine: MethodEnter hook on $signature")
            }.onFailure { RegTrailLog.w("ScriptEngine: MethodEnter hook failed for $signature", it) }
        }
    }

    // -----------------------------------------------------------------------
    // Reflection helpers
    // -----------------------------------------------------------------------

    private fun parseSignature(signature: String): Pair<String, String>? {
        val hash = signature.indexOf('#')
        if (hash <= 0) return null
        val rest = signature.substring(hash + 1).substringBefore('(')
        return signature.substring(0, hash) to rest
    }

    private fun resolveExecutable(
        loader: ClassLoader,
        action: ScriptAction,
    ): Executable? {
        val className = action.className ?: return null
        val methodName = action.memberName ?: return null
        val cls = runCatching { Class.forName(className, false, loader) }.getOrNull() ?: return null

        // Prefer an exact signature match when one is recorded.
        val wanted = action.signature
            ?.removePrefix("(")
            ?.removeSuffix(")")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        val candidates: Array<out Executable> = if (methodName == "<init>") {
            cls.declaredConstructors
        } else {
            cls.declaredMethods.filter { it.name == methodName }.toTypedArray()
        }

        return candidates.firstOrNull { ex ->
            wanted.isEmpty() || ex.parameterTypes.map { it.name } == wanted
        }
    }

    private fun findField(cls: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = cls
        while (c != null) {
            runCatching { c.getDeclaredField(name) }.getOrNull()?.let { return it }
            c = c.superclass
        }
        return null
    }

    /** Text → typed value, driven by the destination type. */
    private fun coerce(
        raw: String?,
        type: Class<*>,
        hint: ScriptAction.ValueType,
    ): Any? {
        if (raw == null && hint == ScriptAction.ValueType.Null) return null
        if (hint == ScriptAction.ValueType.Null) return null

        return runCatching {
            when (type) {
                java.lang.Boolean.TYPE, java.lang.Boolean::class.java ->
                    raw == "1" || raw.equals("true", true)
                java.lang.Integer.TYPE, java.lang.Integer::class.java -> raw?.toIntOrNull()
                java.lang.Long.TYPE, java.lang.Long::class.java -> raw?.toLongOrNull()
                java.lang.Float.TYPE, java.lang.Float::class.java -> raw?.toFloatOrNull()
                java.lang.Double.TYPE, java.lang.Double::class.java -> raw?.toDoubleOrNull()
                java.lang.Short.TYPE, java.lang.Short::class.java -> raw?.toShortOrNull()
                java.lang.Byte.TYPE, java.lang.Byte::class.java -> raw?.toByteOrNull()
                java.lang.Character.TYPE, java.lang.Character::class.java -> raw?.firstOrNull()
                String::class.java -> raw
                else -> when (hint) {
                    ScriptAction.ValueType.String -> raw
                    else -> raw
                }
            }
        }.getOrNull()
    }

    private fun describe(target: Executable): String {
        val owner = target.declaringClass.name
        val name = if (target is java.lang.reflect.Method) target.name else "<init>"
        return "$owner#$name(" + target.parameterTypes.joinToString(",") { it.name } + ")"
    }

    private fun safeToString(value: Any?): String =
        runCatching { value?.toString() ?: "null" }.getOrDefault("<unprintable>")
}

/**
 * Hooks `Application.attach`/`onCreate` and the Activity lifecycle so scripts can
 * fire at the documented triggers, **without** the overlay controller having to
 * know about scripts.
 */
internal object ScriptLifecycleBridge {

    @Volatile
    private var installed = false

    fun install(module: XposedModule, application: Application, loader: ClassLoader) {
        // Scripts that want the Application lifecycle first.
        runCatching { ScriptEngine.boot(module, loader, application) }
            .onFailure { RegTrailLog.w("ScriptLifecycleBridge: boot failed", it) }

        if (installed) return
        installed = true

        runCatching {
            application.registerActivityLifecycleCallbacks(
                object : Application.ActivityLifecycleCallbacks {

                    override fun onActivityCreated(activity: Activity, state: android.os.Bundle?) {
                        ScriptEngine.onActivity(activity, created = true)
                    }

                    override fun onActivityResumed(activity: Activity) {
                        ScriptEngine.onActivity(activity, created = false)
                    }

                    override fun onActivityStarted(activity: Activity) = Unit
                    override fun onActivityPaused(activity: Activity) = Unit
                    override fun onActivityStopped(activity: Activity) = Unit
                    override fun onActivitySaveInstanceState(
                        activity: Activity,
                        state: android.os.Bundle,
                    ) = Unit

                    override fun onActivityDestroyed(activity: Activity) = Unit
                },
            )
            ScriptEngine.onApplicationCreated(application)
        }.onFailure { RegTrailLog.w("ScriptLifecycleBridge: lifecycle registration failed", it) }
    }
}
