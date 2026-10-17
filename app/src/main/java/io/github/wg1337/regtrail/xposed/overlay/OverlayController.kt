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
import io.github.wg1337.regtrail.shared.model.OverlaySizeLimits
import io.github.wg1337.regtrail.xposed.RegTrailLog
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.github.wg1337.regtrail.xposed.script.ScriptEngine
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
 * `ContextImpl` internals, the controller hooks `Application.attach(Context)` —
 * a stable, public, early entry point that hands us the real `Context` — and
 * builds the window from there. If the Application was already created before
 * we were loaded (common for `onPackageLoaded`, which fires after
 * `attachBaseContext`), the well-known
 * `AndroidAppHelper.currentApplication()` is tried first.
 *
 * ## The resize protocol
 * The window's own web UI *is* the resize affordance: a grip in the bottom-right
 * corner posts `{"action":"resize","payload":{"widthDp":320,"heightDp":420}}`
 * through the JS bridge, and this class turns it into `LayoutParams` for whichever
 * mechanism is live. Both mechanisms ultimately size a `View`, so the same clamp
 * ([OverlaySizeLimits]) and the same dp→px conversion ([OverlaySizeMath.px]) are
 * applied on both paths — duplicating either is how a DecorView panel and a real
 * overlay window end up disagreeing about what "90 % of the screen" means.
 *
 * The chosen size is persisted per target app by [OverlaySizeStore]: the module
 * runs with the *host* app's unprivileged UID and therefore cannot write the
 * root-owned `config.json`. The published `overlayWidthDp` / `overlayHeightDp`
 * stay the default, and the settings app publishing a different value supersedes
 * a remembered drag (see [adoptPublishedSize]).
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

    /**
     * Size of the **expanded** panel, in dp.
     *
     * Volatile because it is written on the main thread (a resize drag, an
     * Activity re-attach) and read from the WebView's JS bridge thread by the
     * `status` message, which the page polls. A torn pair would show the user a
     * wrong size, and `Pair` is immutable so a volatile field is enough.
     *
     * The collapsed size is not stored here: it is a fixed pill whose dimensions
     * belong to [FloatingOverlayView], and keeping it out of this field is what
     * makes "expand" able to restore the user's chosen size at all.
     */
    @Volatile
    private var panelSizeDp: Pair<Int, Int> =
        OverlaySizeLimits.DEFAULT_WIDTH_DP to OverlaySizeLimits.DEFAULT_HEIGHT_DP

    /** Remembers a dragged size across target-process restarts. */
    private var sizeStore: OverlaySizeStore? = null

    /**
     * The size the *published config* asked for.
     *
     * Compared against on every config reload: when the settings app publishes a
     * different default, that decision supersedes a remembered drag (see
     * [OverlaySizeStore]).
     */
    @Volatile
    private var configSizeDp: Pair<Int, Int> =
        OverlaySizeLimits.DEFAULT_WIDTH_DP to OverlaySizeLimits.DEFAULT_HEIGHT_DP

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
            "${param.packageName}: evaluated — enabled=${snapshot.enabled}, " +
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
            skipReason = "本应用未勾选「悬浮窗」"
            RegTrailLog.i(
                "${param.packageName}: not an overlay target " +
                    "(tick its 悬浮窗 checkbox in RegTrail → 目标)",
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
            skipReason = "无法 Hook Application.attach：${t.message}"
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
        // debugging feature in their own right (see docs/PLAN.md 5-③).
        (app as? Application)?.let {
            ScriptLifecycleBridge.install(module, it, context.classLoader)
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

        // Resolve a WindowManager even on the DecorView path: that mechanism does
        // not need one to *add* a window, but it is the only reliable source of
        // the screen bounds the resize clamp is expressed against.
        val wm = runCatching {
            context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }.getOrNull()
        wm?.let { windowManager = it }

        // Resolve the panel size before the mechanism is chosen, so the two paths
        // open at the same size. This is also where a size the user dragged in an
        // earlier run of this app comes back. Deliberately after the
        // `WindowManager` is stored, so the screen measurement it uses is the real
        // window metric rather than the raw display metrics.
        resolvePanelSize(context, snapshot)

        // Mechanism selection. DecorView is the default because it needs no
        // permission and, crucially, no system_server hook — so it cannot put the
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

        if (wm == null) {
            RegTrailLog.e("${param.packageName}: WindowManager unavailable; using DecorView")
            installDecorFallback(context, snapshot)
            return
        }

        val assets = ModuleAssets(module, context)
        val jsBridge = JsBridge { action, payload -> onWebMessage(action, payload) }

        val view = FloatingOverlayView(
            context = context,
            assets = assets,
            bridge = jsBridge,
            onCollapsedChanged = { collapsed -> applyCollapsed(collapsed) },
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
     * Reuses [DecorOverlayFallback], which re-attaches on every Activity resume —
     * that is what makes it look persistent even though each Activity gets a
     * fresh view.
     */
    private fun installDecorFallback(
        context: Context,
        snapshot: ModuleConfig.Snapshot,
    ) {
        val app = context.applicationContext as? Application ?: return
        // The size is handed over explicitly: this path has no `params` of its own,
        // and the fallback must still open at whatever the user last dragged.
        val fallback = DecorOverlayFallback(
            module = module,
            assets = ModuleAssets(module, context),
            panelSizeDp = panelSizeDp,
        )
        decorFallback = fallback

        fallback.install(
            app = app,
            handler = { action, payload -> onWebMessage(action, payload) },
            onCollapsed = { collapsed -> applyCollapsed(collapsed) },
        )
        fallback.applyConfig(snapshot.toJsonString())
        RegTrailLog.recent(80).forEach { fallback.appendLog(it) }

        mechanism = Mechanism.ActivityDecor
        RegTrailLog.i(
            "${param.packageName}: overlay attached via DecorView fallback " +
                "(${panelSizeDp.first}x${panelSizeDp.second} dp)",
        )
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
            dp(context, panelSizeDp.first.toFloat()),
            dp(context, panelSizeDp.second.toFloat()),
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

    /**
     * Resolves the size the panel opens at and remembers what the config asked for.
     *
     * Two sources: the published default (settings app) and a remembered drag
     * (this target app's private storage). [OverlaySizeStore.resolve] implements
     * the rule that makes them coexist; this method only clamps the winner.
     */
    private fun resolvePanelSize(context: Context, snapshot: ModuleConfig.Snapshot) {
        configSizeDp = snapshot.overlayWidthDp to snapshot.overlayHeightDp

        val store = sizeStore ?: OverlaySizeStore(context).also { sizeStore = it }
        val remembered = store.resolve(snapshot.overlayWidthDp, snapshot.overlayHeightDp)

        val density = context.resources.displayMetrics.density
        panelSizeDp = OverlaySizeMath.clamp(
            widthDp = remembered.first,
            heightDp = remembered.second,
            screenWidthPx = screenWidthPx(context),
            screenHeightPx = screenHeightPx(context),
            density = density,
        )

        RegTrailLog.d(
            "${param.packageName}: panel size ${panelSizeDp.first}x${panelSizeDp.second} dp " +
                "(config=${snapshot.overlayWidthDp}x${snapshot.overlayHeightDp}, " +
                "density=$density)",
        )
    }

    /**
     * Applies a resize requested by the window's own web UI.
     *
     * Runs on the main thread. Both mechanisms are updated: only one of them is
     * ever non-null, but updating both keeps the caller free of mechanism
     * knowledge — and an in-flight mechanism switch cannot lose the new size.
     */
    private fun applyPanelSize(widthDp: Int, heightDp: Int, persist: Boolean = true) {
        val context = appContext ?: return
        val clamped = clampPanelSize(widthDp, heightDp) ?: return
        panelSizeDp = clamped

        val view = overlayView
        val wm = windowManager
        val lp = params
        if (view != null && wm != null && lp != null && !view.isCollapsed) {
            val px = OverlaySizeMath.px(context, clamped.first, clamped.second)
            lp.width = px.first
            lp.height = px.second
            lp.x = offsetX
            lp.y = offsetY
            runCatching { wm.updateViewLayout(view, lp) }
        }

        decorFallback?.resizeTo(clamped.first, clamped.second)

        if (persist) {
            sizeStore?.remember(
                widthDp = clamped.first,
                heightDp = clamped.second,
                configWidthDp = configSizeDp.first,
                configHeightDp = configSizeDp.second,
            )
        }
    }

    /**
     * Collapses or expands the panel, whichever mechanism is live.
     *
     * Collapsing is a *window size* change in both mechanisms: the WebView is
     * hidden, and the container shrinks to the pill, so the window stops covering
     * (and swallowing touches over) the host app's UI.
     */
    private fun applyCollapsed(collapsed: Boolean) {
        overlayView?.let { view ->
            view.setCollapsed(collapsed)
            resizeForCollapsed(collapsed)
        }
        decorFallback?.setCollapsed(collapsed)
        RegTrailLog.d("${param.packageName}: overlay collapsed=$collapsed")
    }

    /**
     * Re-pushes the current collapsed state to the live views without changing it.
     *
     * Needed because a WebView is recreated more often than the state changes (a
     * new Activity in the DecorView mechanism, a reload after `hide`), and the page
     * always boots in the expanded layout. Calling `setCollapsed` with the *current*
     * value is idempotent — one `evaluateJavascript` and one size re-apply — and is
     * far less error-prone than threading the state through the page URL.
     */
    private fun reassertCollapsedState() {
        overlayView?.let { view ->
            view.setCollapsed(view.isCollapsed)
            resizeForCollapsed(view.isCollapsed)
        }
        decorFallback?.let { it.setCollapsed(it.isCollapsed) }
    }

    private fun resizeForCollapsed(collapsed: Boolean) {
        val wm = windowManager ?: return
        val lp = params ?: return
        val view = overlayView ?: return

        if (collapsed) {
            lp.width = dp(view.context, FloatingOverlayView.COLLAPSED_WIDTH_DP)
            lp.height = dp(view.context, FloatingOverlayView.COLLAPSED_HEIGHT_DP)
        } else {
            lp.width = dp(view.context, panelSizeDp.first.toFloat())
            lp.height = dp(view.context, panelSizeDp.second.toFloat())
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

        // Keep a grabbable margin on screen. `FLAG_LAYOUT_NO_LIMITS` means the
        // window may legitimately hang over the edge, but a window dragged
        // *completely* off-screen can never be recovered: inside the target app
        // there is no other entry point into this UI than this window itself.
        val keepVisible = dp(view.context, KEEP_ON_SCREEN_DP)
        offsetX = offsetX.coerceIn(keepVisible - lp.width, screenWidth(wm) - keepVisible)
        offsetY = offsetY.coerceIn(0, screenHeight(wm) - keepVisible)

        lp.x = offsetX
        lp.y = offsetY
        runCatching { wm.updateViewLayout(view, lp) }
    }

    // -----------------------------------------------------------------------
    // ModuleSession — what the bridge provider in this process can do
    // -----------------------------------------------------------------------

    override fun reloadConfig() {
        val snapshot = config.reload()
        TargetRegistry.setConfiguredTargets(snapshot.targetPackages)
        val json = snapshot.toJsonString()
        main.post {
            adoptPublishedSize(snapshot)
            overlayView?.applyConfig(json)
            decorFallback?.applyConfig(json)
        }
    }

    override fun applyOverlay(request: BridgeApi.Request.Overlay) {
        main.post { applyOverlayOnMain(request) }
    }

    private fun applyOverlayOnMain(request: BridgeApi.Request.Overlay) {
        val view = overlayView
        val wm = windowManager
        val lp = params

        if (view == null || wm == null || lp == null) {
            // DecorView mechanism: there is no window to add or remove, so
            // "visible" maps to "attach on the next resume" through [setHidden]
            // and the x/y fractions have no meaning (the panel lives inside the
            // Activity's content area, positioned by its own margins).
            decorFallback?.let { decor ->
                request.visible?.let { visible -> decor.setHidden(!visible) }
                request.collapsed?.let { collapsed -> decor.setCollapsed(collapsed) }
            }
            return
        }

        request.visible?.let { visible ->
            if (visible) {
                if (view.parent == null) {
                    runCatching { wm.addView(view, lp) }
                }
            } else {
                runCatching { wm.removeView(view) }
            }
        }

        // Routed through the shared helper so a collapsed state pushed by the
        // settings app reaches whichever mechanism is live and keeps the
        // expanded size intact for the next expand.
        request.collapsed?.let { collapsed -> applyCollapsed(collapsed) }

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
        // Live panel geometry, in dp. Read straight out of the field the resize
        // path writes, so the 状态 tab shows what is on screen rather than what the
        // config asked for.
        put("widthDp", JsonPrimitive(panelSizeDp.first))
        put("heightDp", JsonPrimitive(panelSizeDp.second))
        put(
            "collapsed",
            JsonPrimitive(overlayView?.isCollapsed ?: decorFallback?.isCollapsed ?: false),
        )
        // Two different numbers, both useful: `targetCount` is *every* package
        // RegTrail may touch (framework scope merged with the explicit list), while
        // `configuredTargetCount` is the "悬浮窗" tick list the settings app
        // published — that is the one that explains "why does this app have no
        // window".
        put("targetCount", JsonPrimitive(TargetRegistry.snapshot().size))
        put(
            "configuredTargetCount",
            JsonPrimitive(config.snapshot().targetPackages.size),
        )
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
            // The page boots in its expanded layout. Re-assert the real state, so a
            // collapse survives an Activity re-attach (new WebView) and a reload
            // after 「隐藏窗口」 — otherwise the window would be pill-sized while the
            // page renders the full panel.
            main.post { reassertCollapsedState() }
            null
        }

        "collapse" -> {
            main.post { applyCollapsed(true) }
            null
        }

        "expand" -> {
            main.post { applyCollapsed(false) }
            null
        }

        "hide" -> {
            main.post {
                overlayView?.let { runCatching { windowManager?.removeView(it) } }
                // The DecorView mechanism has no window to remove: hiding means
                // detaching the view and not re-attaching on the next resume.
                decorFallback?.setHidden(true)
            }
            null
        }

        "reloadConfig" -> {
            val snapshot = config.reload()
            TargetRegistry.setConfiguredTargets(snapshot.targetPackages)
            val json = snapshot.toJsonString()
            main.post {
                adoptPublishedSize(snapshot)
                overlayView?.applyConfig(json)
                decorFallback?.applyConfig(json)
            }
            webOk(buildJsonObject { put("targets", JsonPrimitive(snapshot.targetPackages.size)) })
        }

        // The resize grip in the page. The clamp happens on the *native* side
        // because only native code knows the display bounds and the density: a
        // stale page (rotation, different target app) must not be able to size the
        // window outside the screen and make it unreachable.
        "resize" -> webResize(payloadJson)

        // Log tail for the 日志 tab. The page polls this and only while that tab
        // is visible, so an idle window costs nothing.
        "logs" -> webLogs()

        "clearLogs" -> {
            val dropped = RegTrailLog.clear()
            RegTrailLog.i("ui: 日志已清空（$dropped 行）")
            webOk(buildJsonObject { put("cleared", JsonPrimitive(dropped)) })
        }

        "listScripts" -> webScripts()

        "runScript" -> webRunScript(payloadJson)

        "status" -> webStatusJson()

        "log" -> {
            RegTrailLog.d("ui: $payloadJson")
            null
        }

        else -> null
    }

    /**
     * Applies a size dragged in the window's web UI.
     *
     * Protocol: `{"action":"resize","payload":{"widthDp":320,"heightDp":420}}`.
     * The reply carries the **clamped** size, so the page can snap its drag maths
     * to what the window actually became instead of drifting out of sync at the
     * limits.
     */
    private fun webResize(payloadJson: String): String {
        val widthDp = payloadInt(payloadJson, "widthDp")
        val heightDp = payloadInt(payloadJson, "heightDp")
        if (widthDp == null || heightDp == null) {
            return webError("resize 需要数值型 widthDp 与 heightDp")
        }

        // The pill deliberately has no grip. Refusing here as well means a page
        // that somehow still sends a resize (a stale tab, a replayed message)
        // cannot leave the collapsed window the wrong size.
        if (overlayView?.isCollapsed == true || decorFallback?.isCollapsed == true) {
            return webError("折叠状态下不可调整大小")
        }

        val clamped = clampPanelSize(widthDp, heightDp) ?: return webError("窗口尚未挂载")
        // `final` marks the last message of a drag: the page sends one resize per
        // animation frame, and writing preferences on each of those would be a
        // file write per frame for no benefit. Absent means "persist", so any other
        // caller (a script, the settings app's future resize push) still works.
        val isFinal = payloadBool(payloadJson, "final") ?: true
        main.post { applyPanelSize(clamped.first, clamped.second, persist = isFinal) }

        return webOk(
            buildJsonObject {
                put("widthDp", JsonPrimitive(clamped.first))
                put("heightDp", JsonPrimitive(clamped.second))
                put(
                    "clamped",
                    JsonPrimitive(clamped.first != widthDp || clamped.second != heightDp),
                )
                put("final", JsonPrimitive(isFinal))
            },
        )
    }

    /** The last [LOG_TAIL] log lines, as the 日志 tab's data source. */
    private fun webLogs(): String {
        val lines = RegTrailLog.recent(LOG_TAIL)
        return webOk(
            buildJsonObject {
                put(
                    "lines",
                    buildJsonArray { lines.forEach { add(JsonPrimitive(it)) } },
                )
                put("count", JsonPrimitive(lines.size))
                put("capacity", JsonPrimitive(RegTrailLog.capacity))
            },
        )
    }

    /** The scripts the 工具 tab may offer a 「运行」 button for. */
    private fun webScripts(): String {
        val scripts = ScriptEngine.scriptEntries()
        return webOk(
            buildJsonObject {
                put(
                    "scripts",
                    buildJsonArray {
                        scripts.forEach { spec ->
                            add(
                                buildJsonObject {
                                    put("id", JsonPrimitive(spec.id))
                                    put("name", JsonPrimitive(spec.name))
                                    put("trigger", JsonPrimitive(spec.trigger.name))
                                    put("actions", JsonPrimitive(spec.actions.size))
                                },
                            )
                        }
                    },
                )
                put("count", JsonPrimitive(scripts.size))
            },
        )
    }

    /** Runs one script by id: `{"action":"runScript","payload":{"id":"..."}}`. */
    private fun webRunScript(payloadJson: String): String {
        val id = payloadString(payloadJson, "id") ?: return webError("runScript 需要脚本 id")
        val result = ScriptEngine.runManual(id, appContext)
        RegTrailLog.i("ui: runScript $id → $result")
        return webOk(
            buildJsonObject {
                put("id", JsonPrimitive(id))
                put("result", JsonPrimitive(result))
            },
        )
    }

    /**
     * Status for the web UI.
     *
     * Shaped as `{"ok":true,"data":{...}}` because that is what the page's
     * `setStatus()` expects; the bridge-facing [statusJson] uses the module's own
     * field names instead.
     */
    private fun webStatusJson(): String = webOk(statusJson())

    /** Wraps a `data` object in the `{"ok":true,...}` envelope the page expects. */
    private fun webOk(data: JsonObject): String = """{"ok":true,"data":$data}"""

    /**
     * The error envelope the page's `request()` helper understands.
     *
     * The message is Chinese because it is rendered verbatim in the 工具 tab's
     * status line — the same voice as every other user-facing string.
     */
    private fun webError(message: String): String =
        """{"ok":false,"error":${JsonPrimitive(message)}}"""

    /**
     * Reads an integer field out of a bridge payload.
     *
     * The page sends `JSON.stringify(...)`, so a number may arrive as `320` or as
     * `320.4`; both are accepted. Anything unparsable yields null so the caller can
     * answer with an error instead of throwing — the JS bridge would otherwise
     * swallow the exception and the grip would just stop working with no
     * explanation.
     */
    private fun payloadInt(payloadJson: String, key: String): Int? = runCatching {
        BridgeApi.json.parseToJsonElement(payloadJson)
            .jsonObject[key]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toFloatOrNull()
            ?.toInt()
    }.getOrNull()

    /** Reads a non-blank string field out of a bridge payload. */
    private fun payloadString(payloadJson: String, key: String): String? = runCatching {
        BridgeApi.json.parseToJsonElement(payloadJson)
            .jsonObject[key]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Reads a boolean field out of a bridge payload; null when absent. */
    private fun payloadBool(payloadJson: String, key: String): Boolean? = runCatching {
        BridgeApi.json.parseToJsonElement(payloadJson)
            .jsonObject[key]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.let { it == "true" || it == "1" }
    }.getOrNull()

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

    /**
     * Screen width in pixels, for the resize clamp.
     *
     * A [WindowManager] is preferred because it reports the *current* display
     * (rotation, insets); the display metrics are the fallback for the DecorView
     * path, which can run before any window service is resolved.
     */
    private fun screenWidthPx(context: Context): Int =
        windowManager?.let { screenWidth(it) } ?: context.resources.displayMetrics.widthPixels

    /** Screen height in pixels, for the resize clamp. See [screenWidthPx]. */
    private fun screenHeightPx(context: Context): Int =
        windowManager?.let { screenHeight(it) } ?: context.resources.displayMetrics.heightPixels

    /**
     * Clamps a requested panel size to the shared bounds.
     *
     * @return null when the window is not attached yet (no context), which is the
     *   only case where the density and screen size are unknown.
     */
    private fun clampPanelSize(widthDp: Int, heightDp: Int): Pair<Int, Int>? {
        val context = appContext ?: return null
        return OverlaySizeMath.clamp(
            widthDp = widthDp,
            heightDp = heightDp,
            screenWidthPx = screenWidthPx(context),
            screenHeightPx = screenHeightPx(context),
            density = context.resources.displayMetrics.density,
        )
    }

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    /** Live state for the settings app. */
    fun status(): Pair<Boolean, String> = (overlayView != null) to mechanism.name

    /** Applies a fresh configuration snapshot pushed by the settings app. */
    fun applyConfig(snapshot: ModuleConfig.Snapshot) {
        TargetRegistry.setConfiguredTargets(snapshot.targetPackages)
        val json = snapshot.toJsonString()
        main.post {
            adoptPublishedSize(snapshot)
            overlayView?.applyConfig(json)
            decorFallback?.applyConfig(json)
        }
    }

    /**
     * Re-applies the size when the settings app published a *different* default.
     *
     * This is what makes the size sliders in the settings app effective on a
     * window that is already on screen: a plain config reload otherwise only
     * re-themes the panel. A remembered drag that was made on top of the previous
     * default is dropped here (see [OverlaySizeStore.resolve]), which doubles as
     * the "reset a window I sized badly" path.
     */
    private fun adoptPublishedSize(snapshot: ModuleConfig.Snapshot) {
        val published = snapshot.overlayWidthDp to snapshot.overlayHeightDp
        if (published == configSizeDp) return

        val context = appContext ?: return
        resolvePanelSize(context, snapshot)
        // `persist = false`: this size came *from* the config, so writing it back
        // as a local override would make the next published change look like a
        // no-op.
        applyPanelSize(panelSizeDp.first, panelSizeDp.second, persist = false)
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
        /**
         * How many lines the 日志 tab fetches per poll.
         *
         * 200 matches what [RegTrailLog.recent] defaults to and is roughly the
         * amount a phone-sized panel can hold while still scrolling smoothly;
         * the ring itself keeps 400, so the extra history is available to other
         * consumers without making every poll expensive.
         */
        const val LOG_TAIL = 200

        /**
         * How much of the window must stay on screen after a drag, in dp.
         *
         * Enough to see and grab the collapsed pill (which is ~112 dp wide), and
         * therefore enough to recover from a drag that overshoots.
         */
        const val KEEP_ON_SCREEN_DP = 48f
    }
}
