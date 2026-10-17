package io.github.wg1337.regtrail.xposed.bridge

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.wg1337.regtrail.shared.bridge.BridgeApi
import io.github.wg1337.regtrail.xposed.RegTrailLog
import io.github.wg1337.regtrail.xposed.debug.ActivityTracker
import io.github.wg1337.regtrail.xposed.debug.DebugEngine
import io.github.wg1337.regtrail.xposed.debug.DebugHookBehavior
import io.github.wg1337.regtrail.xposed.debug.InstanceRegistry
import io.github.wg1337.regtrail.xposed.mem.MemorySessions
import io.github.wg1337.regtrail.xposed.script.ScriptEngine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.lang.reflect.Modifier

/**
 * The tool surface the Agent drives, executed **inside the target process**.
 *
 * Every tool is a pure read or a debugger action; nothing here is destructive to
 * the host app unless the user explicitly installed a hook. Results are JSON so
 * the agent can reason over them without a bespoke parser per tool.
 *
 * ## Why these must run in-process
 * Class loaders, live object graphs and field values only exist here. The
 * settings app could never reach them across a process boundary, which is the
 * whole reason the module exists.
 */
internal object AgentTools {

    /**
     * Dispatches one tool call.
     *
     * @param context the target app's context, used for Activity/View inspection.
     * @param loader the target app's class loader.
     * @param shouldStop polled by the memory tools, which can read 64 MiB. The
     *   settings-app path passes a constant `false` (a Binder call has its own
     *   timeout); the in-window Agent passes its job's cancellation flag.
     */
    fun run(
        name: String,
        args: JsonObject,
        context: android.content.Context?,
        loader: ClassLoader,
        shouldStop: () -> Boolean = { false },
    ): BridgeApi.Response = try {
        when (name) {
            "list_classes" -> listClasses(args, loader)
            "find_class" -> findClass(args, loader)
            "dump_class" -> dumpClass(args, loader)
            "list_activities" -> listActivities(context)
            "dump_view_tree" -> dumpViewTree(context, args)
            "list_hooks" -> listHooks()
            "unhook" -> unhook(args)
            "get_field" -> getField(args, loader)
            "set_field" -> setField(args, loader)
            "get_static_field" -> getStaticField(args, loader)
            "call_method" -> callMethod(args, loader)
            "hook_method" -> hookMethod(args, loader)
            // In-process memory tools. These are the same engine the 内存 tab uses
            // (see `xposed.mem.InProcessMemory`), exposed to the Agent because "find
            // the counter and change it" is the single most common request and it
            // needs no pid, no root and no shell here.
            "mem_scan" -> memScan(args, shouldStop)
            "mem_refine" -> memRefine(args, shouldStop)
            "mem_write" -> memWrite(args)
            "mem_revert" -> memRevert(args)
            // Scripts: the ones the settings app published, executed by the
            // script engine that already owns the trigger hooks. `script_run` is
            // the *same* entry point the floating window's 工具 tab calls, so
            // there is one implementation and it reports failures the same way.
            "script_list" -> scriptList()
            "script_get" -> scriptGet(args)
            "script_run" -> scriptRun(args, context)
            else -> toolError("unknown tool: $name")
        }
    } catch (t: Throwable) {
        RegTrailLog.w("tool $name failed", t)
        toolError(t.message ?: t::class.java.simpleName)
    }

    // -----------------------------------------------------------------------
    // Class inspection
    // -----------------------------------------------------------------------

    private fun listClasses(args: JsonObject, loader: ClassLoader): BridgeApi.Response {
        val pattern = args.str("pattern") ?: ""
        val limit = args.int("limit") ?: 200
        val classes = DebugEngine.loadedClasses(loader)
            .asSequence()
            .map { it.name }
            .filter { pattern.isBlank() || it.contains(pattern, ignoreCase = true) }
            .sorted()
            .take(limit)
            .toList()

        return BridgeApi.Response(
            ok = true,
            data = buildJsonObject {
                put("total", JsonPrimitive(DebugEngine.loadedClasses(loader).size))
                put("returned", JsonPrimitive(classes.size))
                put("classes", buildJsonArray { classes.forEach { add(JsonPrimitive(it)) } })
            },
        )
    }

