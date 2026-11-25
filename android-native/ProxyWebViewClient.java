package com.toptopai.app;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebViewClient;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Routes the WebView's own http/https traffic through the user's proxies.
 * Capacitor's local server (https://localhost) is left untouched.
 */
public class ProxyWebViewClient extends BridgeWebViewClient {
    public ProxyWebViewClient(Bridge bridge) {
        super(bridge);
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        WebResourceResponse local = super.shouldInterceptRequest(view, request);
        if (local != null) return local;

        ProxyRouterState state = ProxyRouterState.get(view.getContext());
        if (!state.isEnabled()) return null;

        Uri uri = request.getUrl();
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;
        String host = uri.getHost();
        if (host == null || host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")
                || host.endsWith(".localhost")) return null;

        Map<String, String> outHeaders = new HashMap<>();
        outHeaders.put("Access-Control-Allow-Origin", "*");
        outHeaders.put("Cache-Control", "no-store");

        try {
            Request.Builder rb = new Request.Builder().url(uri.toString());
            Headers.Builder hb = new Headers.Builder();
            for (Map.Entry<String, String> h : request.getRequestHeaders().entrySet()) {
                String k = h.getKey();
                if (k.equalsIgnoreCase("Host") || k.equalsIgnoreCase("Content-Length")) continue;
                hb.add(k, h.getValue());
            }
            String method = request.getMethod() == null ? "GET" : request.getMethod();
            // POST bodies are not part of Android's public WebResourceRequest API.
            // The JS fetch shim sends those through the ProxyRouter.request plugin method.
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) return null;
            rb.headers(hb.build()).method(method, null);

            Response r = state.execute(rb.build());
            for (String name : r.headers().names()) {
                if (name.equalsIgnoreCase("Content-Length") || name.equalsIgnoreCase("Transfer-Encoding")) continue;
                String v = r.header(name);
                if (v != null) outHeaders.put(name, v);
            }
            outHeaders.put("Access-Control-Allow-Origin", "*");
            String ct = r.header("Content-Type", "application/octet-stream");
            String mime = ct.split(";")[0].trim();
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
