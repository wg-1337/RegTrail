package io.github.wg1337.regtrail.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.wg1337.regtrail.shared.model.ThemeConfig

/**
 * Holds the app's in-memory configuration.
 *
 * Persistence is delegated to the caller through [onPersist] so that the UI
 * module stays free of Android storage concerns (and so the injected payload's
 * config path can reuse the same model later).
 */
class ConfigStore(
    initial: ThemeConfig = ThemeConfig(),
    private val onPersist: (ThemeConfig) -> Unit = {},
) {
    var theme: ThemeConfig by mutableStateOf(initial)
        private set

    /** Applies [transform] to the current theme and persists the result. */
    fun updateTheme(transform: (ThemeConfig) -> ThemeConfig) {
        val next = transform(theme)
        theme = next
        onPersist(next)
    }

    fun resetTheme() {
        updateTheme { ThemeConfig() }
    }
}
