package io.github.wg1337.regtrail.agent

import io.github.wg1337.regtrail.shared.model.ThinkingLevel
import io.github.wg1337.regtrail.shared.model.ThinkingWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The request-body contract for 思考等级.
 *
 * These assertions quote the DeepSeek 思考模式 guide, because the whole feature is
 * "send exactly what the documentation specifies":
 *
 * ```
 * 思考模式开关(1) | {"thinking": {"type": "enabled/disabled"}}
 * 思考强度控制(2) | {"reasoning_effort": "low/high/max"}
 *
 * (1) 思考模式默认打开，且 effort 默认为 `high`
 * ```
 *
 * and the same page states 思考模式不支持 `temperature`、`presence_penalty`、
 * `frequency_penalty` … "设置参数不会报错，但也不会生效" — so temperature must be
 * *absent* when thinking is on, not merely ignored.
 */
class ThinkingRequestBodyTest {

    private fun client(
        thinking: ThinkingLevel,
        wire: ThinkingWire = ThinkingWire.Always,
        baseUrl: String = "https://api.deepseek.com/v1",
    ) = OpenAiCompatibleClient(
        AgentConfig(baseUrl = baseUrl, apiKey = "k", thinking = thinking, thinkingWire = wire),
    )

    private fun body(client: OpenAiCompatibleClient): JsonObject =
        client.buildBody(emptyList(), emptyList())

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.content

    // -----------------------------------------------------------------------

    @Test
    fun highEffortSendsEnabledThinkingAndHighReasoningEffort() {
        val json = body(client(ThinkingLevel.High))

        assertEquals("enabled", json["thinking"]?.jsonObject?.str("type"))
        assertEquals("high", json.str("reasoning_effort"))
        assertNull(
            "thinking mode ignores temperature, so it must not be sent",
            json.str("temperature"),
        )
    }

    @Test
    fun eachLevelMapsToItsDocumentedEffort() {
        assertEquals("low", body(client(ThinkingLevel.Low)).str("reasoning_effort"))
        assertEquals("high", body(client(ThinkingLevel.High)).str("reasoning_effort"))
        assertEquals("max", body(client(ThinkingLevel.Max)).str("reasoning_effort"))
    }

    @Test
    fun offSendsDisabledAndNoEffortAndKeepsTemperature() {
        val json = body(client(ThinkingLevel.Off))

        assertEquals("disabled", json["thinking"]?.jsonObject?.str("type"))
        assertNull("effort alongside disabled would be a contradiction", json.str("reasoning_effort"))
        assertNotNull("temperature is only meaningful with thinking off", json.str("temperature"))
    }

    @Test
    fun neverSendsNoThinkingFieldsAtAll() {
        val json = body(client(ThinkingLevel.Max, wire = ThinkingWire.Never))

        assertFalse("no thinking key", json.containsKey("thinking"))
        assertFalse("no reasoning_effort key", json.containsKey("reasoning_effort"))
        assertNotNull("the historical sampling temperature is still sent", json.str("temperature"))
    }

    @Test
    fun autoSendsForADeepseekBaseUrlAndNotForAnotherProvider() {
        val deepseek = body(
            client(
                ThinkingLevel.High,
                wire = ThinkingWire.Auto,
                baseUrl = "https://api.deepseek.com/v1",
            ),
        )
        assertTrue(deepseek.containsKey("thinking"))

        val openai = body(
            client(
                ThinkingLevel.High,
                wire = ThinkingWire.Auto,
                baseUrl = "https://api.openai.com/v1",
            ),
        )
        assertFalse(
            "an unknown top-level field makes api.openai.com answer 400",
            openai.containsKey("thinking"),
        )
        assertFalse(openai.containsKey("reasoning_effort"))
    }

    @Test
    fun alwaysSendsEvenWhenTheHostnameIsNotDeepseek() {
        val json = body(
            client(
                ThinkingLevel.Low,
                wire = ThinkingWire.Always,
                baseUrl = "https://my-gateway.example.com/v1",
            ),
        )
        assertEquals("enabled", json["thinking"]?.jsonObject?.str("type"))
        assertEquals("low", json.str("reasoning_effort"))
    }

