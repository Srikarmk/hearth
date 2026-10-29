package io.github.srikarmk.hearth;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The phone companion: a small web page the TV serves on the home network (http://<tv ip>:8765). From a phone
 * it opens TV apps, ambience scenes and Chess, picks a wallpaper, and uploads a picture from the phone as one.
 * Every action needs the 4-digit code shown on the TV (Wallpapers screen); the page itself holds no data.
 * Runs inside the launcher's process, which Fire OS keeps alive while it is Home.
 */
final class CompanionServer {

    static final int PORT = 8765;
    private static final String TAG = "Companion";
    private static final int MAX_UPLOAD = 10 * 1024 * 1024;
    private static volatile CompanionServer running;

    private final Context ctx;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    private CompanionServer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    static synchronized void start(Context ctx) {
        if (running != null) return;
        running = new CompanionServer(ctx);
        new Thread(running::serve, "companion").start();
    }

    /** The code a phone needs, made once per TV. */
    static String pin(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("companion", Context.MODE_PRIVATE);
        String pin = p.getString("pin", null);
        if (pin == null) {
            pin = String.format(Locale.ROOT, "%04d", new SecureRandom().nextInt(10000));
            p.edit().putString("pin", pin).apply();
        }
        return pin;
    }

    /** The address to type on a phone, or null when the TV has no IPv4 address on the local network. */
    static String address() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return "http://" + a.getHostAddress() + ":" + PORT;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void serve() {
        try (ServerSocket server = new ServerSocket(PORT)) {
            while (true) {
                Socket s = server.accept();
                if (!s.getInetAddress().isSiteLocalAddress() && !s.getInetAddress().isLoopbackAddress()) {
                    s.close();   // home network only
                    continue;
                }
                pool.execute(() -> handle(s));
            }
        } catch (Exception e) {
            Log.w(TAG, "companion stopped: " + e);
            running = null;
        }
    }

    private void handle(Socket s) {
        try (Socket socket = s) {
            socket.setSoTimeout(15_000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            String requestLine = line(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String target = parts[1];
            Map<String, String> headers = new HashMap<>();
            for (String h; (h = line(in)) != null && !h.isEmpty(); ) {
                int c = h.indexOf(':');
                if (c > 0) headers.put(h.substring(0, c).trim().toLowerCase(Locale.ROOT), h.substring(c + 1).trim());
            }
            String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
            Map<String, String> query = query(target.contains("?") ? target.substring(target.indexOf('?') + 1) : "");
            int length = 0;
            try {
                length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            } catch (NumberFormatException ignored) {
            }
            OutputStream out = socket.getOutputStream();
            if (length > MAX_UPLOAD) {
                send(out, 413, "text/plain", "too big".getBytes(StandardCharsets.UTF_8));
                return;
            }
            byte[] body = readBody(in, length);

            if ("GET".equals(method) && "/".equals(path)) {
                send(out, 200, "text/html; charset=utf-8", page());
                return;
            }
            if (!pin(ctx).equals(query.get("pin"))) {
                send(out, 403, "application/json", "{\"error\":\"code\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            switch (method + " " + path) {
                case "GET /api/state": json(out, state()); break;
                case "GET /api/thumb": thumb(out, query.get("name")); break;
                case "POST /api/launch": ok(out, launch(query.get("pkg"))); break;
                case "POST /api/scene": ok(out, scene(query.get("id"))); break;
                case "POST /api/chess": ok(out, open(ChessActivity.class)); break;
                case "POST /api/wallpaper": ok(out, choose(query.get("name"))); break;
                case "POST /api/upload": ok(out, upload(query.get("name"), body)); break;
                default: send(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "request failed: " + e);
        }
    }

    private JSONObject state() throws Exception {
        JSONObject o = new JSONObject();
        PackageManager pm = ctx.getPackageManager();
        List<ResolveInfo> found = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        JSONArray apps = new JSONArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || !seen.add(pkg)) continue;
            apps.put(new JSONObject().put("pkg", pkg).put("label", String.valueOf(ri.loadLabel(pm))));
        }
        o.put("apps", apps);
        JSONArray scenes = new JSONArray();
        for (Ambience.Scene sc : Ambience.SCENES) {
            scenes.put(new JSONObject().put("id", sc.id).put("label", ctx.getString(sc.label)).put("ready", Ambience.ready(ctx, sc)));
        }
        o.put("scenes", scenes);
        JSONArray walls = new JSONArray();
        for (File f : WallpaperRotation.images(new File(Config.dir(ctx), "wallpapers"))) {
            walls.put(new JSONObject().put("name", f.getName()).put("title", WallpaperPickerActivity.prettyName(f.getName())));
        }
        o.put("wallpapers", walls);
        o.put("current", ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getString(Config.WALLPAPER_CHOICE_KEY, ""));
        return o;
    }

    private boolean launch(String pkg) {
        if (pkg == null) return false;
        PackageManager pm = ctx.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        return true;
    }

    private boolean scene(String id) {
        if (id == null) return false;
        Ambience.remember(ctx, Ambience.byId(id));
        return open(FireplaceActivity.class);
    }

    private boolean open(Class<?> activity) {
        ctx.startActivity(new Intent(ctx, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        return true;
    }

    private boolean choose(String name) {
        File dir = Config.dir(ctx);
        if (name == null || dir == null || !new File(new File(dir, "wallpapers"), name).isFile()) return false;
        setChoice(name);
        return true;
    }

    private boolean upload(String name, byte[] body) throws Exception {
        String clean = clean(name);
        File dir = Config.dir(ctx);
        if (clean == null || dir == null || body.length == 0) return false;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(body, 0, body.length, o);
        if (o.outWidth <= 0) return false;   // not a picture Android can read
        File walls = new File(dir, "wallpapers");
        if (!walls.isDirectory() && !walls.mkdirs()) return false;
        try (FileOutputStream f = new FileOutputStream(new File(walls, clean))) {
            f.write(body);
        }
        setChoice(clean);
        return true;
    }

    /** "IMG 2041.HEIC" style names from phones: letters, digits, - _ . only, and an extension the launcher reads. */
    static String clean(String name) {
        if (name == null) return null;
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot < 0 ? "" : lower.substring(dot + 1);
        if (!ext.equals("jpg") && !ext.equals("jpeg") && !ext.equals("png") && !ext.equals("webp")) return null;
        String base = lower.substring(0, dot).replaceAll("[^a-z0-9_.-]+", "_").replaceAll("^[_.]+|_+$", "");
        if (base.isEmpty()) base = "phone_" + System.currentTimeMillis() / 1000;
        return base + "." + ext;
    }

    private void setChoice(String name) {
        SharedPreferences p = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        p.edit().putString(Config.WALLPAPER_CHOICE_KEY, name)
                .putLong(Config.SETTINGS_REVISION_KEY, p.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
        // Home rebuilds with the new picture now if it is on screen, or next time it comes up.
        ctx.startActivity(new Intent(ctx, MainActivity.class).putExtra(MainActivity.EXTRA_REFRESH, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    private void thumb(OutputStream out, String name) throws Exception {
        File dir = Config.dir(ctx);
        File f = name == null || dir == null ? null : new File(new File(dir, "wallpapers"), name);
        if (f == null || !f.isFile() || !f.getParentFile().equals(new File(dir, "wallpapers"))) {
            send(out, 404, "text/plain", new byte[0]);
            return;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= 320) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) {
            send(out, 404, "text/plain", new byte[0]);
            return;
        }
        ByteArrayOutputStream jpg = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 80, jpg);
        b.recycle();
        send(out, 200, "image/jpeg", jpg.toByteArray());
    }

    private byte[] page() throws Exception {
        try (InputStream in = ctx.getResources().openRawResource(R.raw.companion)) {
            return readBody(in, -1);
        }
    }

    private static void ok(OutputStream out, boolean ok) throws Exception {
        send(out, ok ? 200 : 400, "application/json", (ok ? "{\"ok\":true}" : "{\"ok\":false}").getBytes(StandardCharsets.UTF_8));
    }

    private static void json(OutputStream out, JSONObject o) throws Exception {
        send(out, 200, "application/json", o.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void send(OutputStream out, int status, String type, byte[] body) throws Exception {
        String head = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Error") + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String line(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int c; (c = in.read()) != -1; ) {
            if (c == '\n') return sb.toString().replace("\r", "");
            if (sb.length() > 8192) return null;
            sb.append((char) c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** length -1: to the end of the stream. */
    private static byte[] readBody(InputStream in, int length) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(0, Math.min(length, MAX_UPLOAD)));
        byte[] buf = new byte[16 * 1024];
        int left = length;
        while (length < 0 || left > 0) {
            int n = in.read(buf, 0, length < 0 ? buf.length : Math.min(buf.length, left));
            if (n < 0) break;
            out.write(buf, 0, n);
            left -= n;
        }
        return out.toByteArray();
    }

    private static Map<String, String> query(String q) throws Exception {
        Map<String, String> m = new HashMap<>();
        for (String pair : q.split("&")) {
            if (pair.isEmpty()) continue;
            int e = pair.indexOf('=');
            String k = URLDecoder.decode(e < 0 ? pair : pair.substring(0, e), "UTF-8");
            m.put(k, e < 0 ? "" : URLDecoder.decode(pair.substring(e + 1), "UTF-8"));
        }
        return m;
    }
}
