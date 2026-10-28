package dev.noirly.tv.udemy;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * udemy.com made usable with a TV remote. The site is built for a mouse, so the arrows move a pointer, OK clicks
 * where it is, the pointer at an edge scrolls the page, and Back goes back. Lectures play (protected video included)
 * and go fullscreen; there OK is play/pause and Left/Right seek, as the player's own keys do. The desktop site is
 * asked for at 1280 CSS pixels across, which reads well on a 1080p TV. Sign-in stays: the web view keeps its cookies.
 */
public final class UdemyActivity extends Activity {
    private static final String HOME = "https://www.udemy.com/";
    /** CSS pixels across the page: the desktop layout, large enough to read from the sofa. */
    private static final int PAGE_WIDTH = 1280;

    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView web;
    private Pointer pointer;
    private TextView hint;
    private View fullscreen;
    private WebChromeClient.CustomViewCallback fullscreenDone;
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
        root.setBackgroundColor(0xFF0A0A08);
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
        s.setSupportMultipleWindows(false);
        s.setUserAgentString(desktopAgent(WebSettings.getDefaultUserAgent(this)));
        web.setInitialScale(Math.max(50, Math.round(m.widthPixels * 100f / PAGE_WIDTH)));
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        // The page never takes D-pad focus itself: the pointer does the navigating.
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        pointer = new Pointer(this);
        root.addView(pointer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        pointer.post(() -> pointer.moveTo(pointer.getWidth() / 2f, pointer.getHeight() / 2f));

        hint = new TextView(this);
        hint.setText("ARROWS MOVE THE POINTER  ·  OK CLICKS  ·  BACK GOES BACK");
        hint.setTextColor(0xFFF5F3EA);
        hint.setTextSize(12);
        hint.setLetterSpacing(0.12f);
        hint.setTypeface(android.graphics.Typeface.MONOSPACE);
        hint.setPadding(px(18), px(10), px(18), px(10));
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setColor(0xE6121210);
        pill.setCornerRadius(px(99));
        pill.setStroke(px(1), 0x2EF5F3EA);
        hint.setBackground(pill);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hlp.bottomMargin = px(28);
        root.addView(hint, hlp);
        main.postDelayed(() -> hint.animate().alpha(0f).setDuration(600).start(), 6_000);

        setContentView(root);
        web.requestFocus();   // once: the keyboard needs the web view focused when a field is tapped
        // A udemy.com link handed in (adb: am start -d https://www.udemy.com/...) wins over the page left last time.
        Uri asked = getIntent().getData();
        web.loadUrl(asked != null && "www.udemy.com".equals(asked.getHost()) ? asked.toString()
                : prefs.getString("last", HOME));
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        String url = web.getUrl();
        if (url != null && url.startsWith("https://www.udemy.com/")) prefs.edit().putString("last", url).apply();
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

    // ---- keys ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_BACK) {
            if (down) return true;
            if (fullscreen != null) {
                web.evaluateJavascript("document.exitFullscreen && document.exitFullscreen()", null);
                if (fullscreenDone != null) fullscreenDone.onCustomViewHidden();
            } else if (web.canGoBack()) {
                web.goBack();
            } else {
                finish();
            }
            return true;
        }
        // Play/pause keys and, in fullscreen, OK: the player's play button, or its own Space where there is none.
        // Space alone goes to whatever control has focus, the seek bar after an arrow, and is lost there.
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
        hover();
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

