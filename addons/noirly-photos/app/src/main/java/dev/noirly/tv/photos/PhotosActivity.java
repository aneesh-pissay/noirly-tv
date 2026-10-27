package dev.noirly.tv.photos;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Noirly Photos: choose photos from Google Photos on your phone, keep them on the TV, show them full screen or as
 * the screensaver. The phone does both steps by QR code: it signs in to Google (PhoneSignIn, only when the kept
 * sign-in has run out), then picks in Google Photos (the Photos Picker API), and the TV downloads what was picked.
 */
public final class PhotosActivity extends Activity {
    /** Shown when sign-in is not set up or Google refuses it, so the owner knows what Google Cloud needs. */
    private static final String SETUP_HINT = "This needs a Web application OAuth client in Google Cloud whose "
            + "redirect URI is the hand-off page, with its id and secret in oauth.properties when the app is built, "
            + "the Photos Picker API enabled and your account a test user. See addons/noirly-photos/README.md.";
    /** How long the sign-in QR code waits for the phone. */
    private static final long SIGN_IN_MS = 10 * 60_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Noirly n;
    private Library library;
    private TextView title, words;
    private LinearLayout buttons;
    /** The slideshow options (speed, shuffle), a quieter row under the buttons while photos are kept. */
    private LinearLayout extras;
    private FrameLayout side;
    private PhoneSignIn signIn;
    /** The pick in progress; set to null (or replaced) to stop its worker. */
    private volatile Thread worker;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        n = new Noirly(this);
        library = new Library(this);
        signIn = new PhoneSignIn(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Noirly.BG);
        root.setClipChildren(false);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        // A focused button grows a little; nothing above it may cut that off.
        column.setClipChildren(false);
        TextView eyebrow = n.label(11, Noirly.MUTED);
        eyebrow.setText("Noirly Photos");
        column.addView(eyebrow);
        title = n.title(38);
        LinearLayout.LayoutParams tlp = wrap();
        tlp.topMargin = n.px(14);
        column.addView(title, tlp);
        words = n.text(16, Noirly.TEXT_2, n.body);
        words.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(n.px(440), ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = n.px(14);
        column.addView(words, wlp);
        buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setClipChildren(false);
        LinearLayout.LayoutParams blp = wrap();
        blp.topMargin = n.px(28);
        column.addView(buttons, blp);
        extras = new LinearLayout(this);
        extras.setOrientation(LinearLayout.HORIZONTAL);
        extras.setClipChildren(false);
        LinearLayout.LayoutParams xlp = wrap();
        xlp.topMargin = n.px(14);
        column.addView(extras, xlp);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
        clp.leftMargin = n.px(56);
        root.addView(column, clp);

        side = new FrameLayout(this);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
        slp.rightMargin = n.px(56);
        root.addView(side, slp);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (worker == null) showLibrary();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker = null;
    }

    // ---- states ----

