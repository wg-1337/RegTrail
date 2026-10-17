package io.github.wg1337.regtrail.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.wg1337.regtrail.shared.model.ThemeConfig

/**
 * Holds the app's in-memory configuration: appearance ([theme]) and behaviour
 * ([settings]).
 *
 * Persistence is delegated to the caller through the two `onPersist*` callbacks
 * so that the UI module stays free of Android storage concerns (and so the
 * injected payload's config path can reuse the same model later).
 *
 * The two halves are kept apart because they end up in different places: the
 * theme is published to the module wholesale, while only two fields of
 * [AppSettings] are (see [AppSettings]).
 */
class ConfigStore(
    initialTheme: ThemeConfig = ThemeConfig(),
    initialSettings: AppSettings = AppSettings(),
    private val onPersistTheme: (ThemeConfig) -> Unit = {},
    private val onPersistSettings: (AppSettings) -> Unit = {},
) {
    var theme: ThemeConfig by mutableStateOf(initialTheme)
        private set

    var settings: AppSettings by mutableStateOf(initialSettings)
        private set

    /** Applies [transform] to the current theme and persists the result. */
    fun updateTheme(transform: (ThemeConfig) -> ThemeConfig) {
        val next = transform(theme)
        theme = next
        onPersistTheme(next)
    }

    /** Applies [transform] to the current settings and persists the result. */
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(settings)
        settings = next
        onPersistSettings(next)
    }

    fun resetTheme() {
        updateTheme { ThemeConfig() }
    }

    fun resetSettings() {
        updateSettings { AppSettings() }
    }

    /**
     * Restores both halves to their factory values.
     *
     * Deliberately does *not* touch the framework scope or the produced APKs:
     * those describe what the user is inspecting, not how the app is
     * configured, and silently revoking scope or deleting files from a "reset
     * settings" button would be a trap.
     */
    fun resetAll() {
        resetTheme()
        resetSettings()
    }
}
