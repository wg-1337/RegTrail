package io.github.wg1337.regtrail.payload;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Map;

/**
 * Attaches the RegTrail floating panel into the host app's Activity window.
 *
 * <p>This is the payload side of P6. It deliberately mirrors the shape of the
 * in-process LSPosed overlay ({@code DecorOverlayFallback} +
 * {@code FloatingOverlayView}) instead of sharing code with it, for one hard
 * reason: the payload is dexed into <em>someone else's APK</em>, so it may not
 * depend on {@code io.github.libxposed:api}, the Kotlin stdlib, or anything else
 * that would bloat or destabilise the host. Everything here is plain Java against
 * the framework only.
 *
 * <p>## Why the decor view and not {@code TYPE_APPLICATION_OVERLAY}
 *
 * <p>A real floating window needs {@code SYSTEM_ALERT_WINDOW}, a special
 * permission granted out-of-band from Settings. A re-signed APK cannot obtain it
 * on the user's behalf, and prompting for an overlay permission right after the
 * user installs a patched app would be a hostile first impression. Attaching into
 * {@code android.R.id.content} needs no permission at all.
 *
 * <p>The trade-off is visible to the user: the panel is clipped by the host
 * window (status bar and dialogs draw above it) and is re-attached per Activity.
 * The web UI is stateless, so re-attaching loses nothing.
 *
 * <p>## Attach timing
 *
 * <p>{@code onActivityCreated} is too early: the host sets its own content view
 * afterwards and paints over our container (a previously observed bug — the
 * overlay was attached yet invisible). {@code onActivityResumed} is the earliest
 * point at which the content view is guaranteed to exist, so the container is
 * added last and then brought to front.
 */
public final class OverlayHost {

    static final String TAG = "RegTrailPayload";

    /** Virtual origin for the panel's web UI; every request is served from the APK. */
    static final String BASE_URL = "https://regtrail.local/";

    /** Asset directory inside the patched APK that holds the panel's UI. */
    static final String ASSET_DIR = "regtrail/";

    /** Entry page, relative to {@link #ASSET_DIR}. */
    static final String PAGE = "index.html";

    /** Name of the object exposed to page script via {@code addJavascriptInterface}. */
    static final String BRIDGE_NAME = "RegTrailNative";

    private static final int PANEL_WIDTH_DP = 300;
    private static final int PANEL_HEIGHT_DP = 380;
    private static final int PANEL_TOP_MARGIN_DP = 80;
    private static final int PANEL_END_MARGIN_DP = 8;
    private static final int ACCENT = 0xFF_FF_B3_00;

    /** Guards against a second registration; one Application per process anyway. */
    private static volatile boolean installed;

    /** Application handle, kept because it is the only route to our own assets. */
    private static volatile Application application;

    // ---- Live attachment state (main thread only) ---------------------------

    private static FrameLayout currentContainer;
    private static FrameLayout currentPanel;
    private static WebView currentWebView;
    private static TextView currentPill;
    private static Activity hostActivity;

    /** Recent payload lines, so failures are diagnosable from the panel itself. */
    private static final ArrayDeque<String> LOG_RING = new ArrayDeque<String>(64);

    private OverlayHost() {
    }

    /**
     * Registers the lifecycle observer that keeps the panel attached.
     *
     * <p>Called from {@link RegTrailAppFactory#instantiateApplication} straight
     * after the platform created the host's {@code Application}. Registering
     * callbacks needs no attached base context, and the first attach only happens
     * on the first {@code onActivityResumed}, by which point the host's startup
     * has completed.
     */
    static void install(Application app) {
        if (installed) {
            return;
        }
        installed = true;
        application = app;

        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                // Deliberately empty: see the class comment on attach timing.
            }

            @Override
            public void onActivityStarted(Activity activity) {
            }

            @Override
            public void onActivityResumed(Activity activity) {
                if (hostActivity != activity || currentPanel == null) {
                    attach(activity);
                } else if (currentContainer != null) {
                    currentContainer.bringToFront();
                }
            }

