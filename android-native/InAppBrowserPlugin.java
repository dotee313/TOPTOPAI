package com.toptopai.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.net.Uri;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.getcapacitor.Bridge;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.Response;

/**
 * A real in-app browser: its own WebView, its own back stack, and the same
 * ProxyRouterState as the rest of the app, so user supplied proxies cover it too.
 */
@CapacitorPlugin(name = "InAppBrowser")
public class InAppBrowserPlugin extends Plugin {

    private Dialog dialog;
    private WebView web;
    private TextView address;

    public void open(PluginCall call) {
        final String url = normalize(call.getString("url", ""));
        Activity activity = getBridge().getActivity();
        if (activity == null) {
            call.reject("no_activity", "No Android activity available");
            return;
        }
        activity.runOnUiThread(() -> {
            try {
                build(activity);
                web.loadUrl(url);
                address.setText(url);
                JSObject result = new JSObject();
                result.put("ok", true);
                result.put("url", url);
                call.resolve(result);
            } catch (Exception e) {
                call.reject("browser_open_failed", String.valueOf(e.getMessage()));
            }
        });
    }

    public void close(PluginCall call) {
        getBridge().executeOnMainThread(() -> {
            dismiss();
            JSObject result = new JSObject();
            result.put("ok", true);
            call.resolve(result);
        });
    }

    public void back(PluginCall call) {
        getBridge().executeOnMainThread(() -> {
            if (web != null && web.canGoBack()) web.goBack();
            JSObject result = new JSObject();
            result.put("ok", web != null && web.canGoBack());
            call.resolve(result);
        });
    }

    public void forward(PluginCall call) {
        getBridge().executeOnMainThread(() -> {
            if (web != null && web.canGoForward()) web.goForward();
            JSObject result = new JSObject();
            result.put("ok", web != null && web.canGoForward());
            call.resolve(result);
        });
    }

    public void reload(PluginCall call) {
        getBridge().executeOnMainThread(() -> {
            if (web != null) web.reload();
            JSObject result = new JSObject();
            result.put("ok", web != null);
            call.resolve(result);
        });
    }

    /** Returns title/url/visible text of the current page to JavaScript. */
    public void snapshot(PluginCall call) {
        String js = snapshotJs(false);
        getBridge().eval(js, value -> {
            try {
                JSONObject object = new JSONObject(value == null || value.isEmpty() ? "{}" : value);
                call.resolve(JSObject.fromJSONObject(object));
            } catch (Exception e) {
                call.reject("snapshot_failed", String.valueOf(e.getMessage()));
            }
        });
    }

    private String snapshotJs(boolean sendToChat) {
        String target = sendToChat
                ? "if(window.ttBrowserSnapshot){window.ttBrowserSnapshot({title:t,url:u,text:x});}"
                : "return JSON.stringify({title:t,url:u,text:x});";
        return "(function(){try{"
                + "var t=(document.title||'').slice(0,300);"
                + "var u=location.href||'';"
                + "var x=((document.body&&document.body.innerText)||'').replace(/\\s+/g,' ').trim().slice(0,9000);"
                + target
                + "}catch(e){return JSON.stringify({title:'',url:'',text:'',error:String(e)});}})()";
    }

    private void emitSnapshot() {
        getBridge().eval(snapshotJs(true), null);
    }

    private void build(Activity activity) {
        dismiss();

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0E1418"));

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.parseColor("#161E24"));
        int pad = dp(activity, 6);
        bar.setPadding(pad, pad, pad, pad);

        address = new TextView(activity);
        address.setTextColor(Color.parseColor("#DDE7EE"));
        address.setTextSize(13);
        address.setSingleLine(true);
        address.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        address.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        bar.addView(button(activity, "←", v -> {
            if (web != null && web.canGoBack()) web.goBack();
        }));
        bar.addView(button(activity, "→", v -> {
            if (web != null && web.canGoForward()) web.goForward();
        }));
        bar.addView(button(activity, "↻", v -> {
            if (web != null) web.reload();
        }));
        bar.addView(button(activity, "⇪", v -> emitSnapshot()));
        bar.addView(address);
        bar.addView(button(activity, "✕", v -> dismiss()));

