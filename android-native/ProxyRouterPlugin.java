package com.toptopai.app;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.nio.charset.StandardCharsets;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONObject;

@CapacitorPlugin(name = "ProxyRouter")
public class ProxyRouterPlugin extends Plugin {

    private ProxyRouterState state() {
        return ProxyRouterState.get(getContext());
    }

    public void setConfig(PluginCall call) {
        try {
            boolean on = call.getBoolean("enabled", false);
            String mode = call.getString("mode", "failover");
            JSONArray arr = new JSONArray(call.getString("entries", "[]"));
            state().configure(on, mode, arr);
            JSObject r = new JSObject();
            r.put("ok", true);
            r.put("count", arr.length());
            call.resolve(r);
        } catch (Exception e) {
            call.reject("proxy_config_failed", String.valueOf(e.getMessage()));
        }
    }

    public void getStatus(PluginCall call) {
        try {
            call.resolve(JSObject.fromJSONObject(state().status()));
        } catch (Exception e) {
            call.reject("proxy_status_failed", String.valueOf(e.getMessage()));
        }
    }

    public void resetStats(PluginCall call) {
        state().resetStats();
        JSObject r = new JSObject();
        r.put("ok", true);
        call.resolve(r);
    }

    public void testProxy(PluginCall call) {
        try {
            ProxyRouterState.Entry e = new ProxyRouterState.Entry(
                    call.getString("host", ""),
                    call.getInt("port", 0),
                    call.getString("user", ""),
                    call.getString("pass", ""),
                    "socks".equalsIgnoreCase(call.getString("type", "http"))
            );
            call.resolve(JSObject.fromJSONObject(state().testOne(e)));
        } catch (Exception ex) {
            call.reject("proxy_test_failed", String.valueOf(ex.getMessage()));
        }
    }

    /** Used by the JS fetch shim for POST/SSE calls, which WebView cannot expose as a body. */
    public void request(PluginCall call) {
        try {
            String url = call.getString("url", "");
            String method = call.getString("method", "GET");
            String bodyText = call.getString("body", "");
            Request.Builder rb = new Request.Builder().url(url);
            Headers.Builder hb = new Headers.Builder();
            JSObject hs = call.getObject("headers");
            if (hs != null) {
                java.util.Iterator<String> it = hs.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    String v = hs.optString(k, "");
                    if (!k.equalsIgnoreCase("Host") && !k.equalsIgnoreCase("Content-Length")) hb.add(k, v);
                }
            }
            rb.headers(hb.build());
            RequestBody body = null;
            if (bodyText != null && !bodyText.isEmpty()
                    && !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                MediaType mt = null;
                String ct = hb.build().get("Content-Type");
                if (ct != null) {
                    try {
                        mt = MediaType.parse(ct);
                    } catch (Exception ignored) {
                    }
                }
                body = RequestBody.create(bodyText.getBytes(StandardCharsets.UTF_8), mt);
            }
            rb.method(method, body);
            try (Response r = state().execute(rb.build())) {
                JSObject out = new JSObject();
                out.put("status", r.code());
                out.put("body", r.body() == null ? "" : r.body().string());
                JSONObject hh = new JSONObject();
                for (String name : r.headers().names()) hh.put(name, r.header(name));
                out.put("headers", hh);
                call.resolve(out);
            }
        } catch (Exception e) {
            call.reject("proxy_request_failed", String.valueOf(e.getMessage()));
        }
    }
}
