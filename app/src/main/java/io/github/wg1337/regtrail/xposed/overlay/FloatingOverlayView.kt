package io.github.wg1337.regtrail.xposed.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.wg1337.regtrail.xposed.RegTrailLog
import java.io.ByteArrayInputStream
import kotlin.math.abs

/**
 * The injected floating window: a WebView plus a collapsed "pill" state.
 *
 * ## Why a WebView and not Compose
 * The window lives inside the *target* app's process. Host apps frequently use
 * up the 64K method-reference limit per dex (measured on a real target:
 * `method_ids_size = 65536`), so injecting a UI toolkit is not an option. The
 * whole native payload here is a few KB of Java; all the UI is HTML/CSS/JS
 * shipped in our own APK and served to the WebView by [AssetInterceptingClient]
 * — no network, no external files.
 *
 * ## Layout
 * The view is a `FrameLayout` containing:
 *  - the expanded panel (WebView), draggable by its header, resizable by a grip
 *  - the collapsed indicator (a small pill), draggable anywhere
 *
 * The host-visible behaviour (window type, permission bypass, persistence
 * across Activities) is [OverlayController]'s job, not this class's.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class FloatingOverlayView(
    context: Context,
    private val assets: ModuleAssets,
    private val bridge: JsBridge,
    private val onCollapsedChanged: (Boolean) -> Unit,
) : FrameLayout(context) {

    private val webView: WebView
    private val collapsedPill: LinearLayout

    /** Set by the controller so the drag handler can update window gravity. */
    var onDrag: ((dx: Int, dy: Int) -> Unit)? = null

    /**
     * Called once when a pill drag ends.
     *
     * The per-frame [onDrag] callback must not write a preference file, and the
     * drag can happen while the panel is attached to an Activity that is about to
     * be torn down — so the *end* of the gesture is the only correct moment to
     * persist where the pill was left. Without this the collapsed position was the
     * one thing that did not survive a re-attach.
     */
    var onDragEnd: (() -> Unit)? = null

    /**
     * Called when BACK is pressed while this window has input focus.
     *
     * Only the real overlay window sets it: there, a focusable window intercepts
     * BACK, and the first press must close the keyboard / drop focus instead of
     * leaving the user unable to navigate the host app. The DecorView fallback
     * leaves it null, because there BACK belongs to the Activity.
     */
    var onBack: (() -> Unit)? = null

    private var collapsed = false

    private var downRawX = 0f
    private var downRawY = 0f

    /** Total finger travel since ACTION_DOWN, to tell a tap from a drag. */
    private var dragDistance = 0f

    /** Distance beyond which a gesture is a drag and no longer a tap. */
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        setBackgroundColor(Color.TRANSPARENT)

        /*
         * The *whole* collapsed window expands on tap, not just the pill graphic.
         *
         * The window is sized [COLLAPSED_WIDTH_DP] x [COLLAPSED_HEIGHT_DP] while
         * the pill itself is `wrap_content`, so several dp of transparent
         * container surround the pill. A user aiming at "the floating thing"
         * frequently lands on that transparent margin; without this listener those
         * taps went nowhere and the window read as un-expandable.
         *
         * `isClickable` is toggled together with the collapsed state in
         * [setCollapsed]: while expanded this view must not consume anything, or
         * it would steal taps the WebView never handles (gutter around a rounded
         * panel corner).
         */
        setOnClickListener { expand() }

        webView = WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                settings.forceDark = WebSettings.FORCE_DARK_AUTO
            }
            webViewClient = AssetInterceptingClient(assets)
            addJavascriptInterface(bridge, JsBridge.NAME)
            loadUrl(BASE_URL + ASSET_DIR + PAGE)
        }

        addView(
            webView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        collapsedPill = buildPill(context)
        addView(
            collapsedPill,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            },
        )

        // `setOnClickListener` made this view clickable; undo that until the window
        // actually collapses (see [setCollapsed] for the reasoning).
        isClickable = false
    }

    /**
     * The collapsed state: the RegTrail dot plus a short label in one pill.
     *
     * ## Why a pill and not just a dot
     * A bare dot is indistinguishable from a notification badge or a foreign
     * app's floating button, so the user cannot tell which thing to tap to get
     * the panel back. The label is the recognition anchor; the dot keeps the
     * window's accent colour visible so the pill still reads as "RegTrail" at a
     * glance once the user knows what to look for.
     *
     * The pill is `wrap_content` inside a container the controller sizes to
     * [COLLAPSED_WIDTH_DP] x [COLLAPSED_HEIGHT_DP], centred — so the *touchable*
     * window is a predictable rectangle while the visible part stays compact.
     */
    private fun buildPill(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(9f), dp(6f), dp(11f), dp(6f))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(PILL_CORNER_DP).toFloat()
            setColor(PILL_BG)
            setStroke(dp(1f), PILL_STROKE)
        }

        addView(
            View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ACCENT)
                }
            },
            LinearLayout.LayoutParams(dp(7f), dp(7f)),
        )

        addView(
            TextView(context).apply {
                text = "RegTrail"
                setTextColor(ACCENT)
                // Pixels of the *display* density, not `sp`: `sp` follows the host
                // Activity's `scaledDensity`, which is exactly the per-Activity value
                // the panel must not follow (see [dp]).
                setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_PX,
                    11f * PanelLayoutMath.density(context),
                )
                maxLines = 1
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(6f) },
        )

        visibility = GONE
        // Drag anywhere on the pill, tap to expand. The listener *never* yields a
        // touch back to `View.onTouchEvent` — see [pillTouchListener] for why
        // returning false on ACTION_UP did not make the click fire.
        setOnTouchListener(pillTouchListener())
        setOnClickListener { expand() }
    }

    /**
     * Expands the panel and tells the controller, exactly once.
     *
     * ## Why this is not inlined in the click listeners
     * Two listeners can call it (the pill and the container). Both must be
     * idempotent: a double tap, or a tap that lands while the previous expand is
     * still being applied on the main thread, must not run the controller's
     * transition twice — the second run would re-apply the size and fight an
     * in-flight layout pass.
     */
    private fun expand() {
        if (!collapsed) return
        setCollapsed(false)
        onCollapsedChanged(false)
    }

    /**
     * Switches between the panel and the pill.
     *
     * ## What this must not do
     * It must not call `loadUrl` / `reload`. The WebView carries the whole UI
     * state — the selected tab, the scrolled log, the script list fetched from
     * the bridge, the memory result list — and collapsing is a *window size*
     * change, not a navigation. Hiding the view keeps that state; reloading would
     * throw it away and make expand feel like a fresh start.
     */
    fun setCollapsed(value: Boolean) {
        collapsed = value
        webView.visibility = if (value) GONE else VISIBLE
        collapsedPill.visibility = if (value) VISIBLE else GONE
        /*
         * Only the pill may absorb a tap-to-open.
         *
         * While expanded the container must stay non-clickable: the WebView is
         * `MATCH_PARENT` and normally consumes every touch inside the window, but
         * a tap that the web engine declines (the transparent margin outside the
         * rounded panel corner) would otherwise be swallowed here — and a
         * swallowed touch in a floating window is indistinguishable from a frozen
         * host app.
         */
        isClickable = value
        evaluateJavascript(
            "window.RegTrail && window.RegTrail.onCollapsed($value);",
        )
    }

    val isCollapsed: Boolean get() = collapsed

    /** Pushes a configuration snapshot into the web UI. */
    fun applyConfig(configJson: String) {
        evaluateJavascript("window.RegTrail && window.RegTrail.onConfig($configJson);")
    }

    /** Appends a log line to the web UI's log panel. */
    fun appendLog(line: String) {
        val escaped = line
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
        evaluateJavascript("window.RegTrail && window.RegTrail.onLog('$escaped');")
    }

    fun evaluateJavascript(script: String) {
        runCatching { webView.evaluateJavascript(script, null) }
    }

    /**
     * Swallows BACK while this window is the focus target.
     *
     * The window is normally `FLAG_NOT_FOCUSABLE`, so it never sees a key event at
     * all. It becomes focusable only while a text field is being edited (see
     * `OverlayController.setImeFocus`), and in that state BACK would otherwise be
     * consumed by the WebView and do nothing — the host app would appear to have
     * lost its back button. Reporting the press to [onBack] lets the controller
     * drop focus, so the *next* BACK reaches the host app again.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val handler = onBack ?: return super.dispatchKeyEvent(event)
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.action == android.view.KeyEvent.ACTION_UP) handler()
            // Consume both DOWN and UP: one physical press, one action.
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    fun destroy() {
        runCatching {
            removeView(webView)
            webView.destroy()
        }
    }

    /**
     * Drag anywhere on the pill, *tap* to expand.
     *
     * ## The bug this replaces (regression: "collapse leaves a pill you cannot tap")
     * The previous listener returned `true` for `ACTION_DOWN` (it had to — that is
     * what keeps the subsequent `ACTION_MOVE` stream coming) and `false` for
     * `ACTION_UP` when the gesture had not travelled past the touch slop, in the
     * belief that "returning false on UP lets the click fire".
     *
     * That belief is wrong, and it is the whole root cause. `View.dispatchTouchEvent`
     * only reaches `View.onTouchEvent` when the touch listener returns `false`, and
     * `onTouchEvent` is the only place that sets `PFLAG_PRESSED` on
     * `ACTION_DOWN`. Its `ACTION_UP` branch dispatches the click **inside**
     *
     * ```java
     * if ((mPrivateFlags & PFLAG_PRESSED) != 0 || prepressed) { … performClick() … }
     * ```
     *
     * so an `ACTION_UP` delivered to a view that never saw the `ACTION_DOWN`
     * performs no click at all — the handler installed with `setOnClickListener`
     * was unreachable. Collapse worked (the header's `-` button lives in the
     * WebView and posts through the bridge), and expand never did.
     *
     * The fix: consume every event and turn the tap into an explicit
     * `performClick()` from here, which is also the accessibility-correct path
     * (it announces the click to a screen reader and keeps `OnClickListener`
     * the single definition of "what a tap does").
     */
    private fun pillTouchListener() = object : OnTouchListener {
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragDistance = 0f
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragDistance += abs(dx) + abs(dy)
                    if (dx != 0f || dy != 0f) onDrag?.invoke(dx.toInt(), dy.toInt())
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    // Distinguish a tap (expand) from the end of a drag: expanding
                    // the panel every time the user repositions the pill is the
                    // most annoying possible interaction bug.
                    val wasDrag = dragDistance > touchSlop
                    dragDistance = 0f
                    if (!wasDrag) {
                        v.performClick()
                    } else {
                        onDragEnd?.invoke()
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    // A cancelled drag (the finger left the window, a dialog stole
                    // the gesture) has still moved the pill: persist where it ended
                    // up rather than losing the move.
                    if (dragDistance > touchSlop) onDragEnd?.invoke()
                    dragDistance = 0f
                    return true
                }
            }
            return false
        }
    }

    /**
     * dp → px for the pill's own chrome.
     *
     * Uses the **display** density ([PanelLayoutMath.density]) so the pill keeps a
     * constant pixel size inside a container that the controller also sizes with the
     * display density: mixing the two (the Activity's density for the pill's padding,
     * the display's for the window) made the pill look different in an Activity whose
     * host app overrides its density — and the panel is *inside* that container.
     */
    private fun dp(value: Float): Int =
        (value * PanelLayoutMath.density(context) + 0.5f).toInt()

    internal companion object {
        /** Virtual origin for the web UI; every request is served from the APK. */
        const val BASE_URL = "https://regtrail.local/"

        /** Asset directory inside the module APK that holds the window's UI. */
        const val ASSET_DIR = "regtrail/"

        /** Entry page, relative to [ASSET_DIR]. */
        const val PAGE = "index.html"

        /**
         * Window size while collapsed, in dp.
         *
         * The pill itself is `wrap_content`, so these describe the *window* the
         * controller hands to `LayoutParams` — just large enough for the pill plus
         * a comfortable tap slop margin, and small enough not to cover the host
         * app's UI. Both mechanisms size to these values, so the pill looks the
         * same whichever one is live.
         */
        const val COLLAPSED_WIDTH_DP = 112f
        const val COLLAPSED_HEIGHT_DP = 38f

        /** Height of the collapsed grip; kept here so the corner stays circular. */
        private const val PILL_CORNER_DP = COLLAPSED_HEIGHT_DP / 2f

        private const val ACCENT = 0xFFFFB300.toInt()
        private const val PILL_BG = 0xF218181C.toInt()
        private const val PILL_STROKE = 0x66FFB300
    }

    /**
     * Serves the window's UI straight out of the module APK.
     *
     * Using a real `https://` origin (rather than `file:///android_asset/` or
     * `loadDataWithBaseURL` with a data URL) keeps the page on a proper origin,
     * so `localStorage`, `postMessage` and normal script loading all behave.
     * Nothing escapes to the network: every request under [BASE_URL] is answered
     * from the APK, and anything else is blocked.
     */
    private class AssetInterceptingClient(
        private val assets: ModuleAssets,
    ) : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val url = request.url?.toString() ?: return blocked()
            if (!url.startsWith(BASE_URL)) return blocked()

            // Strip the virtual origin; a bare "/" means the entry page.
            val path = url.removePrefix(BASE_URL).substringBefore('?').substringBefore('#')
            val assetPath = if (path.isEmpty()) ASSET_DIR + PAGE else path

            val text = assets.readText(assetPath)
            if (text == null) {
                RegTrailLog.w("asset not readable from module APK: assets/$assetPath")
                // Fall back to a *visible* error page. A transparent blank
                // WebView is indistinguishable from "the overlay never attached",
                // which makes this class of failure almost impossible to diagnose
                // from a screenshot.
                return if (assetPath.endsWith(".html")) {
                    respond(fallbackHtml("无法从模块 APK 读取 assets/$assetPath"), "text/html")
                } else {
                    blocked()
                }
            }

            return respond(text, mimeTypeOf(assetPath))
        }

        private fun respond(text: String, mime: String) = WebResourceResponse(
            mime,
            "utf-8",
            ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)),
        ).apply {
            responseHeaders = mapOf(
                "Cache-Control" to "no-store",
                // 'self' is REQUIRED here: the page loads its own style.css and
                // app.js as sub-resources. An earlier policy allowed only
                // 'unsafe-inline', so both were blocked and the window rendered as
                // an unstyled, effectively invisible pane.
                "Content-Security-Policy" to
                    "default-src 'none'; style-src 'self' 'unsafe-inline'; " +
                    "script-src 'self' 'unsafe-inline'; img-src 'self' data:;",
            )
        }

        private fun fallbackHtml(message: String): String = """
            <!DOCTYPE html><html><head><meta charset="utf-8">
            <style>
              html,body{margin:0;height:100%;background:#1c1c22;color:#eceff4;
                font-family:sans-serif;font-size:12px;}
              .box{padding:10px;border:1px solid #ff7b72;border-radius:10px;
                margin:6px;height:calc(100% - 12px);box-sizing:border-box;}
              .t{color:#ffb300;font-weight:600;margin-bottom:6px;}
              .m{color:#ff8a80;word-break:break-all;}
            </style></head><body><div class="box">
              <div class="t">RegTrail</div>
              <div class="m">$message</div>
              <div class="m" style="margin-top:8px;color:#9aa0aa">
                模块已注入，但窗口 UI 资源加载失败。
              </div>
            </div></body></html>
        """.trimIndent()

        /** Legacy overload, still used on some ROMs for subresource loads. */
        @Deprecated("Deprecated in Java")
        override fun shouldInterceptRequest(
            view: WebView,
            url: String,
        ): WebResourceResponse? = shouldInterceptRequest(
            view,
            object : WebResourceRequest {
                override fun getUrl() = android.net.Uri.parse(url)
                override fun isForMainFrame() = false
                override fun isRedirect() = false
                override fun hasGesture() = false
                override fun getMethod() = "GET"
                override fun getRequestHeaders() = emptyMap<String, String>()
            },
        )

        private fun blocked() = WebResourceResponse(
            "text/plain",
            "utf-8",
            403,
            "Forbidden",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0)),
        )

        private fun mimeTypeOf(path: String): String = when {
            path.endsWith(".html") -> "text/html"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".js") -> "application/javascript"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".svg") -> "image/svg+xml"
            else -> "text/plain"
        }
    }
}

/**
 * The `window.RegTrail` object exposed to the web UI.
 *
 * Methods are annotated [JavascriptInterface] and are therefore callable from
 * page script. Everything is funnelled through [JsBridge.Handler] so the
 * controller decides what each message means; this class stays a thin,
 * fail-safe adapter.
 */
internal class JsBridge(private val handler: Handler) {

    fun interface Handler {
        /**
         * @return a JSON string to hand back to the page, or null for none.
         */
        fun onMessage(action: String, payloadJson: String): String?
    }

    @JavascriptInterface
    fun post(action: String, payloadJson: String): String =
        runCatching { handler.onMessage(action, payloadJson) ?: "null" }
            .getOrElse { "{\"ok\":false,\"error\":\"bridge error\"}" }

    @JavascriptInterface
    fun log(message: String) {
        runCatching { handler.onMessage("log", message) }
    }

    @JavascriptInterface
    fun ready(): String =
        runCatching { handler.onMessage("ready", "{}") ?: "null" }
            .getOrElse { "null" }

    companion object {
        const val NAME = "RegTrailNative"
    }
}