        web = new WebView(activity);
        web.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setAllowFileAccess(false);
        settings.setUserAgentString(settings.getUserAgentString() + " TOPTOPAI/1.0");

        web.setWebViewClient(new RouterClient(getBridge()));
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView view, String title) {
                address.setText((title == null || title.isEmpty() ? "صفحة ويب" : title)
                        + " — " + (view.getUrl() == null ? "" : view.getUrl()));
            }
        });

        root.addView(bar);
        root.addView(web);

        dialog = new Dialog(activity);
        dialog.setContentView(root);
        dialog.setCancelable(false);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                if (web != null && web.canGoBack()) {
                    web.goBack();
                } else {
                    dismiss();
                }
                return true;
            }
            return false;
        });
        dialog.show();
    }

    private static Button button(Activity activity, String label, View.OnClickListener listener) {
        Button b = new Button(activity);
        b.setText(label);
        b.setTextColor(Color.parseColor("#DDE7EE"));
        b.setTextSize(16);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setPadding(dp(activity, 8), 0, dp(activity, 8), 0);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        b.setOnClickListener(listener);
        return b;
    }

    private static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }

    private void dismiss() {
        if (dialog == null) return;
        try {
            if (dialog.isShowing()) dialog.dismiss();
        } catch (Exception ignored) {
        }
        dialog = null;
        web = null;
        address = null;
    }

    private static String normalize(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return "https://www.google.com";
        if (!s.matches("(?i)^https?://.*")) s = "https://" + s;
        try {
            new URL(s);
            return s;
        } catch (Exception ignored) {
            try {
                return "https://www.google.com/search?q=" + URLEncoder.encode(raw, StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                return "https://www.google.com";
            }
        }
    }

    /** Same router logic as the main WebView, but keeps navigation inside this WebView. */
    private static class RouterClient extends WebViewClient {
        private final Bridge bridge;

        RouterClient(Bridge bridge) {
            this.bridge = bridge;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme();
            return !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)
                    || "about".equalsIgnoreCase(scheme));
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;

            ProxyRouterState state = ProxyRouterState.get(view.getContext());
            if (!state.isEnabled()) return null;

            String host = uri.getHost();
            if (host == null || host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")
                    || host.endsWith(".localhost")) return null;

            Map<String, String> outHeaders = new HashMap<>();
            outHeaders.put("Access-Control-Allow-Origin", "*");
            outHeaders.put("Cache-Control", "no-store");

            String method = request.getMethod() == null ? "GET" : request.getMethod();
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) return null;

            try {
                Request.Builder rb = new Request.Builder().url(uri.toString());
                Headers.Builder hb = new Headers.Builder();
                for (Map.Entry<String, String> h : request.getRequestHeaders().entrySet()) {
                    String key = h.getKey();
                    if (key.equalsIgnoreCase("Host") || key.equalsIgnoreCase("Content-Length")) continue;
                    hb.add(key, h.getValue());
                }
                rb.headers(hb.build()).method(method, null);

                Response r = state.execute(rb.build());
                for (String name : r.headers().names()) {
                    if (name.equalsIgnoreCase("Content-Length")
                            || name.equalsIgnoreCase("Transfer-Encoding")) continue;
                    outHeaders.put(name, r.header(name));
                }
                outHeaders.put("Access-Control-Allow-Origin", "*");
                String contentType = r.header("Content-Type", "application/octet-stream");
                String mime = contentType.split(";")[0].trim();
                if (mime.isEmpty()) mime = "application/octet-stream";
                return new WebResourceResponse(mime, r.header("Content-Encoding"),
                        r.code(), r.message(), outHeaders, r.body().byteStream());
            } catch (Exception e) {
                byte[] err = ("proxy error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
                return new WebResourceResponse("text/plain", "utf-8", 502, "Bad Gateway",
                        outHeaders, new ByteArrayInputStream(err));
            }
        }
    }
}
