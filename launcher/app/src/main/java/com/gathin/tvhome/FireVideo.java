package com.gathin.tvhome;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;

import java.io.File;

/**
 * The fireplace: a short fireplace clip (fireplace.webm in the theme directory, put there by install.sh)
 * looped without a visible seam. Two players take turns; over the last FADE_MS of each pass the other one
 * starts from the top and fades in over it, picture and sound together.
 */
public class FireVideo extends FrameLayout {

    static final String FILE_NAME = "fireplace.webm";
    private static final String TAG = "FireVideo";
    private static final int FADE_MS = 1500;
    private static final int POLL_MS = 100;

    private final TextureView[] views = new TextureView[2];
    private final MediaPlayer[] players = new MediaPlayer[2];
    private final boolean[] ready = new boolean[2];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private File file;
    private boolean sound;
    private boolean running;
    private int current;
    private long fadeStart = -1;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            tick();
            handler.postDelayed(this, POLL_MS);
        }
    };

    public FireVideo(Context context) {
        this(context, null);
    }

    public FireVideo(Context context, AttributeSet attrs) {
        super(context, attrs);
        for (int i = 0; i < 2; i++) {
            TextureView v = new TextureView(context);
            v.setAlpha(i == 0 ? 1f : 0f);
            final int index = i;
            v.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { prepare(index, st); }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { release(index); return true; }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
            });
            views[i] = v;
            addView(v, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
    }

    /** The clip on this TV, or null when install.sh has not put one there. */
    static File clip(Context ctx) {
        File dir = Config.dir(ctx);
        File f = dir == null ? null : new File(dir, FILE_NAME);
        return f != null && f.isFile() && f.length() > 0 ? f : null;
    }

    void play(File clip, boolean withSound) {
        file = clip;
        sound = withSound;
        running = true;
        for (int i = 0; i < 2; i++) {
            if (views[i].isAvailable() && players[i] == null) prepare(i, views[i].getSurfaceTexture());
        }
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(poll);
        release(0);
        release(1);
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    private void prepare(int i, SurfaceTexture st) {
        if (!running || file == null || players[i] != null) return;
        MediaPlayer mp = new MediaPlayer();
        players[i] = mp;
        ready[i] = false;
        try {
            mp.setDataSource(file.getPath());
            mp.setSurface(new Surface(st));
            mp.setVolume(0f, 0f);
            mp.setLooping(true);   // if a crossfade is ever missed, the clip still goes round
            mp.setOnPreparedListener(p -> {
                ready[i] = true;
                if (i == current && running) {
                    p.setVolume(volume(1f), volume(1f));
                    p.start();
                }
            });
            mp.setOnErrorListener((p, what, extra) -> {
                Log.w(TAG, "player " + i + " error " + what + "/" + extra);
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "cannot play " + file + ": " + e);
            release(i);
        }
    }

    private void release(int i) {
        MediaPlayer mp = players[i];
        players[i] = null;
        ready[i] = false;
        if (mp != null) {
            try {
                mp.release();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void tick() {
        int other = 1 - current;
        MediaPlayer cur = players[current], next = players[other];
        if (cur == null || !ready[current]) return;
        int duration = cur.getDuration();
        if (fadeStart < 0) {
            if (duration > FADE_MS * 2 && cur.getCurrentPosition() >= duration - FADE_MS && next != null && ready[other]) {
                fadeStart = android.os.SystemClock.uptimeMillis();
                next.seekTo(0);
                next.setVolume(0f, 0f);
                next.start();
                views[other].bringToFront();
            }
            return;
        }
        float f = Math.min(1f, (android.os.SystemClock.uptimeMillis() - fadeStart) / (float) FADE_MS);
        views[other].setAlpha(f);
        if (next != null) next.setVolume(volume(f), volume(f));
        cur.setVolume(volume(1f - f), volume(1f - f));
        if (f >= 1f) {
            cur.pause();
            cur.seekTo(0);
            views[current].setAlpha(0f);
            current = other;
            fadeStart = -1;
        }
    }

    private float volume(float v) {
        return sound ? v : 0f;
    }
}
