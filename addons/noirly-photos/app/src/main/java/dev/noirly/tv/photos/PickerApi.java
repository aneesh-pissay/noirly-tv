package dev.noirly.tv.photos;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Google Photos Picker API (developers.google.com/photos/picker): a session gives a pickerUri the owner opens on
 * their phone; once they are done the session says mediaItemsSet, and the picked items can be listed and
 * downloaded for 60 minutes. Every call blocks, so PhotosActivity makes them off the UI thread.
 */
final class PickerApi {
    static final String SCOPE = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly";
    private static final String BASE = "https://photospicker.googleapis.com/v1/";
    private static final int TIMEOUT_MS = 15_000;
    /** The most the picker lets the owner choose at once; each is about 0.5 MB at TV size. */
    static final int MAX_ITEMS = 300;

    private PickerApi() { }

    /** A picking session: where the phone goes, and how often and how long to wait for it. */
    static final class Session {
        final String id;
        final String pickerUri;
        final boolean itemsSet;
        final long pollMs;
        final long timeoutMs;

        Session(JSONObject o) {
            id = o.optString("id");
            pickerUri = o.optString("pickerUri");
            itemsSet = o.optBoolean("mediaItemsSet", false);
            JSONObject polling = o.optJSONObject("pollingConfig");
            pollMs = Math.max(2_000, seconds(polling != null ? polling.optString("pollInterval") : "", 5_000));
            timeoutMs = seconds(polling != null ? polling.optString("timeoutIn") : "", 30 * 60_000);
        }
    }

    /** One picked photo: its id and where to download it. Videos are left out. */
    static final class Item {
        final String id;
        final String baseUrl;

        Item(String id, String baseUrl) {
            this.id = id;
            this.baseUrl = baseUrl;
        }
    }

    static Session create(String token) throws IOException, JSONException {
        JSONObject body = new JSONObject().put("pickingConfig",
                new JSONObject().put("maxItemCount", String.valueOf(MAX_ITEMS)));
        return new Session(call("POST", BASE + "sessions", token, body.toString()));
    }

    static Session get(String token, String id) throws IOException, JSONException {
        return new Session(call("GET", BASE + "sessions/" + id, token, null));
    }

    /** The session's photos, every page. */
    static List<Item> photos(String token, String sessionId) throws IOException, JSONException {
        List<Item> out = new ArrayList<>();
        String page = "";
        do {
            String url = BASE + "mediaItems?pageSize=100&sessionId=" + URLEncoder.encode(sessionId, "UTF-8")
                    + (page.isEmpty() ? "" : "&pageToken=" + URLEncoder.encode(page, "UTF-8"));
            JSONObject reply = call("GET", url, token, null);
            JSONArray items = reply.optJSONArray("mediaItems");
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                JSONObject file = item.optJSONObject("mediaFile");
                if (!"PHOTO".equals(item.optString("type")) || file == null) continue;
                out.add(new Item(item.optString("id"), file.optString("baseUrl")));
            }
            page = reply.optString("nextPageToken", "");
        } while (!page.isEmpty());
        return out;
    }

    /** The photo scaled to fit the TV's screen (never larger than it was taken), as a JPEG file. */
    static void download(String token, Item item, int width, int height, File to) throws IOException {
        HttpURLConnection c = open("GET", item.baseUrl + "=w" + width + "-h" + height, token);
        try {
            if (c.getResponseCode() / 100 != 2) throw new IOException("download " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] buffer = new byte[16384];
                for (int n; (n = in.read(buffer)) != -1; ) out.write(buffer, 0, n);
            }
        } finally {
            c.disconnect();
        }
    }

    /** Tells Google the session is done; a failure here changes nothing for the owner. */
    static void delete(String token, String id) {
        try {
            HttpURLConnection c = open("DELETE", BASE + "sessions/" + id, token);
            c.getResponseCode();
            c.disconnect();
        } catch (IOException ignored) {
        }
    }

    private static JSONObject call(String method, String url, String token, String body)
            throws IOException, JSONException {
        HttpURLConnection c = open(method, url, token);
        try {
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            InputStream in = code / 100 == 2 ? c.getInputStream() : c.getErrorStream();
            String text = in != null ? read(in) : "";
            if (code == 401) throw new AuthException();
            if (code / 100 != 2) throw new IOException(method + " " + code + " " + text);
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String method, String url, String token) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = s.read(buffer)) != -1; ) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** "5s" or "1.5s" (protobuf Duration as JSON) in milliseconds, or the fallback. */
    static long seconds(String duration, long fallback) {
        if (duration == null || !duration.endsWith("s")) return fallback;
        try {
            return Math.round(Double.parseDouble(duration.substring(0, duration.length() - 1)) * 1000);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** The token expired or was revoked: ask Android for a new one and try again. */
    static final class AuthException extends IOException {
        AuthException() {
            super("not authorized");
        }
    }
}
