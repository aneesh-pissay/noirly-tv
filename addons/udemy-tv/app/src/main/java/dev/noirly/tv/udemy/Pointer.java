package dev.noirly.tv.udemy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * The remote's pointer over the page: a cream dot in a gold ring (Noirly's Warm Gold), drawn on top and never
 * touched, since every click is synthesised by UdemyActivity at its position.
 */
final class Pointer extends View {
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float radius;
    float x, y;

    Pointer(Context ctx) {
        super(ctx);
        float dp = ctx.getResources().getDisplayMetrics().density;
        radius = 11 * dp;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3 * dp);
        ring.setColor(0xFFFFE66D);
        dot.setColor(0xFFF5F3EA);
        shadow.setStyle(Paint.Style.STROKE);
        shadow.setStrokeWidth(6 * dp);
        shadow.setColor(0x66000000);
        setFocusable(false);
        setClickable(false);
    }

    /** Moves to (x, y), kept inside the view. */
    void moveTo(float nx, float ny) {
        x = Math.max(0, Math.min(getWidth() - 1, nx));
        y = Math.max(0, Math.min(getHeight() - 1, ny));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawCircle(x, y, radius, shadow);
        c.drawCircle(x, y, radius, ring);
        c.drawCircle(x, y, radius / 3, dot);
    }
}
