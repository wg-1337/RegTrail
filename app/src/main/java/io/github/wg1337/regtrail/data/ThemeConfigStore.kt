package io.github.wg1337.regtrail.data

import android.content.Context
import io.github.wg1337.regtrail.agent.AgentConfig
import io.github.wg1337.regtrail.shared.model.OverlayMechanism
import io.github.wg1337.regtrail.shared.model.ThemeConfig
import kotlinx.serialization.json.Json

/**
 * Persists local settings as JSON in SharedPreferences.
 *
 * Two things live here:
 *  - [ThemeConfig] — the appearance settings (also published to the module).
 *  - the RegTrail-level overlay target filter, which is a separate concern from
 *    the framework's scope: scope says "may load here", this says "show the
 *    window here".
 *
 * Deliberately simple for now; moving to DataStore is a later concern and does
 * not change the serialized shape.
 */
class ThemeConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    // ---- Theme ------------------------------------------------------------

    fun load(): ThemeConfig {
        val raw = prefs.getString(KEY_THEME, null) ?: return ThemeConfig()
        return runCatching { json.decodeFromString(ThemeConfig.serializer(), raw) }
            .getOrElse { ThemeConfig() }
    }

    fun save(config: ThemeConfig) {
        runCatching {
            prefs.edit()
                .putString(KEY_THEME, json.encodeToString(ThemeConfig.serializer(), config))
                .apply()
        }
    }

    // ---- Overlay targets --------------------------------------------------

    fun loadOverlayTargets(): Set<String> {
        val raw = prefs.getStringSet(KEY_OVERLAY_TARGETS, null) ?: return emptySet()
        return raw.filter { it.isNotBlank() }.toSet()
    }

    fun saveOverlayTargets(packages: Collection<String>) {
        runCatching {
            prefs.edit()
                .putStringSet(KEY_OVERLAY_TARGETS, packages.filter { it.isNotBlank() }.toSet())
                .apply()
        }
    }

    // ---- Overlay mechanism ------------------------------------------------

    /**
     * Which floating-window mechanism the module should use.
     *
     * Defaults to [OverlayMechanism.DecorView]: no permission, no `system`
     * scope, and — the deciding factor — no system_server hook, so a mistake
     * cannot leave the device unbootable.
     */
    fun loadOverlayMechanism(): OverlayMechanism {
        val raw = prefs.getString(KEY_OVERLAY_MECHANISM, null) ?: return OverlayMechanism.DecorView
        return runCatching { OverlayMechanism.valueOf(raw) }
            .getOrDefault(OverlayMechanism.DecorView)
    }

    fun saveOverlayMechanism(mechanism: OverlayMechanism) {
        runCatching {
            prefs.edit().putString(KEY_OVERLAY_MECHANISM, mechanism.name).apply()
        }
    }

    /**
     * Whether the user has accepted the bootloop risk of hooking system_server.
     *
     * Stored separately from the mechanism so that merely switching back and
     * forth does not silently re-arm a dangerous hook: re-enabling always
     * requires the acknowledgement again.
     */
    fun loadSystemHookAcknowledged(): Boolean =
        prefs.getBoolean(KEY_SYSTEM_HOOK_ACK, false)

    fun saveSystemHookAcknowledged(acknowledged: Boolean) {
        runCatching {
            prefs.edit().putBoolean(KEY_SYSTEM_HOOK_ACK, acknowledged).apply()
        }
    }

    // ---- Agent ------------------------------------------------------------

    /**
     * Loads the Agent configuration, including the API key.
     *
     * The key lives in plain SharedPreferences. That is adequate for a
     * root-only developer tool that already requires full device access — the
     * key is not meaningfully more sensitive than the root shell this app holds —
     * but it is stated in the UI so nobody assumes otherwise. Encrypting it would
     * require a keystore-backed key that the module side could not use anyway.
     */
    fun loadAgentConfig(): AgentConfig {
        val baseUrl = prefs.getString(KEY_AGENT_BASE_URL, null)
            ?: AgentConfig.DEFAULT_BASE_URL
        val model = prefs.getString(KEY_AGENT_MODEL, null) ?: AgentConfig.DEFAULT_MODEL
        val key = prefs.getString(KEY_AGENT_API_KEY, null).orEmpty()
        val rounds = prefs.getInt(KEY_AGENT_ROUNDS, 8)
        return AgentConfig(
            baseUrl = baseUrl,
            apiKey = key,
            model = model,
            maxToolRounds = rounds.coerceIn(1, 30),
        )
    }

    fun saveAgentConfig(config: AgentConfig, newApiKey: String?) {
        runCatching {
            prefs.edit().apply {
                putString(KEY_AGENT_BASE_URL, config.baseUrl)
                putString(KEY_AGENT_MODEL, config.model)
                putInt(KEY_AGENT_ROUNDS, config.maxToolRounds)
                // null means "leave the stored key alone".
                if (newApiKey != null) putString(KEY_AGENT_API_KEY, newApiKey)
            }.apply()
        }
    }

    private companion object {
        const val PREFS_NAME = "regtrail_config"
        const val KEY_THEME = "theme_config_v1"
        const val KEY_OVERLAY_TARGETS = "overlay_targets_v1"
        const val KEY_OVERLAY_MECHANISM = "overlay_mechanism_v1"
        const val KEY_SYSTEM_HOOK_ACK = "system_hook_ack_v1"
        const val KEY_AGENT_BASE_URL = "agent_base_url_v1"
        const val KEY_AGENT_API_KEY = "agent_api_key_v1"
        const val KEY_AGENT_MODEL = "agent_model_v1"
        const val KEY_AGENT_ROUNDS = "agent_rounds_v1"
    }
}
