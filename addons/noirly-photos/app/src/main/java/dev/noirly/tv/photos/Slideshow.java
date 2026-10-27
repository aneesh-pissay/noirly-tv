package dev.noirly.tv.photos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The picked photos full screen, one every SHOW_MS: each fades in over the last and drifts in slowly. Pictures are
 * decoded off the UI thread at the screen's size, and only two are held at a time, for 1 GB TVs.
 */
final class Slideshow extends FrameLayout {
    private static final long SHOW_MS = 9_000, FADE_MS = 1_400;

    private final ImageView[] views = new ImageView[2];
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable next = this::advance;
    private final int width, height;
    private List<File> photos;
    private int index = -1, front;
    private boolean running;

    Slideshow(Context ctx) {
        super(ctx);
        setBackgroundColor(Noirly.BG);
        DisplayMetrics m = ctx.getResources().getDisplayMetrics();
        width = m.widthPixels;
        height = m.heightPixels;
        for (int i = 0; i < 2; i++) {
            ImageView v = new ImageView(ctx);
            v.setScaleType(ImageView.ScaleType.FIT_CENTER);
            v.setAlpha(0f);
            addView(v, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            views[i] = v;
        }
        photos = new Library(ctx).photos();
        if (photos.isEmpty()) {
            Noirly n = new Noirly(ctx);
            TextView empty = n.text(18, Noirly.TEXT_2, n.body);
            empty.setText("No photos yet. Open Noirly Photos and choose some from Google Photos.");
            addView(empty, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        }
    }

    void start() {
        if (running || photos.isEmpty()) return;
        running = true;
        advance();
    }

    void stop() {
        running = false;
        main.removeCallbacks(next);
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
                if (running) show(b);
            });
        });
        main.postDelayed(next, SHOW_MS);
    }

    private void show(Bitmap b) {
        if (b == null) return;
        ImageView in = views[1 - front], out = views[front];
        front = 1 - front;
        in.setImageBitmap(b);
        in.setScaleX(1f);
        in.setScaleY(1f);
        in.bringToFront();
        in.animate().alpha(1f).setDuration(FADE_MS).start();
        in.animate().scaleX(1.06f).scaleY(1.06f).setDuration(SHOW_MS + FADE_MS).start();
        out.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> out.setImageDrawable(null)).start();
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
