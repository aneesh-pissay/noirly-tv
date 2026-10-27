package dev.noirly.tv.photos;

import android.content.Context;
import android.content.SharedPreferences;

/** The slideshow's two options, set with the buttons on the app's screen: seconds per photo, and shuffle. */
final class Options {
    /** The speeds the Speed button steps through, in seconds. */
    static final int[] SPEEDS = {10, 30, 60, 300};

    private Options() { }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("slideshow", Context.MODE_PRIVATE);
    }

    static int seconds(SharedPreferences p) {
        return p.getInt("seconds", SPEEDS[1]);
    }

    static boolean shuffle(SharedPreferences p) {
        return p.getBoolean("shuffle", true);
    }

    /** The next speed after the current one, round again after the slowest. */
    static int nextSpeed(int now) {
        for (int i = 0; i < SPEEDS.length; i++) if (SPEEDS[i] == now) return SPEEDS[(i + 1) % SPEEDS.length];
        return SPEEDS[0];
    }

    static String speedWords(int seconds) {
        return seconds < 60 ? seconds + " s" : seconds / 60 + " min";
    }
}
