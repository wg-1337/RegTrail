package io.github.wg1337.regtrail.shared.model

import kotlinx.serialization.Serializable

/**
 * The configuration snapshot the settings app hands to an injected module.
 *
 * ## How it travels
 * The settings app writes this as JSON to a world-readable file under
 * `/data/local/tmp/regtrail/config.json` (using root, see
 * `ConfigPublisher` on the app side). The module inside the target process
 * reads that file — it needs no permission of its own, no Binder channel, and
 * no dependency on the settings app still running.
 *
 * `RemotePreferences` (from `io.github.libxposed:service`) is used as a
 * *notification* channel only: the app bumps a revision counter there, and the
 * module's change listener re-reads the file. That keeps the data path simple
 * while still giving instant updates.
 *
 * ## Versioning
 * [version] is compared by the module. A module refuses snapshots newer than it
 * understands rather than misinterpreting fields.
 */
@Serializable
data class ModuleConfigSnapshot(
    val version: Int = VERSION,
    /** Master switch for the injected overlay. */
    val enabled: Boolean = true,

    /**
     * Which apps receive the floating window.
     *
     * **Empty is not "all apps".** An empty list combined with
     * [attachAllScoped] == false means the overlay attaches nowhere, which is the
     * safe default: a freshly installed module must not silently decorate every
     * app the user happened to scope. The earlier "empty means everything"
     * reading was ambiguous and made a mis-published config look like "no
     * targets selected" while behaving like "all targets selected".
     */
    val targetPackages: List<String> = emptyList(),

    /**
     * When true, attach in every app the framework scoped RegTrail to, ignoring
     * [targetPackages]. Off by default; enabling it is an explicit user action.
     */
    val attachAllScoped: Boolean = false,

    /** Packages that must never receive the overlay even if scoped. */
    val excludedPackages: List<String> = emptyList(),

    /**
     * How the floating window is realised.
     *
     * Defaults to [OverlayMechanism.DecorView] deliberately: it needs no special
     * scope, no permission, and — critically — **no system_server hook**, so a
     * mistake cannot bootloop the device.
     */
    val overlayMechanism: OverlayMechanism = OverlayMechanism.DecorView,

    /**
     * Set only after the user explicitly accepts that hooking system_server can
     * bootloop the device. The module refuses
     * [OverlayMechanism.ApplicationOverlay] without it.
     */
    val systemHookAcknowledged: Boolean = false,

    // ---- Appearance (mirrors ThemeConfig's overlay half) ----
    val overlayPrimary: Long = 0xFFFFB300,
    val overlayFontScale: Float = 1.0f,
    val overlayAlpha: Float = 0.95f,
    val overlayCornerDp: Int = 14,
    val overlayDefaultX: Float = 0.75f,
    val overlayDefaultY: Float = 0.25f,

    /** Which panels the window shows, in order. */
    val panels: List<String> = listOf("status", "log"),

    /** Scripts the module may auto-run, keyed by trigger (see docs/PLAN.md 5-③). */
    val scripts: List<ScriptSpec> = emptyList(),

    /** Bumped by the app on every write so the module can detect changes. */
    val revision: Long = 0L,
) {
    /** True when the overlay should attach in [packageName]. */
    fun shouldAttach(packageName: String): Boolean {
        if (!enabled) return false
        if (packageName in excludedPackages) return false
        if (attachAllScoped) return true
        return packageName in targetPackages
    }

    companion object {
        const val VERSION = 1
    }
}

/**
 * How the injected floating window is realised.
 *
 * The two options differ in window layer and in risk, which is why this is a
 * user-visible choice rather than an implementation detail.
 */
@Serializable
enum class OverlayMechanism {
    /**
     * Attach the view inside the current Activity's content area and re-attach
     * on every Activity resume.
     *
     * - Needs **no permission** and **no system_server hook**.
     * - Clipped by the system bars, hidden behind dialogs and the notification
     *   shade rather than floating above them.
     * - Cannot bootloop the device: the code runs only inside the target app, so
     *   the worst case is that one app misbehaves and can be un-scoped.
     */
    DecorView,

    /**
     * A real `TYPE_APPLICATION_OVERLAY` window, application-global and above the
     * system bars.
     *
     * - Requires RegTrail to be scoped to **`system`**, which means the module is
     *   loaded into system_server on every boot.
     * - Requires bypassing the `SYSTEM_ALERT_WINDOW` app-op, which is only
     *   possible by hooking `PhoneWindowManager.checkAddPermission` **inside
     *   system_server**.
     * - **A fault there can bootloop the device before the launcher starts**,
     *   leaving no way to disable the module from inside Android. Recovery means
     *   Magisk safe mode, the LSPosed manager's own recovery path, or a data
     *   wipe.
     */
    ApplicationOverlay,
}

/**
 * A recorded debug script.
 *
 * Serialized as JSON so it can be exported, shared, and edited as text. The
 * shape is deliberately flat and forward-compatible: unknown `kind` values are
 * skipped rather than treated as errors.
 */
@Serializable
data class ScriptSpec(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val trigger: ScriptTrigger = ScriptTrigger.Manual,
    /** For [ScriptTrigger.ActivityOnCreate] / `ActivityOnResume`: class-name match. */
    val triggerTarget: String? = null,
    /** For [ScriptTrigger.MethodEnter]: `com.example.Foo#bar`. */
    val triggerMethod: String? = null,
    val actions: List<ScriptAction> = emptyList(),
)

/** When a script runs. Mirrors docs/PLAN.md 5-③. */
@Serializable
enum class ScriptTrigger {
    /** Fired by the user from the floating window. */
    Manual,

    /** `Application.attachBaseContext`. */
    AppAttach,

    /** `Application.onCreate`. */
    AppOnCreate,

    /** A specific Activity's `onCreate` (see [ScriptSpec.triggerTarget]). */
    ActivityOnCreate,

    /** A specific Activity's `onResume`. */
    ActivityOnResume,

    /** A class finished loading. */
    ClassLoaded,

    /** The first time a given method is entered. */
    MethodEnter,
}

/**
 * One recorded action.
 *
 * Kept intentionally small: this is the serialized form of what the live
 * debugger can do, and every field maps to a concrete operation the module
 * performs in the target process.
 */
@Serializable
data class ScriptAction(
    val kind: Kind,
    /** Fully-qualified class name, and method/field name where relevant. */
    val className: String? = null,
    val memberName: String? = null,
    /** JVM-style descriptor list, e.g. `(Ljava/lang/String;I)V`. */
    val signature: String? = null,
    /** Literal value for `SetReturn` / `SetField` / `SetArg`, as text. */
    val value: String? = null,
    /** Value type hint used to parse [value]. */
    val valueType: ValueType = ValueType.Auto,
    /** For `CallMethod`: the target instance expression (see below). */
    val receiver: String? = null,
) {
    @Serializable
    enum class Kind {
        /** Log the call with its arguments. */
        Trace,

        /** Force a fixed return value. */
        SetReturn,

        /** Replace an argument before the original runs. */
        SetArg,

        /** Read a field and log it. */
        GetField,

        /** Write a field. */
        SetField,

        /** Invoke a method for its side effect. */
        CallMethod,
    }

    @Serializable
    enum class ValueType {
        Auto,
        Boolean,
        Int,
        Long,
        Float,
        Double,
        String,
        Null,
    }
}
