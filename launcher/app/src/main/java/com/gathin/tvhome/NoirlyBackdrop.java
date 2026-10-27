package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.DisplayMetrics;

import java.util.Random;

/**
 * Noirly's ambient page background (noirly-ui styles.css, "Ambient page background"), drawn once into one bitmap at
 * half the screen's size: the top-lit wash, the blueprint grid fading out from the top, three soft accent and
 * neutral blobs, the vignette and the grain. The web blobs drift; here they stand still, so Home costs nothing
 * after the first frame.
 */
final class NoirlyBackdrop {
    private NoirlyBackdrop() { }

    static Bitmap draw(NoirlyPalette p, DisplayMetrics m) {
        int w = Math.max(1, m.widthPixels / 2), h = Math.max(1, m.heightPixels / 2);
        float unit = m.density / 2;   // one dp in this bitmap
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

        // Wash: light gathering at the top, shadow at the bottom edge, and the accent beam from above.
        paint.setShader(new LinearGradient(0, 0, 0, h, new int[]{p.washTop, p.page, p.page, p.washBottom},
                new float[]{0f, 0.38f, 0.64f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(ellipse(w * 0.5f, -h * 0.06f, w * 0.78f, h * 0.42f,
                new int[]{p.beam, transparent(p.beam)}, new float[]{0f, 0.72f}));
        c.drawRect(0, 0, w, h, paint);

        // Blueprint grid, 76 dp, masked so it fades out before the middle of the page.
        int layer = c.saveLayer(0, 0, w, h, null);
        Paint line = new Paint();
        line.setColor(p.gridLine);
        line.setStrokeWidth(Math.max(1f, unit));
        float step = 76 * unit;
        for (float x = (w / 2f) % step; x < w; x += step) c.drawLine(x, 0, x, h, line);
        for (float y = 0; y < h; y += step) c.drawLine(0, y, w, y, line);
        Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        mask.setShader(ellipse(w * 0.5f, 0, w * 1.12f, h * 0.78f,
                new int[]{Color.BLACK, 0x73000000, Color.TRANSPARENT}, new float[]{0f, 0.45f, 0.76f}));
        c.drawRect(0, 0, w, h, mask);
        c.restoreToCount(layer);

        // The three blobs, where the web puts them (sizes in vw, offsets in vw and vh).
        blob(c, paint, -0.24f * w, -0.34f * h, 0.66f * w, p.blobAccent);
        blob(c, paint, w - 0.52f * w + 0.20f * w, 0.22f * h, 0.52f * w, p.blobNeutral);
        blob(c, paint, 0.24f * w, h + 0.30f * h - 0.56f * w, 0.56f * w, p.blobAccentSoft);

        // Vignette: pulls the eye back to the middle.
        paint.setShader(ellipse(w * 0.5f, h * 0.42f, w * 1.18f, h * 0.76f,
                new int[]{transparent(p.vignette), transparent(p.vignette), p.vignette}, new float[]{0f, 0.42f, 1f}));
        c.drawRect(0, 0, w, h, paint);

        grain(out, p.grain);
        return out;
    }

    /** A radial blob of size across with its top left at (x, y), fading from colour to nothing at its edge. */
    private static void blob(Canvas c, Paint paint, float x, float y, float size, int color) {
        float r = size / 2;
        paint.setShader(new RadialGradient(x + r, y + r, r, color, transparent(color), Shader.TileMode.CLAMP));
        c.drawCircle(x + r, y + r, r, paint);
    }

    /** CSS's radial-gradient(rx ry at cx cy, ...): a circle of radius 1 stretched to the ellipse. */
    private static Shader ellipse(float cx, float cy, float rx, float ry, int[] colors, float[] stops) {
        RadialGradient g = new RadialGradient(0, 0, 1, colors, stops, Shader.TileMode.CLAMP);
        Matrix matrix = new Matrix();
        matrix.setScale(rx, ry);
        matrix.postTranslate(cx, cy);
        g.setLocalMatrix(matrix);
        return g;
    }

    /**
     * Fine noise at the web's grain opacity, so the big gradients do not band on the TV. It nudges each pixel
     * lighter or darker rather than towards grey, so the colour underneath stays clean.
     */
    private static void grain(Bitmap b, float amount) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        Random random = new Random(7);
        for (int i = 0; i < px.length; i++) {
            int n = Math.round((random.nextInt(256) - 128) * amount), c = px[i];
            px[i] = Color.rgb(clamp(Color.red(c) + n), clamp(Color.green(c) + n), clamp(Color.blue(c) + n));
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static int transparent(int c) {
        return c & 0x00FFFFFF;
    }
}
