package io.github.wg1337.regtrail.bridge

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import io.github.wg1337.regtrail.BuildConfig
import io.github.wg1337.regtrail.shared.bridge.BridgeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Client for the module's bridge provider.
 *
 * ## How this reaches a *target* process
 * The provider is declared inside the module APK, so its authority is
 * `${module applicationId}.RegTrailBridge`. The module code, however, runs inside
 * whatever app the framework injected it into. Because a provider's process is
 * chosen by the *provider's* package — not the caller's — a `call()` to that
 * authority is served by the target app's process, where the module lives.
 *
 * That is what makes this work without any service binding, and it is also why
 * the settings app can talk to a target app it does not own.
 *
 * ## Failure modes are normal, not exceptional
 * Most calls will fail with "module not loaded" simply because the user has not
 * started a scoped app yet, or has not enabled the overlay for it. Callers get a
 * typed [Result] with a readable reason rather than an exception.
 */
class ModuleBridgeClient(private val context: Context) {

    private val authority: String
        get() = BuildConfig.APPLICATION_ID + BridgeApi.AUTHORITY_SUFFIX

    private val uri: Uri
        get() = Uri.parse("content://$authority")

    /** Cheap liveness probe: answers even when no module is loaded. */
    suspend fun ping(): Result<PingResult> =
        call(BridgeApi.Request.Ping()).map { response ->
            val data = response.data as? JsonObject
            PingResult(
                apiVersion = data?.get("apiVersion")
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    ?.toIntOrNull() ?: 0,
                moduleLoaded = data?.get("moduleLoaded")
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } == "true",
            )
        }

    /** Asks the module in this process for its status. */
    suspend fun status(): Result<JsonObject> {
        val response = call(BridgeApi.Request.Status)
        val data = response.getOrNull()?.data as? JsonObject
            ?: return Result.failure(
                BridgeException(
                    response.exceptionOrNull()?.message ?: "目标进程未返回状态",
                ),
            )
        return Result.success(data)
    }

    /** Runs an agent/debugger tool inside the target process. */
    suspend fun tool(name: String, args: JsonObject = JsonObject(emptyMap())): Result<JsonObject> {
        val response = call(BridgeApi.Request.Tool(name = name, args = args))
        return response.map { r ->
            (r.data as? JsonObject) ?: JsonObject(emptyMap())
        }
    }

    /** Tells the module to re-read the published configuration. */
    suspend fun reloadConfig(): Result<Unit> =
        call(BridgeApi.Request.ReloadConfig).map { }

    /** Shows/hides/repositions the overlay from the settings app. */
    suspend fun overlay(
        visible: Boolean? = null,
        collapsed: Boolean? = null,
        x: Float? = null,
        y: Float? = null,
    ): Result<Unit> = call(
        BridgeApi.Request.Overlay(visible = visible, collapsed = collapsed, x = x, y = y),
    ).map { }

    /** True when a target process answered and has the module loaded. */
    suspend fun isModuleLoaded(): Boolean = runCatching {
        val pong = ping().getOrNull() ?: return false
        pong.moduleLoaded
    }.getOrDefault(false)

    // -----------------------------------------------------------------------

    private suspend fun call(
        request: BridgeApi.Request,
    ): Result<BridgeApi.Response> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        runCatching {
            val payload = BridgeApi.json.encodeToString(BridgeApi.Request.serializer(), request)

            val extras = Bundle().apply {
                putString(BridgeApi.KEY_PAYLOAD, payload)
            }

            val resolver: ContentResolver = context.contentResolver
            val bundle = resolver.call(uri, BridgeApi.METHOD_REQUEST, payload, extras)
                ?: return@runCatching BridgeApi.Response(
                    ok = false,
                    error = "目标进程无响应（可能未安装/未启用模块）",
                )

            val ok = bundle.getBoolean(BridgeApi.KEY_OK, false)
            val embedded = bundle.getString(BridgeApi.KEY_PAYLOAD)

            if (embedded.isNullOrBlank()) {
                return@runCatching BridgeApi.Response(
                    ok = false,
                    error = bundle.getString(BridgeApi.KEY_ERROR)
                        ?: "目标进程返回了空响应",
                )
            }

            val parsed = BridgeApi.json.decodeFromString(
                BridgeApi.Response.serializer(),
                embedded,
            )
            // Prefer the outer Bundle's verdict when the inner one disagrees:
            // the provider sets it after running the dispatcher.
            if (!ok && parsed.ok) parsed.copy(ok = false) else parsed
        }.recoverCatching { t ->
            throw BridgeException(
                when (t) {
                    is SecurityException -> "目标进程拒绝了调用（权限或签名不符）"
                    is IllegalArgumentException -> "找不到目标进程的模块通道（模块未在该应用内加载）"
                    else -> t.message ?: t::class.java.simpleName
                },
                t,
            )
        }
    }

    data class PingResult(
        val apiVersion: Int,
        val moduleLoaded: Boolean,
    )
}

class BridgeException(message: String, cause: Throwable? = null) : Exception(message, cause)
