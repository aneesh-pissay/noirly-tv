package dev.noirly.tv.photos;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Google sign-in done on the phone. Android's own sign-in may not ask for the Photos Picker scope ("This OAuth client
 * is not permitted to request ..."), nor may the TV device flow, so this uses a Web application OAuth client:
 *
 * <ol>
 * <li>The TV shows Google's consent page as a QR code. Its state carries a one-time nonce and a random ntfy.sh topic
 * that only this sign-in knows; PKCE binds the code to a verifier that never leaves the TV.</li>
 * <li>After consent Google sends the phone to the hand-off page on GitHub Pages (REDIRECT_URI), which posts the code to
 * that topic. The TV reads the topic over HTTPS, so the phone needs no route to the TV: any network, even mobile data,
 * will do. A phone on a VPN, another Wi-Fi or behind AP isolation could not reach a receiver on the TV.</li>
 * <li>The TV trades the code (with the verifier and the client secret) for tokens and keeps the refresh token, so later
 * picks need no phone sign-in until Google expires it (about a week while the Cloud project is in Testing).</li>
 * </ol>
 *
 * The code alone is worth nothing to whoever reads the topic: without the verifier Google refuses it, and it expires in
 * minutes. The client id and secret come from oauth.properties at build time (never committed); see the README.
 */
final class PhoneSignIn {
    private static final String AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN = "https://oauth2.googleapis.com/token";
    /** The relay; ntfy topics are created by use, no account. */
    private static final String RELAY = "https://ntfy.sh/";
    private static final String PREFS = "auth";
    private static final int TIMEOUT_MS = 15_000;
    private static final long POLL_MS = 2_000;

    private final SharedPreferences prefs;
    private final SecureRandom random = new SecureRandom();
    private String nonce, verifier, topic;
    private long startedSec;

    PhoneSignIn(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** False until oauth.properties gave the build a client id, secret and redirect page. */
    static boolean configured() {
        return !BuildConfig.OAUTH_CLIENT_ID.isEmpty() && !BuildConfig.OAUTH_CLIENT_SECRET.isEmpty()
                && !BuildConfig.OAUTH_REDIRECT_URI.isEmpty();
    }

    /** A fresh access token from the kept refresh token, or null when the phone must sign in (again). */
    String accessToken() throws IOException {
        String refresh = prefs.getString("refresh", "");
        if (refresh.isEmpty()) return null;
        try {
            JSONObject reply = post(form("client_id", BuildConfig.OAUTH_CLIENT_ID,
                    "client_secret", BuildConfig.OAUTH_CLIENT_SECRET,
                    "refresh_token", refresh, "grant_type", "refresh_token"));
            return reply.getString("access_token");
        } catch (Refused e) {
            forget();   // invalid_grant: revoked, or the Testing week is over
            return null;
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    void forget() {
        prefs.edit().remove("refresh").apply();
    }

    /** A new sign-in: fresh nonce, verifier and topic. Returns the consent URL for the QR code. */
    String start() throws IOException {
        nonce = token(12);
        verifier = token(48);
        topic = "noirly-" + token(24);
        startedSec = System.currentTimeMillis() / 1000 - 5;
        return AUTH + "?" + form("client_id", BuildConfig.OAUTH_CLIENT_ID,
                "redirect_uri", BuildConfig.OAUTH_REDIRECT_URI,
                "response_type", "code",
                "scope", PickerApi.SCOPE,
                "access_type", "offline",
                "prompt", "consent",
                "state", nonce + "." + topic,
                "code_challenge", challenge(verifier),
                "code_challenge_method", "S256");
    }

    /**
     * Reads the topic until the hand-off page posts this sign-in's result, then trades the code for tokens and keeps
     * the refresh token. Returns the access token, or null when stillWanted turned false first. Worker thread.
     */
    String await(BooleanSupplier stillWanted, long timeoutMs) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (stillWanted.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new IOException("Sign-in timed out.");
            Map<String, String> result;
            try {
                result = poll();
            } catch (IOException e) {
                result = null;   // a dropped poll; the next one tries again
            }
            if (result != null) {
                android.util.Log.i("NoirlyPhotos", "sign-in: result arrived"
                        + (result.containsKey("error") ? " (error " + result.get("error") + ")" : ""));
                if (result.containsKey("error")) throw new IOException("Google said: " + result.get("error"));
                return exchange(result.get("code"));
            }
            Thread.sleep(POLL_MS);
        }
        return null;
    }

    /** The topic's messages since this sign-in began; the first carrying our nonce, as a query map, or null. */
    private Map<String, String> poll() throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(RELAY + topic + "/json?poll=1&since=" + startedSec)
                .openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            if (c.getResponseCode() / 100 != 2) throw new IOException("relay " + c.getResponseCode());
            for (String line : read(c.getInputStream()).split("\n")) {
                if (line.trim().isEmpty()) continue;
                JSONObject event = new JSONObject(line);
                if (!"message".equals(event.optString("event"))) continue;
                Map<String, String> q = query(event.optString("message"));
                if (q.getOrDefault("state", "").startsWith(nonce + ".")
                        && (q.containsKey("code") || q.containsKey("error"))) {
                    return q;
                }
            }
            return null;
        } catch (JSONException e) {
            throw new IOException(e);
        } finally {
            c.disconnect();
        }
    }

    private String exchange(String code) throws IOException {
        try {
            JSONObject reply = post(form("client_id", BuildConfig.OAUTH_CLIENT_ID,
                    "client_secret", BuildConfig.OAUTH_CLIENT_SECRET,
                    "code", code, "code_verifier", verifier,
                    "redirect_uri", BuildConfig.OAUTH_REDIRECT_URI,
                    "grant_type", "authorization_code"));
            String refresh = reply.optString("refresh_token", "");
            if (!refresh.isEmpty()) prefs.edit().putString("refresh", refresh).apply();
            return reply.getString("access_token");
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private static Map<String, String> query(String q) throws IOException {
        Map<String, String> out = new HashMap<>();
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            out.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
        }
        return out;
    }

    private JSONObject post(String body) throws IOException, JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(TOKEN).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream in = code / 100 == 2 ? c.getInputStream() : c.getErrorStream();
            String text = read(in);
            if (code == 400 || code == 401) throw new Refused(text);
            if (code / 100 != 2) throw new IOException("token " + code + " " + text);
            return new JSONObject(text);
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = s.read(buffer)) != -1; ) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String form(String... pairs) throws IOException {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (b.length() > 0) b.append('&');
            b.append(URLEncoder.encode(pairs[i], "UTF-8")).append('=').append(URLEncoder.encode(pairs[i + 1], "UTF-8"));
        }
        return b.toString();
    }

    /** Random URL-safe text: [A-Za-z0-9_-], which is also what an ntfy topic may hold. */
    private String token(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static String challenge(String verifier) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.encodeToString(digest, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** Google refused a token request: the refresh token or the code is no longer good. */
    static final class Refused extends IOException {
        Refused(String why) {
            super(why);
        }
    }
}
