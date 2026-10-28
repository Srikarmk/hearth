package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The top bar's Wallpapers button: the theme's pictures (wallpaper.jpg and the wallpapers/ collection) as a grid
 * of thumbnails. OK picks one for Home. It is the same choice as Launcher settings > Wallpaper, stored the same
 * way, so Home rebuilds with it on return.
 */
public class WallpaperPickerActivity extends Activity {

    private static final int COLUMNS = 4;
    private static final int THUMB_W = 384, THUMB_H = 216;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newFixedThreadPool(2);
    /** Thumbnails in RGB_565 at 384x216 are 166 KB each; 24 MB holds about 150. */
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private static final class Option {
        final File file;
        final String label;
        /** What goes in WALLPAPER_CHOICE_KEY: a file name, "none", or null for the theme's own choice. */
        final String choice;

        Option(File file, String label, String choice) {
            this.file = file;
            this.label = label;
            this.choice = choice;
        }
    }

    private final List<Option> options = new ArrayList<>();
    private SharedPreferences choices;
    private float density;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);

        File dir = Config.dir(this);
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) options.add(new Option(single, getString(R.string.wallpapers_theme), single.getName()));
        for (File f : WallpaperRotation.images(dir == null ? null : new File(dir, "wallpapers"))) {
            options.add(new Option(f, prettyName(f.getName()), f.getName()));
        }
        options.add(new Option(null, getString(R.string.wallpapers_none), "none"));

        setContentView(buildLayout());
    }

    /** "spider-man_noir.jpg" -> "Spider man noir". */
    static String prettyName(String file) {
        String base = file.contains(".") ? file.substring(0, file.lastIndexOf('.')) : file;
        base = base.replaceAll("^\\d+[-_ ]*", "").replace('_', ' ').replace('-', ' ').trim();
        if (base.isEmpty()) return file;
        return Character.toUpperCase(base.charAt(0)) + base.substring(1);
    }

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xF20B0A10);
        int pad = dp(56);
        root.setPadding(pad, dp(44), pad, 0);

        TextView title = text(R.string.font_light, 40, 0xFFF2EEF8);
        title.setText(R.string.wallpapers);
        root.addView(title);
        TextView hint = text(R.string.font_regular, 15, 0x8CFFFFFF);
        hint.setText(R.string.wallpapers_hint);
        root.addView(hint);

        RecyclerView grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        grid.setPadding(0, dp(28), 0, dp(40));
        grid.setAdapter(new Adapter());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(grid, lp);
        grid.post(() -> {
            RecyclerView.ViewHolder first = grid.findViewHolderForAdapterPosition(currentIndex());
            if (first != null) first.itemView.requestFocus();
            else if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
        });
        return root;
    }

    private int currentIndex() {
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        for (int i = 0; i < options.size(); i++) {
            String c = options.get(i).choice;
            if (current == null ? i == 0 : current.equals(c)) return i;
        }
        return 0;
    }

    private TextView text(int font, float sp, int color) {
        TextView t = new TextView(this);
        t.setTypeface(UiFont.get(this, font));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private void pick(Option o) {
        SharedPreferences.Editor e = choices.edit();
        if (o.choice == null) e.remove(Config.WALLPAPER_CHOICE_KEY);
        else e.putString(Config.WALLPAPER_CHOICE_KEY, o.choice);
        e.putLong(Config.SETTINGS_REVISION_KEY, choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
        finish();
    }

    private final class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView label;
        final View frame;
        String key;

        Holder(LinearLayout cell, View frame, ImageView image, TextView label) {
            super(cell);
            this.frame = frame;
            this.image = image;
            this.label = label;
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout cell = new LinearLayout(WallpaperPickerActivity.this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setFocusable(true);
            int m = dp(12);
            cell.setPadding(m, m, m, m);

            FrameLayout frame = new FrameLayout(WallpaperPickerActivity.this);
            GradientDrawable plate = new GradientDrawable();
            plate.setColor(0xFF1B1824);
            plate.setCornerRadius(dp(14));
            frame.setBackground(plate);
            frame.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(14));
                }
            });
            frame.setClipToOutline(true);
            ImageView image = new ImageView(WallpaperPickerActivity.this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            int width = (parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight()) / COLUMNS - 2 * m;
            cell.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(dp(90), width * 9 / 16)));

            TextView label = text(R.string.font_regular, 16, 0xCCFFFFFF);
            label.setSingleLine(true);
            label.setPadding(dp(4), dp(10), 0, 0);
            cell.addView(label);

            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(dp(14));
            ring.setStroke(dp(3), 0xFFFFFFFF);
            cell.setOnFocusChangeListener((v, focused) -> {
                frame.setForeground(focused ? ring : null);
                v.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f).setDuration(140).start();
                label.setTextColor(focused ? 0xFFFFFFFF : 0xCCFFFFFF);
            });
            return new Holder(cell, frame, image, label);
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            Option o = options.get(position);
            boolean current = position == currentIndex();
            h.label.setText(current ? "✓  " + o.label : o.label);
            h.itemView.setOnClickListener(v -> pick(o));
            h.image.setImageBitmap(null);
            h.key = o.file == null ? null : o.file.getPath();
            if (h.key == null) return;
            Bitmap cached = thumbs.get(h.key);
            if (cached != null) {
                h.image.setImageBitmap(cached);
                return;
            }
            String key = h.key;
            decoder.execute(() -> {
                Bitmap b = decode(o.file);
                if (b == null) return;
                handler.post(() -> {
                    thumbs.put(key, b);
                    if (key.equals(h.key)) h.image.setImageBitmap(b);
                });
            });
        }

        @Override
        public int getItemCount() {
            return options.size();
        }
    }

    private static Bitmap decode(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= THUMB_W && o.outHeight / (sample * 2) >= THUMB_H) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        decoder.shutdownNow();
        super.onDestroy();
    }
}
