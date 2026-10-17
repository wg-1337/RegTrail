package io.github.wg1337.regtrail.data

import android.content.Context
import io.github.wg1337.regtrail.agent.AgentConfig
import io.github.wg1337.regtrail.shared.model.OverlayMechanism
import io.github.wg1337.regtrail.shared.model.ThemeConfig
import io.github.wg1337.regtrail.ui.state.AppSettings
import io.github.wg1337.regtrail.ui.state.HookMode
import io.github.wg1337.regtrail.ui.terminal.TerminalMode
import kotlinx.serialization.json.Json

/**
 * Persists local settings in SharedPreferences.
 *
 * What lives here:
 *  - [ThemeConfig] — the appearance settings (also published to the module),
 *    stored as one JSON blob because it is read and written as a whole;
 *  - [AppSettings] — the behavioural settings, stored as individual scalar
 *    keys. Scalar keys are used on purpose: adding a field later means adding a
 *    key with a default, and an absent key simply reads as that default, so an
 *    old install keeps working without a migration step;
 *  - the RegTrail-level overlay target filter, which is a separate concern from
 *    the framework's scope: scope says "may load here", this says "show the
 *    window here";
 *  - the overlay mechanism and its risk acknowledgement (also separate keys, for
 *    the same reason: an unacknowledged dangerous hook must never be re-armed
 *    just because the theme blob was re-written).
 *
 * Deliberately simple for now; moving to DataStore is a later concern and does
 * not change the stored keys.
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

    // ---- Behavioural settings ---------------------------------------------

    /**
     * Loads the behavioural settings.
     *
     * Every read falls back to the default from [AppSettings], which is the
     * value the previous hard-coded behaviour used. That is what makes adding a
     * key a backwards-compatible change: an install that has never written the
     * key behaves exactly as it did before the key existed.
     */
    fun loadSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            overlayAutoAttach = prefs.getBoolean(
                KEY_OVERLAY_AUTO_ATTACH,
                defaults.overlayAutoAttach,
            ),
            terminalDefaultMode = prefs.getString(KEY_TERMINAL_MODE, null)
                ?.let { raw -> runCatching { TerminalMode.valueOf(raw) }.getOrNull() }
                ?: defaults.terminalDefaultMode,
            terminalWorkspacePath = prefs.getString(KEY_TERMINAL_WORKSPACE, null)
                // A stored path that is no longer absolute (hand-edited prefs, a
                // deleted directory used as a typo) would make every command
                // start in a broken directory, so it is dropped rather than
                // trusted.
                ?.takeIf { AppSettings.isValidWorkspacePath(it) }
                ?: defaults.terminalWorkspacePath,
            terminalFontSizeSp = prefs.getInt(KEY_TERMINAL_FONT_SP, defaults.terminalFontSizeSp)
                .coerceIn(8, 32),
            hookDefaultMode = prefs.getString(KEY_HOOK_MODE, null)
                ?.let { raw -> runCatching { HookMode.valueOf(raw) }.getOrNull() }
                ?: defaults.hookDefaultMode,
            debugAutoDeoptimize = prefs.getBoolean(
                KEY_DEBUG_AUTO_DEOPTIMIZE,
                defaults.debugAutoDeoptimize,
            ),
            debugLogLimit = prefs.getInt(KEY_DEBUG_LOG_LIMIT, defaults.debugLogLimit)
                .coerceIn(20, 2000),
        )
    }

    fun saveSettings(settings: AppSettings) {
        runCatching {
            prefs.edit().apply {
                putBoolean(KEY_OVERLAY_AUTO_ATTACH, settings.overlayAutoAttach)
                putString(KEY_TERMINAL_MODE, settings.terminalDefaultMode.name)
                putString(KEY_TERMINAL_WORKSPACE, settings.terminalWorkspacePath)
                putInt(KEY_TERMINAL_FONT_SP, settings.terminalFontSizeSp)
                putString(KEY_HOOK_MODE, settings.hookDefaultMode.name)
                putBoolean(KEY_DEBUG_AUTO_DEOPTIMIZE, settings.debugAutoDeoptimize)
                putInt(KEY_DEBUG_LOG_LIMIT, settings.debugLogLimit)
            }.apply()
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

    /**
     * Forgets the whole Agent configuration, including the API key.
     *
     * Used by "重置所有设置". A key is a credential, so leaving it behind after
     * the user asked for a full reset would be exactly the kind of half-reset
     * that makes people distrust the button — and there is no other route to
     * clear it once stored.
     */
    fun clearAgentConfig() {
        runCatching {
            prefs.edit().apply {
                remove(KEY_AGENT_BASE_URL)
                remove(KEY_AGENT_API_KEY)
                remove(KEY_AGENT_MODEL)
                remove(KEY_AGENT_ROUNDS)
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

        // Behavioural settings. One key per value, so a missing key reads as the
        // default instead of invalidating a whole JSON blob.
        const val KEY_OVERLAY_AUTO_ATTACH = "settings_overlay_auto_attach_v1"
        const val KEY_TERMINAL_MODE = "settings_terminal_mode_v1"
        const val KEY_TERMINAL_WORKSPACE = "settings_terminal_workspace_v1"
        const val KEY_TERMINAL_FONT_SP = "settings_terminal_font_sp_v1"
        const val KEY_HOOK_MODE = "settings_hook_mode_v1"
        const val KEY_DEBUG_AUTO_DEOPTIMIZE = "settings_debug_auto_deoptimize_v1"
        const val KEY_DEBUG_LOG_LIMIT = "settings_debug_log_limit_v1"
    }
}
