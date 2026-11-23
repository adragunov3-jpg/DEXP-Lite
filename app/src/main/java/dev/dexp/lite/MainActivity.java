package dev.dexp.lite;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * DEXP Lite 0.2: a lightweight, offline Android TV launcher.
 * Android framework only: no network permission, no analytics, no services.
 * Does NOT uninstall/disable system packages or touch firmware.
 */
public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(10, 12, 22);
    private static final int PANEL = Color.rgb(24, 29, 47);
    private static final int PANEL_2 = Color.rgb(31, 38, 59);
    private static final int ACCENT = Color.rgb(139, 111, 255);
    private static final int ACCENT_DARK = Color.rgb(75, 63, 143);
    private static final int WHITE = Color.rgb(247, 249, 255);
    private static final int MUTED = Color.rgb(160, 171, 198);
    private static final String PREFS = "dexp_lite_prefs";
    private static final String FAV_KEY = "favorite_packages";

    private final ArrayList<AppItem> installed = new ArrayList<>();
    private final ArrayList<AppItem> visible = new ArrayList<>();
    private final LruCache<String, Drawable> icons = new LruCache<>(24);
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private final Locale ru = new Locale("ru", "RU");
    private TextView timeView;
    private TextView dateView;
    private TextView hintView;
    private TextView favoritesTab;
    private TextView allTab;
    private GridView appsGrid;
    private BaseAdapter appAdapter;
    private boolean showFavorites = true;

    private static class AppItem {
        final String name;
        final String pkg;
        final ComponentName component;
        AppItem(String name, String pkg, ComponentName component) {
            this.name = name;
            this.pkg = pkg;
            this.component = component;
        }
    }

    private final Runnable clockUpdate = new Runnable() {
        @Override public void run() {
            if (timeView != null) {
                Date now = new Date();
                timeView.setText(new SimpleDateFormat("HH:mm", ru).format(now));
                dateView.setText(new SimpleDateFormat("EEEE, d MMMM", ru).format(now));
            }
            clockHandler.postDelayed(this, 60000L);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(buildUI());
        appAdapter = makeAdapter();
        appsGrid.setAdapter(appAdapter);
        appsGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int index, long id) {
                if (index >= 0 && index < visible.size()) openApp(visible.get(index));
            }
        });
        appsGrid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int index, long id) {
                if (index >= 0 && index < visible.size()) toggleFavorite(visible.get(index));
                return true;
            }
        });
        appsGrid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int index, long id) {
                highlightTiles();
            }
            @Override public void onNothingSelected(AdapterView<?> p) { highlightTiles(); }
        });
        appsGrid.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean focused) { highlightTiles(); }
        });
        refreshAppList();
        setSection(true);
        appsGrid.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshAppList();
        clockHandler.removeCallbacks(clockUpdate);
        clockHandler.post(clockUpdate);
    }

    @Override protected void onPause() {
        clockHandler.removeCallbacks(clockUpdate);
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (!showFavorites) {
            setSection(true);
            appsGrid.requestFocus();
        } else {
            new AlertDialog.Builder(this).setTitle("DEXP Lite")
                    .setMessage("Закрыть лёгкую оболочку и вернуться в штатный экран?")
                    .setNegativeButton("Остаться", null)
                    .setPositiveButton("Закрыть", (d, w) -> finish())
                    .show();
        }
    }

    private View buildUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(34), dp(18), dp(34), dp(18));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(13, 17, 33), BG, Color.rgb(20, 17, 43)}));
        root.setFocusableInTouchMode(true);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView symbol = text("◈", 31, ACCENT, true);
        brand.addView(symbol);
        TextView brandName = text(" DEXP  /  LITE", 22, WHITE, true);
        brand.addView(brandName);
        top.addView(brand, new LinearLayout.LayoutParams(0, dp(50), 1f));
        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        clock.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        timeView = text("--:--", 26, WHITE, true);
        timeView.setGravity(Gravity.RIGHT);
        dateView = text("", 12, MUTED, false);
        dateView.setGravity(Gravity.RIGHT);
        clock.addView(timeView);
        clock.addView(dateView);
        top.addView(clock);
        root.addView(top);

        FrameLayout hero = new FrameLayout(this);
        GradientDrawable heroBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(54, 47, 112), Color.rgb(38, 48, 87), Color.rgb(21, 31, 57)});
        heroBg.setCornerRadius(dp(20));
        heroBg.setStroke(dp(1), Color.rgb(82, 79, 138));
        hero.setBackground(heroBg);
        LinearLayout heroText = new LinearLayout(this);
        heroText.setOrientation(LinearLayout.VERTICAL);
        heroText.setPadding(dp(28), dp(20), dp(28), dp(12));
        TextView tag = text("ЛЕГКО. БЫСТРО. НИЧЕГО ЛИШНЕГО.", 11, Color.rgb(192, 177, 255), true);
        heroText.addView(tag);
        TextView heroTitle = text("Только то, что тебе нужно", 28, WHITE, true);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(9);
        heroText.addView(heroTitle, hp);
        TextView heroSubtitle = text("Приложения под рукой. Без баннеров, новостей и тяжёлых эффектов.",
                14, Color.rgb(207, 212, 240), false);
        LinearLayout.LayoutParams hs = new LinearLayout.LayoutParams(-1, -2);
        hs.topMargin = dp(5);
        heroText.addView(heroSubtitle, hs);
        hero.addView(heroText);
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(-1, dp(142));
        heroLp.topMargin = dp(8);
        root.addView(hero, heroLp);

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        favoritesTab = tab("★  Избранное", true);
        favoritesTab.setOnClickListener(v -> setSection(true));
        allTab = tab("▦  Все приложения", false);
        allTab.setOnClickListener(v -> setSection(false));
        TextView settings = tab("⚙  Настройки", false);
        settings.setOnClickListener(v -> openSettings());
        nav.addView(favoritesTab, tabLp());
        nav.addView(allTab, tabLp());
        nav.addView(settings, tabLp());
        LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(-1, dp(52));
        navLp.topMargin = dp(12);
        root.addView(nav, navLp);

        hintView = text("Избранные приложения", 15, MUTED, false);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.bottomMargin = dp(10);
        root.addView(hintView, titleLp);

        appsGrid = new GridView(this);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float widthDp = metrics.widthPixels / metrics.density;
        appsGrid.setNumColumns(widthDp > 1300 ? 6 : (widthDp > 950 ? 5 : 4));
        appsGrid.setHorizontalSpacing(dp(14));
        appsGrid.setVerticalSpacing(dp(13));
        appsGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        appsGrid.setVerticalScrollBarEnabled(false);
        appsGrid.setClipToPadding(false);
        appsGrid.setSelector(android.R.color.transparent);
        appsGrid.setFocusable(true);
        appsGrid.setDrawSelectorOnTop(false);
        root.addView(appsGrid, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        TextView instruction = text("OK: открыть    •    Удерживать OK: в избранное / убрать    •    Назад: назад", 12, MUTED, false);
        footer.addView(instruction, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView privacy = text("OFFLINE  •  NO ADS", 11, Color.rgb(167, 149, 244), true);
        footer.addView(privacy);
        root.addView(footer);
        return root;
    }

    private BaseAdapter makeAdapter() {
        return new BaseAdapter() {
            @Override public int getCount() { return visible.size(); }
            @Override public Object getItem(int position) { return visible.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                LinearLayout tile;
                ImageView icon;
                TextView label;
                if (recycled == null) {
                    tile = new LinearLayout(MainActivity.this);
                    tile.setOrientation(LinearLayout.VERTICAL);
                    tile.setPadding(dp(17), dp(14), dp(11), dp(10));
                    tile.setGravity(Gravity.TOP);
                    tile.setFocusable(false);
                    icon = new ImageView(MainActivity.this);
                    icon.setId(10001);
                    icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    tile.addView(icon, new LinearLayout.LayoutParams(dp(45), dp(45)));
                    label = text("", 15, WHITE, true);
                    label.setId(10002);
                    label.setMaxLines(2);
                    label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                    lp.topMargin = dp(13);
                    tile.addView(label, lp);
                    tile.setLayoutParams(new AbsListView.LayoutParams(-1, dp(122)));
                } else {
                    tile = (LinearLayout) recycled;
                    icon = tile.findViewById(10001);
                    label = tile.findViewById(10002);
                }
                AppItem item = visible.get(position);
                label.setText(item.name);
                icon.setImageDrawable(loadIcon(item.pkg));
                boolean selected = appsGrid != null && appsGrid.hasFocus()
                        && appsGrid.getSelectedItemPosition() == position;
                tile.setBackground(tileBg(selected));
                return tile;
            }
        };
    }

    private Drawable loadIcon(String pkg) {
        Drawable cached = icons.get(pkg);
        if (cached != null) return cached;
        Drawable icon;
        try { icon = getPackageManager().getApplicationIcon(pkg); }
        catch (Exception ex) { icon = getPackageManager().getDefaultActivityIcon(); }
        icons.put(pkg, icon);
        return icon;
    }

    private void refreshAppList() {
        PackageManager pm = getPackageManager();
        ArrayList<AppItem> newList = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        appendApps(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, newList, seen);
        appendApps(pm, Intent.CATEGORY_LAUNCHER, newList, seen);
        Collections.sort(newList, new Comparator<AppItem>() {
            @Override public int compare(AppItem a, AppItem b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        installed.clear();
        installed.addAll(newList);
        maybeAddFirstFavorites();
        updateVisible();
    }

    private void appendApps(PackageManager pm, String category, List<AppItem> out, Set<String> seen) {
        Intent request = new Intent(Intent.ACTION_MAIN);
        request.addCategory(category);
        List<ResolveInfo> found = pm.queryIntentActivities(request, 0);
        for (ResolveInfo info : found) {
            if (info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if (getPackageName().equals(pkg) || !seen.add(pkg)) continue;
            CharSequence label = info.loadLabel(pm);
            String name = label == null ? pkg : label.toString();
            out.add(new AppItem(name, pkg, new ComponentName(pkg, info.activityInfo.name)));
        }
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    private void maybeAddFirstFavorites() {
        if (prefs().getBoolean("initialized", false)) return;
        Set<String> first = new HashSet<>();
        String[] preferred = {"youtube", "кинопоиск", "kinopoisk", "ivi", "okko", "wink", "vlc"};
        for (String keyword : preferred) {
            for (AppItem item : installed) {
                String both = (item.name + " " + item.pkg).toLowerCase(ru);
                if (both.contains(keyword)) first.add(item.pkg);
                if (first.size() >= 6) break;
            }
            if (first.size() >= 6) break;
        }
        if (first.isEmpty()) {
            for (int i = 0; i < installed.size() && i < 5; i++) first.add(installed.get(i).pkg);
        }
        prefs().edit().putStringSet(FAV_KEY, first).putBoolean("initialized", true).apply();
    }

    private void updateVisible() {
        if (appAdapter == null) return;
        Set<String> favorites = prefs().getStringSet(FAV_KEY, Collections.<String>emptySet());
        visible.clear();
        for (AppItem item : installed) {
            if (!showFavorites || favorites.contains(item.pkg)) visible.add(item);
        }
        appAdapter.notifyDataSetChanged();
        if (hintView != null) {
            hintView.setText(showFavorites
                    ? "Избранное  •  " + visible.size() + " приложений"
                    : "Все приложения  •  " + visible.size() + " установлено");
        }
    }

    private void setSection(boolean favorites) {
        showFavorites = favorites;
        favoritesTab.setBackground(tabBg(favorites));
        allTab.setBackground(tabBg(!favorites));
        updateVisible();
        if (!visible.isEmpty()) appsGrid.setSelection(0);
        highlightTiles();
    }

    private void toggleFavorite(AppItem item) {
        Set<String> original = prefs().getStringSet(FAV_KEY, Collections.<String>emptySet());
        Set<String> changed = new HashSet<>(original);
        boolean add = changed.add(item.pkg);
        if (!add) changed.remove(item.pkg);
        prefs().edit().putStringSet(FAV_KEY, changed).apply();
        Toast.makeText(this, add ? "Добавлено в избранное" : "Убрано из избранного", Toast.LENGTH_SHORT).show();
        updateVisible();
    }

    private void openApp(AppItem item) {
        PackageManager pm = getPackageManager();
        try {
            Intent intent = pm.getLeanbackLaunchIntentForPackage(item.pkg);
            if (intent == null) intent = pm.getLaunchIntentForPackage(item.pkg);
            if (intent == null) {
                intent = new Intent(Intent.ACTION_MAIN);
                intent.setComponent(item.component);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(intent);
        } catch (Exception error) {
            Toast.makeText(this, "Не получилось открыть " + item.name, Toast.LENGTH_LONG).show();
        }
    }

    private void openSettings() {
        try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        catch (Exception error) {
            Toast.makeText(this, "Настройки недоступны через эту прошивку", Toast.LENGTH_SHORT).show();
        }
    }

    private void highlightTiles() {
        if (appsGrid == null) return;
        int first = appsGrid.getFirstVisiblePosition();
        int selected = appsGrid.hasFocus() ? appsGrid.getSelectedItemPosition() : -1;
        for (int i = 0; i < appsGrid.getChildCount(); i++) {
            View child = appsGrid.getChildAt(i);
            if (child != null) child.setBackground(tileBg(first + i == selected));
        }
    }

    private TextView tab(String name, boolean selected) {
        TextView tab = text(name, 14, WHITE, true);
        tab.setGravity(Gravity.CENTER);
        tab.setBackground(tabBg(selected));
        tab.setFocusable(true);
        tab.setClickable(true);
        tab.setOnFocusChangeListener((view, focused) -> {
            // Respect focus while preserving active section styling.
            boolean active = view == favoritesTab ? showFavorites : view == allTab && !showFavorites;
            view.setBackground(tabBg(focused || active));
        });
        return tab;
    }

    private LinearLayout.LayoutParams tabLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(170), dp(44));
        lp.rightMargin = dp(12);
        return lp;
    }

    private GradientDrawable tabBg(boolean emphasized) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(emphasized ? ACCENT_DARK : PANEL);
        shape.setCornerRadius(dp(11));
        shape.setStroke(dp(1), emphasized ? ACCENT : Color.rgb(53, 60, 83));
        return shape;
    }

    private GradientDrawable tileBg(boolean selected) {
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                selected ? new int[]{Color.rgb(75, 64, 146), Color.rgb(47, 58, 104)}
                        : new int[]{PANEL_2, PANEL});
        shape.setCornerRadius(dp(17));
        shape.setStroke(dp(selected ? 2 : 1),
                selected ? ACCENT : Color.rgb(43, 51, 72));
        return shape;
    }

    private TextView text(String content, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextColor(color);
        view.setTextSize(size);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private int dp(float n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }
}
