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
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
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
 * launcher it never goes online: the weather in the bar comes from the Weather add-on (addons/weather), which
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
    /** Noirly's letter spacing: display type tight, mono labels wide (em). */
    private static final float DISPLAY_TRACKING = -0.02f, LABEL_TRACKING = 0.14f;
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
    /** Noirly's three faces: Fraunces for display and figures, Hanken Grotesk for copy, JetBrains Mono for labels. */
    private final Typeface display;
    private final Typeface body;
    private final Typeface mono;
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
        display = a.getResources().getFont(R.font.noirly_fraunces_bold);
        body = a.getResources().getFont(R.font.noirly_hanken_regular);
        mono = a.getResources().getFont(R.font.noirly_mono_medium);
        // The palette and mode chosen with the Theme button; a theme's studio_palette / studio_mode are the defaults.
        JSONObject o = Config.overlay(a);
        SharedPreferences prefs = a.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE);
        p = new NoirlyPalette(
                prefs.getString("palette", o != null ? o.optString("studio_palette", NoirlyPalette.DEFAULT_ID)
                        : NoirlyPalette.DEFAULT_ID),
                prefs.getBoolean("dark", o == null || !"light".equals(o.optString("studio_mode"))));

        // Noirly's ambient page background, drawn once (the theme's wallpaper.jpg is not used).
        root = new FrameLayout(a);
        root.setBackgroundColor(p.bg);
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
        HorizontalScrollView tabScroll = new HorizontalScrollView(a);
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
        bar.setBackground(card(33, p.hairlineStrong));
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
        // Hanken, not the mono label: the wide caps would push the block over the last dock icon.
        weatherSky = text(11, p.text2, body);
        weatherWords.addView(weatherTemp);
        weatherWords.addView(weatherSky);
        weather.addView(weatherWords);
        weather.setVisibility(View.GONE);
        Drawable weatherLit = rounded(p.accentSoft, 12, p.accent, 2);
        focusable(weather, 1.05f, hasFocus -> weather.setBackground(hasFocus ? weatherLit : null));
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
        style(date, 10, p.muted, mono);
        // Tighter than the other labels, so the bar keeps room for every dock icon.
        date.setLetterSpacing(0.04f);
        date.setAllCaps(true);
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
    }

    void onResume() {
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
    }

    void onPause() {
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
        List<String> heroKeys = strings("dock_apps");
        for (String key : heroKeys) {
            App app = apps.get(key);
            if (app == null || heroes.size() == HEROES) continue;
            View card = heroCard(app);
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
        TextView t = label(10, p.text2);
        t.setLetterSpacing(0.1f);
        t.setText(name);
        t.setTag(name);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(14), 0, px(14), 0);
        styleTab(t, false);
        // Moving onto a tab shows its apps at once, as Google TV's tabs do; OK does the same.
        focusable(t, 1.06f, hasFocus -> {
            if (hasFocus) selectTab(name);
            styleTab(t, hasFocus);
        });
        t.setOnClickListener(v -> selectTab(name));
        return t;
    }

    /**
     * Noirly's chip: faint fill and a hairline. Chosen: inverted, cream with dark words (its active state).
     * Focused: gold, the ring colour.
     */
    private void styleTab(TextView t, boolean hasFocus) {
        boolean chosen = tab.equals(t.getTag());
        if (hasFocus) {
            t.setTextColor(p.accentInk);
            t.setBackground(rounded(p.accent, 15, p.accent));
        } else if (chosen) {
            t.setTextColor(p.bg);
            t.setBackground(rounded(p.text, 15, p.text));
        } else {
            t.setTextColor(p.text2);
            t.setBackground(rounded(p.chipFill, 15, p.hairlineStrong));
        }
    }

    private void selectTab(String name) {
        if (tab.equals(name)) return;
        tab = name;
        for (View t : tabs) styleTab((TextView) t, t.hasFocus());
        fillGrid();
        gridScroll.scrollTo(0, 0);
        lastCard = 0;
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

    private View heroCard(App app) {
        Drawable banner = banner(app.info);
        Bitmap art = banner != null ? toBitmap(banner, 320, 180) : null;
        int color = art != null ? dominant(art) : dominant(icon(app));
        FrameLayout card = new FrameLayout(a);
        card.setClipToOutline(true);
        // Noirly's .surface: the dark card gradient with a hairline, r-lg corners.
        card.setBackground(card(18, p.hairlineStrong));
        card.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);

        // Two soft circles, as the mockup's cards have: a faint aura of the app's own colour, and a neutral one.
        View blob = new View(a);
        blob.setBackground(oval((color & 0x00FFFFFF) | 0x33000000));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(px(170), px(170), Gravity.BOTTOM | Gravity.START);
        blp.leftMargin = -px(40);
        blp.bottomMargin = -px(80);
        card.addView(blob, blp);
        View arc = new View(a);
        arc.setBackground(oval(p.glow));
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

        LinearLayout words = new LinearLayout(a);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = displayText(22);
        name.setText(app.label);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        TextView line = text(12, p.text2, body);
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
        ring.setVisibility(View.INVISIBLE);
        card.addView(ring, match());
        focusable(card, 1.04f, hasFocus -> ring.setVisibility(hasFocus ? View.VISIBLE : View.INVISIBLE));
        card.setOnClickListener(v -> launch(app));
        return card;
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
        // Noirly's .surface-flat at r-md; focused, the raised surface with the gold ring.
        Drawable normal = rounded(NoirlyPalette.alpha(p.surface, 0.9f), 12, p.hairlineStrong);
        Drawable lit = rounded(p.surface2, 12, p.accent, 2);
        card.setBackground(normal);
        ImageView image = new ImageView(a);
        image.setImageBitmap(icon);
        card.addView(image, new LinearLayout.LayoutParams(px(40), px(40)));
        LinearLayout words = new LinearLayout(a);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = displayText(15);
        name.setText(title);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        TextView kind = label(10, p.muted);
        kind.setText(subtitle);
        kind.setSingleLine(true);
        words.addView(name);
        words.addView(kind);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        wlp.leftMargin = px(12);
        card.addView(words, wlp);
        focusable(card, 1.03f, hasFocus -> card.setBackground(hasFocus ? lit : normal));
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
        ring.setVisibility(View.INVISIBLE);
        item.addView(ring, new FrameLayout.LayoutParams(px(50), px(50), Gravity.CENTER));
        item.setContentDescription(app.label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(54), px(54));
        lp.leftMargin = px(3);
        lp.rightMargin = px(3);
        item.setLayoutParams(lp);
        focusable(item, 1.15f, hasFocus -> ring.setVisibility(hasFocus ? View.VISIBLE : View.INVISIBLE));
        item.setOnClickListener(v -> launch(app));
        return item;
    }

    private View roundButton(int iconRes, String label, Runnable action) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setClipChildren(false);
        FrameLayout circle = new FrameLayout(a);
        // Noirly's .btn-icon: a thin outline on the surface; focused, the gold fill with accent-ink.
        Drawable normal = oval(p.surface, p.hairlineStrong, 1);
        Drawable lit = oval(p.accent);
        circle.setBackground(normal);
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
        focusable(box, 1.08f, hasFocus -> {
            circle.setBackground(hasFocus ? lit : normal);
            if (icn != null) icn.setTint(hasFocus ? p.accentInk : p.text);
            name.setTextColor(hasFocus ? p.accent : p.text2);
        });
        box.setOnClickListener(v -> action.run());
        top.add(box);
        return box;
    }

    private interface FocusChange { void on(boolean hasFocus); }

    private void focusable(View v, float scale, FocusChange change) {
        v.setFocusable(true);
        v.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) focused = view;
            change.on(hasFocus);
            view.animate().scaleX(hasFocus ? scale : 1f).scaleY(hasFocus ? scale : 1f).setDuration(120).start();
            view.setZ(hasFocus ? px(4) : 0);
        });
    }

    // ---- keys ----

    /** D-pad moves between the header buttons, the hero cards, the app cards and the bar, one step at a time. */
    boolean dispatchKey(KeyEvent event) {
        int code = event.getKeyCode();
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
        popup.showAsDropDown(anchor, -px(64), px(8));
        rows.getChildAt(1).requestFocus();
    }

    private View inputRow(String label, Runnable action) {
        TextView row = displayText(16);
        row.setText(label);
        row.setPadding(px(14), px(9), px(14), px(9));
        Drawable lit = rounded(p.accentSoft, 9, p.accent);
        row.setFocusable(true);
        row.setOnFocusChangeListener((v, f) -> {
            v.setBackground(f ? lit : null);
            row.setTextColor(f ? p.accent : p.text);
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

    // ---- small builders ----

    private int px(float dps) { return Math.round(dps * dp); }

    private TextView text(float sp, int color, Typeface face) {
        TextView t = new TextView(a);
        style(t, sp, color, face);
        return t;
    }

    /** Fraunces, tight: titles, the greeting and the figures. */
    private TextView displayText(float sp) {
        TextView t = text(sp, p.text, display);
        t.setLetterSpacing(DISPLAY_TRACKING);
        return t;
    }

    /** JetBrains Mono in capitals, spaced out: Noirly's .mono-label for categories, tabs and small labels. */
    private TextView label(float sp, int color) {
        TextView t = text(sp, color, mono);
        t.setLetterSpacing(LABEL_TRACKING);
        t.setAllCaps(true);
        return t;
    }

    /** Noirly's .surface: the surface with the faint top-lit card gradient and a hairline edge. */
    private Drawable card(float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{p.cardTop, p.cardBottom});
        g.setCornerRadius(px(radiusDp));
        g.setStroke(px(1), stroke);
        return g;
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