    private fun findClass(args: JsonObject, loader: ClassLoader): BridgeApi.Response {
        val name = args.str("name") ?: return toolError("missing 'name'")
        val cls = runCatching { Class.forName(name, false, loader) }.getOrNull()
            ?: return toolError("class not found: $name")
        return BridgeApi.Response(
            ok = true,
            data = mapToJson(DebugEngine.describeClass(cls)),
        )
    }

    private fun dumpClass(args: JsonObject, loader: ClassLoader): BridgeApi.Response =
        findClass(args, loader)

    // -----------------------------------------------------------------------
    // Hooks
    // -----------------------------------------------------------------------

    private fun listHooks(): BridgeApi.Response {
        val hooks = DebugEngine.snapshot()
        return BridgeApi.Response(
            ok = true,
            data = buildJsonObject {
                put("count", JsonPrimitive(hooks.size))
                put("hooks", buildJsonArray {
                    hooks.forEach { h ->
                        add(buildJsonObject {
                            put("id", JsonPrimitive(h.id))
                            put("target", JsonPrimitive(h.target))
                            put("label", JsonPrimitive(h.label))
                            put("calls", JsonPrimitive(h.calls))
                            put("trace", JsonPrimitive(h.trace))
                            put("returnOverride", BridgeApi.toJsonElement(h.returnOverride))
                        })
                    }
                })
            },
        )
    }

    private fun unhook(args: JsonObject): BridgeApi.Response {
        val id = args.str("id") ?: return toolError("missing 'id'")
        val removed = DebugEngine.unhook(id)
        return if (removed) {
            BridgeApi.Response(ok = true, data = jsonObjectOf("removed" to id))
        } else {
            toolError("no such hook: $id")
        }
    }

    private fun hookMethod(args: JsonObject, loader: ClassLoader): BridgeApi.Response {
        val signature = args.str("signature") ?: return toolError("missing 'signature'")

        val parsed = parseSignature(signature)
            ?: return toolError("无法解析签名，期望 com.example.Foo#bar(int,java.lang.String)")

        val target = DebugEngine.resolveMethod(
            loader = loader,
            className = parsed.className,
            methodName = parsed.methodName,
            paramTypes = parsed.paramTypes,
        ) ?: return toolError("找不到方法: $signature")

        val mode = args.str("mode") ?: "trace"
        // DebugHookBehavior already produces the engine's HookBehavior.
        val behavior = when (mode) {
            "trace" -> DebugHookBehavior.trace(
                id = args.str("id").orEmpty(),
                every = args.long("every") ?: 1L,
            )
            "return" -> DebugHookBehavior.forceReturn(
                id = args.str("id").orEmpty(),
                value = args.str("value"),
                valueType = args.str("valueType") ?: "auto",
            )
            "throw" -> DebugHookBehavior.throwInstead(
                id = args.str("id").orEmpty(),
                throwable = IllegalStateException(
                    args.str("value") ?: "thrown by RegTrail",
                ),
            )
            else -> DebugHookBehavior.trace(id = args.str("id").orEmpty())
        }

        val id = DebugEngine.installHook(
            target = target,
            behavior = behavior,
            label = args.str("label") ?: signature,
        ).getOrElse { return toolError("安装 Hook 失败: ${it.message}") }

        return BridgeApi.Response(
            ok = true,
            data = jsonObjectOf(
                "id" to id,
                "target" to DebugEngine.describe(target),
            ),
        )
    }

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private fun getField(args: JsonObject, loader: ClassLoader): BridgeApi.Response {
        val className = args.str("className") ?: return toolError("missing 'className'")
        val fieldName = args.str("fieldName") ?: return toolError("missing 'fieldName'")

        val cls = runCatching { Class.forName(className, false, loader) }.getOrNull()
            ?: return toolError("class not found: $className")

        val field = findField(cls, fieldName)
            ?: return toolError("field not found: $className#$fieldName")

        val target = if (Modifier.isStatic(field.modifiers)) {
            null
        } else {
            val instance = args.str("instance")?.let { InstanceRegistry.get(it) }
                ?: return toolError("实例字段需要 'instance'（先用工具获取实例句柄）")
            instance
        }

        field.isAccessible = true
        return valueResponse("value", stringify(field.get(target)))
    }

