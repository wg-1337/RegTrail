package io.github.wg1337.regtrail.xposed.overlay

import android.app.Application
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.wg1337.regtrail.shared.bridge.BridgeApi
import io.github.wg1337.regtrail.xposed.RegTrailLog
import io.github.wg1337.regtrail.xposed.script.ScriptLifecycleBridge
import io.github.wg1337.regtrail.xposed.RegTrailModule
import io.github.wg1337.regtrail.xposed.bridge.AgentTools
import io.github.wg1337.regtrail.xposed.bridge.BridgeDispatcher
import io.github.wg1337.regtrail.xposed.bridge.ModuleSession
import io.github.wg1337.regtrail.xposed.debug.ActivityTracker
import io.github.wg1337.regtrail.xposed.debug.DebugEngine
import io.github.wg1337.regtrail.xposed.debug.InstanceRegistry
import io.github.wg1337.regtrail.xposed.hook.TargetRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Owns the injected floating window for one target process.
 *
 * ## Getting a window that survives Activity changes
 * The requirement is a window that floats above every Activity and does not
 * disappear as the user navigates. The mechanism is:
 *
 * **`TYPE_APPLICATION_OVERLAY` added with a null window token.**
 *
 * - The client side performs **no permission check** — verified in AOSP
 *   `WindowManagerGlobal.java`, which contains no `SYSTEM_ALERT_WINDOW`,
 *   `AppOps` or `checkPermission` reference at all.
 * - The single gate is `PhoneWindowManager.checkAddPermission` inside
 *   system_server, neutralised for scoped packages by
 *   [io.github.wg1337.regtrail.xposed.hook.FloatingWindowPermissionHook].
 * - With `token == null` and no internal-system-window capability, WMS builds
 *   the window token from the client binder itself, which makes the window
 *   **application-global**: it outlives every Activity in the process. This is
 *   exactly the "does not change as Activities change" property required.
 *
 * ## When the context arrives
 * All of this needs the target app's `Context`. Rather than guessing at
 * `ContextImpl` internals, the controller hooks `Application.attach(Context)` �?
 * a stable, public, early entry point that hands us the real `Context` �?and
 * builds the window from there. If the Application was already created before
 * we were loaded (common for `onPackageLoaded`, which fires after
 * `attachBaseContext`), the well-known
 * `AndroidAppHelper.currentApplication()` is tried first.
 */
