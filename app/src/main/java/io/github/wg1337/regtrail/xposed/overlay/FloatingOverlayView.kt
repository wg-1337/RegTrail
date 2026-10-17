package io.github.wg1337.regtrail.xposed.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import io.github.wg1337.regtrail.xposed.RegTrailLog
import java.io.ByteArrayInputStream

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
    private val collapsedPill: TextView

    /** Set by the controller so the drag handler can update window gravity. */
    var onDrag: ((dx: Int, dy: Int) -> Unit)? = null

    var onResize: ((width: Int, height: Int) -> Unit)? = null

    private var collapsed = false

    private var downRawX = 0f
    private var downRawY = 0f

    init {
        setBackgroundColor(Color.TRANSPARENT)

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

        collapsedPill = TextView(context).apply {
            text = "RT"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ACCENT)
                setStroke(dp(1.5f), 0x66FFFFFF)
            }
            visibility = GONE
            setOnTouchListener(dragListener())
            setOnClickListener {
                setCollapsed(false)
                onCollapsedChanged(false)
            }
        }
        addView(
            collapsedPill,
            LayoutParams(dp(34f), dp(34f)).apply { gravity = Gravity.CENTER },
        )
    }

    fun setCollapsed(value: Boolean) {
        collapsed = value
        webView.visibility = if (value) GONE else VISIBLE
        collapsedPill.visibility = if (value) VISIBLE else GONE
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

    fun destroy() {
        runCatching {
            removeView(webView)
            webView.destroy()
        }
    }

    private fun dragListener() = object : OnTouchListener {
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    onDrag?.invoke(dx, dy)
                    return true
                }
            }
            return false
        }
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    internal companion object {
        /** Virtual origin for the web UI; every request is served from the APK. */
        const val BASE_URL = "https://regtrail.local/"

        /** Asset directory inside the module APK that holds the window's UI. */
        const val ASSET_DIR = "regtrail/"

        /** Entry page, relative to [ASSET_DIR]. */
        const val PAGE = "index.html"

        private const val ACCENT = 0xFFFFB300.toInt()
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
