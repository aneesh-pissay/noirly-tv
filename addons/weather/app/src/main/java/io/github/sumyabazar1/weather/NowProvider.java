package io.github.sumyabazar1.weather;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The weather now for a home screen, read-only: one row with the place, the temperature in the page's unit, the
 * words for the sky and when it was read. A query older than {@link Forecast#REFRESH_MS} (or
 * {@link Forecast#RETRY_MS} after a failure) also starts one read of Open-Meteo, and the provider notifies
 * {@link #URI} when it lands; nothing runs unless a home screen asks. No row until a city is chosen in the app.
 */
public final class NowProvider extends ContentProvider {
    static final Uri URI = Uri.parse("content://io.github.sumyabazar1.weather.now/now");
    static final String PREFS = "now";
    /** code is the WMO weather code (rain already turned into snow where it is cold), day 1 or 0, for an icon. */
    private static final String[] COLUMNS = {"place", "temp", "unit", "sky", "updated", "code", "day"};

    private final AtomicBoolean reading = new AtomicBoolean();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        Context ctx = getContext();
        SharedPreferences place = ctx.getSharedPreferences(WeatherActivity.PREFS, Context.MODE_PRIVATE);
        MatrixCursor out = new MatrixCursor(COLUMNS);
        if (!place.contains("name")) return out;
        SharedPreferences now = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long age = System.currentTimeMillis() - now.getLong("updated", 0);
        long tried = System.currentTimeMillis() - now.getLong("tried", 0);
        if (age > Forecast.REFRESH_MS && tried > Forecast.RETRY_MS) read(ctx, place, now);
        if (now.contains("code")) {
            boolean fahrenheit = "f".equalsIgnoreCase(ctx.getString(R.string.temperature_unit).trim());
            double tempC = Double.longBitsToDouble(now.getLong("tempC", 0));
            out.addRow(new Object[]{place.getString("name", ""), Forecast.degrees(tempC, fahrenheit),
                    fahrenheit ? "F" : "C",
                    ctx.getString(Forecast.sky(now.getInt("code", 3), now.getBoolean("day", true))),
                    now.getLong("updated", 0), now.getInt("code", 3), now.getBoolean("day", true) ? 1 : 0});
        }
        return out;
    }

    private void read(Context ctx, SharedPreferences place, SharedPreferences now) {
        if (!reading.compareAndSet(false, true)) return;
        now.edit().putLong("tried", System.currentTimeMillis()).apply();
        double lat = Double.longBitsToDouble(place.getLong("lat", 0));
        double lon = Double.longBitsToDouble(place.getLong("lon", 0));
        new Thread(() -> {
            try {
                keep(ctx, Forecast.fetch(lat, lon, Forecast.MIN_DAYS));
            } catch (Exception ignored) {
                // offline: the last reading stays, and the next query after RETRY_MS tries again
            } finally {
                reading.set(false);
            }
        }, "weather-now").start();
    }

    /** Keeps a reading for the home screen and tells it; the page calls this too, so a new city shows at once. */
    static void keep(Context ctx, Forecast.Report r) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("tempC", Double.doubleToRawLongBits(r.tempC))
                .putInt("code", r.code)
                .putBoolean("day", r.day)
                .putLong("updated", System.currentTimeMillis())
                .apply();
        ctx.getContentResolver().notifyChange(URI, null);
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }
}
