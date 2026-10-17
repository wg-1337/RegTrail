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

    /**
     * Size of the expanded floating window, in dp.
     *
     * These two fields were added **after** the first signed builds shipped, so
     * both *must* keep defaults: an older `config.json` (which has neither key)
     * still decodes to the historical 300x380 dp panel instead of failing to
     * parse. `@Serializable` fills in defaults for absent keys, which is exactly
     * the backwards-compatibility property this relies on.
     *
     * The value published here is only the **starting** size. The user can drag
     * the resize grip inside the window, and that choice is remembered per
     * target process (see `OverlaySizeStore` on the module side). When the
     * settings app publishes a value that differs from the one an earlier
     * resize was based on, the published value wins — that is what makes the
     * slider in the settings app actually do something.
     */
    val overlayWidthDp: Int = OverlaySizeLimits.DEFAULT_WIDTH_DP,
    val overlayHeightDp: Int = OverlaySizeLimits.DEFAULT_HEIGHT_DP,

    /**
     * Whether the module deoptimizes a method before hooking it.
     *
     * `deoptimize` is what makes a hook fire on a method the JIT already inlined
     * into its callers; without it a hook can be installed and silently never
     * run. It also forces a recompile of that method, which is measurable in a
     * hot loop — hence the switch, and hence the default: `true` reproduces the
     * behaviour every earlier build had, so an old `config.json` without this
     * key keeps working (and keeps hooking) exactly as before.
     */
    val autoDeoptimize: Boolean = true,

    /** Which panels the window shows, in order. */
    val panels: List<String> = listOf("status", "log"),

    // ---- Agent endpoint, published for the in-window Agent ----
    //
    // The floating window's Agent tab runs the model loop *inside the target
    // process* (see `xposed/agent/InProcessAgent.kt`), because the tools it drives
    // — live class loaders, object graphs, that process's own memory — exist
    // nowhere else. That process therefore needs the endpoint settings, and the
    // only channel between the settings app and a target process is this snapshot.
    //
    // ## The exposure this creates, stated plainly
    // `/data/local/tmp/regtrail/config.json` is written `0644`, so any app on the
    // device that knows that path can read it. Publishing [agentApiKey] therefore
    // publishes the key device-wide, not just to RegTrail's own targets.
    //
    // That is an accepted, documented trade-off for this build:
    //  - every field has a safe default (`""`), so a settings app that does not
    //    fill these in decodes to "Agent 未配置" instead of failing to parse;
    //  - the module never logs the key and never sends it to the WebView — see
    //    `ModuleConfig.Snapshot.toJsonString`, which redacts it before the page
    //    ever sees the config;
    //  - the settings UI warns next to the key field, and the in-window Agent says
    //    「未配置」 until it is filled in.
    //
    // Moving the key to a 0600 file or a RemotePreferences channel needs both
    // halves (publisher and reader) to change together; changing only one silently
    // breaks the Agent, so it is deliberately not done here.

    /** Base URL *including* `/v1`, e.g. `https://api.openai.com/v1`. Empty = default. */
    val agentBaseUrl: String = "",

    /** Model name, e.g. `gpt-4o-mini`. Empty = default. */
    val agentModel: String = "",

    /** Bearer key for that endpoint. Empty means "Agent 未配置" in the window. */
    val agentApiKey: String = "",

    /** Tool rounds one turn may use; 0 = the module's own default. */
    val agentMaxRounds: Int = 0,

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
 * Size limits for the injected floating window, in dp.
 *
 * ## Why these live in `shared`
 * Three different places need the *same* limits and would drift apart if each
 * hard-coded its own numbers:
 *
 *  - the module, which clamps a `resize` message coming from the window's own
 *    web UI (a drag can otherwise produce negative or off-screen sizes),
 *  - the module again, which clamps what the settings app published before
 *    handing it to `LayoutParams` (a hand-edited config file must not be able to
 *    create a window the user cannot reach),
 *  - the settings app's sliders, so the range the user can pick is the range the
 *    module will accept.
 *
 * The floor is deliberately larger than "a few pixels": below roughly 220x200 dp
 * the tab bar and the resize grip overlap and the window becomes unusable with
 * no way to recover except clearing app data. The ceiling is expressed as a
 * *fraction of the screen* rather than a fixed dp value because the honest upper
 * bound depends on the device — a 320 dp-wide phone and a 1000 dp-wide tablet
 * have nothing useful in common here.
 */
object OverlaySizeLimits {

    /** Narrowest usable panel; the tab labels stop fitting below this. */
    const val MIN_WIDTH_DP = 220

    /** Shortest usable panel; header + tabs + one row of content. */
    const val MIN_HEIGHT_DP = 200

    /**
     * Size the window opens at when nothing else is known.
     *
     * Chosen (and verified) against a landscape 2000x1200 @ density 320 device:
     * 300x380 dp is a comfortable fraction of the 1000x600 dp available and does
     * not cover the host app's primary content.
     */
    const val DEFAULT_WIDTH_DP = 300
    const val DEFAULT_HEIGHT_DP = 380

    /** Upper bound as a fraction of the usable screen: never a full-screen window. */
    const val MAX_SCREEN_FRACTION = 0.9f

    /** Slider ceiling in the settings app; the module still clamps to the screen. */
    const val SLIDER_MAX_WIDTH_DP = 700
    const val SLIDER_MAX_HEIGHT_DP = 1000

    /** Clamps to [MIN_WIDTH_DP]..[maxWidthDp]; `maxDp` comes from the screen. */
    fun clampWidth(widthDp: Int, maxWidthDp: Int): Int =
        widthDp.coerceIn(MIN_WIDTH_DP, maxOf(MIN_WIDTH_DP, maxWidthDp))

    /** Clamps to [MIN_HEIGHT_DP]..[maxHeightDp]; `maxDp` comes from the screen. */
    fun clampHeight(heightDp: Int, maxHeightDp: Int): Int =
        heightDp.coerceIn(MIN_HEIGHT_DP, maxOf(MIN_HEIGHT_DP, maxHeightDp))

    /** The largest panel width, in dp, that fits [screenWidthPx] at [density]. */
    fun maxWidthDp(screenWidthPx: Int, density: Float): Int =
        (screenWidthPx * MAX_SCREEN_FRACTION / density.coerceAtLeast(0.1f)).toInt()

    /** The largest panel height, in dp, that fits [screenHeightPx] at [density]. */
    fun maxHeightDp(screenHeightPx: Int, density: Float): Int =
        (screenHeightPx * MAX_SCREEN_FRACTION / density.coerceAtLeast(0.1f)).toInt()
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
