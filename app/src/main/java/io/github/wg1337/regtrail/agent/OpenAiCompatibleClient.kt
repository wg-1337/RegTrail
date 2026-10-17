package io.github.wg1337.regtrail.agent

import io.github.wg1337.regtrail.shared.bridge.BridgeApi
import io.github.wg1337.regtrail.shared.model.ThinkingLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Minimal OpenAI-compatible chat-completions client.
 *
 * ## Why not a client library
 * The request is one JSON POST and the response is one JSON object. A library
 * would add a dependency, its own OkHttp/Ktor stack, and — for the multiplatform
 * clients — a Kotlin/Native-adjacent release cadence to keep in step, in exchange
 * for saving about sixty lines here. On Android, `HttpURLConnection` is already
 * backed by OkHttp and needs no permission beyond INTERNET.
 *
 * ## Error handling
 * Providers signal failure in three different ways (HTTP status, an `error`
 * object, or a 200 with an unexpected shape). All three are turned into a
 * readable [AgentException] rather than a serialization stack trace, because the
 * user's first debugging step is always "what did the API actually say".
 */
class OpenAiCompatibleClient(private val config: AgentConfig) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    /**
     * One chat-completions round.
     *
     * @param messages the conversation so far, in the provider's shape.
     * @param tools tool definitions; empty disables tool calling.
     * @return the parsed assistant message plus any tool calls it requested.
     */
    suspend fun complete(
        messages: List<JsonObject>,
        tools: List<AgentTool>,
    ): Result<Completion> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildBody(messages, tools)

            val url = URL(config.baseUrl.trimEnd('/') + "/chat/completions")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TimeUnit.SECONDS.toMillis(config.timeoutSeconds.toLong()).toInt()
                readTimeout = TimeUnit.SECONDS.toMillis(config.timeoutSeconds.toLong()).toInt()
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }

            try {
                connection.outputStream.use { out ->
                    out.write(body.toString().toByteArray(Charsets.UTF_8))
                }

                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream

                val text = stream?.let { input ->
                    BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
                }.orEmpty()

                if (status !in 200..299) {
                    throw AgentException("HTTP $status：${extractError(text)}")
                }
                if (text.isBlank()) {
                    throw AgentException("服务端返回了空响应")
                }

                parseCompletion(text)
            } finally {
                runCatching { connection.disconnect() }
            }
        }
    }

    /**
     * Builds the `POST /chat/completions` body, thinking fields included when
     * the configured endpoint expects them.
     *
     * ## What the thinking fields are, exactly
     * Per the DeepSeek 思考模式 guide
     * (https://api-docs.deepseek.com/zh-cn/guides/thinking_mode), the
     * OpenAI-format request has two independent controls:
     *
     * ```
     * 思考模式开关: {"thinking": {"type": "enabled/disabled"}}
     * 思考强度控制: {"reasoning_effort": "low/high/max"}
     * ```
     *
     * so this emits a **top-level** `thinking` object plus a top-level
     * `reasoning_effort` string. (The guide's Python example puts `thinking`
     * inside `extra_body`, which is only how the OpenAI *SDK* is told to forward
     * an unknown field — on the wire it is a plain body member.)
     *
     * `reasoning_effort` is omitted when the level is [ThinkingLevel.Off],
     * because the documented way to turn thinking off is
     * `{"thinking":{"type":"disabled"}}` and an effort alongside `disabled`
     * would be a contradiction.
     *
     * ## Why `temperature` is conditional
     * The same guide states 思考模式不支持 `temperature`、`presence_penalty`、
     * `frequency_penalty` — "设置参数不会报错，但也不会生效". Sending a
     * temperature the service silently ignores is worse than not sending it: the
     * user would tune a knob that does nothing. So when thinking is on, the
     * sampling temperature is left out; when thinking is off, the historical 0.2
     * still applies.
     *
     * `internal` rather than `private` so the JVM test suite can assert the exact
     * body: the thinking parameters are a wire contract with the provider, and a
     * regression there is invisible until a user notices the model stopped
     * thinking (or started getting HTTP 400).
     */
    internal fun buildBody(messages: List<JsonObject>, tools: List<AgentTool>): JsonObject {
        val thinking = config.thinking
        val sendThinking = config.thinkingWire.sendsFor(config.baseUrl)

        return buildJsonObject {
            put("model", JsonPrimitive(config.model))
            put("messages", buildJsonArray { messages.forEach { add(it) } })

            if (!sendThinking || thinking == ThinkingLevel.Off) {
                // Deterministic-ish sampling, as before. Only skipped when the
                // service is documented to ignore it.
                put("temperature", JsonPrimitive(0.2))
            }

            if (sendThinking) {
                putJsonObject("thinking") {
                    put("type", JsonPrimitive(thinking.thinkingType))
                }
                thinking.effort?.let { put("reasoning_effort", JsonPrimitive(it)) }
            }

            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    tools.forEach { tool ->
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive("function"))
                                putJsonObject("function") {
                                    put("name", JsonPrimitive(tool.name))
                                    put("description", JsonPrimitive(tool.description))
                                    // The schema is stored as JSON text; it must be
                                    // embedded as a real object, not a string.
                                    put(
                                        "parameters",
                                        json.parseToJsonElement(tool.parameters),
                                    )
                                }
                            },
                        )
                    }
                })
                put("tool_choice", JsonPrimitive("auto"))
            }
        }
    }

    private fun parseCompletion(text: String): Completion {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw AgentException("响应不是合法 JSON：${text.take(200)}") }

        // Some gateways wrap failures in a 200 with an error object.
        root["error"]?.let { error ->
            val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull
            throw AgentException("服务端错误：${message ?: error.toString().take(200)}")
        }

        val choices = root["choices"]?.jsonArray
        if (choices.isNullOrEmpty()) {
            throw AgentException("响应中没有 choices 字段")
        }

        val message = choices[0].jsonObject["message"]?.jsonObject
            ?: throw AgentException("响应中没有 message 字段")

        val content = message["content"]?.jsonPrimitive?.contentOrNull.orEmpty()

        // 思维链. The guide returns it next to `content` as `reasoning_content`;
        // it is *not* part of the answer, so it is kept in its own field and the
        // UI shows it collapsed rather than appending it to the chat text.
        val reasoning = message["reasoning_content"]?.jsonPrimitive?.contentOrNull.orEmpty()

        val toolCalls = message["tool_calls"]?.jsonArray?.mapNotNull { element ->
            val call = element.jsonObject
            val function = call["function"]?.jsonObject ?: return@mapNotNull null
            val name = function["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val rawArgs = function["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
            ToolCall(
                id = call["id"]?.jsonPrimitive?.contentOrNull
                    ?: "call_${name}_${System.nanoTime()}",
                name = name,
                arguments = runCatching { json.parseToJsonElement(rawArgs).jsonObject }
                    .getOrElse { JsonObject(emptyMap()) },
                rawArguments = rawArgs,
            )
        }.orEmpty()

        val finishReason = choices[0].jsonObject["finish_reason"]
            ?.jsonPrimitive?.contentOrNull

        return Completion(
            content = content,
            reasoning = reasoning,
            toolCalls = toolCalls,
            finishReason = finishReason,
            rawMessage = message,
        )
    }

    /** Pulls the most useful sentence out of a provider error body. */
    private fun extractError(body: String): String {
        if (body.isBlank()) return "无响应体"
        val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val message = parsed?.get("error")
            ?.let { element ->
                runCatching { element.jsonObject["message"]?.jsonPrimitive?.contentOrNull }
                    .getOrNull()
            }
        return message ?: body.take(300)
    }

    /** One assistant turn. */
    data class Completion(
        val content: String,
        val toolCalls: List<ToolCall>,
        val finishReason: String?,
        /** Passed back verbatim so the provider sees its own message again. */
        val rawMessage: JsonObject,
        /**
         * `reasoning_content`, when the service runs in thinking mode.
         *
         * Empty when the model did not think or the endpoint does not support
         * it. **Never merged into [content]**: with `tools` present the guide
         * requires it to be sent back verbatim, which happens automatically
         * because [rawMessage] carries it — the transcript re-appends the raw
         * assistant message rather than a rebuilt one.
         */
        val reasoning: String = "",
    )

    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: JsonObject,
        val rawArguments: String,
    )

    companion object {
        /** Builds a user/assistant/tool message in the provider's shape. */
        fun message(role: String, content: String): JsonObject = buildJsonObject {
            put("role", JsonPrimitive(role))
            put("content", JsonPrimitive(content))
        }

        /** Builds the `tool` message that answers a `tool_call`. */
        fun toolResult(callId: String, content: String): JsonObject = buildJsonObject {
            put("role", JsonPrimitive("tool"))
            put("tool_call_id", JsonPrimitive(callId))
            put("content", JsonPrimitive(content))
        }

        /** Builds a system message. */
        fun system(content: String): JsonObject = message("system", content)

        /** Serializes a tool result compactly for the transcript. */
        fun summarize(data: Any?, limit: Int = 4000): String {
            val text = when (data) {
                null -> "null"
                is JsonObject -> data.toString()
                else -> data.toString()
            }
            return if (text.length <= limit) text else text.take(limit) + "…（已截断）"
        }

        /** Extra field used by some gateways to disable parallel tool calls. */
        fun parallelToolCalls(enabled: Boolean): JsonObject = buildJsonObject {
            put("parallel_tool_calls", JsonPrimitive(enabled))
        }
    }
}

class AgentException(message: String, cause: Throwable? = null) : Exception(message, cause)
