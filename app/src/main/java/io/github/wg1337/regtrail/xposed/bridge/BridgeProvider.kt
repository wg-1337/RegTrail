package io.github.wg1337.regtrail.xposed.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import io.github.wg1337.regtrail.shared.bridge.BridgeApi
import io.github.wg1337.regtrail.xposed.RegTrailLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The command channel an injected module exposes to the settings app.
 *
 * Declared in the manifest with authority `${applicationId}.RegTrailBridge`.
 * The settings app calls it with `ContentResolver.call(...)`.
 *
 * ## Why this provider exists at all
 * The interesting operations — installing a hook, reading a field, enumerating a
 * classloader — can only run **inside the target process**, but the UI that
 * drives them lives in the settings app. This provider is the seam.
 *
 * ## Safety
 * It is exported because the caller is a different app's process, so the design
 * keeps the reachable surface deliberately small:
 *  - every request is dispatched through [BridgeDispatcher] to operations the
 *    module itself installed; there is no reflection on caller-supplied strings
 *    that was not already gated by the module's own logic;
 *  - nothing here reads or writes Android user data;
 *  - when no module is installed in this process, every call answers
 *    `{"ok":false,"error":"module not loaded"}` rather than doing anything.
 *
 * The provider is only ever reachable for real once the module is loaded, so an
 * un-injected app sees a harmless stub.
 */
class BridgeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val response = Bundle()
        if (method != BridgeApi.METHOD_REQUEST) {
            response.putBoolean(BridgeApi.KEY_OK, false)
            response.putString(BridgeApi.KEY_ERROR, "unknown method: $method")
            return response
        }

        val payload = extras?.getString(BridgeApi.KEY_PAYLOAD) ?: arg
        if (payload.isNullOrBlank()) {
            response.putBoolean(BridgeApi.KEY_OK, false)
            response.putString(BridgeApi.KEY_ERROR, "empty payload")
            return response
        }

        val result = runCatching { BridgeDispatcher.dispatch(payload) }
            .getOrElse { t ->
                RegTrailLog.w("bridge dispatch failed", t)
                BridgeApi.Response(
                    ok = false,
                    error = t.message ?: t::class.java.simpleName,
                )
            }

        response.putBoolean(BridgeApi.KEY_OK, result.ok)
        result.error?.let { response.putString(BridgeApi.KEY_ERROR, it) }
        response.putString(BridgeApi.KEY_PAYLOAD, BridgeApi.json.encodeToString(
            BridgeApi.Response.serializer(), result,
        ))
        return response
    }

    // A command channel only; Android data access is deliberately unsupported.
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}

/**
 * Turns a decoded [BridgeApi.Request] into work.
 *
 * Split out from the provider so the dispatch logic is testable and so the
 * provider stays a pure transport adapter.
 */
internal object BridgeDispatcher {

    /** Set by the module once it has booted in this process. */
    @Volatile
    var session: ModuleSession? = null

    fun dispatch(payload: String): BridgeApi.Response {
        val request = runCatching {
            BridgeApi.json.decodeFromString(BridgeApi.Request.serializer(), payload)
        }.getOrElse {
            return BridgeApi.Response(ok = false, error = "无法解析请求: ${it.message}")
        }

        return when (request) {
            is BridgeApi.Request.Ping -> BridgeApi.Response(
                ok = true,
                data = buildJsonObject {
                    put("apiVersion", JsonPrimitive(BridgeApi.API_VERSION))
                    put("moduleLoaded", JsonPrimitive(session != null))
                },
            )

            is BridgeApi.Request.Status -> statusResponse()

            is BridgeApi.Request.ReloadConfig -> {
                val s = session ?: return notLoaded()
                s.reloadConfig()
                BridgeApi.Response(ok = true)
            }

            is BridgeApi.Request.Overlay -> {
                val s = session ?: return notLoaded()
                s.applyOverlay(request)
                BridgeApi.Response(ok = true)
            }

            is BridgeApi.Request.Tool -> {
                val s = session ?: return notLoaded()
                s.runTool(request.name, request.args)
            }
        }
    }

    private fun statusResponse(): BridgeApi.Response {
        val s = session ?: return BridgeApi.Response(
            ok = true,
            data = buildJsonObject {
                put("moduleLoaded", JsonPrimitive(false))
                put("note", JsonPrimitive("此进程中未加载 RegTrail 模块"))
            },
        )
        return BridgeApi.Response(ok = true, data = s.statusJson())
    }

    private fun notLoaded() = BridgeApi.Response(
        ok = false,
        error = "此进程中未加载 RegTrail 模块",
    )
}

/**
 * What the module exposes once it is alive in a target process.
 *
 * Implemented by the overlay controller, which already owns the process-scoped
 * state (window, config, classloader).
 */
internal interface ModuleSession {
    fun reloadConfig()
    fun applyOverlay(request: BridgeApi.Request.Overlay)
    fun runTool(name: String, args: JsonObject): BridgeApi.Response
    fun statusJson(): JsonObject
}

/** Shared helpers for building tool JSON without a serializer for every shape. */
internal fun jsonObjectOf(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((k, v) in pairs) put(k, BridgeApi.toJsonElement(v))
}

/** Convenience for tools that return a single value. */
internal fun valueResponse(key: String, value: Any?): BridgeApi.Response =
    BridgeApi.Response(ok = true, data = jsonObjectOf(key to value))

/** Convenience for tools that fail with a readable reason. */
internal fun toolError(message: String): BridgeApi.Response =
    BridgeApi.Response(ok = false, error = message)
