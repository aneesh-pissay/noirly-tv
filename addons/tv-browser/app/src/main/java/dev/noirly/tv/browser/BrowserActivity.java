package dev.noirly.tv.browser;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A web browser for the TV remote, on the TV's own WebView (Chromium). The arrows move a pointer, OK clicks where
 * it is, the pointer at an edge scrolls the page, and Back goes back. A bar along the top has Back, Forward,
 * Reload, Home, the address (a web address or words to search for) and a star that keeps the page on Home. Home
 * is a page drawn here: a search box and the starred pages. Video goes fullscreen; there OK is play/pause and the
 * arrows go to the player. Pages ask for the desktop site, which reads well from the sofa.
 */
public final class BrowserActivity extends Activity {
    /** Home: drawn by home(), never fetched. Links on it that start with HOME act on the bookmarks. */
    private static final String HOME = "tvbrowser://home/";
    private static final String SEARCH = "https://www.google.com/search?q=";
    /** CSS pixels across the page: the desktop layout, large enough to read from the sofa. */
    private static final int PAGE_WIDTH = 1280;

    private static final int INK = 0xFFF5F3EA;
    private static final int MUTED = 0xFF8C8A80;
    private static final int GOLD = 0xFFFFE66D;
    private static final int BG = 0xFF0A0A08;
    private static final int SURFACE = 0xFF1A1A17;

    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private LinearLayout bar;
    private EditText address;
    private TextView star;
    private WebView web;
    private Pointer pointer;
    private TextView hint;
    private View fullscreen;
    private WebChromeClient.CustomViewCallback fullscreenDone;
    private View lit;
    private SharedPreferences prefs;
    private float dp;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("web", MODE_PRIVATE);
        DisplayMetrics m = getResources().getDisplayMetrics();
        dp = m.density;

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(px(10), 0, px(10), 0);
        bar.setBackgroundColor(BG);
        bar.addView(button("‹", () -> { if (web.canGoBack()) web.goBack(); }));
        bar.addView(button("›", () -> { if (web.canGoForward()) web.goForward(); }));
        bar.addView(button("↻", () -> web.reload()));
        bar.addView(button("⌂", this::goHome));
        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(15);
        address.setTextColor(INK);
        address.setHintTextColor(MUTED);
        address.setHint("Search or type a web address");
        address.setSelectAllOnFocus(true);
        address.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setPadding(px(16), 0, px(16), 0);
        address.setBackground(pill(SURFACE, 0x2EF5F3EA));
        address.setOnEditorActionListener((v, action, event) -> {
            go(address.getText().toString());
            return true;
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, px(36), 1);
        alp.leftMargin = px(8);
        alp.rightMargin = px(8);
        bar.addView(address, alp);
        star = button("☆", this::toggleBookmark);
        bar.addView(star);
        column.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(52)));

        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setSupportMultipleWindows(false);   // target=_blank opens here, as there is one tab
        s.setUserAgentString(desktopAgent(WebSettings.getDefaultUserAgent(this)));
        web.setInitialScale(Math.max(50, Math.round(m.widthPixels * 100f / PAGE_WIDTH)));
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener((url, agent, disposition, mime, length) ->
                Toast.makeText(this, "Downloads aren't supported here.", Toast.LENGTH_SHORT).show());
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        column.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        pointer = new Pointer(this);
        root.addView(pointer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        pointer.post(() -> pointer.moveTo(pointer.getWidth() / 2f, pointer.getHeight() / 2f));

        hint = new TextView(this);
        hint.setText("ARROWS MOVE THE POINTER  ·  OK CLICKS  ·  BACK GOES BACK");
        hint.setTextColor(INK);
        hint.setTextSize(12);
        hint.setLetterSpacing(0.12f);
        hint.setTypeface(Typeface.MONOSPACE);
        hint.setPadding(px(18), px(10), px(18), px(10));
        hint.setBackground(pill(0xE6121210, 0x2EF5F3EA));
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hlp.bottomMargin = px(28);
        root.addView(hint, hlp);
        main.postDelayed(() -> hint.animate().alpha(0f).setDuration(600).start(), 6_000);

        setContentView(root);
        web.requestFocus();   // once: the keyboard needs the web view focused when a field is tapped
        // A link handed in (another app's, or adb: am start -d https://...) wins over the page left last time.
        Uri asked = getIntent().getData();
        String last = prefs.getString("last", HOME);
        if (asked != null && isWeb(asked)) web.loadUrl(asked.toString());
        else if (last.equals(HOME)) goHome();
        else web.loadUrl(last);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Uri asked = intent.getData();
        if (asked != null && isWeb(asked)) web.loadUrl(asked.toString());
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        String url = web.getUrl();
        if (url != null && (url.equals(HOME) || url.startsWith("http"))) prefs.edit().putString("last", url).apply();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        web.destroy();
    }

    // ---- the bar ----

    private TextView button(String symbol, Runnable action) {
        TextView b = new TextView(this);
        b.setText(symbol);
        b.setTextSize(22);
        b.setTextColor(INK);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setFocusable(false);   // the pointer presses it; D-pad focus stays on the page
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(40), px(40));
        lp.rightMargin = px(2);
        b.setLayoutParams(lp);
        return b;
    }

    /** The bar's button or field under the pointer gets a gold ring, as a link under a mouse would. */
    private void lightBar() {
        View under = null;
        if (pointer.y < bar.getHeight()) {
            for (int i = 0; i < bar.getChildCount(); i++) {
                View c = bar.getChildAt(i);
                if (pointer.x >= c.getLeft() && pointer.x < c.getRight()) under = c;
            }
        }
        if (under == lit) return;
        if (lit != null) lit.setBackground(lit == address ? pill(SURFACE, 0x2EF5F3EA) : null);
        lit = under;
        if (lit != null) lit.setBackground(pill(lit == address ? SURFACE : 0x33FFE66D, GOLD));
    }

    /** Words become a search; anything with a dot and no spaces is a web address. */
    private void go(String typed) {
        String t = typed.trim();
        hideKeyboard();
        web.requestFocus();
        if (t.isEmpty()) return;
        Uri u = Uri.parse(t);
        if (isWeb(u)) web.loadUrl(t);
        else if (!t.contains(" ") && t.contains(".")) web.loadUrl("https://" + t);
        else web.loadUrl(SEARCH + Uri.encode(t));
    }

    private void hideKeyboard() {
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(root.getWindowToken(), 0);
        address.clearFocus();
    }

    /** Shows what is loaded, and the star filled when the page is on Home. */
    private void showPage(String url) {
        boolean home = url == null || url.equals(HOME);
        if (!address.hasFocus()) address.setText(home ? "" : url);
        star.setText(!home && indexOf(url) >= 0 ? "★" : "☆");
        star.setTextColor(!home && indexOf(url) >= 0 ? GOLD : INK);
    }

    // ---- bookmarks and Home ----

    private JSONArray bookmarks() {
        try {
            return new JSONArray(prefs.getString("bookmarks",
                    "[{\"u\":\"https://www.google.com/\",\"t\":\"Google\"},"
                            + "{\"u\":\"https://www.wikipedia.org/\",\"t\":\"Wikipedia\"}]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private int indexOf(String url) {
        JSONArray b = bookmarks();
        for (int i = 0; i < b.length(); i++) if (url.equals(b.optJSONObject(i).optString("u"))) return i;
        return -1;
    }

    private void toggleBookmark() {
        String url = web.getUrl();
        if (url == null || !url.startsWith("http")) return;
        JSONArray b = bookmarks();
        int at = indexOf(url);
        if (at >= 0) {
            b.remove(at);
        } else {
            try {
                String title = web.getTitle();
                b.put(new JSONObject().put("u", url).put("t", TextUtils.isEmpty(title) ? url : title));
            } catch (JSONException ignored) {
                return;
            }
        }
        prefs.edit().putString("bookmarks", b.toString()).apply();
        Toast.makeText(this, at >= 0 ? "Taken off Home" : "Kept on Home", Toast.LENGTH_SHORT).show();
        showPage(url);
    }

    /** Home: a search box and the starred pages as tiles, each with a × that takes it off. */
    private void goHome() {
        StringBuilder tiles = new StringBuilder();
        JSONArray b = bookmarks();
        for (int i = 0; i < b.length(); i++) {
            JSONObject o = b.optJSONObject(i);
            String u = o.optString("u");
            String host = Uri.parse(u).getHost();
            tiles.append("<div class=t><a class=go href=\"").append(TextUtils.htmlEncode(u)).append("\"><b>")
                    .append(TextUtils.htmlEncode(o.optString("t"))).append("</b><span>")
                    .append(TextUtils.htmlEncode(host != null ? host : u)).append("</span></a><a class=x href=\"")
                    .append(HOME).append("remove?i=").append(i).append("\">×</a></div>");
        }
        if (b.length() == 0) tiles.append("<p class=none>Press ☆ on a page to keep it here.</p>");
        String html = "<!doctype html><meta charset=utf-8><title>Home</title><style>"
                + "body{margin:0;background:#0A0A08;color:#F5F3EA;font:18px system-ui,sans-serif;padding:56px 72px}"
                + "h1{font:600 40px Georgia,serif;margin:0 0 24px}"
                + "form{display:flex;max-width:820px;margin-bottom:40px}"
                + "input{flex:1;font-size:22px;padding:14px 22px;border-radius:99px;border:1px solid #3a3a34;"
                + "background:#1A1A17;color:#F5F3EA;outline:none}input:focus{border-color:#FFE66D}"
                + "h2{font:12px monospace;letter-spacing:.14em;color:#8C8A80;margin:0 0 14px}"
                + ".g{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:16px}"
                + ".t{position:relative;background:#1A1A17;border:1px solid #2e2e29;border-radius:14px}"
                + ".t:hover{border-color:#FFE66D}"
                + ".go{display:block;padding:20px 22px;color:inherit;text-decoration:none}"
                + ".go b{display:block;font-size:20px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}"
                + ".go span{color:#8C8A80;font-size:14px}"
                + ".x{position:absolute;top:6px;right:12px;color:#8C8A80;text-decoration:none;font-size:22px}"
                + ".x:hover{color:#FFE66D}.none{color:#8C8A80}</style>"
                + "<h1>Where to?</h1><form action=\"https://www.google.com/search\">"
                + "<input name=q placeholder=\"Search Google\" autocomplete=off></form>"
                + "<h2>KEPT ON HOME</h2><div class=g>" + tiles + "</div>";
        web.loadDataWithBaseURL(HOME, html, "text/html", "utf-8", HOME);
    }

    private static boolean isWeb(Uri u) {
        return "https".equals(u.getScheme()) || "http".equals(u.getScheme());
    }

    // ---- keys ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_BACK) {
            if (down) return true;
            if (address.hasFocus()) {
                hideKeyboard();
                web.requestFocus();
                showPage(web.getUrl());
            } else if (fullscreen != null) {
                web.evaluateJavascript("document.exitFullscreen && document.exitFullscreen()", null);
                if (fullscreenDone != null) fullscreenDone.onCustomViewHidden();
            } else if (web.canGoBack()) {
                web.goBack();
            } else {
                finish();
            }
            return true;
        }
        // Play/pause keys and, in fullscreen, OK: the page's video, or Space where there is none.
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY
                || code == KeyEvent.KEYCODE_MEDIA_PAUSE
                || (fullscreen != null && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER))) {
            if (down && e.getRepeatCount() == 0) web.evaluateJavascript(PLAY_PAUSE, pressed -> {
                if ("true".equals(pressed)) return;
                long now = SystemClock.uptimeMillis();
                super.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, 0));
                super.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE, 0));
            });
            return true;
        }
        // Fullscreen video: the arrows go to the player (seek and volume), as on a computer.
        if (fullscreen != null) return super.dispatchKeyEvent(e);
        // Enter from the on-screen keyboard (a search box's Go), or any Enter in the address field, is typing.
        if (code == KeyEvent.KEYCODE_ENTER
                && ((e.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) != 0 || address.hasFocus())) {
            return super.dispatchKeyEvent(e);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: if (down) move(0, -1, e.getRepeatCount()); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (down) move(0, 1, e.getRepeatCount()); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: if (down) move(-1, 0, e.getRepeatCount()); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (down) move(1, 0, e.getRepeatCount()); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (e.getRepeatCount() == 0) touch(down ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP);
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    /** One step, faster the longer the key is held; past an edge the page scrolls instead. */
    private void move(int dx, int dy, int repeat) {
        float step = px(Math.min(10 + repeat * 3, 46));
        float nx = pointer.x + dx * step, ny = pointer.y + dy * step;
        float margin = px(4);
        boolean scrollV = (dy < 0 && pointer.y <= margin) || (dy > 0 && pointer.y >= pointer.getHeight() - 1 - margin);
        boolean scrollH = (dx < 0 && pointer.x <= margin) || (dx > 0 && pointer.x >= pointer.getWidth() - 1 - margin);
        if (scrollV || scrollH) {
            scroll(scrollH ? -dx : 0, scrollV ? -dy : 0, repeat);
            return;
        }
        pointer.moveTo(nx, ny);
        lightBar();
        if (pointer.y >= web.getTop()) hover();
        hint.animate().alpha(0f).setDuration(300).start();
    }

    /** A mouse move at the pointer, so menus and hover states open as they do on a computer. */
    private void hover() {
        web.dispatchGenericMotionEvent(mouse(MotionEvent.ACTION_HOVER_MOVE, 0, 0));
    }

    /** A wheel turn at the pointer: inner scrolling lists move too, not only the page. */
    private void scroll(float h, float v, int repeat) {
        float amount = Math.min(1 + repeat / 4f, 4);
        web.dispatchGenericMotionEvent(mouse(MotionEvent.ACTION_SCROLL, h * amount, v * amount));
    }

    /**
     * A tap at the pointer, into the whole window so the bar's buttons take it too: OK down presses, OK up
     * releases, so a held OK is a long press.
     */
    private void touch(int action) {
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) downAt = now;
        boolean onPage = pointer.y >= web.getTop();
        if (action == MotionEvent.ACTION_DOWN && onPage) web.evaluateJavascript(CLICK_WATCH, null);
        MotionEvent ev = MotionEvent.obtain(downAt, now, action, pointer.x, pointer.y, 0);
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        root.dispatchTouchEvent(ev);
        ev.recycle();
        if (action != MotionEvent.ACTION_UP) return;
        if (!onPage) {
            // The address field: a synthesised tap focuses it but raises no keyboard on a TV, and the keyboard
            // is not yet serving the field, so connect it first.
            if (address.hasFocus()) main.postDelayed(() -> {
                InputMethodManager im = getSystemService(InputMethodManager.class);
                im.restartInput(address);
                im.showSoftInput(address, 0);
            }, 150);
            return;
        }
        main.postDelayed(this::clickIfSwallowed, 300);
        main.postDelayed(this::keyboardIfTyping, 250);
    }

    /** Toggles the page's first video, if it has one. */
    private static final String PLAY_PAUSE = "(function(){var v=document.querySelector('video');if(!v)return false;"
            + "v.paused?v.play():v.pause();return true;})()";

    /**
     * Notes when the page last saw a real tap (and what it landed on), a click, a cancelled touchend and a video
     * starting or stopping, so
     * clickIfSwallowed knows whether the tap did anything. Times, not flags reset at OK down: this script may run
     * after the tap. The target is taken from the tap itself: a page may close an open menu on the tap.
     */
    private static final String CLICK_WATCH = "window.__tvClick||(window.__tvClick=window.__tvDown="
            + "window.__tvCancel=window.__tvToggled=-1e9,['click','pointerdown','touchend','play','pause']"
            + ".forEach(function(n){window.addEventListener(n,function(e){if(!e.isTrusted)return;"
            + "var t=performance.now();if(n==='click')window.__tvClick=t;else if(n==='pointerdown'){"
            + "window.__tvDown=t;window.__tvTarget=e.target;}else if(n==='touchend'){if(e.defaultPrevented)"
            + "window.__tvCancel=t;}else window.__tvToggled=t;},n!=='touchend');}));";

    /**
     * Video players (Udemy's Shaka among them) cancel the touchend of a tap, so the tap never becomes a click and
     * their buttons and sliders ignore OK. When the page cancelled the tap and no click followed, click it from the
     * page instead: a slider (seek, volume) jumps to the pointer, anything else gets a mouse click. The tap still
     * counts as the user's gesture, so a fullscreen button works too.
     */
    private void clickIfSwallowed() {
        float x = pointer.x - web.getLeft(), y = pointer.y - web.getTop();
        web.evaluateJavascript("(function(X,Y){X=X*innerWidth/" + web.getWidth() + ";Y=Y*innerHeight/"
                + web.getHeight() + ";var n=performance.now();"
                + "var e=n-window.__tvDown<1000&&window.__tvTarget;"
                + "if(!e||!e.isConnected)e=document.elementFromPoint(X,Y);if(!e)return;"
                // On the bare video, OK is play/pause: YouTube's player takes a tap's click as "show controls".
                + "if(e.tagName==='VIDEO'){if(n-window.__tvToggled>1000)e.paused?e.play():e.pause();return;}"
                + "if(n-window.__tvClick<400||n-window.__tvCancel>1000)return;"
                + "e.dispatchEvent(new MouseEvent('mousemove',{bubbles:true,clientX:X,clientY:Y}));"
                + "var r=e.closest&&e.closest('input[type=range]');if(r){var b=r.getBoundingClientRect(),"
                + "mn=+r.min||0,mx=r.max===''?100:+r.max;"
                + "r.value=mn+Math.max(0,Math.min(1,(X-b.left)/b.width))*(mx-mn);"
                + "r.dispatchEvent(new Event('input',{bubbles:true}));"
                + "r.dispatchEvent(new Event('change',{bubbles:true}));return;}"
                + "var o={bubbles:true,cancelable:true,composed:true,clientX:X,clientY:Y,button:0,view:window};"
                + "[['pointerdown',1],['mousedown',1],['pointerup',0],['mouseup',0]].forEach(function(t){"
                + "e.dispatchEvent(new (t[0][0]=='p'?PointerEvent:MouseEvent)(t[0],"
                + "Object.assign({pointerType:'mouse',buttons:t[1]},o)));});"
                + "e.dispatchEvent(new MouseEvent('click',o));})(" + x + "," + y + ")", null);
    }

    /** A synthesised tap focuses a field but does not raise the keyboard on a TV; ask the page and raise it. */
    private void keyboardIfTyping() {
        web.evaluateJavascript("(function(){var e=document.activeElement;if(!e)return false;"
                + "var t=(e.type||'').toLowerCase();return e.isContentEditable||e.tagName==='TEXTAREA'||"
                + "(e.tagName==='INPUT'&&['button','submit','checkbox','radio','range','file','image','reset']"
                + ".indexOf(t)<0)"
                // The keyboard covers the lower half and a fullscreen window does not shrink: lift the field up.
                + "&&(e.scrollIntoView({block:'start'}),window.scrollBy(0,-180),true);})()", typing -> {
            if (!"true".equals(typing)) return;
            // No web.requestFocus() here: on an already focused web view it moves the page's focus to its first
            // link ("Skip to content"), away from the field just tapped.
            getSystemService(InputMethodManager.class).showSoftInput(web, 0);
        });
    }

    private long downAt;

    /** A mouse event at the pointer, in the web view's own coordinates (the bar sits above it). */
    private MotionEvent mouse(int action, float h, float v) {
        MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties()};
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
        coords[0].x = pointer.x - web.getLeft();
        coords[0].y = Math.max(0, pointer.y - web.getTop());
        coords[0].setAxisValue(MotionEvent.AXIS_HSCROLL, h);
        coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, v);
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0);
    }

    // ---- web ----

    private final class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (u.toString().startsWith(HOME)) {
                // Only Home itself may change the bookmarks: a site can link here too.
                if (HOME.equals(view.getUrl()) && "/remove".equals(u.getPath())) {
                    JSONArray b = bookmarks();
                    try {
                        b.remove(Integer.parseInt(u.getQueryParameter("i")));
                        prefs.edit().putString("bookmarks", b.toString()).apply();
                    } catch (RuntimeException ignored) {
                    }
                }
                goHome();
                return true;
            }
            return !isWeb(u);   // intent:, market: and the like stay out of the browser
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            showPage(url);
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            showPage(url);
        }
    }

    private final class Chrome extends WebChromeClient {
        /** Protected (Widevine) video; the camera, microphone and the rest are refused. */
        @Override
        public void onPermissionRequest(PermissionRequest request) {
            for (String r : request.getResources()) {
                if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(r)) {
                    request.grant(new String[]{r});
                    return;
                }
            }
            request.deny();
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (fullscreen != null) {
                callback.onCustomViewHidden();
                return;
            }
            fullscreen = view;
            fullscreenDone = callback;
            view.setBackgroundColor(Color.BLACK);
            root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            pointer.setVisibility(View.GONE);
            view.requestFocus();
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        @Override
        public void onHideCustomView() {
            if (fullscreen == null) return;
            root.removeView(fullscreen);
            fullscreen = null;
            fullscreenDone = null;
            pointer.setVisibility(View.VISIBLE);
            web.requestFocus();
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /** The web view's own agent, as desktop Chrome on Linux: the full site rather than the "get the app" one. */
    static String desktopAgent(String webview) {
        Matcher m = Pattern.compile("Chrome/([\\d.]+)").matcher(webview != null ? webview : "");
        String version = m.find() ? m.group(1) : "130.0.0.0";
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + version
                + " Safari/537.36";
    }

    private GradientDrawable pill(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(px(99));
        d.setStroke(px(1), stroke);
        return d;
    }

    private int px(float dps) {
        return Math.round(dps * dp);
    }
}