    /** What is on the TV now, and what can be done with it. */
    private void showLibrary() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        List<File> photos = library.photos();
        side.removeAllViews();
        buttons.removeAllViews();
        extras.removeAllViews();
        if (photos.isEmpty()) {
            title.setText("Your Google Photos,\non the TV");
            words.setText("Choose albums or photos on your phone and they are kept on the TV for a slideshow "
                    + "and the screensaver. Only what you pick is shared, and only with this TV.");
            addButton("Choose photos", true, this::choose).requestFocus();
        } else {
            title.setText(photos.size() + (photos.size() == 1 ? " photo" : " photos"));
            words.setText("On this TV and ready, with or without the internet. Choose again to replace them; "
                    + "they also play as the screensaver (Noirly Photos in the TV's screensaver settings).");
            addButton("Slideshow", true, () -> startActivity(new Intent(this, SlideshowActivity.class)))
                    .requestFocus();
            android.content.SharedPreferences o = Options.prefs(this);
            int secs = Options.seconds(o);
            addTo(extras, "Every " + Options.speedWords(secs), () -> {
                o.edit().putInt("seconds", Options.nextSpeed(secs)).apply();
                showLibrary();
                extras.getChildAt(0).requestFocus();
            });
            boolean shuffle = Options.shuffle(o);
            addTo(extras, shuffle ? "Shuffle on" : "Shuffle off", () -> {
                o.edit().putBoolean("shuffle", !shuffle).apply();
                showLibrary();
                extras.getChildAt(1).requestFocus();
            });
            addButton("Choose again", false, this::choose);
            addButton("Remove", false, () -> {
                library.clearAll();
                showLibrary();
            });
            side.addView(preview(photos.get(0)));
        }
    }

    private void choose() {
        if (!PhoneSignIn.configured()) {
            failed("Sign-in is not set up", SETUP_HINT);
            return;
        }
        busy("Connecting", "Checking that this TV may still open your Google Photos.");
        run(this::signInThenPick);
    }

    /** The kept sign-in if Google still takes it, else the phone signs in by QR code; then the pick. Worker thread. */
    private void signInThenPick() {
        Thread me = Thread.currentThread();
        try {
            String token = signIn.accessToken();
            if (token == null) {
                String url = signIn.start();
                ui(() -> showCode(url, "Sign in on your phone", "Scan the code and sign in to Google with the account "
                        + "that has your photos, then allow Noirly Photos to open the photos you pick. Any network "
                        + "will do, even mobile data.", "Scan to sign in"));
                token = signIn.await(() -> worker == me, SIGN_IN_MS);
                if (token == null) return;
            }
            pick(token);
        } catch (InterruptedException e) {
            // Cancel or the screen closing: nothing to undo, the topic is simply never read again.
        } catch (IOException e) {
            String why = String.valueOf(e.getMessage());
            String say = why.contains("invalid_client") || why.contains("redirect_uri") ? SETUP_HINT
                    : why.contains("invalid_grant") ? "That sign-in had expired or was already used. Choose photos "
                            + "to show a new code, and scan it within a few minutes."
                    : why.contains("access_denied") ? "Access was not allowed on the phone. Choose photos to try again."
                    : "Check the TV's internet connection and try again. (" + why.replaceAll("\\s+", " ") + ")";
            ui(() -> failed("Sign-in did not work", say));
        }
    }

    /** Runs a pick on its own thread; the screen follows it until it ends or Cancel replaces it. */
    private void run(Runnable job) {
        // The owner is on the phone meanwhile: no screensaver may cover the code or stop the wait.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Thread t = new Thread(job, "noirly-photos");
        worker = t;
        t.start();
    }

    /** Session, QR code, wait for the phone, download, done. Runs on the worker thread. */
    private void pick(String token) {
        Thread me = Thread.currentThread();
        PickerApi.Session session = null;
        try {
            session = PickerApi.create(token);
            PickerApi.Session s = session;
            ui(() -> showCode(s.pickerUri, "Pick on your phone", "Scan the code with your phone's camera. Google Photos "
                    + "opens: choose photos or a whole album (up to " + PickerApi.MAX_ITEMS + "), then tap Done. "
                    + "This screen moves on by itself.", "Scan to pick photos"));
            long deadline = System.currentTimeMillis() + session.timeoutMs;
            while (!session.itemsSet) {
                if (worker != me) return;
                if (System.currentTimeMillis() > deadline) {
                    ui(() -> failed("The picker timed out", "Nothing was chosen in time. Choose photos to try again."));
                    return;
                }
                Thread.sleep(session.pollMs);
                session = PickerApi.get(token, session.id);
            }
            List<PickerApi.Item> items = PickerApi.photos(token, session.id);
            if (items.isEmpty()) {
                ui(() -> failed("No photos picked", "Only photos are kept; videos are left out. Choose again?"));
                return;
            }
            DisplayMetrics m = getResources().getDisplayMetrics();
            int w = Math.max(m.widthPixels, 1280), h = Math.max(m.heightPixels, 720);
            File into = library.startIncoming();
            for (int i = 0; i < items.size(); i++) {
                if (worker != me) {
                    library.abandonIncoming();
                    return;
                }
                int done = i, total = items.size();
                ui(() -> busy("Bringing them over", (done + 1) + " of " + total
                        + ". You can put the phone down; this takes a few seconds a photo."));
                PickerApi.download(token, items.get(i), w, h, new File(into, Library.name(i)));
            }
            library.commit();
            ui(() -> {
                worker = null;
                showLibrary();
            });
        } catch (PickerApi.AuthException e) {
            signIn.forget();
            library.abandonIncoming();
            ui(() -> failed("Google asked to sign in again", "Choose photos to sign in and try again."));
        } catch (InterruptedException e) {
            library.abandonIncoming();
        } catch (Exception e) {
            library.abandonIncoming();
            String why = String.valueOf(e.getMessage());
            ui(() -> failed("That did not work", why.contains("403")
                    ? "Google refused the request. " + SETUP_HINT
                    : "Check the TV's internet connection and try again. (" + why + ")"));
        } finally {
            if (session != null && session.id != null && !session.id.isEmpty()) PickerApi.delete(token, session.id);
        }
    }

    /** A QR code for the phone, what to do with it, and the link written out under it. */
    private void showCode(String uri, String head, String body, String caption) {
        if (worker == null) return;
        title.setText(head);
        words.setText(body);
        buttons.removeAllViews();
        extras.removeAllViews();
        addButton("Cancel", false, this::cancel).requestFocus();

        side.removeAllViews();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(n.px(22), n.px(22), n.px(22), n.px(18));
        card.setBackground(n.rounded(Noirly.SURFACE, 18, Noirly.HAIRLINE_STRONG, 1));
        ImageView code = new ImageView(this);
        code.setImageBitmap(qr(uri, n.px(240)));
        code.setBackground(n.rounded(Color.WHITE, 12, Color.WHITE, 1));
        code.setPadding(n.px(10), n.px(10), n.px(10), n.px(10));
        card.addView(code, new LinearLayout.LayoutParams(n.px(260), n.px(260)));
        TextView scan = n.label(10, Noirly.ACCENT);
        scan.setText(caption);
        LinearLayout.LayoutParams lp = wrap();
        lp.topMargin = n.px(14);
        card.addView(scan, lp);
        TextView link = n.text(10, Noirly.MUTED, n.mono);
        link.setText(uri);
        link.setMaxLines(2);
        link.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(n.px(260), ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = n.px(8);
        card.addView(link, llp);
        side.addView(card);
    }

    private void busy(String head, String body) {
        title.setText(head);
        words.setText(body);
        if (buttons.getChildCount() == 0 || worker == null) {
            buttons.removeAllViews();
            extras.removeAllViews();
        extras.removeAllViews();
            addButton("Cancel", false, this::cancel).requestFocus();
        }
        side.removeAllViews();
    }

    private void failed(String head, String body) {
        worker = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        title.setText(head);
        words.setText(body);
        buttons.removeAllViews();
        extras.removeAllViews();
        side.removeAllViews();
        addButton("Choose photos", true, this::choose).requestFocus();
        if (!library.photos().isEmpty()) addButton("Back", false, this::showLibrary);
    }

    private void cancel() {
        worker = null;
        showLibrary();
    }

    // ---- small builders ----

    private void addTo(LinearLayout row, String words, Runnable action) {
        TextView b = n.button(words, false, action);
        b.setTextSize(10);
        b.setPadding(n.px(16), n.px(8), n.px(16), n.px(8));
        LinearLayout.LayoutParams lp = wrap();
        if (row.getChildCount() > 0) lp.leftMargin = n.px(10);
        row.addView(b, lp);
    }

    private View addButton(String words, boolean primary, Runnable action) {
        TextView b = n.button(words, primary, action);
        LinearLayout.LayoutParams lp = wrap();
        if (buttons.getChildCount() > 0) lp.leftMargin = n.px(12);
        buttons.addView(b, lp);
        return b;
    }

    /** The first photo, framed like Noirly's cards. */
    private View preview(File photo) {
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 2;
        image.setImageBitmap(BitmapFactory.decodeFile(photo.getPath(), o));
        image.setBackground(n.rounded(Noirly.SURFACE_2, 18, Noirly.HAIRLINE_STRONG, 1));
        image.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        image.setClipToOutline(true);
        image.setLayoutParams(new FrameLayout.LayoutParams(n.px(400), n.px(225)));
        return image;
    }

    private static Bitmap qr(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 0);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) px[y * size + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            }
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
    }

    private void ui(Runnable r) {
        main.post(() -> {
            if (!isDestroyed()) r.run();
        });
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
