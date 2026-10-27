package dev.noirly.tv.photos;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/**
 * Noirly's Warm Gold, dark (noirly-ui themes/index.ts), and its three faces, as the Noirly TV launcher draws them:
 * Fraunces for titles, Hanken Grotesk for copy, JetBrains Mono in capitals for labels.
 */
final class Noirly {
    static final int BG = 0xFF0A0A08;
    static final int SURFACE = 0xFF121210;
    static final int SURFACE_2 = 0xFF2D2D2A;
    static final int TEXT = 0xFFF5F3EA;
    static final int TEXT_2 = 0xADF5F3EA;
    static final int MUTED = 0x7DF5F3EA;
    static final int HAIRLINE_STRONG = 0x2EF5F3EA;
    static final int ACCENT = 0xFFFFE66D;
    static final int ACCENT_INK = 0xFF222222;
    static final int ACCENT_SOFT = 0x24FFE66D;

    final Context ctx;
    final float dp;
    final Typeface display, body, mono;

    Noirly(Context ctx) {
        this.ctx = ctx;
        dp = ctx.getResources().getDisplayMetrics().density;
        display = ctx.getResources().getFont(R.font.noirly_fraunces_bold);
        body = ctx.getResources().getFont(R.font.noirly_hanken_regular);
        mono = ctx.getResources().getFont(R.font.noirly_mono_medium);
    }

    int px(float dps) {
        return Math.round(dps * dp);
    }

    TextView text(float sp, int color, Typeface face) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    TextView title(float sp) {
        TextView t = text(sp, TEXT, display);
        t.setLetterSpacing(-0.02f);
        return t;
    }

    TextView label(float sp, int color) {
        TextView t = text(sp, color, mono);
        t.setLetterSpacing(0.14f);
        t.setAllCaps(true);
        return t;
    }

    Drawable rounded(int fill, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(px(radiusDp));
        if (Color.alpha(stroke) > 0) g.setStroke(px(strokeDp), stroke);
        return g;
    }

    /**
     * Noirly's pill button: solid cream when it is the main action, a hairline ghost otherwise, and gold with the
     * accent ink while focused.
     */
    TextView button(String words, boolean primary, Runnable action) {
        TextView b = label(12, primary ? BG : TEXT);
        b.setText(words);
        b.setGravity(Gravity.CENTER);
        b.setPadding(px(22), px(12), px(22), px(12));
        Drawable normal = primary ? rounded(TEXT, 99, TEXT, 1) : rounded(0x09F5F3EA, 99, HAIRLINE_STRONG, 1);
        Drawable lit = rounded(ACCENT, 99, ACCENT, 1);
        b.setBackground(normal);
        b.setFocusable(true);
        b.setOnFocusChangeListener((v, f) -> {
            v.setBackground(f ? lit : normal);
            b.setTextColor(f ? ACCENT_INK : primary ? BG : TEXT);
            v.animate().scaleX(f ? 1.05f : 1f).scaleY(f ? 1.05f : 1f).setDuration(120).start();
        });
        b.setOnClickListener(v -> action.run());
        return b;
    }
}
