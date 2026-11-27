package com.toptopai.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.PluginMethod;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Receives files shared from other Android apps (ACTION_SEND / SEND_MULTIPLE),
 * copies them into the app-private cache, and exposes them to the web layer.
 * Nothing leaves the device: the web view reads the bytes back through this plugin.
 */
@CapacitorPlugin(name = "ShareReceive")
public class ShareReceivePlugin extends Plugin {
    private static final int MAX_BYTES = 25 * 1024 * 1024;
    private static final List<SharedFile> pending = new ArrayList<>();
    private static Context appContext;
    private static String pendingText;

    static final class SharedFile {
        final String path;
        final String name;
        final String type;
        final long size;

        SharedFile(String path, String name, String type, long size) {
            this.path = path;
            this.name = name;
            this.type = type;
            this.size = size;
        }
    }

    @Override
    public void load() {
        appContext = getContext().getApplicationContext();
    }

    public static void onIntent(Context ctx, Intent intent) {
        if (ctx == null || intent == null) return;
        appContext = ctx.getApplicationContext();
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;

        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri one = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (one != null) uris.add(one);
        } else {
            ArrayList<Uri> many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (many != null) uris.addAll(many);
        }
        if (uris.isEmpty()) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null && !text.isEmpty()) {
                synchronized (pending) { pendingText = text; }
            }
            return;
        }
        for (Uri uri : uris) copyUri(uri);
    }

    private static File dir() {
        File d = new File(appContext.getCacheDir(), "shared");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String nameOf(Uri uri) {
        String name = null;
        try {
            android.database.Cursor c = appContext.getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && c.moveToFirst()) name = c.getString(i);
                c.close();
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.isEmpty()) name = "file-" + System.currentTimeMillis();
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static void copyUri(Uri uri) {
        try {
            InputStream in = appContext.getContentResolver().openInputStream(uri);
            if (in == null) return;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) != -1 && buf.size() + n <= MAX_BYTES) buf.write(chunk, 0, n);
            in.close();
            String name = nameOf(uri);
            String type = appContext.getContentResolver().getType(uri);
            if (type == null || type.isEmpty()) type = "application/octet-stream";
            File out = new File(dir(), UUID.randomUUID() + "_" + name);
            FileOutputStream fo = new FileOutputStream(out);
            fo.write(buf.toByteArray());
            fo.close();
            synchronized (pending) {
                pending.add(new SharedFile(out.getAbsolutePath(), name, type, buf.size()));
            }
        } catch (Exception ignored) {
        }
    }

    @PluginMethod
    public void consume(PluginCall call) {
        JSONArray arr = new JSONArray();
        synchronized (pending) {
            for (SharedFile f : pending) {
                JSObject o = new JSObject();
                o.put("path", f.path);
                o.put("name", f.name);
                o.put("type", f.type);
                o.put("size", f.size);
                arr.put(o);
            }
            pending.clear();
        }
        JSObject out = new JSObject();
        out.put("files", arr);
        synchronized (pending) { if (pendingText != null) { out.put("text", pendingText); pendingText = null; } }
        call.resolve(out);
    }

    @PluginMethod
    public void read(PluginCall call) {
        String path = call.getString("path", "");
        if (!path.startsWith(dir().getAbsolutePath())) { call.reject("invalid_path"); return; }
        File f = new File(path);
        if (!f.exists() || !f.isFile()) { call.reject("file_missing"); return; }
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) != -1 && buf.size() + n <= MAX_BYTES) buf.write(chunk, 0, n);
            in.close();
            byte[] bytes = buf.toByteArray();
            JSObject out = new JSObject();
            out.put("data", Base64.encodeToString(bytes, Base64.NO_WRAP));
            out.put("name", f.getName().replaceFirst("^[^_]+_", ""));
            out.put("size", bytes.length);
            call.resolve(out);
        } catch (Exception e) {
            call.reject("read_failed", String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void clear(PluginCall call) {
        File[] files = dir().listFiles();
        if (files != null) for (File f : files) try { f.delete(); } catch (Exception ignored) {}
        synchronized (pending) { pending.clear(); pendingText = null; }
        JSObject out = new JSObject();
        out.put("ok", true);
        call.resolve(out);
    }
}