            @Override
            public void onActivityPaused(Activity activity) {
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
                if (hostActivity == activity) {
                    detach();
                    hostActivity = null;
                }
            }
        });

        log("payload installed for " + app.getPackageName());
    }

    // -----------------------------------------------------------------------
    // Attach / detach
    // -----------------------------------------------------------------------

    private static void attach(Activity activity) {
        ViewGroup root;
        try {
            root = activity.findViewById(android.R.id.content);
        } catch (Throwable t) {
            Log.w(TAG, "findViewById(android.R.id.content) failed", t);
            return;
        }
        if (root == null) {
            Log.w(TAG, "activity has no content view; cannot attach overlay");
            return;
        }

        detach();
        hostActivity = activity;

        FrameLayout container = new FrameLayout(activity);
        container.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // The container covers the host's whole content area, so it must not
        // swallow touches meant for the app underneath: only the panel consumes
        // events, and a non-clickable ViewGroup simply does not consume.
        container.setClickable(false);
        container.setFocusable(false);
        container.setClipChildren(false);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(activity, PANEL_WIDTH_DP), dp(activity, PANEL_HEIGHT_DP));
        params.gravity = Gravity.TOP | Gravity.END;
        params.topMargin = dp(activity, PANEL_TOP_MARGIN_DP);
        params.setMarginEnd(dp(activity, PANEL_END_MARGIN_DP));

        FrameLayout panel = createPanel(activity);
        container.addView(panel, params);

        root.addView(container);
        // Belt and braces: if the host added content in the same frame, make sure
        // our container is the last child so it draws on top.
        container.bringToFront();

        currentContainer = container;
        currentPanel = panel;
        log("overlay attached to " + activity.getClass().getName());
    }

    private static void detach() {
        FrameLayout container = currentContainer;
        currentContainer = null;
        currentPanel = null;
        currentPill = null;

        WebView webView = currentWebView;
        currentWebView = null;

        if (container != null) {
            try {
                ViewGroup parent = (ViewGroup) container.getParent();
                if (parent != null) {
                    parent.removeView(container);
                }
            } catch (Throwable t) {
                Log.w(TAG, "failed to detach overlay container", t);
            }
        }
        if (webView != null) {
            try {
                webView.destroy();
            } catch (Throwable t) {
                Log.w(TAG, "failed to destroy WebView", t);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Panel construction
    // -----------------------------------------------------------------------

    /**
     * Builds the panel: a WebView plus a collapsible "RT" pill.
     *
     * <p>Constructed entirely in code. The payload ships no XML resources, and a
     * re-signed third-party APK has no {@code R} class of ours to reference
     * anyway.
     *
     * <p>If WebView creation fails (no WebView provider, or a host that mangles
     * the WebView data directory), a TextView fallback is shown instead of
     * nothing: an invisible overlay is indistinguishable from "the patch did not
     * work", which is the worst possible diagnostic outcome.
     */
    private static FrameLayout createPanel(Activity activity) {
        FrameLayout host = new FrameLayout(activity);
        host.setClipChildren(false);

        try {
            WebView webView = new WebView(activity);
            try {
                WebSettings settings = webView.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setAllowFileAccess(false);
                settings.setAllowContentAccess(false);
                settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
            } catch (Throwable t) {
                Log.w(TAG, "WebSettings setup failed (continuing)", t);
            }

            webView.setBackgroundColor(Color.TRANSPARENT);
            webView.setWebViewClient(new AssetInterceptingClient(activity.getAssets()));
            try {
                webView.addJavascriptInterface(new Bridge(), BRIDGE_NAME);
            } catch (Throwable t) {
                // Page script degrades to a read-only shell by design.
                Log.w(TAG, "addJavascriptInterface failed", t);
            }

            host.addView(webView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            currentWebView = webView;
            addCollapsePill(activity, host);

            webView.loadUrl(BASE_URL + ASSET_DIR + PAGE);
            return host;
        } catch (Throwable t) {
            Log.e(TAG, "WebView unavailable; falling back to a text panel", t);
            TextView fallback = new TextView(activity);
            fallback.setText("RegTrail\n窗口 UI 加载失败：" + t.getClass().getSimpleName());
            fallback.setTextColor(Color.WHITE);
            fallback.setTextSize(11f);
            int pad = dp(activity, 8);
            fallback.setPadding(pad, pad, pad, pad);
            fallback.setBackgroundColor(0xCC_1C_1C_22);
            host.addView(fallback, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return host;
        }
    }

    /**
     * Adds the collapsed "RT" pill.
     *
     * <p>Collapsing is optional for the payload, but the shared web UI has a
     * collapse button and a panel that cannot be moved out of the way is a real
     * usability problem in a third-party app. The pill is the cheapest way to
     * offer it without a WindowManager: hide the WebView, show a small dot, tap to
     * restore.
     */
    private static void addCollapsePill(Activity activity, FrameLayout host) {
        TextView pill = new TextView(activity);
        pill.setText("RT");
        pill.setTextColor(Color.WHITE);
        pill.setTextSize(12f);
        pill.setGravity(Gravity.CENTER);

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(ACCENT);
        background.setStroke(dp(activity, 1.5f), 0x66_FF_FF_FF);
        pill.setBackground(background);
        pill.setVisibility(View.GONE);
        pill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyCollapsed(false);
            }
        });

        FrameLayout.LayoutParams pillParams = new FrameLayout.LayoutParams(
                dp(activity, 34f), dp(activity, 34f));
        pillParams.gravity = Gravity.CENTER;
        host.addView(pill, pillParams);

        currentPill = pill;
    }

    /** Swaps between the expanded panel and the collapsed pill. */
    private static void applyCollapsed(boolean collapsed) {
        WebView webView = currentWebView;
        TextView pill = currentPill;
        if (webView != null) {
            webView.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        }
        if (pill != null) {
            pill.setVisibility(collapsed ? View.VISIBLE : View.GONE);
        }
        if (currentContainer != null) {
            currentContainer.bringToFront();
        }
        if (webView != null) {
            try {
                webView.evaluateJavascript(
                        "window.RegTrail && window.RegTrail.onCollapsed(" + collapsed + ");", null);
            } catch (Throwable ignored) {
                // evaluateJavascript is best-effort by definition.
            }
        }
    }

    // -----------------------------------------------------------------------
    // Status / logging
    // -----------------------------------------------------------------------

    /** Appends a payload line to logcat and to the ring buffer. */
    static void log(String message) {
        Log.i(TAG, message);
        synchronized (LOG_RING) {
            LOG_RING.addLast(message);
            while (LOG_RING.size() > 64) {
                LOG_RING.removeFirst();
            }
        }
    }

    /**
     * The JSON the web UI shows in its status tab.
     *
     * <p>The shape matches what the module-side bridge returns, because the page
     * is the same {@code assets/regtrail/app.js} in both worlds:
     * {@code {"ok":true,"data":{...}}}.
     */
    private static String statusJson() {
        try {
            String packageName = currentPackageName();

            JSONObject data = new JSONObject();
            data.put("package", packageName);
            data.put("mechanism", "ActivityDecor");
            data.put("framework", "RegTrail 注入载荷（免 Root）");
            data.put("apiVersion", "payload/P6");

            JSONArray targets = new JSONArray();
            targets.put(packageName);
            data.put("targets", targets);

            synchronized (LOG_RING) {
                data.put("logLines", LOG_RING.size());
            }

            JSONObject root = new JSONObject();
            root.put("ok", true);
            root.put("data", data);
            return root.toString();
        } catch (Throwable t) {
            Log.w(TAG, "statusJson failed", t);
            return "{\"ok\":false,\"error\":\"status unavailable\"}";
        }
    }

    private static String currentPackageName() {
        Activity activity = hostActivity;
        if (activity != null) {
            return activity.getPackageName();
        }
        Application app = application;
        return app != null ? app.getPackageName() : "?";
    }

    private static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    // -----------------------------------------------------------------------
    // JS bridge
    // -----------------------------------------------------------------------

    /**
     * The {@code window.RegTrailNative} object.
     *
     * <p>Intentionally tiny. The in-process LSPosed overlay exposes a full command
     * bridge (terminal, debugger, agent tools); the payload must not, because it
     * runs inside an app it does not control and every extra surface is a way to
     * break that app. So: {@link #status()} and {@link #log(String)} only, plus
     * {@link #post(String, String)} as a compatibility shim for the web UI that
     * the module and the payload share.
     */
    public static final class Bridge {

        /** Read-only status payload, as JSON. */
        @JavascriptInterface
        public String status() {
            return statusJson();
        }

        /** Appends a line to logcat. Used by the web UI for its own diagnostics. */
        @JavascriptInterface
        public void log(String message) {
            OverlayHost.log("web: " + message);
        }

        /**
         * Compatibility shim for the shared {@code app.js}, which calls
         * {@code RegTrailNative.post(action, payload)}.
         *
         * <p>Only the handful of actions the panel itself needs are understood;
         * everything else answers {@code unsupported} rather than throwing, so a
         * newer page against an older payload still renders.
         */
        @JavascriptInterface
        public String post(String action, String payloadJson) {
            if (action == null) {
                return "{\"ok\":false,\"error\":\"no action\"}";
            }
            if ("status".equals(action) || "ready".equals(action)
                    || "reloadConfig".equals(action)) {
                return statusJson();
            }
            if ("collapse".equals(action) || "hide".equals(action)) {
                try {
                    applyCollapsed(true);
                    return "{\"ok\":true}";
                } catch (Throwable t) {
                    Log.w(TAG, "collapse failed", t);
                    return "{\"ok\":false}";
                }
            }
            Log.d(TAG, "unsupported bridge action: " + action + " " + payloadJson);
            return "{\"ok\":false,\"error\":\"unsupported action\"}";
        }
    }

    // -----------------------------------------------------------------------
    // Asset serving
    // -----------------------------------------------------------------------

    /**
     * Serves the panel's UI out of the patched APK.
     *
     * <p>Using a real {@code https://} origin (rather than
     * {@code file:///android_asset/} or a {@code data:} URL) keeps the page on a
     * proper origin, so {@code localStorage}, sub-resource loads and normal script
     * loading behave. Nothing escapes to the network: every request under
     * {@link #BASE_URL} is answered from {@code assets/regtrail/}, everything else
     * is refused.
     *
     * <p>In a patched APK {@code Activity.getAssets()} <em>is</em> this APK, so
     * there is no path discovery to do — the failure mode that plagued the
     * LSPosed module (whose classes live in an in-memory dex with no owning APK)
     * cannot happen here.
     */
    private static final class AssetInterceptingClient extends WebViewClient {

        private final AssetManager assets;

        AssetInterceptingClient(AssetManager assets) {
            this.assets = assets;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String url = null;
            if (request != null && request.getUrl() != null) {
                url = request.getUrl().toString();
            }
            return serve(url);
        }

        @Override
        @SuppressWarnings("deprecation")
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            // Legacy overload: some ROMs still route sub-resource loads through it.
            return serve(url);
        }

        private WebResourceResponse serve(String url) {
            if (url == null || !url.startsWith(BASE_URL)) {
                return blocked();
            }
            String path = url.substring(BASE_URL.length());
            int cut = path.indexOf('?');
            if (cut >= 0) {
                path = path.substring(0, cut);
            }
            cut = path.indexOf('#');
            if (cut >= 0) {
                path = path.substring(0, cut);
            }
            String assetPath = path.isEmpty() ? ASSET_DIR + PAGE : path;

            try {
                InputStream in = assets.open(assetPath);
                return respond(in, mimeTypeOf(assetPath));
            } catch (Throwable t) {
                Log.w(TAG, "asset not readable: " + assetPath, t);
                if (assetPath.endsWith(".html")) {
                    byte[] body = fallbackHtml("无法从 APK 读取 assets/" + assetPath)
                            .getBytes(Charset.forName("UTF-8"));
                    return respond(new ByteArrayInputStream(body), "text/html");
                }
                return blocked();
            }
        }

        private static WebResourceResponse respond(InputStream in, String mime) {
            WebResourceResponse response = new WebResourceResponse(mime, "utf-8", in);
            response.setResponseHeaders(Collections.singletonMap("Cache-Control", "no-store"));
            return response;
        }

        private static WebResourceResponse blocked() {
            Map<String, String> headers = Collections.singletonMap("Cache-Control", "no-store");
            return new WebResourceResponse("text/plain", "utf-8", 403, "Forbidden",
                    headers, new ByteArrayInputStream(new byte[0]));
        }

        private static String mimeTypeOf(String path) {
            if (path.endsWith(".html")) {
                return "text/html";
            }
            if (path.endsWith(".css")) {
                return "text/css";
            }
            if (path.endsWith(".js")) {
                return "application/javascript";
            }
            if (path.endsWith(".json")) {
                return "application/json";
            }
            if (path.endsWith(".svg")) {
                return "image/svg+xml";
            }
            return "text/plain";
        }

        private static String fallbackHtml(String message) {
            return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head>"
                    + "<body style=\"margin:0;background:#1c1c22;color:#eceff4;"
                    + "font-family:sans-serif;font-size:12px\">"
                    + "<div style=\"padding:10px;border:1px solid #ff7b72;border-radius:10px;"
                    + "margin:6px\">"
                    + "<div style=\"color:#ffb300;font-weight:600;margin-bottom:6px\">RegTrail</div>"
                    + "<div style=\"color:#ff8a80;word-break:break-all\">" + message + "</div>"
                    + "<div style=\"margin-top:8px;color:#9aa0aa\">载荷已注入，但窗口 UI 资源加载失败。</div>"
                    + "</div></body></html>";
        }
    }
}
