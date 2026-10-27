package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A second Home layout, drawn after one owner's mockup: a greeting with a subtitle, round Inputs / Wi-Fi /
 * Settings buttons with labels, three hero cards with a tagline, "Your apps" as wide cards with a category,
 * and a glass bar of round app icons with the clock. MainActivity hands its window to this class when the
 * pushed theme sets "studio_home": true; nothing else in the launcher changes. Like the rest of the
 * launcher it never goes online: the weather in the bar comes from the Weather add-on (io.github.sumyabazar1.weather), which
 * reads Open-Meteo itself, through its read-only NowProvider. Without the add-on the bar has no weather.
 *
 * Theme keys it reads: studio_home, greeting_name, studio_subtitle, dock_apps (the first three are the hero
 * cards), studio_apps (order of Your apps), studio_dock (the round icons), studio_taglines and
 * studio_categories (objects keyed by package), hidden_apps, live_tv_package, studio_palette and studio_mode (the
 * Theme button's defaults), studio_weather_place (false hides the city in the weather block).
 */
final class StudioHome {
    private static final int COLUMNS = 4;
    private static final int HEROES = 3;
    private static final String SETTINGS_KEY = "#settings";

    /** Where the Theme button keeps its choice: "palette" (an id from NoirlyPalette) and "dark". */
    private static final String THEME_PREFS = "studio_theme";
    /** The owner's app for each big card ("slot0".."slot2"), chosen by holding OK on it; over the theme's dock_apps. */
    private static final String HERO_PREFS = "studio_heroes";
    /** Letter spacing for display type, slightly tight (Inter Display is already set close), in em. */
    private static final float DISPLAY_TRACKING = -0.01f;
    /** The first tab under "Your apps", and the category the Settings card is filed under. */
    private static final String ALL = "All";
    private static final String SETTINGS_CATEGORY = "System";
    /** Apps with no known category; its tab comes last. */
    private static final String OTHER = "Other";
    /** The Weather add-on and its read-only weather-now provider. */
    private static final String WEATHER_PKG = "io.github.sumyabazar1.weather";
    private static final Uri WEATHER_NOW = Uri.parse("content://io.github.sumyabazar1.weather.now/now");
    /** How often the bar asks again while Home is on screen; the add-on decides whether that reads Open-Meteo. */
    private static final long WEATHER_TICK_MS = 15 * 60_000L;

    /** Taglines and categories for apps this TV has; a theme's studio_taglines / studio_categories win. */
    private static final Map<String, String> TAGLINES = new HashMap<>();
    private static final Map<String, String> CATEGORIES = new HashMap<>();
    static {
        tag("com.google.android.youtube.tv", "Watch your favourites.", "Entertainment");
        tag("com.netflix.ninja", "Unlimited movies and TV shows.", "Entertainment");
        tag("com.amazon.amazonvideo.livingroom", "Movies, TV shows and Originals.", "Entertainment");
        tag("in.startv.hotstar", "Live sports, movies and shows.", "Entertainment");
        tag("com.google.android.youtube.tvmusic", "Music for every moment.", "Music");
        tag("com.sonyliv", "Sports, shows and movies.", "Entertainment");
        tag("com.graymatrix.did", "Shows, movies and news.", "Entertainment");
        tag("com.jio.media.jiotvplus", "Live TV channels.", "Live TV");
        tag("com.jio.media.stb.ondemand", "Movies and shows.", "Entertainment");
        tag("com.crunchyroll.crunchyroid", "Anime, simulcast.", "Anime");
        tag("com.viki.android", "Asian dramas and movies.", "Entertainment");
        tag("com.mxtech.videoplayer.television", "Free movies and shows.", "Entertainment");
        tag("fit.cure.android.livetv", "Workouts at home.", "Fitness");
        tag("com.udemytv", "Learn something new.", "Education");
        tag("com.seedr.app", "Your cloud downloads.", "Utilities");
        tag("com.alphainventor.filemanager", "Files on this TV.", "Utilities");
        tag("com.tcl.browser", "Browse the web.", "Utilities");
        tag("com.google.android.apps.tachyon", "Video calls.", "Communication");
        tag("com.tcl.ui_mediaCenter", "Play from USB.", "System");
        tag("com.tcl.miracast", "Mirror your phone.", "Connectivity");
        tag("com.android.vending", "Get more apps.", "System");
        tag("io.github.sumyabazar1.weather", "The forecast for your city.", "Utilities");
        tag("dev.noirly.tv.photos", "Your Google Photos.", "Photos & Videos");
        tag("dev.noirly.tv.udemy", "Courses with your remote.", "Education");
    }

    private static void tag(String pkg, String tagline, String category) {
        TAGLINES.put(pkg, tagline);
        CATEGORIES.put(pkg, category);
    }

    static boolean enabled(Context ctx) {
        JSONObject o = Config.overlay(ctx);
        return o != null && o.optBoolean("studio_home", false);
    }

    /** One launchable app: its package, label and launch intent. */
    private static final class App {
        final String key;
        final String label;
        final ResolveInfo info;
        App(String key, String label, ResolveInfo info) { this.key = key; this.label = label; this.info = info; }
    }

    private final Activity a;
    private final PackageManager pm;
    private final float dp;
    /** Every colour on the page: one of Noirly's palettes, light or dark. */
    private final NoirlyPalette p;
    /** Two faces: Inter Display for titles, figures and labels, Hanken Grotesk for copy and subtitles. */
    private final Typeface display;
    private final Typeface body;
    private JSONObject cfg = new JSONObject();
    private String liveTvPkg = "";

    private final FrameLayout root;
    private final TextView greeting;
    private final LinearLayout heroRow;
    private final LinearLayout tabRow;
    private final List<View> tabs = new ArrayList<>();
    /** The chosen tab: ALL or a category name. */
    private String tab = ALL;
    /** Every app the grid can show, in grid order; fillGrid() picks the chosen tab's from it. */
    private List<App> gridApps = new ArrayList<>();
    /** Every launchable app, and the package on each big card, from the last rebuild. */
    private Map<String, App> allApps = new HashMap<>();
    private List<String> heroPkgs = new ArrayList<>();
    /** The Wi-Fi button's icon and its colour at rest (showNet). */
    private Drawable lastIcon, wifiIcon;
    private TextView lastLabel, wifiLabel;
    private int wifiColor;
    private final ScrollView gridScroll;
    private final LinearLayout grid;
    private final HorizontalScrollView dockScroll;
    private final LinearLayout dock;
    private final LinearLayout weather;
    private final ImageView weatherIcon;
    private final TextView weatherTemp;
    private final TextView weatherSky;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable weatherTick = this::askWeather;
    private final ContentObserver weatherChanged = new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
            askWeather();
        }
    };

    private final List<View> top = new ArrayList<>();
    private final List<View> heroes = new ArrayList<>();
    private final List<View> cards = new ArrayList<>();
    private final List<View> dockItems = new ArrayList<>();
    private final Map<String, Bitmap> icons = new HashMap<>();
    private int lastTop, lastHero, lastCard, lastDock;
    private View focused;
    private boolean dirty;
    /** The artwork on each hero card, which drifts a little as the focus moves along the row (parallax). */
    private final List<View> heroArt = new ArrayList<>();
    /** Each tab's colours, eased between rest, chosen and focused. */
    private final Map<View, StudioMotion.Colors> tabColors = new HashMap<>();
    private HorizontalScrollView tabScroll;
    /** False when animations are off in Developer options: then nothing moves, it just changes. */
    private final boolean motion;
    private boolean paused;
    /** Set just before a theme change recreates the activity: that Home appears at once, without the startup fade. */
    private static boolean themeSwitch;

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            dirty = true;
            if (a.hasWindowFocus()) rebuild();
        }
    };

    StudioHome(Activity activity) {
        a = activity;
        pm = a.getPackageManager();
        dp = a.getResources().getDisplayMetrics().density;
        motion = StudioMotion.on(a);
        display = a.getResources().getFont(R.font.noirly_inter_bold);
        body = a.getResources().getFont(R.font.noirly_hanken_regular);
        // The palette and mode chosen with the Theme button; a theme's studio_palette / studio_mode are the defaults.
        JSONObject o = Config.overlay(a);
        SharedPreferences prefs = a.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE);
        p = new NoirlyPalette(
                prefs.getString("palette", o != null ? o.optString("studio_palette", NoirlyPalette.DEFAULT_ID)
                        : NoirlyPalette.DEFAULT_ID),
                prefs.getBoolean("dark", o == null || !"light".equals(o.optString("studio_mode"))));

        // Noirly's ambient page background, drawn once (the theme's wallpaper.jpg is not used).
        root = new FrameLayout(a);
        root.setBackgroundColor(p.page);
        root.setClipChildren(false);
        ImageView backdrop = new ImageView(a);
        backdrop.setScaleType(ImageView.ScaleType.FIT_XY);
        backdrop.setImageBitmap(NoirlyBackdrop.draw(p, a.getResources().getDisplayMetrics()));
        root.addView(backdrop, match());

        // Header: greeting and subtitle on the left, round buttons with labels on the right.
        greeting = displayText(30);
        root.addView(greeting, at(36, 26, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView subtitle = text(15, p.text2, body);
        subtitle.setTag("subtitle");
        root.addView(subtitle, at(36, 68, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(a);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(roundButton(R.drawable.ic_input, "Inputs", this::showInputs));
        buttons.addView(roundButton(R.drawable.ic_wifi, "Wi-Fi",
                () -> open(SystemSettings.intent(a, Settings.ACTION_WIFI_SETTINGS))));
        wifiIcon = lastIcon;
        wifiLabel = lastLabel;
        wifiColor = p.text;
        View[] theme = new View[1];
        theme[0] = roundButton(R.drawable.ic_palette, "Theme", () -> showThemes(theme[0]));
        buttons.addView(theme[0]);
        View settings = roundButton(R.drawable.ic_settings, "Settings",
                () -> open(SystemSettings.intent(a, Settings.ACTION_SETTINGS)));
        settings.setOnLongClickListener(v -> {
            open(new Intent(a, LauncherSettingsActivity.class));
            return true;
        });
        buttons.addView(settings);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP);
        blp.topMargin = px(22);
        blp.rightMargin = px(26);
        root.addView(buttons, blp);

        heroRow = new LinearLayout(a);
        heroRow.setOrientation(LinearLayout.HORIZONTAL);
        heroRow.setClipChildren(false);
        root.addView(heroRow, at(36, 104, ViewGroup.LayoutParams.MATCH_PARENT, 128, 36));

        TextView yourApps = displayText(19);
        yourApps.setText("Your apps");
        root.addView(yourApps, at(36, 244, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Category tabs under the title: All, then the categories of the apps in the grid (buildTabs).
        tabScroll = new HorizontalScrollView(a);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setClipToPadding(false);
        tabScroll.setClipChildren(false);
        tabScroll.setPadding(px(6), px(2), px(6), px(2));
        tabRow = new LinearLayout(a);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setClipChildren(false);
        tabScroll.addView(tabRow, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(tabScroll, at(30, 272, ViewGroup.LayoutParams.MATCH_PARENT, 34, 30));

        // Two rows of cards show at a time; the rest scroll up under them.
        gridScroll = new ScrollView(a);
        gridScroll.setVerticalScrollBarEnabled(false);
        // The padding leaves room for the focus zoom. Neither the padding nor grid's own bounds clip, or a
        // zoomed card in the first or last column loses its side borders; gridBox below does the clipping.
        gridScroll.setClipToPadding(false);
        gridScroll.setClipChildren(false);
        gridScroll.setPadding(px(6), px(4), px(6), px(4));
        gridScroll.setSmoothScrollingEnabled(true);
        grid = new LinearLayout(a);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        gridScroll.addView(grid, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        // A view is clipped by its parent, and root clips nothing so focused hero cards can zoom past their
        // row; this box is the parent that keeps the scrolled rows inside the grid's two-row window.
        FrameLayout gridBox = new FrameLayout(a);
        gridBox.addView(gridScroll, match());
        root.addView(gridBox, at(30, 310, ViewGroup.LayoutParams.MATCH_PARENT, 126, 30));

        // The bottom bar: round app icons, a line, then the clock and date.
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(glass(33, false));
        bar.setPadding(px(14), 0, px(26), 0);
        dockScroll = new HorizontalScrollView(a);
        dockScroll.setHorizontalScrollBarEnabled(false);
        dockScroll.setClipToPadding(false);
        dockScroll.setPadding(px(4), 0, px(4), 0);
        dock = new LinearLayout(a);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setClipChildren(false);
        dockScroll.addView(dock);
        bar.addView(dockScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        // The weather now, from the Weather add-on; hidden until it answers. OK opens its full page.
        weather = new LinearLayout(a);
        weather.setOrientation(LinearLayout.HORIZONTAL);
        weather.setGravity(Gravity.CENTER_VERTICAL);
        weather.setPadding(px(8), px(5), px(10), px(5));
        weatherIcon = new ImageView(a);
        weatherIcon.setVisibility(View.GONE);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(px(30), px(30));
        ilp.rightMargin = px(8);
        weather.addView(weatherIcon, ilp);
        LinearLayout weatherWords = new LinearLayout(a);
        weatherWords.setOrientation(LinearLayout.VERTICAL);
        weatherTemp = displayText(22);
        weatherSky = text(11, p.text2, body);
        StudioMotion.softText(weatherTemp);
        StudioMotion.softText(weatherSky);
        weatherWords.addView(weatherTemp);
        weatherWords.addView(weatherSky);
        weather.addView(weatherWords);
        weather.setVisibility(View.GONE);
        TransitionDrawable weatherLit = fader(rounded(Color.TRANSPARENT, 12, Color.TRANSPARENT),
                rounded(p.accentSoft, 12, p.accent, 2));
        weather.setBackground(weatherLit);
        focusable(weather, 1.05f, hasFocus -> fade(weatherLit, hasFocus));
        weather.setOnClickListener(v -> {
            Intent i = pm.getLeanbackLaunchIntentForPackage(WEATHER_PKG);
            open(i != null ? i : pm.getLaunchIntentForPackage(WEATHER_PKG));
        });
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.leftMargin = px(4);
        bar.addView(weather, wlp);
        View line = new View(a);
        line.setBackgroundColor(p.hairlineStrong);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(px(1), px(34));
        llp.leftMargin = px(10);
        llp.rightMargin = px(22);
        bar.addView(line, llp);
        LinearLayout clockBlock = new LinearLayout(a);
        clockBlock.setOrientation(LinearLayout.VERTICAL);
        TextClock clock = new TextClock(a);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        style(clock, 22, p.text, display);
        clock.setLetterSpacing(DISPLAY_TRACKING);
        clock.setFontFeatureSettings("tnum");
        TextClock date = new TextClock(a);
        date.setFormat12Hour("EEE, d MMM");
        date.setFormat24Hour("EEE, d MMM");
        // Hanken, as the weather's sky line beside it.
        style(date, 11, p.text2, body);
        StudioMotion.softText(clock);
        StudioMotion.softText(date);
        clockBlock.addView(clock);
        clockBlock.addView(date);
        bar.addView(clockBlock);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(66),
                Gravity.BOTTOM);
        barLp.leftMargin = px(28);
        barLp.rightMargin = px(28);
        barLp.bottomMargin = px(16);
        root.addView(bar, barLp);

        a.setContentView(root);
        a.getWindow().setBackgroundDrawable(null);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        a.registerReceiver(packages, f);

        rebuild();
        focusFirst();
        // Startup: a short fade in, no splash and no wait. A theme change redraws at once instead.
        if (motion && !themeSwitch) {
            root.setAlpha(0f);
            root.animate().alpha(1f).setDuration(StudioMotion.HERO_IN)
                    .setInterpolator(StudioMotion.SETTLE).withLayer().start();
        }
        themeSwitch = false;
    }

    void onResume() {
        // Back from an app: Home settles in from slightly faded rather than snapping.
        if (paused && motion && root.getAlpha() == 1f) {
            root.setAlpha(0.6f);
            root.animate().alpha(1f).setDuration(StudioMotion.FOCUS_IN).setInterpolator(StudioMotion.SETTLE)
                    .withLayer().start();
        }
        paused = false;
        updateGreeting();
        if (dirty) rebuild();
        if (focused != null && focused.isAttachedToWindow() && focused.getVisibility() == View.VISIBLE) {
            focused.requestFocus();
        } else {
            focusFirst();
        }
        try {
            a.getContentResolver().registerContentObserver(WEATHER_NOW, false, weatherChanged);
        } catch (SecurityException ignored) {
        }
        askWeather();
        try {
            a.getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(net, main);
        } catch (RuntimeException ignored) {
        }
        showNet();
    }

    void onPause() {
        paused = true;
        try {
            a.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(net);
        } catch (RuntimeException ignored) {
        }
        main.removeCallbacks(weatherTick);
        a.getContentResolver().unregisterContentObserver(weatherChanged);
    }

    /** Home pressed while Home is showing: back to the first hero card, the grid scrolled to the top. */
    void onHome() {
        gridScroll.scrollTo(0, 0);
        dockScroll.scrollTo(0, 0);
        focusFirst();
    }

    void destroy() {
        main.removeCallbacks(weatherTick);
        io.shutdownNow();
        try {
            a.unregisterReceiver(packages);
        } catch (IllegalArgumentException ignored) {
        }
    }

    // ---- content ----

    private void rebuild() {
        dirty = false;
        JSONObject o = Config.overlay(a);
        cfg = o != null ? o : new JSONObject();
        liveTvPkg = cfg.optString("live_tv_package", "");
        ((TextView) root.findViewWithTag("subtitle")).setText(
                cfg.optString("studio_subtitle", "Discover, stream and do more on your TV"));
        updateGreeting();

        Map<String, App> apps = loadApps();
        List<App> ordered = order(apps, strings("studio_apps"));
        icons.clear();

        heroRow.removeAllViews();
        heroes.clear();
        heroArt.clear();
        allApps = apps;
        heroPkgs = heroPicks(apps, ordered);
        for (int slot = 0; slot < heroPkgs.size(); slot++) {
            View card = heroCard(apps.get(heroPkgs.get(slot)), slot);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            if (!heroes.isEmpty()) lp.leftMargin = px(14);
            heroRow.addView(card, lp);
            heroes.add(card);
        }

        gridApps = ordered;
        buildTabs();
        fillGrid();

        dock.removeAllViews();
        dockItems.clear();
        List<String> dockKeys = strings("studio_dock");
        List<App> dockApps = new ArrayList<>();
        for (String key : dockKeys) if (apps.containsKey(key)) dockApps.add(apps.get(key));
        if (dockApps.isEmpty()) dockApps = ordered.subList(0, Math.min(10, ordered.size()));
        for (App app : dockApps) {
            View item = dockIcon(app);
            dock.addView(item);
            dockItems.add(item);
        }
        lastTop = clamp(lastTop, top.size());
        lastHero = clamp(lastHero, heroes.size());
        lastDock = clamp(lastDock, dockItems.size());
    }

    /**
     * The tabs under "Your apps": All, then each category the grid's apps have, the biggest first. The tab chosen
     * before a rebuild stays chosen while it still has apps.
     */
    private void buildTabs() {
        Map<String, Integer> counts = new HashMap<>();
        for (App app : gridApps) counts.merge(category(app), 1, Integer::sum);
        counts.merge(SETTINGS_CATEGORY, 1, Integer::sum);
        List<String> names = new ArrayList<>(counts.keySet());
        Collator c = Collator.getInstance();
        names.sort((x, y) -> !counts.get(x).equals(counts.get(y)) ? counts.get(y) - counts.get(x) : c.compare(x, y));
        if (names.remove(OTHER)) names.add(OTHER);
        names.add(0, ALL);
        if (!names.contains(tab)) tab = ALL;
        tabRow.removeAllViews();
        tabs.clear();
        tabColors.clear();
        for (String name : names) {
            View t = tabChip(name);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            if (!tabs.isEmpty()) lp.leftMargin = px(8);
            tabRow.addView(t, lp);
            tabs.add(t);
        }
    }

    private View tabChip(String name) {
        TextView t = label(11, p.text2);
        t.setText(name);
        t.setTag(name);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(14), 0, px(14), 0);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(px(15));
        t.setBackground(pill);
        int[] rest = tabStyle(name, false);
        tabColors.put(t, new StudioMotion.Colors(c -> {
            pill.setColor(c[0]);
            pill.setStroke(px(1), c[1]);
            t.setTextColor(c[2]);
        }, rest));
        // Moving onto a tab shows its apps at once, as Google TV's tabs do; OK does the same.
        focusable(t, 1.06f, hasFocus -> {
            if (hasFocus) {
                selectTab(name);
                // Keep the focused tab near the middle, so the next ones are always in sight.
                tabScroll.post(() -> tabScroll.smoothScrollTo(t.getLeft() + t.getWidth() / 2 - tabScroll.getWidth() / 2, 0));
            }
            styleTab(t, hasFocus);
        });
        t.setOnClickListener(v -> selectTab(name));
        return t;
    }

    /**
     * Noirly's chip: faint fill and a hairline. Chosen: inverted, cream with dark words (its active state).
     * Focused: gold, the ring colour. The pill and its words ease from one to the next.
     */
    private void styleTab(TextView t, boolean hasFocus) {
        StudioMotion.Colors c = tabColors.get(t);
        if (c != null) c.to(StudioMotion.TAB, tabStyle((String) t.getTag(), hasFocus));
    }

    /** Fill, edge and words of a tab. */
    private int[] tabStyle(String name, boolean hasFocus) {
        if (hasFocus) return new int[]{p.accent, p.accent, p.accentInk};
        if (tab.equals(name)) return new int[]{p.text, p.text, p.bg};
        return new int[]{p.chipFill, p.hairlineStrong, p.text2};
    }

    private void selectTab(String name) {
        if (tab.equals(name)) return;
        tab = name;
        for (View t : tabs) styleTab((TextView) t, t.hasFocus());
        fillGrid();
        gridScroll.scrollTo(0, 0);
        lastCard = 0;
        // The new cards rise a few dp into place as they fade in; only the grid redraws, not the page.
        if (motion) StudioMotion.reveal(grid, px(8), StudioMotion.GRID);
    }

    /** The app cards of the chosen tab, COLUMNS to a row, with the Settings card under All and System. */
    private void fillGrid() {
        List<App> shown = new ArrayList<>();
        for (App app : gridApps) if (tab.equals(ALL) || tab.equals(category(app))) shown.add(app);
        boolean withSettings = tab.equals(ALL) || tab.equals(SETTINGS_CATEGORY);
        grid.removeAllViews();
        cards.clear();
        LinearLayout row = null;
        int total = shown.size() + (withSettings ? 1 : 0);
        for (int i = 0; i < total; i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(54));
                if (i > 0) rlp.topMargin = px(10);
                grid.addView(row, rlp);
            }
            View card = i < shown.size() ? appCard(shown.get(i)) : settingsCard();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            if (i % COLUMNS > 0) lp.leftMargin = px(10);
            row.addView(card, lp);
            cards.add(card);
        }
        // A short last row keeps its cards at full width.
        int missing = (COLUMNS - cards.size() % COLUMNS) % COLUMNS;
        for (int i = 0; i < missing && row != null; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1);
            lp.leftMargin = px(10);
            row.addView(new View(a), lp);
        }
        lastCard = clamp(lastCard, cards.size());
    }

    private Map<String, App> loadApps() {
        Set<String> hidden = new HashSet<>(strings("hidden_apps"));
        hidden.add(a.getPackageName());
        Map<String, App> apps = new LinkedHashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            String pkg = ri.activityInfo.packageName;
            if (hidden.contains(pkg) || apps.containsKey(pkg)) continue;
            CharSequence label = ri.loadLabel(pm);
            apps.put(pkg, new App(pkg, label != null ? label.toString() : pkg, ri));
        }
        return apps;
    }

    /** studio_apps first, in that order; the rest by name. */
    private static List<App> order(Map<String, App> apps, List<String> first) {
        List<App> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (String key : first) {
            App app = apps.get(key);
            if (app != null && used.add(key)) out.add(app);
        }
        List<App> rest = new ArrayList<>();
        for (App app : apps.values()) if (!used.contains(app.key)) rest.add(app);
        Collator c = Collator.getInstance();
        rest.sort((x, y) -> c.compare(x.label, y.label));
        out.addAll(rest);
        return out;
    }

    /**
     * The three big cards: the theme's dock_apps (topped up from Your apps when fewer are installed), then the owner's
     * own choice for each slot, made by holding OK on a card (HERO_PREFS).
     */
    private List<String> heroPicks(Map<String, App> apps, List<App> ordered) {
        List<String> out = new ArrayList<>();
        for (String key : strings("dock_apps")) if (apps.containsKey(key) && !out.contains(key)) out.add(key);
        for (App app : ordered) if (out.size() < HEROES && !out.contains(app.key)) out.add(app.key);
        while (out.size() > HEROES) out.remove(out.size() - 1);
        SharedPreferences chosen = a.getSharedPreferences(HERO_PREFS, Context.MODE_PRIVATE);
        for (int slot = 0; slot < out.size(); slot++) {
            String pick = chosen.getString("slot" + slot, "");
            if (!apps.containsKey(pick)) continue;
            int was = out.indexOf(pick);
            if (was >= 0) out.set(was, out.get(slot));   // already on another card: they trade places
            out.set(slot, pick);
        }
        return out;
    }

    /** Hold OK on a big card: every app, the one there now marked; OK puts it on this card. */
    private void showHeroPicker(int slot, View anchor) {
        LinearLayout rows = new LinearLayout(a);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(px(8), px(10), px(8), px(8));
        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackground(rounded(p.surface, 14, p.hairlineStrong));
        scroll.addView(rows);
        PopupWindow popup = new PopupWindow(scroll, px(260), Math.min(px(360),
                a.getResources().getDisplayMetrics().heightPixels - px(80)), true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));

        rows.addView(popupHeading("Big card " + (slot + 1)));
        View current = null;
        rows.addView(pickRow("Default", null, false, () -> { popup.dismiss(); chooseHero(slot, null); }));
        List<App> sorted = new ArrayList<>(allApps.values());
        Collator c = Collator.getInstance();
        sorted.sort((x, y) -> c.compare(x.label, y.label));
        for (App app : sorted) {
            boolean on = app.key.equals(heroPkgs.get(slot));
            View row = pickRow(app.label, circle(icon(app)), on, () -> { popup.dismiss(); chooseHero(slot, app.key); });
            rows.addView(row);
            if (on) current = row;
        }
        StudioMotion.popup(popup);
        popup.showAsDropDown(anchor, (anchor.getWidth() - px(260)) / 2, -anchor.getHeight() / 2);
        if (motion) StudioMotion.drop(scroll, px(6));
        View focus = current != null ? current : rows.getChildAt(1);
        focus.requestFocus();
    }

    private View pickRow(String label, Bitmap icon, boolean on, Runnable action) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(10), px(6), px(10), px(6));
        if (icon != null) {
            ImageView image = new ImageView(a);
            image.setImageBitmap(icon);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(px(24), px(24));
            ilp.rightMargin = px(10);
            row.addView(image, ilp);
        }
        TextView name = displayText(15);
        name.setText(label);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView mark = label(9, p.accent);
        mark.setText(on ? "On" : "");
        row.addView(mark);
        Drawable lit = rounded(p.accentSoft, 9, p.accent);
        row.setFocusable(true);
        row.setOnFocusChangeListener((v, f) -> {
            v.setBackground(f ? lit : null);
            name.setTextColor(f ? p.accent : p.text);
        });
        row.setOnClickListener(v -> action.run());
        return row;
    }

    /** Keeps the choice for this card (null: back to the theme's) and redraws Home with focus on it. */
    private void chooseHero(int slot, String pkg) {
        SharedPreferences.Editor e = a.getSharedPreferences(HERO_PREFS, Context.MODE_PRIVATE).edit();
        if (pkg == null) {
            e.remove("slot" + slot);
        } else {
            // The app may sit on another card by its own choice: that card takes this card's old app.
            for (int other = 0; other < heroPkgs.size(); other++) {
                if (other != slot && pkg.equals(heroPkgs.get(other))) e.putString("slot" + other, heroPkgs.get(slot));
            }
            e.putString("slot" + slot, pkg);
        }
        e.apply();
        rebuild();
        lastHero = slot;
        go(heroes, slot);
    }

    private View heroCard(App app, int slot) {
        Drawable banner = banner(app.info);
        Bitmap art = banner != null ? toBitmap(banner, 320, 180) : null;
        int color = art != null ? dominant(art) : dominant(icon(app));
        FrameLayout card = new FrameLayout(a);
        card.setClipToOutline(true);
        // The app's own colour, lit at the top left and deepening to the bottom right, with a faint light edge.
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{shade(color, 1.25f), color, shade(color, 0.55f)});
        fill.setCornerRadius(px(18));
        fill.setStroke(px(1), 0x33FFFFFF);
        card.setBackground(fill);
        card.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);

        // Two soft circles, as the mockup's cards have: a deeper one of the app's colour, and a light sheen.
        View blob = new View(a);
        blob.setBackground(oval((shade(color, 0.7f) & 0x00FFFFFF) | 0x99000000));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(px(170), px(170), Gravity.BOTTOM | Gravity.START);
        blp.leftMargin = -px(40);
        blp.bottomMargin = -px(80);
        card.addView(blob, blp);
        View arc = new View(a);
        arc.setBackground(oval(0x1AFFFFFF));
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(px(260), px(260), Gravity.BOTTOM | Gravity.END);
        alp.rightMargin = -px(70);
        alp.bottomMargin = -px(150);
        card.addView(arc, alp);

        ImageView image = new ImageView(a);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (art != null) image.setImageBitmap(roundedBitmap(art, 10));
        else image.setImageBitmap(circle(icon(app)));
        image.setElevation(px(6));
        FrameLayout.LayoutParams ilp = art != null
                ? new FrameLayout.LayoutParams(px(96), px(54), Gravity.BOTTOM | Gravity.END)
                : new FrameLayout.LayoutParams(px(54), px(54), Gravity.BOTTOM | Gravity.END);
        ilp.rightMargin = px(16);
        ilp.bottomMargin = px(10);
        card.addView(image, ilp);
        heroArt.add(image);

        LinearLayout words = new LinearLayout(a);
        words.setOrientation(LinearLayout.VERTICAL);
        // White on the app's colour, whatever the palette.
        TextView name = displayText(22);
        name.setTextColor(Color.WHITE);
        name.setText(app.label);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        TextView line = text(12, 0xD9FFFFFF, body);
        line.setText(tagline(app));
        line.setSingleLine(true);
        line.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(name);
        words.addView(line);
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        wlp.leftMargin = px(18);
        wlp.topMargin = px(14);
        wlp.rightMargin = px(18);
        card.addView(words, wlp);

        View ring = new View(a);
        ring.setBackground(rounded(Color.TRANSPARENT, 18, p.accent, 3));
        ring.setAlpha(0f);
        card.addView(ring, match());
        // Apple TV-style: the card rises with a glow of the theme's accent under it, its ring fades in, and the
        // artwork on every card drifts a little with the focus (heroParallax).
        focusable(card, 1.05f, 14, StudioMotion.HERO_IN, hasFocus -> {
            ring.animate().alpha(hasFocus ? 1f : 0f).setDuration(hasFocus ? StudioMotion.HERO_IN : StudioMotion.FOCUS_OUT)
                    .setInterpolator(StudioMotion.EASE).start();
            if (hasFocus) heroParallax(heroes.indexOf(card));
            else main.post(() -> { if (!heroes.contains(a.getCurrentFocus())) heroParallax(-1); });
        });
        card.setOnClickListener(v -> launch(app));
        card.setOnLongClickListener(v -> {
            showHeroPicker(slot, v);
            return true;
        });
        return card;
    }

    /**
     * The hero row's parallax, without sensors: the focused card's artwork comes forward a touch, and the others
     * lean away from it, so moving along the row shifts the artwork at its own pace. -1 settles them all.
     */
    private void heroParallax(int focus) {
        for (int j = 0; j < heroArt.size(); j++) {
            View art = heroArt.get(j);
            float shift = focus < 0 || j == focus ? 0f : Math.signum(j - focus) * px(6);
            float scale = j == focus ? 1.06f : 1f;
            art.animate().translationX(shift).translationY(j == focus ? -px(2) : 0f).scaleX(scale).scaleY(scale)
                    .setDuration(StudioMotion.HERO_IN).setInterpolator(StudioMotion.EASE).start();
        }
    }

    private View appCard(App app) {
        return listCard(app.label, category(app), circleless(icon(app)), () -> launch(app));
    }

    private View settingsCard() {
        Bitmap gear = Bitmap.createBitmap(px(40), px(40), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(gear);
        Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
        plate.setColor(p.surface2);
        c.drawRoundRect(new RectF(0, 0, gear.getWidth(), gear.getHeight()), px(10), px(10), plate);
        Drawable d = a.getDrawable(R.drawable.ic_settings);
        if (d != null) {
            d.setTint(p.text);
            int inset = px(9);
            d.setBounds(inset, inset, gear.getWidth() - inset, gear.getHeight() - inset);
            d.draw(c);
        }
        return listCard("Settings", "System", gear, () -> open(SystemSettings.intent(a, Settings.ACTION_SETTINGS)));
    }

    private View listCard(String title, String subtitle, Bitmap icon, Runnable action) {
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(px(10), 0, px(10), 0);
        // Glass at r-md; focused, brighter glass with the accent ring, cross-faded rather than swapped.
        TransitionDrawable glass = fader(glass(12, false), glass(12, true));
        card.setBackground(glass);
        ImageView image = new ImageView(a);
        image.setImageBitmap(icon);
        card.addView(image, new LinearLayout.LayoutParams(px(40), px(40)));
        LinearLayout words = new LinearLayout(a);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = displayText(15);
        name.setText(title);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        TextView kind = text(11, p.text2, body);   // Hanken, as the subtitle under the greeting, a step below the title
        kind.setText(subtitle);
        kind.setSingleLine(true);
        words.addView(name);
        words.addView(kind);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        wlp.leftMargin = px(12);
        card.addView(words, wlp);
        focusable(card, 1.04f, hasFocus -> fade(glass, hasFocus));
        card.setOnClickListener(v -> action.run());
        return card;
    }

    private View dockIcon(App app) {
        FrameLayout item = new FrameLayout(a);
        item.setClipChildren(false);
        ImageView image = new ImageView(a);
        image.setImageBitmap(circle(icon(app)));
        item.addView(image, new FrameLayout.LayoutParams(px(44), px(44), Gravity.CENTER));
        View ring = new View(a);
        ring.setBackground(oval(Color.TRANSPARENT, p.accent, 3));
        ring.setAlpha(0f);
        item.addView(ring, new FrameLayout.LayoutParams(px(50), px(50), Gravity.CENTER));
        item.setContentDescription(app.label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(54), px(54));
        lp.leftMargin = px(3);
        lp.rightMargin = px(3);
        item.setLayoutParams(lp);
        // The icon's round outline casts the accent glow; the bar itself never moves or resizes.
        int inset = px(5);
        item.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setOval(inset, inset, v.getWidth() - inset, v.getHeight() - inset);
            }
        });
        StudioMotion.glow(item, p.accent);
        StudioMotion.Spring spring = new StudioMotion.Spring(item);
        item.setFocusable(true);
        item.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) focused = v;
            ring.animate().alpha(hasFocus ? 1f : 0f).setDuration(hasFocus ? StudioMotion.DOCK : StudioMotion.FOCUS_OUT)
                    .setInterpolator(StudioMotion.EASE).start();
            v.setElevation(hasFocus ? px(4) : 0);
            v.animate().translationZ(hasFocus ? px(8) : 0).setDuration(StudioMotion.DOCK).start();
            spring.to(hasFocus ? 1.15f : 1f, motion);
        });
        item.setOnClickListener(v -> launch(app));
        return item;
    }

    private View roundButton(int iconRes, String label, Runnable action) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setClipChildren(false);
        FrameLayout circle = new FrameLayout(a);
        // Noirly's .btn-icon: a thin outline on the surface; focused, the accent fill with accent-ink, eased.
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        circle.setBackground(disc);
        circle.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        StudioMotion.glow(circle, p.accent);
        ImageView icon = new ImageView(a);
        Drawable d = a.getDrawable(iconRes);
        if (d != null) d = d.mutate();
        if (d != null) d.setTint(p.text);
        icon.setImageDrawable(d);
        int pad = px(10);
        icon.setPadding(pad, pad, pad, pad);
        circle.addView(icon, match());
        box.addView(circle, new LinearLayout.LayoutParams(px(42), px(42)));
        TextView name = label(9, p.text2);
        name.setText(label);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = px(5);
        box.addView(name, nlp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(62), ViewGroup.LayoutParams.WRAP_CONTENT);
        box.setLayoutParams(lp);
        final Drawable icn = d;
        lastIcon = d;
        lastLabel = name;
        StudioMotion.Colors look = new StudioMotion.Colors(c -> {
            disc.setColor(c[0]);
            disc.setStroke(px(1), c[1]);
            if (icn != null) icn.setTint(c[2]);
            name.setTextColor(c[3]);
        }, p.surface, p.hairlineStrong, p.text, p.text2);
        focusable(box, 1.08f, 0, StudioMotion.FOCUS_IN, hasFocus -> {
            // At rest the Wi-Fi icon keeps its status colour (showNet).
            int rest = icn != null && icn == wifiIcon ? wifiColor : p.text;
            look.to(hasFocus ? StudioMotion.FOCUS_IN : StudioMotion.FOCUS_OUT, hasFocus ? p.accent : p.surface,
                    hasFocus ? p.accent : p.hairlineStrong, hasFocus ? p.accentInk : rest, hasFocus ? p.accent : p.text2);
            circle.animate().translationZ(hasFocus ? px(8) : 0).setDuration(StudioMotion.FOCUS_IN)
                    .setInterpolator(StudioMotion.EASE).start();
        });
        box.setOnClickListener(v -> action.run());
        top.add(box);
        return box;
    }

    /** The Wi-Fi icon's colour at rest: green online, orange connected without internet, red offline. */
    private void showNet() {
        ConnectivityManager cm = a.getSystemService(ConnectivityManager.class);
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        wifiColor = nc == null ? 0xFFEF4444
                : nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 0xFF22C55E : 0xFFF59E0B;
        View b = top.size() > 1 ? top.get(1) : null;
        if (wifiIcon != null && b != null && !b.hasFocus()) wifiIcon.setTint(wifiColor);
        // wifi_label_width (dp): the network name under the button instead of "Wi-Fi", cut with an ellipsis.
        int w = cfg.optInt("wifi_label_width", a.getResources().getInteger(R.integer.wifi_label_width));
        if (w <= 0 || wifiLabel == null || b == null) return;
        String ssid = null;
        if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            WifiInfo info = a.getSystemService(WifiManager.class).getConnectionInfo();
            ssid = info != null ? info.getSSID().replace("\"", "").trim() : null;
            if (ssid != null && (ssid.isEmpty() || ssid.contains("unknown"))) ssid = null;
        }
        wifiLabel.setText(ssid != null ? ssid : nc == null ? "Offline" : "Wi-Fi");
        wifiLabel.setSingleLine(true);
        wifiLabel.setEllipsize(TextUtils.TruncateAt.END);
        wifiLabel.setMaxWidth(px(w));
        b.getLayoutParams().width = ViewGroup.LayoutParams.WRAP_CONTENT;
        b.setMinimumWidth(px(62));
        b.requestLayout();
    }

    /** Android tells Home when the network comes, goes or passes its internet check. */
    private final ConnectivityManager.NetworkCallback net = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { showNet(); }

        @Override
        public void onLost(Network n) { main.post(StudioHome.this::showNet); }
    };

    private interface FocusChange { void on(boolean hasFocus); }

    private void focusable(View v, float scale, FocusChange change) {
        focusable(v, scale, 6, StudioMotion.FOCUS_IN, change);
    }

    /**
     * Focus zooms the view to scale and lifts it by liftDp, casting the theme's accent glow where its outline is
     * solid; the old zoom is retargeted, never queued. The focused view is raised at once, so it draws over its
     * neighbours while it grows.
     */
    private void focusable(View v, float scale, float liftDp, int inMs, FocusChange change) {
        v.setFocusable(true);
        StudioMotion.glow(v, p.accent);
        v.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) focused = view;
            change.on(hasFocus);
            view.setElevation(hasFocus ? px(4) : 0);
            StudioMotion.zoom(view, hasFocus, scale, px(liftDp), inMs);
        });
    }

    /** Two backgrounds that cross-fade, for focus. */
    private static TransitionDrawable fader(Drawable rest, Drawable lit) {
        TransitionDrawable t = new TransitionDrawable(new Drawable[]{rest, lit});
        t.setCrossFadeEnabled(true);
        return t;
    }

    private static void fade(TransitionDrawable t, boolean lit) {
        if (lit) t.startTransition(StudioMotion.FOCUS_IN);
        else t.reverseTransition(StudioMotion.FOCUS_OUT);
    }

    // ---- keys ----

    /** D-pad moves between the header buttons, the hero cards, the app cards and the bar, one step at a time. */
    boolean dispatchKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (settingsKey(event)) return true;
        if (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN
                && code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        View now = a.getCurrentFocus();
        int i;
        if ((i = top.indexOf(now)) >= 0) {
            lastTop = i;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) go(top, i - 1);
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) go(top, i + 1);
            else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (!heroes.isEmpty()) go(heroes, heroes.size() - 1);
                else go(cards, 0);
            }
        } else if ((i = heroes.indexOf(now)) >= 0) {
            lastHero = i;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) go(heroes, i - 1);
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) go(heroes, i + 1);
            else if (code == KeyEvent.KEYCODE_DPAD_UP) go(top, lastTop);
            else focusTab();
        } else if ((i = tabs.indexOf(now)) >= 0) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) go(tabs, i - 1);
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) go(tabs, i + 1);
            else if (code == KeyEvent.KEYCODE_DPAD_UP) {
                if (!heroes.isEmpty()) go(heroes, lastHero);
                else go(top, lastTop);
            } else if (!cards.isEmpty()) {
                gridScroll.smoothScrollTo(0, 0);
                go(cards, 0);
            }
        } else if ((i = cards.indexOf(now)) >= 0) {
            lastCard = i;
            int col = i % COLUMNS;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) { if (col > 0) go(cards, i - 1); }
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { if (col < COLUMNS - 1 && i + 1 < cards.size()) go(cards, i + 1); }
            else if (code == KeyEvent.KEYCODE_DPAD_UP) {
                if (i >= COLUMNS) go(cards, i - COLUMNS);
                else focusTab();
            } else {
                int lastRow = (cards.size() - 1) / COLUMNS;
                if (i + COLUMNS < cards.size()) go(cards, i + COLUMNS);
                else if (i / COLUMNS < lastRow) go(cards, cards.size() - 1);
                else if (!dockItems.isEmpty()) go(dockItems, lastDock);
            }
        } else if ((i = dockItems.indexOf(now)) >= 0) {
            lastDock = i;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) go(dockItems, i - 1);
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (i == dockItems.size() - 1 && weather.getVisibility() == View.VISIBLE) weather.requestFocus();
                else go(dockItems, i + 1);
            } else if (code == KeyEvent.KEYCODE_DPAD_UP) {
                if (!cards.isEmpty()) go(cards, lastCard);
                else if (!heroes.isEmpty()) go(heroes, lastHero);
            }
        } else if (now == weather) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) go(dockItems, dockItems.size() - 1);
            else if (code == KeyEvent.KEYCODE_DPAD_UP) {
                if (!cards.isEmpty()) go(cards, cards.size() - 1);
                else if (!heroes.isEmpty()) go(heroes, heroes.size() - 1);
            }
        } else {
            focusFirst();
        }
        return true;
    }

    /** Onto the chosen tab, which keeps the grid as it is. */
    private void focusTab() {
        for (View t : tabs) {
            if (tab.equals(t.getTag())) {
                t.requestFocus();
                return;
            }
        }
        go(tabs, 0);
    }

    /**
     * The remote's Settings button while Home is on screen, as MainActivity does for the classic home: a key in
     * settings_key_keycodes (config.xml, or the theme's config.json) opens the TV's Settings, or the quick settings
     * panel with settings_key_panel. Other apps need HomeKeyService for it; this covers Home without one.
     */
    private boolean settingsKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && !KeyEvent.isGamepadButton(code)
                && (code < KeyEvent.KEYCODE_DPAD_UP || code > KeyEvent.KEYCODE_DPAD_CENTER)) {
            // Which code a remote button sends, for settings_key_keycodes: adb logcat -s NoirlyHome
            android.util.Log.i("NoirlyHome", "key " + code + " (" + KeyEvent.keyCodeToString(code) + ") scan "
                    + event.getScanCode());
        }
        boolean match = code == KeyEvent.KEYCODE_SETTINGS;
        JSONArray own = cfg.optJSONArray("settings_key_keycodes");
        if (own != null) {
            for (int i = 0; i < own.length(); i++) match |= own.optInt(i, -1) == code;
        } else {
            for (int c : a.getResources().getIntArray(R.array.settings_key_keycodes)) match |= c == code;
        }
        if (!match) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            boolean panel = cfg.optBoolean("settings_key_panel", a.getResources().getBoolean(R.bool.settings_key_panel));
            open(panel ? new Intent(a, QuickSettingsActivity.class) : SystemSettings.intent(a, Settings.ACTION_SETTINGS));
        }
        return true;
    }

    private void go(List<View> views, int index) {
        if (views.isEmpty()) return;
        views.get(Math.max(0, Math.min(views.size() - 1, index))).requestFocus();
    }

    private void focusFirst() {
        if (!heroes.isEmpty()) heroes.get(0).requestFocus();
        else if (!cards.isEmpty()) cards.get(0).requestFocus();
        else if (!top.isEmpty()) top.get(0).requestFocus();
    }

    // ---- actions ----

    private void launch(App app) {
        ApplicationInfo ai = app.info.activityInfo.applicationInfo;
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setClassName(ai.packageName, app.info.activityInfo.name);
        open(i);
    }

    private void open(Intent i) {
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            a.startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(a, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** A small glass list under the Inputs button: the TV's HDMI and AV inputs, then Live TV. */
    private void showInputs() {
        LinearLayout rows = new LinearLayout(a);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(px(6), px(10), px(6), px(6));
        rows.setBackground(rounded(p.surface, 14, p.hairlineStrong));
        PopupWindow popup = new PopupWindow(rows, px(190), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        TvInputManager tim = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = new ArrayList<>();
        if (tim != null) {
            try {
                for (TvInputInfo info : tim.getTvInputList()) {
                    if (info.isPassthroughInput() && info.getParentId() == null && !info.isHidden(a)) inputs.add(info);
                }
            } catch (RuntimeException ignored) {
            }
        }
        // The framework lists inputs in no useful order (AV, HDMI 3, HDMI 1, HDMI 2 on the TCL): HDMI ports
        // first, then the rest, each by name.
        Map<String, String> labels = new LinkedHashMap<>();
        for (TvInputInfo info : inputs) {
            CharSequence custom = info.loadCustomLabel(a);
            labels.put(info.getId(), custom != null && custom.length() > 0 ? custom.toString() : String.valueOf(info.loadLabel(a)));
        }
        List<String> ids = new ArrayList<>(labels.keySet());
        ids.sort((x, y) -> {
            String lx = labels.get(x), ly = labels.get(y);
            boolean hx = lx.toUpperCase().startsWith("HDMI"), hy = ly.toUpperCase().startsWith("HDMI");
            return hx != hy ? (hx ? -1 : 1) : lx.compareToIgnoreCase(ly);
        });
        rows.addView(popupHeading("Inputs"));
        for (String id : ids) rows.addView(inputRow(labels.get(id), () -> { popup.dismiss(); openInput(id); }));
        if (!liveTvPkg.isEmpty()) {
            rows.addView(inputRow("Live TV", () -> {
                popup.dismiss();
                Intent i = pm.getLeanbackLaunchIntentForPackage(liveTvPkg);
                open(i != null ? i : pm.getLaunchIntentForPackage(liveTvPkg));
            }));
        }
        if (rows.getChildCount() == 1) {   // the heading alone
            Toast.makeText(a, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        View anchor = top.get(0);
        StudioMotion.popup(popup);
        popup.showAsDropDown(anchor, -px(64), px(8));
        if (motion) StudioMotion.drop(rows, px(6));
        rows.getChildAt(1).requestFocus();
    }

    private View inputRow(String label, Runnable action) {
        // Inter, like the tabs and button labels; quiet at rest, the accent pill on focus.
        TextView row = label(12, p.text2);
        row.setText(label);
        row.setPadding(px(14), px(11), px(14), px(11));
        Drawable lit = rounded(p.accentSoft, 10, p.accent);
        row.setFocusable(true);
        row.setOnFocusChangeListener((v, f) -> {
            v.setBackground(f ? lit : null);
            row.setTextColor(f ? p.accent : p.text2);
        });
        row.setOnClickListener(v -> action.run());
        return row;
    }

    /**
     * The Theme button's list, glass like the inputs one: Dark and Light, then Noirly's seven palettes with their
     * accent as a swatch. A choice is kept and Home redraws in it at once.
     */
    private void showThemes(View anchor) {
        LinearLayout rows = new LinearLayout(a);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(px(8), px(10), px(8), px(8));
        rows.setBackground(rounded(p.surface, 14, p.hairlineStrong));
        PopupWindow popup = new PopupWindow(rows, px(230), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));

        rows.addView(popupHeading("Mode"));
        LinearLayout modes = new LinearLayout(a);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.setPadding(px(6), px(4), px(6), px(8));
        View first = null;
        for (boolean dark : new boolean[]{true, false}) {
            TextView chip = label(10, p.text2);
            chip.setText(dark ? "Dark" : "Light");
            chip.setGravity(Gravity.CENTER);
            boolean on = dark == p.dark;
            Drawable normal = on ? rounded(p.text, 15, p.text) : rounded(p.chipFill, 15, p.hairlineStrong);
            Drawable lit = rounded(p.accent, 15, p.accent);
            chip.setTextColor(on ? p.bg : p.text2);
            chip.setBackground(normal);
            chip.setFocusable(true);
            chip.setOnFocusChangeListener((v, f) -> {
                v.setBackground(f ? lit : normal);
                chip.setTextColor(f ? p.accentInk : on ? p.bg : p.text2);
            });
            chip.setOnClickListener(v -> { popup.dismiss(); applyTheme(p.id, dark); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(30), 1);
            if (!dark) lp.leftMargin = px(8);
            modes.addView(chip, lp);
            if (on) first = chip;
        }
        rows.addView(modes);

        rows.addView(popupHeading("Palette"));
        View current = null;
        for (String[] theme : NoirlyPalette.THEMES) {
            View row = themeRow(theme, () -> { popup.dismiss(); applyTheme(theme[0], p.dark); });
            rows.addView(row);
            if (theme[0].equals(p.id)) current = row;
        }
        popup.showAsDropDown(anchor, -px(84), px(8));
        (current != null ? current : first != null ? first : rows.getChildAt(1)).requestFocus();
    }

    private TextView popupHeading(String words) {
        TextView t = label(9, p.muted);
        t.setText(words);
        t.setPadding(px(8), px(4), px(8), px(4));
        return t;
    }

    /** One palette: its accent as a dot, its name, and "On" beside the one in use. */
    private View themeRow(String[] theme, Runnable action) {
        boolean on = theme[0].equals(p.id);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(10), px(7), px(10), px(7));
        View dot = new View(a);
        dot.setBackground(oval(NoirlyPalette.swatch(theme, p.dark), p.hairlineStrong, 1));
        row.addView(dot, new LinearLayout.LayoutParams(px(14), px(14)));
        TextView name = displayText(15);
        name.setText(theme[1]);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        nlp.leftMargin = px(10);
        row.addView(name, nlp);
        TextView mark = label(9, p.accent);
        mark.setText(on ? "On" : "");
        row.addView(mark);
        Drawable lit = rounded(p.accentSoft, 9, p.accent);
        row.setFocusable(true);
        row.setOnFocusChangeListener((v, f) -> {
            v.setBackground(f ? lit : null);
            name.setTextColor(f ? p.accent : p.text);
        });
        row.setOnClickListener(v -> action.run());
        return row;
    }

    /** Keeps the choice and rebuilds Home in it; the Theme button keeps the focus. */
    private void applyTheme(String id, boolean dark) {
        if (id.equals(p.id) && dark == p.dark) return;
        a.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE).edit()
                .putString("palette", id).putBoolean("dark", dark).apply();
        themeSwitch = true;
        a.recreate();
    }

    /** The standard way to show a passthrough input: its channel URI, aimed at the Live TV app first. */
    private void openInput(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!liveTvPkg.isEmpty()) {
            try {
                a.startActivity(new Intent(view).setPackage(liveTvPkg));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        open(view);
    }

    private void updateGreeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String part = h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 17 ? "Good afternoon"
                : h < 21 ? "Good evening" : "Good night";
        String name = cfg.optString("greeting_name", "").trim();
        greeting.setText(name.isEmpty() ? part : part + ", " + name);
    }

    /**
     * Asks the Weather add-on for the weather now, off the UI thread, and asks again every WEATHER_TICK_MS while
     * Home is resumed. No add-on: the block stays hidden. No city chosen yet: it offers to choose one.
     */
    private void askWeather() {
        main.removeCallbacks(weatherTick);
        main.postDelayed(weatherTick, WEATHER_TICK_MS);
        io.execute(() -> {
            String temp = null, sky = null;
            int icon = 0;
            boolean installed = false;
            try (Cursor c = a.getContentResolver().query(WEATHER_NOW, null, null, null, null)) {
                if (c != null) {
                    installed = true;
                    if (c.moveToFirst()) {
                        temp = c.getInt(c.getColumnIndexOrThrow("temp")) + "°";
                        sky = c.getString(c.getColumnIndexOrThrow("sky"));
                        // "studio_weather_place": false in the theme leaves the city off the screen.
                        if (cfg.optBoolean("studio_weather_place", true)) {
                            sky += " · " + c.getString(c.getColumnIndexOrThrow("place"));
                        }
                        // An add-on from before the code column: words only, no icon.
                        int code = c.getColumnIndex("code"), day = c.getColumnIndex("day");
                        if (code >= 0) icon = weatherIcon(c.getInt(code), day < 0 || c.getInt(day) == 1);
                    }
                }
            } catch (RuntimeException ignored) {
                // the add-on is missing, disabled or older than its provider
            }
            boolean show = installed;
            String t = temp != null ? temp : "Weather";
            String s = sky != null ? sky : "Choose a city";
            int iconRes = icon;
            main.post(() -> {
                if (a.isDestroyed()) return;
                if (iconRes != 0) weatherIcon.setImageResource(iconRes);
                weatherIcon.setVisibility(iconRes != 0 ? View.VISIBLE : View.GONE);
                weatherTemp.setText(t);
                weatherSky.setText(s);
                weather.setVisibility(show ? View.VISIBLE : View.GONE);
            });
        });
    }

    /** The bar's icon for a WMO weather code, with the moon for clear and partly cloudy nights. */
    static int weatherIcon(int code, boolean day) {
        if (code <= 1) return day ? R.drawable.ic_wx_sun : R.drawable.ic_wx_moon;
        if (code == 2) return day ? R.drawable.ic_wx_partly_day : R.drawable.ic_wx_partly_night;
        if (code == 45 || code == 48) return R.drawable.ic_wx_fog;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ic_wx_snow;
        if (code >= 95) return R.drawable.ic_wx_thunder;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return R.drawable.ic_wx_rain;
        return R.drawable.ic_wx_cloud;
    }

    // ---- words ----

    private String tagline(App app) {
        JSONObject own = cfg.optJSONObject("studio_taglines");
        if (own != null && own.has(app.key)) return own.optString(app.key);
        String t = TAGLINES.get(app.key);
        return t != null ? t : "Open " + app.label + ".";
    }

    private String category(App app) {
        JSONObject own = cfg.optJSONObject("studio_categories");
        if (own != null && own.has(app.key)) return own.optString(app.key);
        String c = CATEGORIES.get(app.key);
        if (c != null) return c;
        switch (app.info.activityInfo.applicationInfo.category) {
            case ApplicationInfo.CATEGORY_GAME: return "Games";
            case ApplicationInfo.CATEGORY_AUDIO: return "Music";
            case ApplicationInfo.CATEGORY_VIDEO: return "Entertainment";
            case ApplicationInfo.CATEGORY_IMAGE: return "Photos & Videos";
            case ApplicationInfo.CATEGORY_NEWS: return "News";
            case ApplicationInfo.CATEGORY_SOCIAL: return "Social";
            case ApplicationInfo.CATEGORY_PRODUCTIVITY: return "Productivity";
            default: return OTHER;
        }
    }

    private List<String> strings(String key) {
        List<String> out = new ArrayList<>();
        JSONArray arr = cfg.optJSONArray(key);
        if (arr != null) for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i));
        if (out.isEmpty() && "hidden_apps".equals(key)) {
            for (String s : a.getResources().getStringArray(R.array.hidden_apps)) out.add(s);
        }
        return out;
    }

    // ---- pictures ----

    /** The app's icon at a high density, drawn once into a square bitmap. */
    private Bitmap icon(App app) {
        Bitmap cached = icons.get(app.key);
        if (cached != null) return cached;
        Drawable d = null;
        int id = app.info.getIconResource();
        if (id != 0) {
            try {
                Resources r = pm.getResourcesForApplication(app.info.activityInfo.applicationInfo);
                d = r.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        if (d == null) d = app.info.loadIcon(pm);
        int size = px(48);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if (d instanceof AdaptiveIconDrawable) {
            // Full bleed: background and foreground, so the circle crop below shows the whole tile.
            AdaptiveIconDrawable ad = (AdaptiveIconDrawable) d;
            int extra = size / 4;
            if (ad.getBackground() != null) { ad.getBackground().setBounds(-extra, -extra, size + extra, size + extra); ad.getBackground().draw(c); }
            if (ad.getForeground() != null) { ad.getForeground().setBounds(-extra, -extra, size + extra, size + extra); ad.getForeground().draw(c); }
        } else {
            d.setBounds(0, 0, size, size);
            d.draw(c);
        }
        icons.put(app.key, b);
        return b;
    }

    private Drawable banner(ResolveInfo ri) {
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        Drawable banner = null;
        if (id != 0) {
            try {
                Resources r = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                banner = r.getDrawableForDensity(id, DisplayMetrics.DENSITY_XHIGH, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        if (banner == null) banner = ri.activityInfo.loadBanner(pm);
        if (banner != null && banner.getIntrinsicWidth() > 0
                && banner.getIntrinsicWidth() <= banner.getIntrinsicHeight()) banner = null;
        return banner;
    }

    /** The icon in a circle: full-bleed icons are cropped to it, icons with a transparent edge sit on white. */
    private Bitmap circle(Bitmap icon) {
        int size = icon.getWidth();
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (fullBleed(icon)) {
            p.setShader(new BitmapShader(icon, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        } else {
            p.setColor(Color.WHITE);
            c.drawCircle(size / 2f, size / 2f, size / 2f, p);
            int inset = size / 6;
            c.drawBitmap(icon, null, new RectF(inset, inset, size - inset, size - inset), new Paint(Paint.FILTER_BITMAP_FLAG));
        }
        plateEdge(c, new RectF(0, 0, size, size), size / 2f);
        return out;
    }

    /** The icon as a rounded square, for the app cards; transparent-edged icons get a white plate. */
    private Bitmap circleless(Bitmap icon) {
        int size = icon.getWidth();
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        RectF all = new RectF(0, 0, size, size);
        float r = size * 0.24f;
        if (fullBleed(icon)) {
            p.setShader(new BitmapShader(icon, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            c.drawRoundRect(all, r, r, p);
        } else {
            p.setColor(Color.WHITE);
            c.drawRoundRect(all, r, r, p);
            int inset = size / 7;
            c.drawBitmap(icon, null, new RectF(inset, inset, size - inset, size - inset), new Paint(Paint.FILTER_BITMAP_FLAG));
        }
        plateEdge(c, all, r);
        return out;
    }

    /** In light mode every icon gets the hairline edge, or white ones melt into the white cards and bar. */
    private void plateEdge(Canvas c, RectF bounds, float radius) {
        if (p.dark) return;
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1f, bounds.width() / 40f));
        edge.setColor(p.hairlineStrong);
        float inset = edge.getStrokeWidth() / 2;
        RectF r = new RectF(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset);
        c.drawRoundRect(r, radius - inset, radius - inset, edge);
    }

    private static boolean fullBleed(Bitmap b) {
        int w = b.getWidth() - 1, h = b.getHeight() - 1, inset = Math.max(1, b.getWidth() / 10);
        return Color.alpha(b.getPixel(inset, inset)) > 200 && Color.alpha(b.getPixel(w - inset, inset)) > 200
                && Color.alpha(b.getPixel(inset, h - inset)) > 200 && Color.alpha(b.getPixel(w - inset, h - inset)) > 200;
    }

    private Bitmap roundedBitmap(Bitmap src, int radiusDp) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float r = radiusDp * src.getWidth() / 118f;
        c.drawRoundRect(new RectF(0, 0, src.getWidth(), src.getHeight()), r, r, p);
        return out;
    }

    private static Bitmap toBitmap(Drawable d, int w, int h) {
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
            return Bitmap.createScaledBitmap(((BitmapDrawable) d).getBitmap(), w, h, true);
        }
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return b;
    }

    /** The most vivid colour of a picture, for the hero card behind it; a calm blue for grey artwork. */
    private static int dominant(Bitmap b) {
        Bitmap small = Bitmap.createScaledBitmap(b, 24, 24, true);
        float[] hsv = new float[3];
        float best = -1;
        int pick = 0xFF2B4C8C;
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 24; x++) {
                int px = small.getPixel(x, y);
                if (Color.alpha(px) < 200) continue;
                Color.colorToHSV(px, hsv);
                if (hsv[2] < 0.25f || (hsv[1] < 0.2f && hsv[2] > 0.85f)) continue;
                float score = hsv[1] * hsv[2];
                if (score > best) { best = score; pick = px; }
            }
        }
        if (best < 0.12f) pick = 0xFF2B4C8C;
        Color.colorToHSV(pick, hsv);
        hsv[1] = Math.min(1f, Math.max(hsv[1], 0.55f));
        hsv[2] = Math.min(hsv[2], 0.8f);
        return Color.HSVToColor(hsv);
    }

    /** The colour made brighter (factor above 1) or darker (below 1). */
    private static int shade(int color, float factor) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.min(1f, hsv[2] * factor);
        return Color.HSVToColor(hsv);
    }

    // ---- small builders ----

    /**
     * Frosted glass without blur (the launcher draws none, for 1 GB TVs): a see-through white tint that fades
     * from the top left, a sheen across the top and a bright hairline edge. Lit, it brightens and takes the
     * accent ring.
     */
    private Drawable glass(float radiusDp, boolean lit) {
        int top = p.dark ? (lit ? 0x38FFFFFF : 0x24FFFFFF) : (lit ? 0xE6FFFFFF : 0xB3FFFFFF);
        int bottom = p.dark ? (lit ? 0x14FFFFFF : 0x08FFFFFF) : (lit ? 0xA6FFFFFF : 0x66FFFFFF);
        GradientDrawable tint = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{top, bottom});
        tint.setCornerRadius(px(radiusDp));
        if (lit) tint.setStroke(px(2), p.accent);
        else tint.setStroke(px(1), p.dark ? 0x33FFFFFF : 0xE6FFFFFF);
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{p.dark ? 0x1AFFFFFF : 0x66FFFFFF, Color.TRANSPARENT, Color.TRANSPARENT});
        sheen.setCornerRadius(px(radiusDp));
        return new android.graphics.drawable.LayerDrawable(new Drawable[]{tint, sheen});
    }

    private int px(float dps) { return Math.round(dps * dp); }

    private TextView text(float sp, int color, Typeface face) {
        TextView t = new TextView(a);
        style(t, sp, color, face);
        return t;
    }

    /** Inter Display, tight: titles, the greeting and the figures. */
    private TextView displayText(float sp) {
        TextView t = text(sp, p.text, display);
        t.setLetterSpacing(DISPLAY_TRACKING);
        return t;
    }

    /** Inter Display, as written: categories, tabs and small labels. */
    private TextView label(float sp, int color) {
        return text(sp, color, display);
    }

    private static void style(TextView t, float sp, int color, Typeface face) {
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        t.setPadding(0, 0, 0, 0);
    }

    private Drawable rounded(int fill, float radiusDp, int stroke) { return rounded(fill, radiusDp, stroke, 1); }

    private Drawable rounded(int fill, float radiusDp, int stroke, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(px(radiusDp));
        if (Color.alpha(stroke) > 0) g.setStroke(px(strokeDp), stroke);
        return g;
    }

    private Drawable oval(int fill) { return oval(fill, Color.TRANSPARENT, 0); }

    private Drawable oval(int fill, int stroke, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        if (strokeDp > 0) g.setStroke(px(strokeDp), stroke);
        return g;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private FrameLayout.LayoutParams at(int leftDp, int topDp, int w, int h) { return at(leftDp, topDp, w, h, 0); }

    /** Positioned in dp from the top left; w and h are dp, or MATCH_PARENT / WRAP_CONTENT. */
    private FrameLayout.LayoutParams at(int leftDp, int topDp, int w, int h, int rightDp) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w < 0 ? w : px(w), h < 0 ? h : px(h),
                Gravity.TOP | Gravity.START);
        lp.leftMargin = px(leftDp);
        lp.topMargin = px(topDp);
        lp.rightMargin = px(rightDp);
        return lp;
    }

    private static int clamp(int i, int size) { return size == 0 ? 0 : Math.max(0, Math.min(size - 1, i)); }
}
