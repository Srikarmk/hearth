package io.github.srikarmk.hearth;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The ambience scenes: freely licensed clips from Wikimedia Commons, each fetched by the TV the first time it is
 * picked and kept in the theme directory (scenes/<id>.webm). The credit shows on screen when a scene comes up.
 * The only network use is that one download per scene, from upload.wikimedia.org.
 */
final class Ambience {

    static final class Scene {
        final String id;
        final int label;
        final String url;
        final String credit;

        Scene(String id, int label, String url, String credit) {
            this.id = id;
            this.label = label;
            this.url = url;
            this.credit = credit;
        }
    }

    private static final String COMMONS = "https://upload.wikimedia.org/wikipedia/commons/";

    static final Scene[] SCENES = {
            new Scene("fire", R.string.scene_fire,
                    COMMONS + "b/bb/Fantastic-fireplace-fire-chimney-hearth-_background_-_texture_-_motion_graphics_-_free_video_library.webm",
                    "free video library · Wikimedia Commons · CC BY 3.0"),
            new Scene("rain", R.string.scene_rain,
                    COMMONS + "transcoded/2/2e/Radevormwald_-_Raindrops_on_a_window_10_%281%29_ies.webm/Radevormwald_-_Raindrops_on_a_window_10_%281%29_ies.webm.1080p.vp9.webm",
                    "Frank Vincentz · Wikimedia Commons · CC BY-SA 3.0"),
            new Scene("aquarium", R.string.scene_aquarium,
                    COMMONS + "transcoded/b/b5/Fish_tank_1_2014-12-27.ogv/Fish_tank_1_2014-12-27.ogv.1080p.vp9.webm",
                    "FASTILY · Wikimedia Commons · CC BY-SA 4.0"),
            new Scene("snow", R.string.scene_snow,
                    COMMONS + "transcoded/b/b5/Snow_on_Myrstigen.webm/Snow_on_Myrstigen.webm.1080p.vp9.webm",
                    "W.carter · Wikimedia Commons · CC BY-SA 4.0"),
            new Scene("ocean", R.string.scene_ocean,
                    COMMONS + "transcoded/2/24/Waves_crashing_on_rocks_off_Beach_4%2C_Kalaloch_Beach%2C_Washington_01.webm/Waves_crashing_on_rocks_off_Beach_4%2C_Kalaloch_Beach%2C_Washington_01.webm.1080p.vp9.webm",
                    "Joe Mabel · Wikimedia Commons · CC BY-SA 4.0"),
    };

    private static final String PREFS = "ambience";
    private static final String TAG = "Ambience";

    private Ambience() {}

    static Scene byId(String id) {
        for (Scene s : SCENES) if (s.id.equals(id)) return s;
        return SCENES[0];
    }

    static int index(Scene scene) {
        for (int i = 0; i < SCENES.length; i++) if (SCENES[i] == scene) return i;
        return 0;
    }

    static File file(Context ctx, Scene s) {
        File dir = Config.dir(ctx);
        return dir == null ? null : new File(new File(dir, "scenes"), s.id + ".webm");
    }

    static boolean ready(Context ctx, Scene s) {
        File f = file(ctx, s);
        return f != null && f.isFile() && f.length() > 0;
    }

    static Scene last(Context ctx) {
        return byId(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("scene", "fire"));
    }

    static void remember(Context ctx, Scene s) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("scene", s.id).apply();
    }

    /** The scene the idle screen and screensaver show: the last one picked if it is on the TV, else any that is. */
    static Scene forIdle(Context ctx) {
        Scene last = last(ctx);
        if (ready(ctx, last)) return last;
        for (Scene s : SCENES) if (ready(ctx, s)) return s;
        return null;
    }

    interface Listener {
        void onProgress(int percent);
        void onDone(boolean ok);
    }

    /** Fetches a scene on a background thread; the listener is called on the main thread. */
    static Thread download(Context ctx, Scene s, Listener listener) {
        Handler main = new Handler(Looper.getMainLooper());
        File target = file(ctx, s);
        Thread t = new Thread(() -> {
            boolean ok = false;
            File part = target == null ? null : new File(target.getPath() + ".part");
            HttpURLConnection c = null;
            try {
                if (part == null || (!part.getParentFile().isDirectory() && !part.getParentFile().mkdirs())) throw new java.io.IOException("no storage");
                c = (HttpURLConnection) new URL(s.url).openConnection();
                c.setRequestProperty("User-Agent", "Hearth (Fire TV home screen; https://github.com/Srikarmk/hearth)");
                c.setConnectTimeout(15_000);
                c.setReadTimeout(30_000);
                long total = c.getContentLengthLong();
                byte[] buf = new byte[64 * 1024];
                long got = 0;
                int lastPct = -1;
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                    for (int n; (n = in.read(buf)) > 0; ) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        out.write(buf, 0, n);
                        got += n;
                        int pct = total > 0 ? (int) (got * 100 / total) : -1;
                        if (pct != lastPct) {
                            lastPct = pct;
                            main.post(() -> listener.onProgress(pct));
                        }
                    }
                }
                ok = (total <= 0 || got == total) && part.renameTo(target);
            } catch (Exception e) {
                Log.w(TAG, "could not fetch " + s.id + ": " + e);
            } finally {
                if (c != null) c.disconnect();
                if (!ok && part != null) part.delete();
            }
            boolean result = ok;
            main.post(() -> listener.onDone(result));
        }, "ambience-" + s.id);
        t.start();
        return t;
    }
}
