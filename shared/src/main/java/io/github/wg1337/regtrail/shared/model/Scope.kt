package io.github.wg1337.regtrail.shared.model

/**
 * A launchable, third-party app the user may pick as an injection target.
 *
 * Lives in `:shared` rather than next to the enumeration code because both
 * `:app` (which reads PackageManager) and `:ui` (which renders the list) need
 * the type, and `:ui` must not depend on `:app`.
 */
data class TargetApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val uid: Int,
)

/**
 * Snapshot of the framework's scope state, shaped for the UI.
 *
 * Kept free of any `io.github.libxposed.service` type so `:ui` never needs the
 * framework on its classpath: `:app` translates the live objects into this.
 */
data class ScopeUiState(
    val bound: Boolean = false,
    val frameworkName: String? = null,
    val frameworkVersion: String? = null,
    val apiVersion: Int = 0,
    /** Packages currently inside the module's framework scope. */
    val scope: List<String> = emptyList(),
    /** Packages the user has ticked in the UI but not yet requested. */
    val pendingGrant: Set<String> = emptySet(),
    /** Packages whose overlay checkbox is on (RegTrail-level filter). */
    val overlayTargets: Set<String> = emptySet(),
    val apps: List<TargetApp> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    /**
     * True only when the user selected the `TYPE_APPLICATION_OVERLAY` mechanism
     * **and** acknowledged its bootloop risk.
     *
     * The default DecorView mechanism needs no `system` scope at all, so the UI
     * must not nag about it. Getting this wrong actively pushes users toward the
     * one option that can leave their device unbootable.
     */
    val requiresSystemScope: Boolean = false,
) {
    /**
     * Whether `system` is currently in scope.
     *
     * Modern scope vocabulary uses `system` for system_server; `android` and
     * `system_server` are accepted too because older configurations and some
     * forks use those spellings.
     */
    val hasSystemScope: Boolean
        get() = scope.any { it == "system" || it == "system_server" || it == "android" }

    /** Show the "grant system scope" call-to-action only when it is needed. */
    val showSystemScopePrompt: Boolean
        get() = bound && requiresSystemScope && !hasSystemScope
}