    private fun getStaticField(args: JsonObject, loader: ClassLoader): BridgeApi.Response {
        val className = args.str("className") ?: return toolError("missing 'className'")
        val fieldName = args.str("fieldName") ?: return toolError("missing 'fieldName'")

        val cls = runCatching { Class.forName(className, false, loader) }.getOrNull()
            ?: return toolError("class not found: $className")
        val field = findField(cls, fieldName)
            ?: return toolError("field not found: $className#$fieldName")

        field.isAccessible = true
        return valueResponse("value", stringify(field.get(null)))
    }

    private fun setField(args: JsonObject, loader: ClassLoader): BridgeApi.Response {
        val className = args.str("className") ?: return toolError("missing 'className'")
        val fieldName = args.str("fieldName") ?: return toolError("missing 'fieldName'")
        val raw = args.str("value") ?: return toolError("missing 'value'")

        val cls = runCatching { Class.forName(className, false, loader) }.getOrNull()
            ?: return toolError("class not found: $className")
        val field = findField(cls, fieldName)
            ?: return toolError("field not found: $className#$fieldName")

        val coerced = coerce(raw, field.type, args.str("valueType") ?: "auto")
            ?: return toolError("无法把 '$raw' 转换为 ${field.type.name}")

        val target = if (Modifier.isStatic(field.modifiers)) {
            null
        } else {
            args.str("instance")?.let { InstanceRegistry.get(it) }
                ?: return toolError("实例字段需要 'instance'")
        }

        field.isAccessible = true
        val before = runCatching { stringify(field.get(target)) }.getOrNull()
        field.set(target, coerced)

        return BridgeApi.Response(
            ok = true,
            data = jsonObjectOf("before" to before, "after" to stringify(coerced)),
        )
    }

    // -----------------------------------------------------------------------
    // Methods
    // -----------------------------------------------------------------------

    private fun callMethod(args: JsonObject, loader: ClassLoader): BridgeApi.Response {
        val signature = args.str("signature") ?: return toolError("missing 'signature'")
        val parsed = parseSignature(signature) ?: return toolError("无法解析签名")

        val method = DebugEngine.resolveMethod(
            loader = loader,
            className = parsed.className,
            methodName = parsed.methodName,
            paramTypes = parsed.paramTypes,
        ) as? java.lang.reflect.Method ?: return toolError("找不到方法: $signature")

        val receiver = if (Modifier.isStatic(method.modifiers)) {
            null
        } else {
            args.str("instance")?.let { InstanceRegistry.get(it) }
                ?: return toolError("实例方法需要 'instance'")
        }

        val rawArgs = args["args"] as? JsonArray
        val params = method.parameterTypes.mapIndexed { i, type ->
            val element = rawArgs?.getOrNull(i)
            val text = (element as? JsonPrimitive)?.contentOrNull
            if (text == null) {
                if (type.isPrimitive) {
                    return toolError("参数 $i (${type.name}) 是原生类型，必须提供值")
                }
                null
            } else {
                coerce(text, type, "auto")
                    ?: return toolError("无法把 '$text' 转换为 ${type.name}")
            }
        }.toTypedArray()

        method.isAccessible = true
        val result = method.invoke(receiver, *params)
        return valueResponse("result", stringify(result))
    }

    // -----------------------------------------------------------------------
    // UI inspection
    // -----------------------------------------------------------------------