    /** A tap at the pointer: OK down presses, OK up releases, so a held OK is a long press. */
    private void touch(int action) {
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) {
            downAt = now;
            // A new tap drops the last one's pending fallback, which would act on this tap's target instead.
            main.removeCallbacks(pendingClick);
            web.evaluateJavascript(CLICK_WATCH, null);
        }
        MotionEvent ev = MotionEvent.obtain(downAt, now, action, pointer.x, pointer.y, 0);
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(ev);
        ev.recycle();
        if (action == MotionEvent.ACTION_UP) {
            float x = pointer.x, y = pointer.y;
            pendingClick = () -> clickIfSwallowed(x, y);
            main.postDelayed(pendingClick, 300);
            main.postDelayed(this::keyboardIfTyping, 250);
        }
    }

    /** The last tap's clickIfSwallowed, at the pointer where that tap was. */
    private Runnable pendingClick = () -> { };

    /** Presses the player's play/pause button, after showing its controls (a hidden one ignores the press). */
    private static final String PLAY_PAUSE = "(function(){var b=document.querySelector('.shaka-small-play-button');"
            + "if(!b)return false;var c=b.closest('.shaka-controls-container');if(c)c.dispatchEvent(new MouseEvent("
            + "'mousemove',{bubbles:true}));b.click();return true;})()";

    /**
     * Notes when the page last saw a real tap (and what it landed on), click and play/pause, so clickIfSwallowed
     * knows whether the tap did anything. Times, not flags reset at OK down: this script may run after the tap.
     * The target is taken from the tap itself: the player closes an open menu (the captions languages) on the tap,
     * and by then the pointer is over the video instead.
     */
    private static final String CLICK_WATCH = "window.__tvClick||(window.__tvClick=window.__tvDown="
            + "window.__tvToggled=-1e9,['click','play','pause','pointerdown'].forEach(function(n){"
            + "window.addEventListener(n,function(e){if(!e.isTrusted)return;var t=performance.now();"
            + "if(n==='click')window.__tvClick=t;else if(n==='pointerdown'){window.__tvDown=t;"
            + "window.__tvTarget=e.target;}else window.__tvToggled=t;},true);}));";

    /**
     * Udemy's player (Shaka) cancels the touchend of a tap, so the tap never becomes a click and its buttons and
     * seek bar ignore OK. When the tap landed in the player and no click followed, click it from the page instead:
     * on the video it plays or pauses, another button gets a mouse click once the controls
     * show, a slider (seek, volume) jumps to the pointer. The tap still counts as the user's gesture, so the
     * fullscreen button works too.
     */
    private void clickIfSwallowed(float x, float y) {
        web.evaluateJavascript("(function(X,Y){X=X*innerWidth/" + web.getWidth() + ";Y=Y*innerHeight/"
                + web.getHeight() + ";var n=performance.now();if(n-window.__tvClick<400)return;"
                + "var e=n-window.__tvDown<1000&&window.__tvTarget;"
                + "if(!e||!e.isConnected)e=document.elementFromPoint(X,Y);"
                // The video and the loading spinner sit beside the controls, in the same video container.
                + "var c=e&&e.closest('.shaka-controls-container');if(!c&&e){var w=e.closest('.shaka-video-container');"
                + "c=w&&w.querySelector('.shaka-controls-container');}if(!c)return;"
                // The touch player hides its controls while playing, and a tap on them only shows them again.
                + "c.dispatchEvent(new MouseEvent('mousemove',{bubbles:true,clientX:X,clientY:Y}));"
                // On the video (its big play button, or the loading spinner over it): press the bar's play button,
                // the one that always toggles. The video's own play() or pause() is undone by Udemy's player.
                + "if(e.closest('.shaka-play-button')||!e.closest('button,input')){if(n-window.__tvToggled<1000)return;"
                + "e=c.querySelector('.shaka-small-play-button');if(!e)return;}"
                + "var r=e.closest('input[type=range]');if(r){var b=r.getBoundingClientRect(),mn=+r.min||0,"
                + "mx=r.max===''?100:+r.max;r.value=mn+Math.max(0,Math.min(1,(X-b.left)/b.width))*(mx-mn);"
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
            android.view.inputmethod.InputMethodManager im = getSystemService(
                    android.view.inputmethod.InputMethodManager.class);
            im.showSoftInput(web, 0);
        });
    }

    private long downAt;

    private MotionEvent mouse(int action, float h, float v) {
        MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties()};
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
        coords[0].x = pointer.x;
        coords[0].y = pointer.y;
        coords[0].setAxisValue(MotionEvent.AXIS_HSCROLL, h);
        coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, v);
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0);
    }

    // ---- web ----

    /** Google and Apple refuse to sign in inside an app's web view; say so instead of showing their error page. */
    private final class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            String host = u.getHost() != null ? u.getHost() : "";
            if (host.endsWith("accounts.google.com") || host.endsWith("appleid.apple.com")
                    || host.endsWith("facebook.com")) {
                Toast.makeText(UdemyActivity.this, "Google, Apple and Facebook sign-in don't work in TV apps. "
                        + "Use \"Continue with email\": Udemy sends a code to your email.", Toast.LENGTH_LONG).show();
                return true;
            }
            String scheme = u.getScheme() != null ? u.getScheme() : "";
            return !scheme.equals("https") && !scheme.equals("http");   // intent:, market: and the like
        }
    }

    private final class Chrome extends WebChromeClient {
        /** Protected (Widevine) lecture video. */
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

    private int px(float dps) {
        return Math.round(dps * dp);
    }
}
