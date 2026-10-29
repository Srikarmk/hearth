package io.github.srikarmk.hearth;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Live Dr. Malvek: each line written on the spot by Claude, from what just happened on the board. Off unless a
 * Claude API key has been put on the TV (install.sh CLAUDE_API_KEY=..., stored as claude_key.txt in the theme
 * directory). Any failure (no network, a bad key, a refusal, a slow answer) falls back to Malvek's written lines.
 *
 * Plain HttpURLConnection and org.json rather than the Java SDK: the SDK's OkHttp, Jackson and Kotlin runtime
 * would be several times the size of the whole launcher.
 */
final class MalvekLive {

    static final String KEY_FILE = "claude_key.txt";
    private static final String TAG = "MalvekLive";
    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-opus-5-5";
    private static final int TIMEOUT_MS = 8000;

    private static final String SYSTEM = "You are Dr. Malvek, Sovereign of Vyrnhold: an armored tyrant-genius playing chess "
            + "against a guest on a living-room TV. You are grandiose, theatrical and vain, you speak of yourself in the third "
            + "person, and you treat every move as proof of your destiny. You are an original character: never mention comics, "
            + "films or any other villain. Keep it playful and family-friendly: no profanity, no real threats, nothing about "
            + "the player's appearance or identity. Reply with one or two sentences, at most 30 words, as plain text with no "
            + "quotation marks, emoji or stage directions. React to the specific moment you are given, and never repeat a "
            + "line you said recently.";

    interface Callback {
        void onLine(String line);   // null: use a written line instead
    }

    private MalvekLive() {}

    /** The key on this TV, or null when live mode is off. */
    static String key(Context ctx) {
        File dir = Config.dir(ctx);
        File f = dir == null ? null : new File(dir, KEY_FILE);
        if (f == null || !f.isFile()) return null;
        try {
            String k = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            return k.isEmpty() ? null : k;
        } catch (Exception e) {
            return null;
        }
    }

    /** Asks for a line on a background thread; the callback runs on the main thread. */
    static void line(String apiKey, String moment, String situation, List<String> recent, Callback cb) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String result = null;
            try {
                result = request(apiKey, moment, situation, recent);
            } catch (Exception e) {
                Log.w(TAG, "no live line: " + e);
            }
            String line = result;
            main.post(() -> cb.onLine(line));
        }, "malvek-live").start();
    }

    private static String request(String apiKey, String moment, String situation, List<String> recent) throws Exception {
        StringBuilder prompt = new StringBuilder()
                .append("What just happened: ").append(moment).append('\n')
                .append("The game: ").append(situation).append('\n');
        if (!recent.isEmpty()) prompt.append("Lines you said recently: ").append(String.join(" | ", recent)).append('\n');
        prompt.append("Say your line.");

        JSONObject body = new JSONObject()
                .put("model", MODEL)
                .put("max_tokens", 2048)
                .put("fallbacks", "default")
                .put("output_config", new JSONObject().put("effort", "low"))
                .put("system", SYSTEM)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt.toString())));

        HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setDoOutput(true);
            c.setRequestProperty("content-type", "application/json");
            c.setRequestProperty("x-api-key", apiKey);
            c.setRequestProperty("anthropic-version", "2026-10-21");
            c.setRequestProperty("anthropic-beta", "server-side-fallback-2026-07-01");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = c.getResponseCode();
            if (status != 200) {
                // 401 bad key, 429 rate limit, 529/5xx overloaded: a written line is better than a retry mid-game.
                Log.w(TAG, "HTTP " + status + ": " + read(c.getErrorStream()));
                return null;
            }
            JSONObject res = new JSONObject(read(c.getInputStream()));
            String stop = res.optString("stop_reason");
            if ("refusal".equals(stop) || "max_tokens".equals(stop)) return null;
            JSONArray content = res.optJSONArray("content");
            StringBuilder text = new StringBuilder();
            for (int i = 0; content != null && i < content.length(); i++) {
                JSONObject block = content.getJSONObject(i);
                if ("text".equals(block.optString("type"))) text.append(block.optString("text"));
            }
            String line = text.toString().trim().replaceAll("^[\"“]|[\"”]$", "");
            return line.isEmpty() || line.length() > 280 ? null : line;
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            for (int n; (n = s.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