internal class OverlayController(
    private val module: XposedModule,
    private val param: PackageLoadedParam,
) : ModuleSession {

    private val main = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var overlayView: FloatingOverlayView? = null
    private var params: WindowManager.LayoutParams? = null

    /** Non-null when the degraded Activity-embedded mechanism is in use. */
    private var decorFallback: DecorOverlayFallback? = null

    private var offsetX = 0
    private var offsetY = 0

    private val config = ModuleConfig(module)

    @Volatile
    private var attached = false

    /** The target app's context, captured once it exists. */
    @Volatile
    private var appContext: Context? = null

    /** Which mechanism actually took effect; reported to the settings app. */
    @Volatile
    var mechanism: Mechanism = Mechanism.None
        private set

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Why nothing attached, when nothing attached.
     *
     * Surfaced to the settings app so "no overlay appeared" is never a silent
     * mystery: the user gets the same reason the log has.
     */
    @Volatile
    var skipReason: String? = null
        private set

    fun install() {
        RegTrailLog.bind(module)
        DebugEngine.attach(module)

        TargetRegistry.modulePackage = RegTrailModule.MODULE_PACKAGE
        TargetRegistry.registerFromFramework(param.packageName)

        // Publish this session so the bridge provider in *this* process can
        // reach the debugger and the tools. This happens regardless of whether an
        // overlay attaches: debugging a scoped app is useful on its own.
        BridgeDispatcher.session = this

        // Log the decision inputs. When a user reports "no window", this single
        // line usually answers it without a round trip.
        val snapshot = config.snapshot()
        RegTrailLog.i(
            "${param.packageName}: evaluated �?enabled=${snapshot.enabled}, " +
                "attachAllScoped=${snapshot.attachAllScoped}, " +
                "targets=${snapshot.targetPackages.size}, " +
                "mechanism=${snapshot.overlayMechanism}, " +
                "acknowledged=${snapshot.systemHookAcknowledged}",
        )

        if (!snapshot.enabled) {
            skipReason = "悬浮窗总开关已关闭"
            RegTrailLog.i("${param.packageName}: $skipReason")
            return
        }

        if (!config.isOverlayTarget(param.packageName)) {
            skipReason = "本应用未勾选「悬浮窗�?
            RegTrailLog.i(
                "${param.packageName}: not an overlay target " +
                    "(tick its 悬浮�?checkbox in RegTrail �?目标)",
            )
            return
        }

        val existing = currentApplication()
        if (existing != null) {
            RegTrailLog.d("${param.packageName}: application already available")
            onContextReady(existing)
            return
        }

        // Not up yet: hook the earliest public point that hands us a Context.
        try {
            val cls = Application::class.java
            val attach = cls.getDeclaredMethod("attach", Context::class.java)
            module.hook(attach)
                .setId("regtrail.app.attach")
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    val ctx = chain.getArg(0) as? Context
                    if (ctx != null) onContextReady(ctx)
                    result
                }
            RegTrailLog.d("${param.packageName}: waiting for Application.attach(Context)")
        } catch (t: Throwable) {
            skipReason = "无法 Hook Application.attach�?{t.message}"
            RegTrailLog.w("${param.packageName}: cannot hook Application.attach", t)
        }
    }

    private fun onContextReady(context: Context) {
        val app = context.applicationContext ?: context
        appContext = app

        // Activity tracking is what makes "list activities" / "dump view tree"
        // possible; register it before the window so the first Activity is seen.
        (app as? Application)?.let { ActivityTracker.register(it) }

        // Recorded scripts run even when the window is disabled: they are a
        // debugging feature in their own right (see docs/PLAN.md 5-�?.
        (app as? Application)?.let {
            ScriptLifecycleBridge.install(module, it, param.classLoader)
        }

        if (attached) return
        attached = true
        main.post { attachWindow(app) }
    }

    /**
     * The target application, or null if it has not been created yet.
     *
     * `AndroidAppHelper.currentApplication()` is the sanctioned way to reach it
     * from a module and is what the framework itself uses; the `ActivityThread`
     * reflection path is the documented fallback.
     */
    private fun currentApplication(): Application? {
        runCatching {
            val helper = Class.forName("android.app.AndroidAppHelper")
            val app = helper.getMethod("currentApplication").invoke(null)
            if (app is Application) return app
        }

        runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("currentActivityThread").invoke(null) ?: return@runCatching
            val field = at.getDeclaredField("mInitialApplication").apply { isAccessible = true }
            val app = field.get(thread)
            if (app is Application) return app
        }

        return null
    }

    // -----------------------------------------------------------------------
    // Window
    // -----------------------------------------------------------------------

    private fun attachWindow(context: Context) {
        if (overlayView != null || decorFallback != null) return

        val snapshot = config.snapshot()
        TargetRegistry.setConfiguredTargets(snapshot.targetPackages)

        // Mechanism selection. DecorView is the default because it needs no
        // permission and, crucially, no system_server hook �?so it cannot put the
        // device into a bootloop.
        if (!snapshot.useSystemOverlay) {
            RegTrailLog.i(
                "${param.packageName}: using the DecorView overlay " +
                    "(mechanism=${snapshot.overlayMechanism}, " +
                    "acknowledged=${snapshot.systemHookAcknowledged})",
            )
            installDecorFallback(context, snapshot)
            return
        }

        val wm = runCatching {
            context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }.getOrNull()
        if (wm == null) {
            RegTrailLog.e("${param.packageName}: WindowManager unavailable; using DecorView")
            installDecorFallback(context, snapshot)
            return
        }
        windowManager = wm

        val assets = ModuleAssets(module, context)
        val jsBridge = JsBridge { action, payload -> onWebMessage(action, payload) }

        val view = FloatingOverlayView(
            context = context,
            assets = assets,
            bridge = jsBridge,
            onCollapsedChanged = { collapsed -> resizeForCollapsed(collapsed) },
        )
        view.onDrag = { dx, dy -> moveBy(dx, dy) }

        offsetX = (snapshot.overlayDefaultX * screenWidth(wm)).toInt()
        offsetY = (snapshot.overlayDefaultY * screenHeight(wm)).toInt()

        val lp = buildLayoutParams(context, snapshot)
        params = lp

        try {
            wm.addView(view, lp)
        } catch (t: Throwable) {
            // Refused. Either RegTrail is not scoped to system_server (so the
            // app-op bypass never ran), or the ROM formats the window
            // differently. Degrade rather than disappearing silently.
            RegTrailLog.w(
                "${param.packageName}: TYPE_APPLICATION_OVERLAY rejected " +
                    "(is RegTrail scoped to `system`?); falling back to DecorView",
                t,
            )
            installDecorFallback(context, snapshot)
            return
        }

        overlayView = view
        mechanism = Mechanism.ApplicationOverlay
        RegTrailLog.i(
            "${param.packageName}: overlay attached via TYPE_APPLICATION_OVERLAY " +
                "(permission-free, application-global)",
        )

        view.applyConfig(snapshot.toJsonString())
        RegTrailLog.recent(80).forEach { view.appendLog(it) }
    }

    /**
     * Degraded path: put the overlay inside the current Activity.
     *
     * Reuses [DecorOverlayFallback], which re-attaches on every Activity resume �?
     * that is what makes it look persistent even though each Activity gets a
     * fresh view.
     */
    private fun installDecorFallback(
        context: Context,
        snapshot: ModuleConfig.Snapshot,
    ) {
        val app = context.applicationContext as? Application ?: return
        val fallback = DecorOverlayFallback(module, ModuleAssets(module, context))
        decorFallback = fallback

        fallback.install(app) { action, payload -> onWebMessage(action, payload) }
        fallback.applyConfig(snapshot.toJsonString())
        RegTrailLog.recent(80).forEach { fallback.appendLog(it) }

        mechanism = Mechanism.ActivityDecor
        RegTrailLog.i("${param.packageName}: overlay attached via DecorView fallback")
    }

    private fun buildLayoutParams(
        context: Context,
        snapshot: ModuleConfig.Snapshot,
    ): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        return WindowManager.LayoutParams(
            dp(context, PANEL_WIDTH_DP),
            dp(context, PANEL_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = offsetX
            y = offsetY
            // No token: this is what makes the window application-global instead
            // of a child of a single Activity.
            token = null
            setTitle("RegTrail")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageName = RegTrailModule.MODULE_PACKAGE
            }
        }
    }

    private fun resizeForCollapsed(collapsed: Boolean) {
        val wm = windowManager ?: return
        val lp = params ?: return
        val view = overlayView ?: return

        if (collapsed) {
            lp.width = dp(view.context, COLLAPSED_SIZE_DP)
            lp.height = dp(view.context, COLLAPSED_SIZE_DP)
        } else {
            lp.width = dp(view.context, PANEL_WIDTH_DP)
            lp.height = dp(view.context, PANEL_HEIGHT_DP)
        }
        lp.x = offsetX
        lp.y = offsetY
        runCatching { wm.updateViewLayout(view, lp) }
    }

    private fun moveBy(dx: Int, dy: Int) {
        val wm = windowManager ?: return
        val lp = params ?: return
        val view = overlayView ?: return

        offsetX += dx
        offsetY += dy
        lp.x = offsetX
        lp.y = offsetY
        runCatching { wm.updateViewLayout(view, lp) }
    }

    // -----------------------------------------------------------------------
    // ModuleSession �?what the bridge provider in this process can do
    // -----------------------------------------------------------------------

    override fun reloadConfig() {
        val snapshot = config.reload()
        TargetRegistry.setConfiguredTargets(snapshot.targetPackages)
        val json = snapshot.toJsonString()
        main.post {
            overlayView?.applyConfig(json)
            decorFallback?.applyConfig(json)
        }
    }

    override fun applyOverlay(request: BridgeApi.Request.Overlay) {
        main.post { applyOverlayOnMain(request) }
    }

    private fun applyOverlayOnMain(request: BridgeApi.Request.Overlay) {
        val view = overlayView ?: return
        val wm = windowManager ?: return
        val lp = params ?: return

        request.visible?.let { visible ->
            if (visible) {
                if (view.parent == null) {
                    runCatching { wm.addView(view, lp) }
                }
            } else {
                runCatching { wm.removeView(view) }
            }
        }

        request.collapsed?.let { collapsed ->
            view.setCollapsed(collapsed)
            resizeForCollapsed(collapsed)
        }

        if (request.x != null || request.y != null) {
            request.x?.let { offsetX = (it * screenWidth(wm)).toInt() }
            request.y?.let { offsetY = (it * screenHeight(wm)).toInt() }
            lp.x = offsetX
            lp.y = offsetY
            runCatching { wm.updateViewLayout(view, lp) }
        }
    }

    override fun runTool(name: String, args: JsonObject): BridgeApi.Response =
        AgentTools.run(
            name = name,
            args = args,
            context = appContext,
            loader = param.defaultClassLoader,
        )

    override fun statusJson(): JsonObject = buildJsonObject {
        put("moduleLoaded", JsonPrimitive(true))
        put("package", JsonPrimitive(param.packageName))
        put("processName", JsonPrimitive(currentProcessName()))
        put("mechanism", JsonPrimitive(mechanism.name))
        put(
            "overlayAttached",
            JsonPrimitive(overlayView != null || decorFallback?.isAttached == true),
        )
        // The single most useful diagnostic field: why no window, when there is
        // no window.
        put("skipReason", JsonPrimitive(skipReason ?: ""))
        put("apiVersion", JsonPrimitive(module.apiVersion))
        put("framework", JsonPrimitive("${module.frameworkName} ${module.frameworkVersion}"))
        put("hooks", JsonPrimitive(DebugEngine.snapshot().size))
        put("instances", JsonPrimitive(InstanceRegistry.size()))
        put("activities", JsonPrimitive(ActivityTracker.current().size))
        put("targets", buildJsonArray {
            TargetRegistry.snapshot().forEach { add(JsonPrimitive(it)) }
        })
    }

    /**
     * The current process name.
     *
     * `Application.getProcessName()` needs API 28 and a Context, and can be
     * wrong during early attach; `ActivityThread.currentProcessName()` is the
     * value the system itself uses.
     */
    private fun currentProcessName(): String = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        at.getMethod("currentProcessName").invoke(null) as? String
    }.getOrNull() ?: param.packageName

    // -----------------------------------------------------------------------
    // Web UI messages
    // -----------------------------------------------------------------------

    fun onWebMessage(action: String, payloadJson: String): String? = when (action) {
        "ready" -> {
            RegTrailLog.d("${param.packageName}: web UI ready")
            null
        }

        "collapse" -> {
            main.post {
                overlayView?.setCollapsed(true)
                resizeForCollapsed(true)
            }
            null
        }

        "expand" -> {
            main.post {
                overlayView?.setCollapsed(false)
                resizeForCollapsed(false)
            }
            null
        }

        "hide" -> {
            main.post {
                overlayView?.let { runCatching { windowManager?.removeView(it) } }
            }
            null
        }

        "reloadConfig" -> {
            val snapshot = config.reload()
            TargetRegistry.setConfiguredTargets(snapshot.targetPackages)
            main.post { overlayView?.applyConfig(snapshot.toJsonString()) }
            null
        }

        "status" -> webStatusJson()

        "log" -> {
            RegTrailLog.d("ui: $payloadJson")
            null
        }

        else -> null
    }

    /**
     * Status for the web UI.
     *
     * Shaped as `{"ok":true,"data":{...}}` because that is what the page's
     * `setStatus()` expects; the bridge-facing [statusJson] uses the module's own
     * field names instead.
     */
    private fun webStatusJson(): String {
        val data = statusJson()
        return """{"ok":true,"data":${data}}"""
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun screenWidth(wm: WindowManager): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds.width()
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.width
        }

    private fun screenHeight(wm: WindowManager): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds.height()
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.height
        }

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    /** Live state for the settings app. */
    fun status(): Pair<Boolean, String> = (overlayView != null) to mechanism.name

    /** Applies a fresh configuration snapshot pushed by the settings app. */
    fun applyConfig(snapshot: ModuleConfig.Snapshot) {
        TargetRegistry.setConfiguredTargets(snapshot.targetPackages)
        main.post { overlayView?.applyConfig(snapshot.toJsonString()) }
    }

    fun detach() {
        val wm = windowManager ?: return
        val view = overlayView ?: return
        runCatching { wm.removeViewImmediate(view) }
        overlayView = null
        attached = false
        mechanism = Mechanism.None
    }

    enum class Mechanism {
        None,

        /** `TYPE_APPLICATION_OVERLAY`, application-global, permission bypassed. */
        ApplicationOverlay,

        /** Reserved for the DecorView fallback used by exported no-root builds. */
        ActivityDecor,
    }

    private companion object {
        const val PANEL_WIDTH_DP = 300f
        const val PANEL_HEIGHT_DP = 380f
        const val COLLAPSED_SIZE_DP = 40f
    }
}
