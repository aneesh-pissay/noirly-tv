package dev.noirly.tv.photos;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The picked photos full screen, one every few seconds (Options: speed and shuffle), changed at once with no fade or
 * zoom. Over them, at the bottom left on a soft dark edge: the weather from the Weather add-on, the clock and the date,
 * moved a little every minute so nothing burns in. Pictures are decoded off the UI thread at the screen's size, one at
 * a time, for 1 GB TVs.
 */
final class Slideshow extends FrameLayout {
    /** The Weather add-on's read-only weather now (its NowProvider); missing add-on: no weather line. */
    private static final Uri WEATHER_NOW = Uri.parse("content://io.github.sumyabazar1.weather.now/now");
    private static final long MINUTE_MS = 60_000, WEATHER_MS = 15 * MINUTE_MS;

    private final ImageView view;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable next = this::advance;
    private final Runnable drift = this::drift;
    private final Runnable weatherTick = this::askWeather;
    private final Random random = new Random();
    private final int width, height;
    private final long showMs;
    private final List<File> photos;
    private final LinearLayout corner;
    private final ImageView weatherIcon;
    private final TextView weatherText;
    private final Noirly n;
    private int index = -1;
    private boolean running;

    Slideshow(Context ctx) {
        super(ctx);
        n = new Noirly(ctx);
        setBackgroundColor(Noirly.BG);
        DisplayMetrics m = ctx.getResources().getDisplayMetrics();
        width = m.widthPixels;
        height = m.heightPixels;
        view = new ImageView(ctx);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        addView(view, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        SharedPreferences o = Options.prefs(ctx);
        showMs = Options.seconds(o) * 1000L;
        photos = new Library(ctx).photos();
        if (Options.shuffle(o)) Collections.shuffle(photos, random);

        // The soft dark edge along the bottom, so the clock reads on a bright photo.
        View edge = new View(ctx);
        edge.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xD9000000, 0x66000000, 0x00000000}));
        addView(edge, new LayoutParams(LayoutParams.MATCH_PARENT, height * 2 / 5, Gravity.BOTTOM));

        corner = new LinearLayout(ctx);
        corner.setOrientation(LinearLayout.VERTICAL);
        LinearLayout weather = new LinearLayout(ctx);
        weather.setGravity(Gravity.CENTER_VERTICAL);
        weatherIcon = new ImageView(ctx);
        weather.addView(weatherIcon, new LinearLayout.LayoutParams(n.px(28), n.px(28)));
        weatherText = n.text(16, 0xE6F5F3EA, n.body);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        wlp.leftMargin = n.px(10);
        weather.addView(weatherText, wlp);
        weather.setVisibility(GONE);
        corner.addView(weather);
        TextClock clock = new TextClock(ctx);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        style(clock, 72, Noirly.TEXT, n.display);
        clock.setLetterSpacing(-0.03f);
        corner.addView(clock);
        TextClock date = new TextClock(ctx);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        style(date, 13, 0xE6F5F3EA, n.mono);
        date.setLetterSpacing(0.18f);
        date.setAllCaps(true);
        corner.addView(date);
        LayoutParams clp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        clp.leftMargin = n.px(56);
        clp.bottomMargin = n.px(44);
        addView(corner, clp);

        if (photos.isEmpty()) {
            TextView empty = n.text(18, Noirly.TEXT_2, n.body);
            empty.setText("No photos yet. Open Noirly Photos and choose some from Google Photos.");
            addView(empty, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        }
    }

    private static void style(TextView t, float sp, int color, android.graphics.Typeface face) {
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
    }

    void start() {
        if (running) return;
        running = true;
        advance();
        main.postDelayed(drift, MINUTE_MS);
        askWeather();
    }

    void stop() {
        running = false;
        main.removeCallbacks(next);
        main.removeCallbacks(drift);
        main.removeCallbacks(weatherTick);
    }

    /** D-pad Left and Right: the photo before or after, at once. */
    void skip(int by) {
        if (photos.isEmpty()) return;
        main.removeCallbacks(next);
        index = Math.floorMod(index + by - 1, photos.size());
        advance();
    }

    void release() {
        stop();
        io.shutdownNow();
    }

    private void advance() {
        if (!running || photos.isEmpty()) return;
        index = (index + 1) % photos.size();
        File file = photos.get(index);
        io.execute(() -> {
            Bitmap b = decode(file);
            main.post(() -> {
                if (running && b != null) view.setImageBitmap(b);
            });
        });
        main.postDelayed(next, showMs);
    }

    /** A few dp in any direction each minute, against burn-in. */
    private void drift() {
        corner.setTranslationX(n.px(random.nextInt(25) - 12));
        corner.setTranslationY(n.px(random.nextInt(17) - 8));
        main.postDelayed(drift, MINUTE_MS);
    }

    /** The weather now from the Weather add-on, off the UI thread; hidden while the add-on has none. */
    private void askWeather() {
        main.removeCallbacks(weatherTick);
        main.postDelayed(weatherTick, WEATHER_MS);
        io.execute(() -> {
            String words = null;
            int icon = 0;
            try (Cursor c = getContext().getContentResolver().query(WEATHER_NOW, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    words = c.getInt(c.getColumnIndexOrThrow("temp")) + "°  " + c.getString(c.getColumnIndexOrThrow("sky"));
                    int code = c.getColumnIndex("code"), day = c.getColumnIndex("day");
                    if (code >= 0) icon = weatherIcon(c.getInt(code), day < 0 || c.getInt(day) == 1);
                }
            } catch (RuntimeException ignored) {
                // no Weather add-on, or one without its provider
            }
            String w = words;
            int i = icon;
            main.post(() -> {
                if (i != 0) weatherIcon.setImageResource(i);
                weatherIcon.setVisibility(i != 0 ? VISIBLE : GONE);
                weatherText.setText(w != null ? w : "");
                ((View) weatherText.getParent()).setVisibility(w != null ? VISIBLE : GONE);
            });
        });
    }

    /** The same icons as the Noirly TV bar, by WMO weather code. */
    static int weatherIcon(int code, boolean day) {
        if (code <= 1) return day ? R.drawable.ic_wx_sun : R.drawable.ic_wx_moon;
        if (code == 2) return day ? R.drawable.ic_wx_partly_day : R.drawable.ic_wx_partly_night;
        if (code == 45 || code == 48) return R.drawable.ic_wx_fog;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ic_wx_snow;
        if (code >= 95) return R.drawable.ic_wx_thunder;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return R.drawable.ic_wx_rain;
        return R.drawable.ic_wx_cloud;
    }

    /** The photo at no more than the screen's size, so a large one costs what a screenful does. */
    private Bitmap decode(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= width || o.outHeight / (sample * 2) >= height) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }
}
