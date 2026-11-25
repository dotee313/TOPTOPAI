package com.toptopai.app;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.PluginMethod;

import org.json.JSONArray;
import org.json.JSONObject;

@CapacitorPlugin(name = "ProxyRouter")
public class ProxyRouterPlugin extends Plugin {

    private ProxyRouterState state() {
        return ProxyRouterState.get(getContext());
    }

    @PluginMethod
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

    @PluginMethod
    public void getStatus(PluginCall call) {
        try {
            call.resolve(JSObject.fromJSONObject(state().status()));
        } catch (Exception e) {
            call.reject("proxy_status_failed", String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void resetStats(PluginCall call) {
        state().resetStats();
        JSObject r = new JSObject();
        r.put("ok", true);
        call.resolve(r);
    }

    @PluginMethod
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
}