    @Test
    fun defaultsBehaveLikeTheDocumentedApiDefault() {
        // An AgentConfig built the way ThemeConfigStore builds one from *absent*
        // preferences: thinking on at high, wire auto.
        val store = AgentConfig().copy(thinking = ThinkingLevel.fromWire(null))
        assertEquals(ThinkingLevel.High, store.thinking)

        val json = body(OpenAiCompatibleClient(store))
        // Default base URL is api.openai.com, so `auto` correctly stays silent…
        assertFalse(json.containsKey("thinking"))

        // …while a DeepSeek endpoint gets the documented default effort.
        val deepseek = body(OpenAiCompatibleClient(store.copy(baseUrl = "https://api.deepseek.com")))
        assertEquals("high", deepseek.str("reasoning_effort"))
    }

    @Test
    fun wireStringsRoundTripThroughTheirParsers() {
        ThinkingLevel.entries.forEach { level ->
            assertEquals(level, ThinkingLevel.fromWire(level.wire))
        }
        ThinkingWire.entries.forEach { wire ->
            assertEquals(wire, ThinkingWire.fromWire(wire.wire))
        }
        // Lenient on purpose: a config from a newer build must not break.
        assertEquals(ThinkingLevel.Default(), ThinkingLevel.fromWire("some-future-level"))
        assertEquals(ThinkingWire.Auto, ThinkingWire.fromWire("some-future-mode"))
        assertEquals(ThinkingLevel.High, ThinkingLevel.fromWire(null))
        assertEquals(ThinkingLevel.Off, ThinkingLevel.fromWire("disabled"))
        assertEquals(ThinkingLevel.Max, ThinkingLevel.fromWire("ultra"))
    }

    private fun ThinkingLevel.Companion.Default(): ThinkingLevel = ThinkingLevel.DEFAULT

    // -----------------------------------------------------------------------
    // Tool schemas must be valid JSON — they are embedded verbatim in requests
    // -----------------------------------------------------------------------

    @Test
    fun everyToolSchemaIsValidJson() {
        val json = Json { ignoreUnknownKeys = true }
        AgentTools.ALL.forEach { tool ->
            val parsed = runCatching { json.parseToJsonElement(tool.parameters) }
            assertTrue(
                "tool \"${tool.name}\" has invalid parameters JSON: " +
                    "${parsed.exceptionOrNull()?.message}",
                parsed.isSuccess,
            )
            val root = parsed.getOrThrow().jsonObject
            assertEquals(
                "tool \"${tool.name}\" schema must be an object",
                "object",
                root["type"]?.jsonPrimitive?.content,
            )
        }

        // The shared action fragment is interpolated into two of them; parse it
        // on its own so a syntax error names the fragment, not the enclosing tool.
        val actions = Json.parseToJsonElement(AgentTools.SCRIPT_ACTIONS_SCHEMA).jsonObject
        assertEquals("array", actions["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun scriptToolsCoverTheEngineSurface() {
        val names = AgentTools.ALL.map { it.name }.toSet()
        listOf(
            "script_list",
            "script_get",
            "script_create",
            "script_update",
            "script_run",
            "script_versions",
            "script_restore",
            "read_file",
            "write_file",
        ).forEach { required ->
            assertTrue("missing agent tool $required", required in names)
        }

        // Every action kind the engine executes must appear in the schema's enum,
        // or the model cannot express it.
        val schema = AgentTools.SCRIPT_ACTIONS_SCHEMA
        listOf("Trace", "SetReturn", "SetArg", "GetField", "SetField", "CallMethod")
            .forEach { kind ->
                assertTrue("action kind $kind missing from the schema", schema.contains("\"$kind\""))
            }
        // …and so must every trigger.
        val createSchema = AgentTools.ALL.single { it.name == "script_create" }.parameters
        listOf(
            "AppAttach",
            "AppOnCreate",
            "ActivityOnCreate",
            "ActivityOnResume",
            "ClassLoaded",
            "MethodEnter",
            "Manual",
        ).forEach { trigger ->
            assertTrue("trigger $trigger missing from script_create", createSchema.contains("\"$trigger\""))
        }
    }
}