    private fun listActivities(context: android.content.Context?): BridgeApi.Response {
        val activities = ActivityTracker.current()
        return BridgeApi.Response(
            ok = true,
            data = buildJsonObject {
                put("count", JsonPrimitive(activities.size))
                put("activities", buildJsonArray {
                    activities.forEach { a ->
                        add(buildJsonObject {
                            put("class", JsonPrimitive(a.javaClass.name))
                            put("title", JsonPrimitive(runCatching { a.title?.toString() }.getOrNull()))
                            put("resumed", JsonPrimitive(a === activities.lastOrNull()))
                            put("hash", JsonPrimitive(System.identityHashCode(a)))
                        })
                    }
                })
            },
        )
    }

    private fun dumpViewTree(
        context: android.content.Context?,
        args: JsonObject,
    ): BridgeApi.Response {
        val maxDepth = args.int("maxDepth") ?: 12
        val activity = ActivityTracker.current().lastOrNull()
            ?: return toolError("当前没有活动 Activity")

        val root = activity.window?.decorView
            ?: return toolError("Activity 没有 decorView")

        val sb = StringBuilder()
        walk(root, 0, maxDepth, sb)
        return valueResponse("tree", sb.toString())
    }

    private fun walk(view: View, depth: Int, maxDepth: Int, out: StringBuilder) {
        if (depth > maxDepth) return
        repeat(depth) { out.append("  ") }
        out.append(view.javaClass.name)
        out.append(" id=").append(
            runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault(view.id.toString()),
        )
        out.append(" ").append(view.width).append('x').append(view.height)
        if (view is TextView) {
            val text = view.text?.toString().orEmpty()
            if (text.isNotEmpty()) out.append(" text=\"").append(text.take(60)).append('"')
        }
        out.append('\n')

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                walk(view.getChildAt(i), depth + 1, maxDepth, out)
            }
        }
    }

    // -----------------------------------------------------------------------
    // In-process memory
    // -----------------------------------------------------------------------

    /**
     * First memory scan, in this process's own address space.
     *
     * The result is *compacted* before it reaches the Agent: a 500-hit scan is
     * ~60 KB of JSON, which would burn most of a model's context on addresses it
     * cannot use yet. The compact form keeps the session id, the counts and the
     * first addresses, and only inlines per-address values when the set is small
     * enough for a human (≤ 20) to actually look at.
     */
    private fun memScan(args: JsonObject, shouldStop: () -> Boolean): BridgeApi.Response = runCatching {
        MemorySessions.scan(
            typeRaw = args.str("type") ?: "int32",
            value = args.str("value"),
            writableOnly = args.bool("writableOnly") ?: false,
            maxResults = args.int("maxResults") ?: MemorySessions.DEFAULT_MAX_RESULTS,
            shouldStop = shouldStop,
        )
    }.fold(
        onSuccess = { BridgeApi.Response(ok = true, data = compactScan(it)) },
        onFailure = { toolError(it.message ?: "内存扫描失败") },
    )

    private fun memRefine(args: JsonObject, shouldStop: () -> Boolean): BridgeApi.Response =
        runCatching {
            val session = args.int("session") ?: return toolError("missing 'session'")
            MemorySessions.refine(
                sessionId = session,
                value = args.str("value"),
                typeRaw = args.str("type"),
                maxResults = args.int("maxResults") ?: MemorySessions.DEFAULT_MAX_RESULTS,
                shouldStop = shouldStop,
            )
        }.fold(
            onSuccess = { BridgeApi.Response(ok = true, data = compactScan(it)) },
            onFailure = { toolError(it.message ?: "二次扫描失败") },
        )

    private fun memWrite(args: JsonObject): BridgeApi.Response = runCatching {
        MemorySessions.write(
            addressRaw = args.str("addr") ?: args.str("address"),
            typeRaw = args.str("type"),
            value = args.str("value"),
        )
    }.fold(
        onSuccess = { BridgeApi.Response(ok = true, data = it) },
        onFailure = { toolError(it.message ?: "写入失败") },
    )

    private fun memRevert(args: JsonObject): BridgeApi.Response = runCatching {
        MemorySessions.revert(args.str("addr") ?: args.str("address"))
    }.fold(
        onSuccess = { BridgeApi.Response(ok = true, data = it) },
        onFailure = { toolError(it.message ?: "还原失败") },
    )

    /** Addresses only when there are many; full rows when a human can read them. */
    private fun compactScan(data: JsonObject): JsonObject {
        val results = data["results"] as? JsonArray ?: return data
        val count = (data["count"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: results.size
        return buildJsonObject {
            for (key in listOf("session", "type", "value", "count", "truncated", "note")) {
                val element = data[key] ?: continue
                put(key, element)
            }
            put(
                "addresses",
                buildJsonArray {
                    results.take(COMPACT_ADDRESS_LIMIT).forEach { row ->
                        val address = (row as? JsonObject)?.get("addr") ?: return@forEach
                        add(address)
                    }
                },
            )
            if (count <= COMPACT_FULL_ROWS) put("results", results)
            put(
                "hint",
                JsonPrimitive(
                    "命中较多时只给出 addresses；用 mem_refine 复核后结果会自动变小",
                ),
            )
        }
    }

    // -----------------------------------------------------------------------
    // Scripts
    // -----------------------------------------------------------------------

    /**
     * The scripts the settings app published into `config.json`.
     *
     * Only entries the engine actually holds are listed: a script whose
     * `enabled` flag is false is not registered, so offering it here would
     * promise a run that cannot happen.
     */
    private fun scriptList(): BridgeApi.Response {
        val scripts = ScriptEngine.scriptEntries()
        return BridgeApi.Response(
            ok = true,
            data = buildJsonObject {
                put("count", JsonPrimitive(scripts.size))
                put(
                    "scripts",
                    buildJsonArray {
                        scripts.forEach { spec ->
                            add(
                                buildJsonObject {
                                    put("id", JsonPrimitive(spec.id))
                                    put("name", JsonPrimitive(spec.name))
                                    put("trigger", JsonPrimitive(spec.trigger.name))
                                    put("actions", JsonPrimitive(spec.actions.size))
                                    put("version", JsonPrimitive(spec.version))
                                    if (spec.description.isNotBlank()) {
                                        put("description", JsonPrimitive(spec.description))
                                    }
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    /** One published script in full, so the model can reason about its actions. */
    private fun scriptGet(args: JsonObject): BridgeApi.Response {
        val id = args.str("id") ?: return toolError("missing 'id'")
        val spec = ScriptEngine.scriptEntries().firstOrNull { it.id == id }
            ?: return toolError("没有已下发的脚本 $id（先看 script_list）")

        return BridgeApi.Response(
            ok = true,
            data = buildJsonObject {
                put("id", JsonPrimitive(spec.id))
                put("name", JsonPrimitive(spec.name))
                put("enabled", JsonPrimitive(spec.enabled))
                put("trigger", JsonPrimitive(spec.trigger.name))
                put("version", JsonPrimitive(spec.version))
                spec.triggerTarget?.let { put("triggerTarget", JsonPrimitive(it)) }
                spec.triggerMethod?.let { put("triggerMethod", JsonPrimitive(it)) }
                put(
                    "actions",
                    buildJsonArray {
                        spec.actions.forEach { action ->
                            add(
                                buildJsonObject {
                                    put("kind", JsonPrimitive(action.kind.name))
                                    action.className?.let { put("className", JsonPrimitive(it)) }
                                    action.memberName?.let { put("memberName", JsonPrimitive(it)) }
                                    action.signature?.let { put("signature", JsonPrimitive(it)) }
                                    action.value?.let { put("value", JsonPrimitive(it)) }
                                    put("valueType", JsonPrimitive(action.valueType.name))
                                    action.receiver?.let { put("receiver", JsonPrimitive(it)) }
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    /**
     * Runs one script now, from inside the process it will act on.
     *
     * `ScriptEngine.runManual` reports failure as a Chinese sentence rather than
     * throwing, so it is passed through verbatim instead of being turned into a
     * generic "工具执行失败".
     */
    private fun scriptRun(
        args: JsonObject,
        context: android.content.Context?,
    ): BridgeApi.Response {
        val id = args.str("id") ?: return toolError("missing 'id'")
        val result = ScriptEngine.runManual(id, context)
        RegTrailLog.i("tool script_run $id → $result")
        return BridgeApi.Response(
            ok = true,
            data = buildJsonObject {
                put("id", JsonPrimitive(id))
                put("result", JsonPrimitive(result))
            },
        )
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun findField(cls: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = cls
        while (c != null) {
            runCatching { c.getDeclaredField(name) }.getOrNull()?.let { return it }
            c = c.superclass
        }
        return null
    }

    /** Parses `com.example.Foo#bar(int,java.lang.String)`. */
    internal fun parseSignature(signature: String): ParsedSignature? {
        val hash = signature.indexOf('#')
        if (hash <= 0) return null
        val className = signature.substring(0, hash)
        val rest = signature.substring(hash + 1)

        val open = rest.indexOf('(')
        if (open < 0) return ParsedSignature(className, rest, emptyList())

        val methodName = rest.substring(0, open)
        val close = rest.lastIndexOf(')')
        if (close < open) return null
        val paramsText = rest.substring(open + 1, close).trim()

        val params = if (paramsText.isEmpty()) {
            emptyList()
        } else {
            paramsText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        return ParsedSignature(className, methodName, params)
    }

    internal data class ParsedSignature(
        val className: String,
        val methodName: String,
        val paramTypes: List<String>,
    )

    /** Best-effort text → value conversion, driven by the target type. */
    internal fun coerce(raw: String, type: Class<*>, hint: String): Any? = runCatching {
        when (type) {
            java.lang.Boolean.TYPE, java.lang.Boolean::class.java ->
                raw.equals("true", true) || raw == "1"
            java.lang.Integer.TYPE, java.lang.Integer::class.java -> raw.toInt()
            java.lang.Long.TYPE, java.lang.Long::class.java -> raw.toLong()
            java.lang.Float.TYPE, java.lang.Float::class.java -> raw.toFloat()
            java.lang.Double.TYPE, java.lang.Double::class.java -> raw.toDouble()
            java.lang.Short.TYPE, java.lang.Short::class.java -> raw.toShort()
            java.lang.Byte.TYPE, java.lang.Byte::class.java -> raw.toByte()
            java.lang.Character.TYPE, java.lang.Character::class.java -> raw.first()
            String::class.java -> raw
            else -> when (hint) {
                "null" -> null
                "string" -> raw
                else -> if (raw == "null") null else raw
            }
        }
    }.getOrNull()

    internal fun stringify(value: Any?): String = when (value) {
        null -> "null"
        is String -> value
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { stringify(it) }
        else -> runCatching { value.toString() }.getOrDefault("<unprintable>")
    }

    private fun mapToJson(map: Map<String, Any?>): JsonObject = buildJsonObject {
        for ((k, v) in map) {
            put(k, BridgeApi.toJsonElement(v))
        }
    }

    /**
     * How many addresses a compact scan result lists.
     *
     * The Agent only needs enough addresses to recognise the one it will refine;
     * a full 500-address list is ~60 KB and would crowd out the class dump it also
     * needs to reason about.
     */
    private const val COMPACT_ADDRESS_LIMIT = 80

    /** Rows are inlined (with values) only when the set is small enough to read. */
    private const val COMPACT_FULL_ROWS = 20
}

/** Small typed accessors so tools read cleanly. */
internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()

internal fun JsonObject.long(key: String): Long? = str(key)?.toLongOrNull()

/** JSON booleans *and* the strings `"true"`/`"1"`; a model may send either. */
internal fun JsonObject.bool(key: String): Boolean? {
    val text = str(key) ?: return null
    return text == "true" || text == "1"
}
