package com.toptopai.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Authenticator;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;

/**
 * In-app proxy router. Mirrors the round-robin / failover idea of
 * @mark2messmore/proxy-rotator, but runs inside the Android WebView process so
 * every request made by the app can be tunnelled through a user supplied proxy.
 */
public final class ProxyRouterState {
    private static ProxyRouterState instance;

    public static final class Entry {
        public final String host;
        public final int port;
        public final String user;
        public final String pass;
        public final boolean socks;
        volatile long cooldownUntil;

        public Entry(String host, int port, String user, String pass, boolean socks) {
            this.host = host;
            this.port = port;
            this.user = user == null ? "" : user;
            this.pass = pass == null ? "" : pass;
            this.socks = socks;
        }

        public static Entry from(JSONObject o) {
            String t = o.optString("type", "http");
            return new Entry(
                    o.optString("host", "").trim(),
                    o.optInt("port", 0),
                    o.optString("user", ""),
                    o.optString("pass", ""),
                    "socks".equalsIgnoreCase(t)
            );
        }

        public String label() {
            return host + ":" + port + (socks ? " (SOCKS)" : "");
        }
    }

    private final SharedPreferences prefs;
    private final Random random = new Random();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger cursor = new AtomicInteger();
    private volatile List<Entry> entries = new ArrayList<>();
    private volatile boolean enabled = false;
    private volatile String mode = "failover";
    private volatile Entry active = null;
    private volatile int lastLatency = -1;

    public static synchronized ProxyRouterState get(Context ctx) {
        if (instance == null) instance = new ProxyRouterState(ctx.getApplicationContext());
        return instance;
    }

    private ProxyRouterState(Context ctx) {
        prefs = ctx.getSharedPreferences("toptopai_proxy", Context.MODE_PRIVATE);
        enabled = prefs.getBoolean("enabled", false);
        mode = prefs.getString("mode", "failover");
        parse(prefs.getString("entries", "[]"));
    }

    private void parse(String raw) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Entry e = Entry.from(o);
                if (!e.host.isEmpty() && e.port > 0 && e.port < 65536) out.add(e);
            }
        } catch (Exception ignored) {
        }
        entries = out;
    }

    public synchronized void configure(boolean on, String m, JSONArray arr) {
        enabled = on;
        mode = (m == null || m.isEmpty()) ? "failover" : m;
        prefs.edit()
                .putBoolean("enabled", enabled)
                .putString("mode", mode)
                .putString("entries", arr.toString())
                .apply();
        parse(arr.toString());
        cursor.set(0);
    }

    public boolean isEnabled() {
        return enabled && !entries.isEmpty();
    }

    public synchronized Entry pick() {
        if (entries.isEmpty()) return null;
        if ("single".equals(mode)) {
            active = entries.get(0);
            return active;
        }
        long now = System.currentTimeMillis();
        List<Entry> ok = new ArrayList<>();
        for (Entry e : entries) if (e.cooldownUntil <= now) ok.add(e);
        if (ok.isEmpty()) ok = new ArrayList<>(entries);
        Entry e;
        if ("random".equals(mode)) e = ok.get(random.nextInt(ok.size()));
        else if ("round_robin".equals(mode)) e = ok.get(Math.abs(cursor.getAndIncrement() % ok.size()));
        else e = ok.get(0);
        active = e;
        return e;
    }

    private void mark(Entry e, boolean good, int latency) {
        if (good) {
            lastLatency = latency;
        } else {
            failures.incrementAndGet();
            if (e != null) e.cooldownUntil = System.currentTimeMillis() + 60_000L;
        }
    }

    private OkHttpClient client(Entry e) {
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .proxy(new Proxy(e.socks ? Proxy.Type.SOCKS : Proxy.Type.HTTP,
                        new InetSocketAddress(e.host, e.port)))
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS);
        if (!e.user.isEmpty()) {
            final String basic = Credentials.basic(e.user, e.pass);
            b.proxyAuthenticator(new Authenticator() {
                @Override
                public Request authenticate(Route route, Response response) {
                    if (response.request().header("Proxy-Authorization") != null) return null;
                    return response.request().newBuilder()
                            .header("Proxy-Authorization", basic).build();
                }
            });
        }
        return b.build();
    }

    /** Execute a request, trying the next proxy on failure. Caller owns the Response. */
    public Response execute(Request req) throws Exception {
        Exception last = null;
        int tries = Math.min(3, Math.max(1, entries.size()));
        for (int i = 0; i < tries; i++) {
            Entry e = pick();
            if (e == null) break;
            requests.incrementAndGet();
            long t = System.currentTimeMillis();
            try {
                Response r = client(e).newCall(req).execute();
                mark(e, true, (int) (System.currentTimeMillis() - t));
                return r;
            } catch (Exception ex) {
                last = ex;
                mark(e, false, -1);
            }
        }
        if (last != null) throw last;
        throw new IllegalStateException("لم يُعنّ أي بروكسي صالح");
    }

    public JSONObject testOne(Entry e) {
        JSONObject o = new JSONObject();
        Request req = new Request.Builder()
                .url("https://www.google.com/generate_204")
                .header("User-Agent", "TOPTOPAI-ProxyTest/1.0")
                .build();
        long t = System.currentTimeMillis();
        try (Response r = client(e).newCall(req).execute()) {
            o.put("ok", r.isSuccessful());
            o.put("status", r.code());
            o.put("latencyMs", (int) (System.currentTimeMillis() - t));
            o.put("label", e.label());
        } catch (Exception ex) {
            try {
                o.put("ok", false);
                o.put("error", String.valueOf(ex.getMessage()));
                o.put("label", e.label());
            } catch (Exception ignored) {
            }
        }
        return o;
    }

    public JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            o.put("enabled", enabled);
            o.put("mode", mode);
            o.put("count", entries.size());
            o.put("active", active == null ? "" : active.label());
            o.put("requests", requests.get());
            o.put("failures", failures.get());
            o.put("lastLatency", lastLatency);
        } catch (Exception ignored) {
        }
        return o;
    }

    public void resetStats() {
        requests.set(0);
        failures.set(0);
        lastLatency = -1;
        for (Entry e : entries) e.cooldownUntil = 0;
    }
}
