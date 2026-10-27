package com.gathin.tvhome;

import android.graphics.Color;

/**
 * Noirly's seven palettes (D:\Projects\noirly-ui, themes/index.ts) in light and dark, and the colours StudioHome
 * draws with, derived from each palette's five the way buildThemeCss does for the web apps.
 */
final class NoirlyPalette {
    /** id, name, then light bg, surface, text, accent, accent-ink, then the same five for dark. */
    static final String[][] THEMES = {
            {"blue", "Ocean Blue", "#fafaf9", "#ffffff", "#08080a", "#1d4ed8", "#ffffff",
                    "#050506", "#0c0c0e", "#f7f7f5", "#7ca6ff", "#06121f"},
            {"gold", "Warm Gold", "#faf8f2", "#fffef8", "#222222", "#8a6b00", "#ffffff",
                    "#0a0a08", "#121210", "#f5f3ea", "#ffe66d", "#222222"},
            {"forest", "Forest Green", "#f7faf8", "#ffffff", "#0a1a12", "#047857", "#ffffff",
                    "#040a07", "#0b1410", "#ecfdf5", "#34d399", "#052e1f"},
            {"coral", "Coral Ember", "#faf8f7", "#ffffff", "#1a0a08", "#c2410c", "#ffffff",
                    "#0a0605", "#140c0a", "#fff5f2", "#ff8a7a", "#2a0a06"},
            {"violet", "Soft Violet", "#f9f8fc", "#ffffff", "#120a1f", "#6d28d9", "#ffffff",
                    "#07050c", "#100d18", "#f5f0ff", "#a78bfa", "#1a0a2e"},
            {"teal", "Deep Teal", "#f6fbfb", "#ffffff", "#082120", "#0f766e", "#ffffff",
                    "#040c0b", "#0a1413", "#ecfeff", "#2dd4bf", "#042f2e"},
            {"rose", "Rose Quartz", "#faf7f8", "#ffffff", "#1a0810", "#be123c", "#ffffff",
                    "#0a0507", "#140a0e", "#fff1f4", "#fb7185", "#2a0610"},
    };
    static final String DEFAULT_ID = "gold";

    final String id;
    final boolean dark;
    final int bg, bgDeep, surface, surface2, text, text2, muted, hairline, hairlineStrong, glow;
    final int accent, accentInk, accentSoft;
    /** The card gradient's two ends (.surface), the chip's faint fill (.chip). */
    final int cardTop, cardBottom, chipFill;
    /** The ambient background's layers (styles.css, "Ambient page background"). */
    final int beam, blobAccent, blobAccentSoft, blobNeutral, gridLine, vignette, washTop, washBottom;
    final float grain;

    /** The palette for id (gold when unknown), light or dark. */
    NoirlyPalette(String id, boolean dark) {
        String[] t = find(id);
        this.id = t[0];
        this.dark = dark;
        int o = dark ? 7 : 2;
        bg = Color.parseColor(t[o]);
        surface = Color.parseColor(t[o + 1]);
        text = Color.parseColor(t[o + 2]);
        accent = Color.parseColor(t[o + 3]);
        accentInk = Color.parseColor(t[o + 4]);
        if (dark) {
            bgDeep = Color.BLACK;
            surface2 = mix(surface, text, 0.12f);
            text2 = alpha(text, 0.68f);
            muted = alpha(text, 0.49f);
            hairline = alpha(text, 0.09f);
            glow = alpha(text, 0.08f);
            accentSoft = alpha(accent, 0.14f);
            cardTop = mix(surface, text, 0.07f);
            cardBottom = mix(surface, text, 0.02f);
            beam = alpha(accent, 0.09f);
            blobAccent = alpha(accent, 0.14f);
            blobAccentSoft = alpha(accent, 0.08f);
            blobNeutral = alpha(text, 0.08f);
            gridLine = alpha(text, 0.07f);
            vignette = alpha(bgDeep, 0.62f);
            washTop = mix(bg, text, 0.05f);
            washBottom = bgDeep;
            grain = 0.045f;
        } else {
            bgDeep = mix(bg, text, 0.12f);
            surface2 = mix(surface, text, 0.08f);
            text2 = alpha(text, 0.66f);
            muted = alpha(text, 0.58f);
            hairline = alpha(text, 0.1f);
            glow = alpha(text, 0.06f);
            accentSoft = alpha(accent, 0.1f);
            cardTop = mix(surface, Color.WHITE, 0.9f);
            cardBottom = mix(surface, bg, 0.28f);
            beam = alpha(accent, 0.07f);
            blobAccent = alpha(accent, 0.1f);
            blobAccentSoft = alpha(accent, 0.06f);
            blobNeutral = alpha(text, 0.06f);
            gridLine = alpha(text, 0.055f);
            vignette = alpha(bgDeep, 0.32f);
            washTop = mix(bg, surface, 0.85f);
            washBottom = mix(bg, bgDeep, 0.7f);
            grain = 0.03f;
        }
        hairlineStrong = alpha(text, 0.18f);
        chipFill = alpha(text, 0.035f);
    }

    private static String[] find(String id) {
        for (String[] t : THEMES) if (t[0].equals(id)) return t;
        for (String[] t : THEMES) if (t[0].equals(DEFAULT_ID)) return t;
        return THEMES[0];
    }

    /** The accent a palette shows as its swatch in the Theme list, in this mode. */
    static int swatch(String[] theme, boolean dark) {
        return Color.parseColor(theme[dark ? 10 : 5]);
    }

    /** color-mix(in srgb, a (1 - f), b f). */
    static int mix(int a, int b, float f) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * f),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * f),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f));
    }

    static int alpha(int c, float a) {
        return (Math.round(a * 255) << 24) | (c & 0x00FFFFFF);
    }
}
