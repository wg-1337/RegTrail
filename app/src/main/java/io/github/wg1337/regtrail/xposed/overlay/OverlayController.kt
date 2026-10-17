package io.github.wg1337.regtrail.xposed.overlay

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.wg1337.regtrail.agent.AgentConfig
import io.github.wg1337.regtrail.shared.bridge.BridgeApi
import io.github.wg1337.regtrail.shared.model.OverlaySizeLimits
import io.github.wg1337.regtrail.xposed.RegTrailLog
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.github.wg1337.regtrail.xposed.agent.InProcessAgent
import io.github.wg1337.regtrail.xposed.bridge.InProcessJobs
import io.github.wg1337.regtrail.xposed.mem.MemorySessions
import io.github.wg1337.regtrail.xposed.script.ScriptEngine
import io.github.wg1337.regtrail.xposed.script.ScriptLifecycleBridge
import io.github.wg1337.regtrail.xposed.RegTrailModule
import io.github.wg1337.regtrail.xposed.bridge.AgentTools
import io.github.wg1337.regtrail.xposed.bridge.BridgeDispatcher
import io.github.wg1337.regtrail.xposed.bridge.ModuleSession
import io.github.wg1337.regtrail.xposed.debug.ActivityTracker
import io.github.wg1337.regtrail.xposed.debug.DebugEngine
import io.github.wg1337.regtrail.xposed.debug.DebugHookBehavior
import io.github.wg1337.regtrail.xposed.debug.InstanceRegistry
import io.github.wg1337.regtrail.xposed.hook.TargetRegistry
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
     * Window offset at the moment a header drag started, in pixels.
     *
     * The page sends the **total** delta since the finger went down rather than a
     * per-frame increment, so the native side only has to add that delta to this
     * origin. That makes the operation idempotent: a duplicated or reordered
     * message cannot accumulate drift, and the clamp is applied to an absolute
     * position every time instead of to a running sum.
     */
    private var dragOriginOffsetX = 0
    private var dragOriginOffsetY = 0

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
     * Source of the numeric hook ids the 调试 tab hands out.
     *
     * The page's protocol is `{"hookId":N}`, so the ids it sees are plain numbers
     * rather than the engine's `h1`/`h2` strings. Both remain valid ids inside
     * [DebugEngine]; the Agent's own hooks simply report `hookId: 0` in the list.
     */
    private val debugHookSeq = AtomicInteger(0)

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
        // See [setImeFocus]: while a text field in the page is being edited this
        // window is focusable, and BACK has to drop that focus again.
        view.onBack = { main.post { setImeFocus(false) } }

        offsetX = (snapshot.overlayDefaultX * screenWidth(wm)).toInt()
        offsetY = (snapshot.overlayDefaultY * screenHeight(wm)).toInt()
        // A position the user dragged a previous run to comes back here, clamped to
        // the *current* display: a window parked near the right edge in landscape
        // would otherwise open off-screen after a rotation.
        sizeStore?.position()?.let { saved ->
            offsetX = saved.first
            offsetY = saved.second
        }
        // Whichever of the two sources won, the panel must open reachable: the
        // published default is a fraction of a screen that may have rotated since,
        // and a remembered drag is a pair of pixels from that older screen.
        snapIntoBounds(
            context,
            wm,
            dp(context, panelSizeDp.first.toFloat()),
            dp(context, panelSizeDp.second.toFloat()),
        )

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
            // A position the user dragged the panel to survives the target process
            // being killed. The fallback clamps it once the container is laid out,
            // so a saved value from a bigger screen cannot hide the panel.
            savedMargins = sizeStore?.margins(),
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
            snapIntoBounds(context, wm, px.first, px.second)
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
     * Pulls the window back so that a size change cannot hide its own grip.
     *
     * ## Why this exists
     * Growing the window from the resize grip extends it **right and down** from
     * its top-left corner. A panel docked near the bottom-right of the screen
     * therefore pushes its own bottom-right — where the grip lives — off the
     * display, and with `FLAG_LAYOUT_NO_LIMITS` the system will happily let it.
     * The result is a window the user cannot shrink back: the one failure mode the
     * size limits were supposed to make impossible.
     *
     * The rule is "fully on screen when it fits, otherwise keep [KEEP_ON_SCREEN_DP]
     * px visible", which is the same rule [moveBy] applies to a drag.
     */
    private fun snapIntoBounds(
        context: Context,
        wm: WindowManager,
        widthPx: Int,
        heightPx: Int,
    ) {
        val keep = dp(context, KEEP_ON_SCREEN_DP)
        val minX = keep - widthPx
        val minY = keep - heightPx
        val maxX = maxOf(minX, screenWidth(wm) - widthPx)
        val maxY = maxOf(minY, screenHeight(wm) - heightPx)
        offsetX = offsetX.coerceIn(minX, maxX)
        offsetY = offsetY.coerceIn(minY, maxY)
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

    /**
     * Adds or removes input focus from the overlay window.
     *
     * ## Why the window is normally not focusable
     * `FLAG_NOT_FOCUSABLE` is what stops the panel from stealing the keyboard, the
     * BACK key and the IME from the host app while the user is only reading it.
     *
     * ## Why it has to be lifted for text fields
     * The soft keyboard attaches to the *focused window*. A `FLAG_NOT_FOCUSABLE`
     * window cannot take input focus, so an `<input>` inside it draws a caret that
     * never produces a keyboard — the 内存 search box, the Agent chat box and the
     * tool console would all be decorative. That is why the page calls `imeFocus`
     * before a field is edited, and why [FloatingOverlayView.dispatchKeyEvent]
     * routes BACK back to `false`: the first BACK closes the keyboard, the next one
     * reaches the host app.
     *
     * The DecorView fallback needs none of this: it lives inside the Activity's own
     * window, which is focusable already, so `overlayView` is null there and this
     * method returns immediately.
     */
    private fun setImeFocus(focused: Boolean) {
        val wm = windowManager ?: return
        val lp = params ?: return
        val view = overlayView ?: return

        val notFocusable = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val updated = if (focused) lp.flags and notFocusable.inv() else lp.flags or notFocusable
        if (updated == lp.flags) return

        lp.flags = updated
        val applied = runCatching { wm.updateViewLayout(view, lp) }.isSuccess
        RegTrailLog.d("${param.packageName}: overlay ime focus=$focused applied=$applied")
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
            // Expanding grows the window down-right from the same origin: pull it
            // back on screen so the grip (and the 关闭 button) stay reachable.
            snapIntoBounds(view.context, wm, lp.width, lp.height)
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

    /**
     * The same tool dispatch, plus a cancellation flag.
     *
     * Only the in-window Agent can cancel: it runs inside a [InProcessJobs] job
     * whose cancel button must be able to stop a 64 MiB `mem_scan` mid-pass. The
     * settings-app path has no equivalent handle (its Binder call has its own
     * timeout), so [runTool] keeps passing a constant `false`.
     */
    private fun runTool(
        name: String,
        args: JsonObject,
        shouldStop: () -> Boolean,
    ): BridgeApi.Response = AgentTools.run(
        name = name,
        args = args,
        context = appContext,
        loader = param.defaultClassLoader,
        shouldStop = shouldStop,
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
        // Geometry the page needs to clamp a resize *before* it happens. Without
        // these the grip has to wait for a native round trip per frame to discover
        // it hit the wall, which is exactly what made the drag feel broken.
        screenMetrics().forEach { (key, element) -> put(key, element) }
    }

    /**
     * Screen bounds and resize limits, in the units the page works in (dp).
     *
     * Mirrors [OverlaySizeLimits] so the grip can clamp client-side and "hit a
     * wall" instead of fighting the native clamp. The native clamp stays
     * authoritative — a stale page can only be optimistic, never produce a window
     * the user cannot reach.
     */
    private fun screenMetrics(): JsonObject {
        val context = appContext ?: return buildJsonObject {
            put("density", JsonPrimitive(1))
        }
        val density = context.resources.displayMetrics.density.coerceAtLeast(0.1f)
        val widthPx = screenWidthPx(context)
        val heightPx = screenHeightPx(context)
        return buildJsonObject {
            put("screenWidthPx", JsonPrimitive(widthPx))
            put("screenHeightPx", JsonPrimitive(heightPx))
            put("screenWidthDp", JsonPrimitive((widthPx / density).toInt()))
            put("screenHeightDp", JsonPrimitive((heightPx / density).toInt()))
            put("density", JsonPrimitive(density))
            put("minWidthDp", JsonPrimitive(OverlaySizeLimits.MIN_WIDTH_DP))
            put("minHeightDp", JsonPrimitive(OverlaySizeLimits.MIN_HEIGHT_DP))
            put("maxWidthDp", JsonPrimitive(OverlaySizeLimits.maxWidthDp(widthPx, density)))
            put("maxHeightDp", JsonPrimitive(OverlaySizeLimits.maxHeightDp(heightPx, density)))
            put("screenFraction", JsonPrimitive(OverlaySizeLimits.MAX_SCREEN_FRACTION))
        }
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
            main.post {
                // A collapsed window has no text fields, and leaving it focusable
                // would keep the IME attached to a 112 dp pill.
                setImeFocus(false)
                applyCollapsed(true)
            }
            null
        }

        "expand" -> {
            main.post { applyCollapsed(false) }
            null
        }

        "hide" -> {
            main.post {
                setImeFocus(false)
                overlayView?.let { runCatching { windowManager?.removeView(it) } }
                // The DecorView mechanism has no window to remove: hiding means
                // detaching the view and not re-attaching on the next resume.
                decorFallback?.setHidden(true)
            }
            // Nothing may keep reading memory or sitting in a socket after the
            // user has dismissed the window: that work would be invisible, and the
            // Agent one would keep costing tokens.
            InProcessJobs.cancelAll()
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

        // Header drag of the *expanded* panel. Split into three messages because
        // the origin has to be captured once per gesture — see `webDragBegin`.
        "dragBegin" -> webDragBegin()
        "dragMove" -> webDragMove(payloadJson)

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

        // ---- 内存：直接读写本进程自己的内存，不需要 Root ------------------
        // The scan/refine run in the background and answer inline when they can
        // (see `InProcessJobs` for why the page cannot simply wait).
        "memScan" -> webMemScan(payloadJson)
        "memRefine" -> webMemRefine(payloadJson)
        "memWrite" -> webMemWrite(payloadJson)
        "memRevert" -> webMemRevert(payloadJson)
        "memSessions" -> webMemSessions()
        "memClear" -> webMemClear()

        // 「诊断」: a plain-text report of everything that decides whether the 内存
        // tab can work (maps, the region filter, /proc/self/mem read+write, a sample
        // page read through each transport). This is the cheapest possible answer to
        // "内存搜索不可用" on an unknown ROM, so it ships even when nothing is wrong.
        "memProbe" -> webMemProbe()
        "jobPoll" -> webJobPoll(payloadJson)
        "jobCancel" -> webJobCancel(payloadJson)

        // ---- Agent：在同一进程里驱动已有工具 + 模型 ReAct 循环 ------------
        "agentTool" -> webAgentTool(payloadJson)
        "agentChat" -> webAgentChat(payloadJson)
        "agentState" -> webAgentState()
        "agentReset" -> webAgentReset()

        // ---- 调试：选定一个方法，实时看它的参数与返回值 ------------------
        "debugSearch" -> webDebugSearch(payloadJson)
        "debugHook" -> webDebugHook(payloadJson)
        "debugCalls" -> webDebugCalls(payloadJson)
        "debugUnhook" -> webDebugUnhook(payloadJson)
        "debugHooks" -> webDebugHooks()
        "debugClear" -> webDebugClear(payloadJson)

        "status" -> webStatusJson()

        // The page's text fields ask for real input focus before the IME is needed.
        "imeFocus" -> {
            val focused = payloadBool(payloadJson, "focused") ?: false
            main.post { setImeFocus(focused) }
            null
        }

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
                // The screen may have rotated since the page booted; sending the
                // limits with every resize keeps the grip's client-side clamp
                // correct without an extra `status` round trip per drag.
                screenMetrics().forEach { (key, element) -> put(key, element) }
            },
        )
    }

    // -----------------------------------------------------------------------
    // Header drag of the expanded panel
    // -----------------------------------------------------------------------

    /**
     * Starts a drag of the expanded panel by its title bar.
     *
     * ## Why the origin is captured natively
     * The page reports the **total** finger travel since `pointerdown`, not a
     * per-frame increment. Native code therefore has to remember where the window
     * was when the gesture started; adding a total delta to a fixed origin keeps
     * every frame independent, so a coalesced (`requestAnimationFrame`), delayed or
     * duplicated message can only ever produce the same clamped position — never
     * drift.
     *
     * ## Why the bounds are returned
     * The page draws the panel at the finger *immediately* and lets native code
     * catch up (the same local-first strategy as the resize grip). Without the
     * bounds it would happily drag the panel past the screen edge and snap back on
     * release, so native code hands over the exact `[min,max]` travel in dp and the
     * page clamps its own transform to it.
     */
    private fun webDragBegin(): String {
        val collapsedNow = overlayView?.isCollapsed == true || decorFallback?.isCollapsed == true
        if (collapsedNow) return webError("折叠状态下请拖动胶囊本身")

        val decor = decorFallback
        if (decor != null && decor.isAttached) {
            decor.beginDrag()
            val bounds = decor.dragBoundsDp()
            return webOk(
                buildJsonObject {
                    put("mechanism", JsonPrimitive("DecorView"))
                    dragBounds(bounds).forEach { (key, element) -> put(key, element) }
                },
            )
        }

        val context = appContext ?: return webError("窗口尚未挂载")
        val wm = windowManager ?: return webError("窗口尚未挂载")
        val lp = params ?: return webError("窗口尚未挂载")
        val view = overlayView ?: return webError("窗口尚未挂载")
        if (view.parent == null) return webError("窗口尚未显示")

        val density = context.resources.displayMetrics.density.coerceAtLeast(0.1f)
        val keep = dp(context, KEEP_ON_SCREEN_DP)

        dragOriginOffsetX = offsetX
        dragOriginOffsetY = offsetY

        // The same rule `moveBy` applies to the collapsed pill: keep a grabbable
        // margin on screen in every direction.
        val deltaMinX = keep - lp.width - offsetX
        val deltaMaxX = maxOf(deltaMinX, screenWidth(wm) - keep - offsetX)
        val deltaMinY = -offsetY
        val deltaMaxY = maxOf(deltaMinY, screenHeight(wm) - keep - offsetY)

        return webOk(
            buildJsonObject {
                put("mechanism", JsonPrimitive("ApplicationOverlay"))
                dragBounds(
                    intArrayOf(
                        (deltaMinX / density).toInt(),
                        (deltaMaxX / density).toInt(),
                        (deltaMinY / density).toInt(),
                        (deltaMaxY / density).toInt(),
                    ),
                ).forEach { (key, element) -> put(key, element) }
            },
        )
    }

    /**
     * Applies one frame of a header drag: `{"dx":12,"dy":-3,"final":false}`.
     *
     * `dx`/`dy` are **dp** (CSS pixels inside the WebView are dp at scale 1), which
     * is the unit the page measures in. The reply carries the delta that was really
     * applied after clamping, so the page can compute the residue its local
     * transform still has to draw (`wanted - applied`).
     */
    private fun webDragMove(payloadJson: String): String {
        val dxDp = payloadInt(payloadJson, "dx") ?: 0
        val dyDp = payloadInt(payloadJson, "dy") ?: 0
        val isFinal = payloadBool(payloadJson, "final") ?: false

        val decor = decorFallback
        if (decor != null && decor.isAttached) {
            val applied = decor.dragToDp(dxDp, dyDp)
            if (isFinal && applied != 0 to 0) {
                val margins = decor.marginsPx()
                sizeStore?.rememberMargins(margins.first, margins.second)
            }
            return webOk(
                buildJsonObject {
                    put("dx", JsonPrimitive(applied.first))
                    put("dy", JsonPrimitive(applied.second))
                    put("final", JsonPrimitive(isFinal))
                },
            )
        }

        val context = appContext ?: return webError("窗口尚未挂载")
        val wm = windowManager ?: return webError("窗口尚未挂载")
        val lp = params ?: return webError("窗口尚未挂载")
        val view = overlayView ?: return webError("窗口尚未挂载")
        if (view.isCollapsed) return webError("折叠状态下不可拖动")

        val density = context.resources.displayMetrics.density.coerceAtLeast(0.1f)
        val keep = dp(context, KEEP_ON_SCREEN_DP)
        val minX = keep - lp.width
        val maxX = maxOf(minX, screenWidth(wm) - keep)
        val minY = 0
        val maxY = maxOf(0, screenHeight(wm) - keep)

        val targetX = (dragOriginOffsetX + (dxDp * density).toInt()).coerceIn(minX, maxX)
        val targetY = (dragOriginOffsetY + (dyDp * density).toInt()).coerceIn(minY, maxY)
        val appliedDx = ((targetX - dragOriginOffsetX) / density).toInt()
        val appliedDy = ((targetY - dragOriginOffsetY) / density).toInt()

        // The layout write must happen on the view's own thread; the reply does not
        // wait for it because the clamp above already decided the final position.
        main.post {
            offsetX = targetX
            offsetY = targetY
            lp.x = offsetX
            lp.y = offsetY
            runCatching { wm.updateViewLayout(view, lp) }
        }

        // Persist once per gesture, never per frame: `final` is what the page sends
        // on pointer-up (the same contract the resize grip uses).
        if (isFinal) sizeStore?.rememberPosition(targetX, targetY)

        return webOk(
            buildJsonObject {
                put("dx", JsonPrimitive(appliedDx))
                put("dy", JsonPrimitive(appliedDy))
                put("final", JsonPrimitive(isFinal))
            },
        )
    }

    /** `[minDx, maxDx, minDy, maxDy]` → the four named fields the page reads. */
    private fun dragBounds(bounds: IntArray): Map<String, JsonPrimitive> = mapOf(
        "minDx" to JsonPrimitive(bounds[0]),
        "maxDx" to JsonPrimitive(bounds[1]),
        "minDy" to JsonPrimitive(bounds[2]),
        "maxDy" to JsonPrimitive(bounds[3]),
    )

    /** The last [LOG_TAIL] log lines, as the 日志 tab's data source. */
    private fun webLogs(): String {        val lines = RegTrailLog.recent(LOG_TAIL)
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

    /**
     * Reads a nested object field out of a bridge payload.
     *
     * `agentTool` takes `{"name":"…","args":{…}}` and the args object has to reach
     * `AgentTools.run` unchanged — a model asks for `{"limit":200}` and the tool
     * reads a number, not a string. Parsing it here keeps every action's payload
     * handling symmetric.
     */
    private fun payloadObject(payloadJson: String, key: String): JsonObject? = runCatching {
        BridgeApi.json.parseToJsonElement(payloadJson).jsonObject[key] as? JsonObject
    }.getOrNull()

    // -----------------------------------------------------------------------
    // 内存 tab (in-process, no root)
    // -----------------------------------------------------------------------

    /**
     * First scan: `{"type":"int32","value":"100","writableOnly":false}`.
     *
     * The reply is the completed result when the scan fits into
     * [InProcessJobs.defaultWaitMs], otherwise `{"pending":true,"job":N}` and the
     * page polls [webJobPoll]. Both shapes carry the same fields, so the page has
     * exactly one renderer.
     */
    private fun webMemScan(payloadJson: String): String {
        val type = payloadString(payloadJson, "type") ?: "int32"
        val value = payloadString(payloadJson, "value")
            ?: return webError("请先输入要搜索的值")
        val writableOnly = payloadBool(payloadJson, "writableOnly") ?: false
        val maxResults = payloadInt(payloadJson, "maxResults")
            ?: MemorySessions.DEFAULT_MAX_RESULTS

        val job = InProcessJobs.submit("memScan", InProcessJobs.Lane.MEMORY) { handle ->
            MemorySessions.scan(
                typeRaw = type,
                value = value,
                writableOnly = writableOnly,
                maxResults = maxResults,
                shouldStop = { handle.cancelled },
            )
        }
        return webJobReply(job, InProcessJobs.defaultWaitMs)
    }

    /** Next scan: `{"session":3,"value":"101"}`. */
    private fun webMemRefine(payloadJson: String): String {
        val session = payloadInt(payloadJson, "session")
            ?: return webError("请先做一次首次扫描")
        val value = payloadString(payloadJson, "value")
            ?: return webError("请输入数值变化后的新值")
        val type = payloadString(payloadJson, "type")
        val maxResults = payloadInt(payloadJson, "maxResults")
            ?: MemorySessions.DEFAULT_MAX_RESULTS

        val job = InProcessJobs.submit("memRefine", InProcessJobs.Lane.MEMORY) { handle ->
            MemorySessions.refine(
                sessionId = session,
                value = value,
                typeRaw = type,
                maxResults = maxResults,
                shouldStop = { handle.cancelled },
            )
        }
        return webJobReply(job, InProcessJobs.defaultWaitMs)
    }

    /**
     * Writes one value: `{"addr":"0x7f…","type":"int32","value":"999"}`.
     *
     * Deliberately synchronous: it is a handful of syscalls, and the page needs the
     * result (including the original bytes) before it can offer 「还原」.
     *
     * The UI is responsible for the confirmation step — see the 内存 tab's
     * two-stage 「写入」/「确认写入」 button. Native code cannot enforce that, and
     * it must not pretend to: the requirement is that the *user* confirms, not
     * that the bridge refuses.
     */
    private fun webMemWrite(payloadJson: String): String = runCatching {
        MemorySessions.write(
            addressRaw = payloadString(payloadJson, "addr"),
            typeRaw = payloadString(payloadJson, "type"),
            value = payloadString(payloadJson, "value"),
        )
    }.fold(
        onSuccess = { webOk(it) },
        onFailure = { webError(it.message ?: "写入失败") },
    )

    /** Restores one address, or every modified one when `addr` is absent. */
    private fun webMemRevert(payloadJson: String): String = runCatching {
        MemorySessions.revert(payloadString(payloadJson, "addr"))
    }.fold(
        onSuccess = { webOk(it) },
        onFailure = { webError(it.message ?: "还原失败") },
    )

    /** Sessions + modified addresses, for the 内存 tab's 「已修改」 section. */
    private fun webMemSessions(): String = runCatching {
        webOk(MemorySessions.describe())
    }.getOrElse { webError(it.message ?: "读取内存状态失败") }

    /**
     * Runs the memory diagnostics: `{"action":"memProbe"}`.
     *
     * Deliberately synchronous. It opens two file descriptors, parses `/proc/self/maps`
     * once and reads one 4 KiB page — milliseconds, not the seconds a scan takes — and
     * the page shows the text immediately instead of polling for it.
     */
    private fun webMemProbe(): String = runCatching {
        webOk(MemorySessions.probe())
    }.getOrElse {
        RegTrailLog.w("memProbe failed", it)
        webError(it.message ?: "内存诊断失败")
    }

    /** Drops every session and forgets the remembered originals. */
    private fun webMemClear(): String = runCatching {
        webOk(MemorySessions.clear())
    }.getOrElse { webError(it.message ?: "清空失败") }

    /**
     * Polls a job started by `memScan` / `memRefine` / `agentChat`.
     *
     * Answers `{"ok":true,"data":{…,"pending":false}}` when it finished,
     * `{"ok":true,"data":{"pending":true,"progress":…}}` while it runs, and the
     * normal error envelope when it failed.
     */
    private fun webJobPoll(payloadJson: String): String {
        val id = payloadInt(payloadJson, "job") ?: return webError("jobPoll 需要 job 编号")
        val job = InProcessJobs.find(id)
            ?: return webError("任务 $id 不存在（可能已经结束并被回收）")
        return webJobReply(job, 0L)
    }

    /** Best-effort cancellation: the reply says whether the job was still running. */
    private fun webJobCancel(payloadJson: String): String {
        val id = payloadInt(payloadJson, "job") ?: return webError("jobCancel 需要 job 编号")
        val cancelled = InProcessJobs.cancel(id)
        return webOk(buildJsonObject {
            put("job", JsonPrimitive(id))
            put("cancelled", JsonPrimitive(cancelled))
        })
    }

    /**
     * The one place a job becomes a bridge reply.
     *
     * @param waitMs how long to block *this* call before answering "still running".
     *   0 for actions that are always asynchronous (a model turn), the usual
     *   [InProcessJobs.defaultWaitMs] for ones that are usually fast.
     */
    private fun webJobReply(job: InProcessJobs.Handle, waitMs: Long): String {
        if (waitMs > 0L) InProcessJobs.await(job, waitMs)

        job.error?.let { message -> return webError(message) }

        val data = job.data
        return webOk(
            buildJsonObject {
                // Flatten the finished payload into the reply, so a caller sees
                // `{"session":1,"count":N,…}` exactly as the protocol documents.
                data?.forEach { (key, element) -> put(key, element) }
                put("pending", JsonPrimitive(data == null))
                put("job", JsonPrimitive(job.id))
                put("kind", JsonPrimitive(job.kind))
                put("progress", JsonPrimitive(job.progress))
                put("elapsedMs", JsonPrimitive(job.elapsedMs))
            },
        )
    }

    // -----------------------------------------------------------------------
    // Agent tab (runs in this process)
    // -----------------------------------------------------------------------

    /**
     * Configuration state for the Agent tab.
     *
     * Answers "why can I not chat" before the user types anything: no key, no
     * INTERNET permission, or simply not published yet. **Never contains the key**;
     * `hasKey` is a boolean and `baseUrl`/`model` are not secrets.
     */
    private fun webAgentState(): String {
        val snapshot = config.snapshot()
        return webOk(
            buildJsonObject {
                put("configured", JsonPrimitive(snapshot.agentConfigured))
                put("hasKey", JsonPrimitive(snapshot.agentApiKey.isNotBlank()))
                put("baseUrl", JsonPrimitive(snapshot.agentBaseUrl))
                put("model", JsonPrimitive(snapshot.agentModel))
                put("internet", JsonPrimitive(hasInternetPermission()))
                put("messages", JsonPrimitive(InProcessAgent.pending()))
                put("memSession", JsonPrimitive(MemorySessions.current() ?: 0))
                put(
                    "maxRounds",
                    JsonPrimitive(
                        if (snapshot.agentMaxRounds > 0) {
                            snapshot.agentMaxRounds
                        } else {
                            DEFAULT_AGENT_ROUNDS
                        },
                    ),
                )
                put("tools", buildJsonArray {
                    InProcessAgent.toolNames().forEach { add(JsonPrimitive(it)) }
                })
            },
        )
    }

    /**
     * Runs one tool directly: `{"name":"list_activities","args":{…}}`.
     *
     * This is the 「工具控制台」 and also the same entry point the Agent loop uses,
     * so what the console shows is exactly what the model would have received.
     */
    private fun webAgentTool(payloadJson: String): String {
        val name = payloadString(payloadJson, "name") ?: return webError("缺少工具名")
        val args = payloadObject(payloadJson, "args") ?: JsonObject(emptyMap())
        RegTrailLog.d("ui: agentTool $name")

        val response = runToolOnMain(name, args)
        return webOk(
            buildJsonObject {
                put("name", JsonPrimitive(name))
                put("toolOk", JsonPrimitive(response.ok))
                put("text", JsonPrimitive(InProcessAgent.renderToolResult(response)))
                put("json", response.data)
            },
        )
    }

    /**
     * One Agent turn: `{"message":"…","allowDestructive":false}`.
     *
     * Always asynchronous. A model turn is a network round trip and several tool
     * executions; blocking the WebView's bridge thread for that would freeze the
     * panel — the exact failure mode this whole job mechanism exists to avoid.
     */
    private fun webAgentChat(payloadJson: String): String {
        val message = payloadString(payloadJson, "message")
            ?: return webError("请输入要问 Agent 的内容")
        val allowDestructive = payloadBool(payloadJson, "allowDestructive") ?: false

        val snapshot = config.snapshot()
        if (!snapshot.agentConfigured) {
            return webError(
                "Agent 未配置：请在 RegTrail 设置端填写 Base URL、模型与 API Key，" +
                    "然后在窗口里点「重载配置」",
            )
        }
        // The target app owns the permission, not RegTrail: a host without
        // INTERNET can never reach any endpoint, and saying so beats a socket
        // timeout the user would read as a hang.
        if (!hasInternetPermission()) {
            return webError(
                "目标应用没有 INTERNET 权限，无法在本进程内调用模型接口。" +
                    "可以改用设置端的 Agent，或给该应用放行网络。",
            )
        }

        val agentConfig = AgentConfig(
            baseUrl = snapshot.agentBaseUrl,
            apiKey = snapshot.agentApiKey,
            model = snapshot.agentModel,
            maxToolRounds = if (snapshot.agentMaxRounds > 0) {
                snapshot.agentMaxRounds
            } else {
                DEFAULT_AGENT_ROUNDS
            },
        )

        val job = InProcessJobs.submit("agentChat", InProcessJobs.Lane.AGENT) { handle ->
            val outcome = InProcessAgent.chat(
                config = agentConfig,
                prompt = message,
                allowDestructive = allowDestructive,
                // The loop's tools are this process's tools: same dispatcher the
                // console and the settings-side bridge use (`AgentTools.run`), so
                // there is exactly one implementation of every tool. The cancel
                // flag is threaded through, so 「停止」 stops a running mem_scan.
                execute = { name, args -> runToolOnMain(name, args) { handle.cancelled } },
                shouldStop = { handle.cancelled },
                onStep = { step -> handle.report(step) },
            )
            buildJsonObject {
                put("reply", JsonPrimitive(outcome.reply))
                put("steps", buildJsonArray {
                    outcome.steps.forEach { add(JsonPrimitive(it)) }
                })
                put("allowDestructive", JsonPrimitive(allowDestructive))
                put("messages", JsonPrimitive(InProcessAgent.pending()))
            }
        }
        RegTrailLog.i("ui: agentChat (destructive=$allowDestructive)")
        return webJobReply(job, 0L)
    }

    /** Forgets the conversation so the next turn starts fresh. */
    private fun webAgentReset(): String {
        InProcessAgent.reset()
        return webOk(buildJsonObject {
            put("reset", JsonPrimitive(true))
            put("messages", JsonPrimitive(InProcessAgent.pending()))
        })
    }

    /**
     * Runs a tool, hopping to the main thread for the one tool that needs it.
     *
     * `dump_view_tree` walks the live `View` hierarchy, and `View` is not
     * thread-safe: reading `width`/`childCount` off the main thread can throw or,
     * worse, return a torn tree. Every other tool is pure reflection and is happy
     * on the bridge/JO thread.
     *
     * The wait is bounded: if the host app's main thread is wedged, a tool that
     * "never returns" would freeze the panel with no explanation. A timeout
     * converts that into an error message, and the queued runnable is harmless.
     */
    private fun runToolOnMain(
        name: String,
        args: JsonObject,
        shouldStop: () -> Boolean = { false },
    ): BridgeApi.Response {
        if (name != "dump_view_tree") return runTool(name, args, shouldStop)
        if (Looper.myLooper() == Looper.getMainLooper()) return runTool(name, args, shouldStop)

        val latch = CountDownLatch(1)
        val result = AtomicReference<BridgeApi.Response?>(null)
        val posted = main.post {
            result.set(
                runCatching { runTool(name, args, shouldStop) }.getOrElse {
                    BridgeApi.Response(
                        ok = false,
                        error = it.message ?: "工具执行失败",
                        data = JsonNull,
                    )
                },
            )
            latch.countDown()
        }
        if (!posted) return BridgeApi.Response(false, "主线程不可用", JsonNull)
        if (!latch.await(TOOL_MAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return BridgeApi.Response(false, "主线程繁忙，未能执行 $name", JsonNull)
        }
        return result.get() ?: BridgeApi.Response(false, "工具没有返回结果", JsonNull)
    }

    /**
     * True when **this** process holds `android.permission.INTERNET`.
     *
     * `Context.checkPermission(…, myPid, myUid)` asks about the calling process,
     * which inside the injected window is the target app — the app whose socket
     * the request would be made from. A false answer here is the difference between
     * "the feature is broken" and "this app has no network permission".
     */
    private fun hasInternetPermission(): Boolean {
        val context = appContext ?: return false
        return runCatching {
            context.checkPermission(
                android.Manifest.permission.INTERNET,
                android.os.Process.myPid(),
                android.os.Process.myUid(),
            ) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    // -----------------------------------------------------------------------
    // 调试 tab — watch one method's arguments and return value live
    // -----------------------------------------------------------------------

    /**
     * `{"query":"Login"}` → the methods it matches.
     *
     * Runs on the bridge thread: it only touches already-loaded classes through the
     * cached class list, and `DebugEngine.search` bounds the expensive part in time.
     */
    private fun webDebugSearch(payloadJson: String): String {
        val query = payloadString(payloadJson, "query") ?: return webError("请输入类名或方法名")
        val limit = (payloadInt(payloadJson, "limit") ?: DebugEngine.SEARCH_LIMIT)
            .coerceIn(1, DebugEngine.SEARCH_LIMIT)

        val startedAt = System.nanoTime()
        val result = runCatching {
            DebugEngine.search(param.defaultClassLoader, query, limit)
        }.getOrElse {
            RegTrailLog.w("ui: debugSearch failed", it)
            return webError(it.message ?: "搜索失败")
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        RegTrailLog.d(
            "ui: debugSearch '$query' → ${result.methods.size} 个方法 " +
                "(${result.classesScanned} 个类, ${elapsedMs}ms, truncated=${result.truncated})",
        )

        return webOk(
            buildJsonObject {
                put("query", JsonPrimitive(query))
                put("count", JsonPrimitive(result.methods.size))
                put("classesScanned", JsonPrimitive(result.classesScanned))
                put("truncated", JsonPrimitive(result.truncated))
                put("elapsedMs", JsonPrimitive(elapsedMs))
                put(
                    "methods",
                    buildJsonArray {
                        result.methods.forEach { ref ->
                            add(
                                buildJsonObject {
                                    put("className", JsonPrimitive(ref.className))
                                    put("methodName", JsonPrimitive(ref.methodName))
                                    put("returnType", JsonPrimitive(ref.returnType))
                                    put("isStatic", JsonPrimitive(ref.isStatic))
                                    put("signature", JsonPrimitive(ref.signature))
                                    put(
                                        "params",
                                        buildJsonArray {
                                            ref.paramTypes.forEach { add(JsonPrimitive(it)) }
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    /**
     * `{"className":"…","methodName":"…","params":["int"],"mode":"trace"}`.
     *
     * Modes:
     *  - `trace` (default) — record the call: arguments and return value;
     *  - `return` — record *and* force the returned value;
     *  - `args` — record *and* replace one argument (`value` is `index=value`).
     *
     * The hook is installed through [DebugEngine.installHook], which uses
     * `ExceptionMode.PROTECTIVE`: a bug in this debugger must never take the host app
     * down. The recorded id is a plain number so the page can send it back as
     * `hookId`.
     */
    private fun webDebugHook(payloadJson: String): String {
        val className = payloadString(payloadJson, "className")
            ?: return webError("缺少 className")
        val methodName = payloadString(payloadJson, "methodName")
            ?: return webError("缺少 methodName")
        val paramTypes = payloadStringArray(payloadJson, "params")
        val mode = (payloadString(payloadJson, "mode") ?: "trace").lowercase()
        val value = payloadString(payloadJson, "value")
        val every = (payloadInt(payloadJson, "every") ?: 1).coerceAtLeast(1).toLong()

        val target = DebugEngine.resolveMethod(
            loader = param.defaultClassLoader,
            className = className,
            methodName = methodName,
            paramTypes = paramTypes,
        )
        if (target == null) {
            val shown = paramTypes.joinToString(",")
            return webError("找不到方法：$className#$methodName($shown)")
        }

        val behavior = when (mode) {
            "return" -> DebugHookBehavior.forceReturn(
                value = value,
                valueType = payloadString(payloadJson, "valueType") ?: "auto",
                trace = true,
            ).copy(record = true, recordEvery = every)

            "args" -> {
                val (index, raw) = parseArgOverride(value)
                val types = target.parameterTypes
                if (index !in types.indices) {
                    return webError("参数下标 $index 超出范围（共 ${types.size} 个参数）")
                }
                val coerced = AgentTools.coerce(raw, types[index], "auto")
                    ?: return webError("无法把「$raw」转换为 ${types[index].name}")
                DebugHookBehavior.replaceArg(index = index, value = coerced, trace = true)
                    .copy(record = true, recordEvery = every)
            }

            else -> DebugHookBehavior.record(every = every)
        }

        // Numeric ids: the documented protocol is `{"hookId":N}`.
        val hookNumber = debugHookSeq.incrementAndGet()
        val id = DebugEngine.installHook(
            target = target,
            behavior = behavior.copy(id = hookNumber.toString()),
            label = DebugEngine.describe(target),
        ).getOrElse { return webError("安装 Hook 失败：${it.message}") }

        RegTrailLog.i("ui: debugHook $id → ${DebugEngine.describe(target)} mode=$mode")
        return webOk(
            buildJsonObject {
                put("hookId", JsonPrimitive(hookNumber))
                put("id", JsonPrimitive(id))
                put("target", JsonPrimitive(DebugEngine.describe(target)))
                put("mode", JsonPrimitive(mode))
                put("recording", JsonPrimitive(true))
            },
        )
    }

    /**
     * `{"hookId":1,"since":123}` → the recorded calls with `seq > since`.
     *
     * The page polls this only for the hooks it is watching and only while the 调试
     * tab is visible, and it sends the `next` cursor it got back, so an idle panel
     * costs one empty reply per tick.
     */
    private fun webDebugCalls(payloadJson: String): String {
        val hookId = payloadInt(payloadJson, "hookId")
            ?: return webError("debugCalls 需要 hookId")
        val since = (payloadInt(payloadJson, "since") ?: 0).toLong()
        val limit = (payloadInt(payloadJson, "limit") ?: DebugEngine.MAX_CALLS_PER_HOOK)
            .coerceIn(1, DebugEngine.MAX_CALLS_PER_HOOK)

        val page = DebugEngine.calls(hookId.toString(), since, limit)
            ?: return webError("Hook $hookId 不存在（可能已解除）")

        return webOk(
            buildJsonObject {
                put("hookId", JsonPrimitive(hookId))
                put("next", JsonPrimitive(page.next))
                put("total", JsonPrimitive(page.total))
                put("dropped", JsonPrimitive(page.dropped))
                put("calls", JsonPrimitive(page.hookCalls))
                put(
                    "entries",
                    buildJsonArray {
                        page.entries.forEach { entry ->
                            add(
                                buildJsonObject {
                                    put("seq", JsonPrimitive(entry.seq))
                                    put("at", JsonPrimitive(entry.atMillis))
                                    put("thread", JsonPrimitive(entry.thread))
                                    put("ms", JsonPrimitive(entry.durationMs))
                                    put(
                                        "args",
                                        buildJsonArray {
                                            entry.args.forEach { add(JsonPrimitive(it)) }
                                        },
                                    )
                                    put("result", BridgeApi.toJsonElement(entry.result))
                                    put("threw", BridgeApi.toJsonElement(entry.threw))
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    /** `{"hookId":1}` → removes the hook. Always reachable from the page. */
    private fun webDebugUnhook(payloadJson: String): String {
        val hookId = payloadInt(payloadJson, "hookId")
            ?: return webError("debugUnhook 需要 hookId")
        val removed = DebugEngine.unhook(hookId.toString())
        if (!removed) return webError("Hook $hookId 不存在（可能已解除）")
        RegTrailLog.i("ui: debugUnhook $hookId")
        return webOk(buildJsonObject { put("hookId", JsonPrimitive(hookId)) })
    }

    /** Every active hook with its call count: the 调试 tab's header list. */
    private fun webDebugHooks(): String {
        val hooks = DebugEngine.snapshot()
        return webOk(
            buildJsonObject {
                put("count", JsonPrimitive(hooks.size))
                put(
                    "hooks",
                    buildJsonArray {
                        hooks.forEach { info ->
                            add(
                                buildJsonObject {
                                    // Numeric when the 调试 tab installed it, 0 for a
                                    // hook that came from the Agent (`hook_method`) —
                                    // `id` is always there for display.
                                    put("hookId", JsonPrimitive(info.id.toIntOrNull() ?: 0))
                                    put("id", JsonPrimitive(info.id))
                                    put("target", JsonPrimitive(info.target))
                                    put("label", JsonPrimitive(info.label))
                                    put("calls", JsonPrimitive(info.calls))
                                    put("recorded", JsonPrimitive(info.recorded))
                                    put("recording", JsonPrimitive(info.recording))
                                    put("trace", JsonPrimitive(info.trace))
                                    put(
                                        "returnOverride",
                                        BridgeApi.toJsonElement(info.returnOverride),
                                    )
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    /** `{"hookId":1}` → forgets the recorded calls, keeps the hook installed. */
    private fun webDebugClear(payloadJson: String): String {
        val hookId = payloadInt(payloadJson, "hookId") ?: return webError("debugClear 需要 hookId")
        val cleared = DebugEngine.clearCalls(hookId.toString())
        if (!cleared) return webError("Hook $hookId 不存在（可能已解除）")
        return webOk(buildJsonObject { put("hookId", JsonPrimitive(hookId)) })
    }

    /** `index=value` (or `index:value`) → the argument override a `args` hook means. */
    private fun parseArgOverride(raw: String?): Pair<Int, String> {
        val text = raw.orEmpty()
        val sep = text.indexOfFirst { it == '=' || it == ':' }
        if (sep <= 0) return 0 to text
        val index = text.substring(0, sep).trim().toIntOrNull() ?: return 0 to text
        return index to text.substring(sep + 1)
    }

    /** Reads a string-array field out of a bridge payload. */
    private fun payloadStringArray(payloadJson: String, key: String): List<String> = runCatching {
        val element = BridgeApi.json.parseToJsonElement(payloadJson).jsonObject[key]
        (element as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()
    }.getOrDefault(emptyList())

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

        /**
         * Tool rounds one Agent turn may use when the config publishes no limit.
         *
         * Six is enough for the common "find the class → read the field → hook it"
         * chain and short enough that a confused model cannot spend a large budget
         * before the user sees anything.
         */
        const val DEFAULT_AGENT_ROUNDS = 6

        /**
         * How long a tool may take when it must run on the main thread.
         *
         * Only `dump_view_tree` needs this. Three seconds is generous for walking a
         * `View` tree and short enough that a wedged host main thread produces an
         * error instead of a frozen panel.
         */
        const val TOOL_MAIN_TIMEOUT_MS = 3_000L
    }
}
