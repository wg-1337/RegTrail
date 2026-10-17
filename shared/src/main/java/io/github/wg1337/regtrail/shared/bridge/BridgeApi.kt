package io.github.wg1337.regtrail.shared.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Wire contract between the settings app and an injected module.
 *
 * ## Why a ContentProvider `call()` and not Binder AIDL
 * The module runs inside the *target* app's process, so the two sides have
 * different UIDs and no shared service. A `ContentProvider.call()` is the
 * lightest cross-UID request/response channel that needs no bind lifecycle:
 *
 * ```
 * settings app ──call(REQUEST)──▶  target app process (module BridgeProvider)
 *              ◀──Bundle(RESPONSE)─
 * ```
 *
 * Payloads are JSON strings so this contract can evolve without AIDL churn.
 * Large results (screenshots, big object dumps) are written to a file by the
 * module and only the path travels over Binder, keeping transactions well
 * under the ~1 MB limit.
 *
 * ## Direction
 * - **Down (app -> module)**: configuration, commands, hook-script activation.
 * - **Up (module -> app)**: the module also *pulls* commands; the app records
 *   requests. Streaming output does not go over Binder — see [LogFile].
 */
object BridgeApi {

    /** Provider authority. Must stay in sync with the manifest. */
    const val AUTHORITY_SUFFIX = ".RegTrailBridge"

    /** `call()` method names. */
    const val METHOD_REQUEST = "request"

    /** Keys inside the request/response Bundles. */
    const val KEY_PAYLOAD = "payload"
    const val KEY_OK = "ok"
    const val KEY_ERROR = "error"

    /**
     * Every request is one of these. `PING` doubles as a liveness and
     * version-handshake probe.
     */
    @Serializable
    sealed interface Request {
        @Serializable
        @SerialName("ping")
        data class Ping(val apiVersion: Int = API_VERSION) : Request

        /** Current module state: which framework, which process, hook counts. */
        @Serializable
        @SerialName("status")
        data object Status : Request

        /** Ask the module to (re-)read configuration from RemotePreferences. */
        @Serializable
        @SerialName("reloadConfig")
        data object ReloadConfig : Request

        /** Show / hide / reposition the floating window. */
        @Serializable
        @SerialName("overlay")
        data class Overlay(
            val visible: Boolean? = null,
            val x: Float? = null,
            val y: Float? = null,
            val collapsed: Boolean? = null,
        ) : Request

        /** Run a named tool inside the target process (see docs/PLAN.md 5-①). */
        @Serializable
        @SerialName("tool")
        data class Tool(
            val name: String,
            val args: JsonObject = JsonObject(emptyMap()),
        ) : Request
    }

    @Serializable
    data class Response(
        val ok: Boolean,
        val error: String? = null,
        val data: JsonElement = JsonNull,
    )

    @Serializable
    data class ModuleStatus(
        val apiVersion: Int,
        val frameworkName: String,
        val frameworkVersion: String,
        val frameworkVersionCode: Long,
        val processName: String,
        val packageName: String,
        val isSystemServer: Boolean,
        /** Hook ids currently installed by this module in this process. */
        val installedHooks: List<String> = emptyList(),
        val overlayAttached: Boolean = false,
        val overlayMechanism: String? = null,
    )

    /** Bumped whenever the request/response shape changes incompatibly. */
    const val API_VERSION = 1

    /** Lenient JSON: unknown keys are ignored so old modules keep working. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
        prettyPrint = false
    }

    /** Convenience for building a JSON object argument map. */
    fun args(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
        pairs.associate { (k, v) -> k to toJsonElement(v) },
    )

    fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }
}

/**
 * Files shared between the two processes.
 *
 * Both sides run with root available, and the module process can always write
 * its own app-private directory, so a small spool directory under
 * `/data/local/tmp` is the shared scratch space. Streaming data (terminal
 * output, debug logs) goes here rather than through Binder.
 *
 * Directory contents:
 * - `regtrail.log`  – append-only module log (also mirrored to the overlay)
 * - `cmd/`          – settings app drops command files for the terminal
 * - `out/`          – module writes command output files
 */
object SharedPaths {
    const val BASE_DIR = "/data/local/tmp/regtrail"
    const val LOG_FILE = "$BASE_DIR/regtrail.log"
    const val CMD_DIR = "$BASE_DIR/cmd"
    const val OUT_DIR = "$BASE_DIR/out"
    const val CONFIG_CACHE = "$BASE_DIR/config.json"
}
