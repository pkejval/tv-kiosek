package cz.kiosek.tv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Way out of the kiosk: system shortcuts and a grid of launchable TV apps. */
public final class AppDrawerActivity extends Activity {
    private static final int COLUMNS = 5;
    private TvUi ui;
    private int tileWidth;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new TvUi(this);
        PackageManager pm = getPackageManager();
        int sidePadding = ui.dp(56);
        int gap = ui.dp(14);
        tileWidth = (getResources().getDisplayMetrics().widthPixels - 2 * sidePadding - COLUMNS * gap) / COLUMNS;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(sidePadding, ui.dp(36), sidePadding, ui.dp(36));
        root.setClipChildren(false);
        root.addView(ui.text("Nabídka", 34, TvUi.TEXT, true));
        root.addView(ui.text("HOME na ovladači se kdykoli vrátí do kiosku", 16, TvUi.MUTED, false));

        GridLayout shortcuts = grid();
        View back = addTile(shortcuts, "Zpět do kiosku", null, v -> finish());
        addTile(shortcuts, "Nastavení kiosku", null, v -> {
            finish();
            startActivity(new Intent(this, SettingsActivity.class));
        });
        addTile(shortcuts, "Nastavení Android TV", null, v -> launch(new Intent(Settings.ACTION_SETTINGS)));
        for (ResolveInfo home : otherHomeScreens(pm)) {
            addTile(shortcuts, "Domovská obrazovka: " + home.loadLabel(pm), home.loadIcon(pm),
                    v -> launch(new Intent(Intent.ACTION_MAIN).setComponent(component(home.activityInfo))));
        }
        root.addView(shortcuts);

        TextView appsTitle = ui.text("APLIKACE", 13, TvUi.ACCENT, true);
        appsTitle.setLetterSpacing(0.12f);
        appsTitle.setPadding(0, ui.dp(24), 0, ui.dp(4));
        root.addView(appsTitle);
        GridLayout apps = grid();
        for (ResolveInfo app : findLaunchableApps(pm)) {
            Drawable banner = app.activityInfo.loadBanner(pm);
            addTile(apps, app.loadLabel(pm).toString(), banner != null ? banner : app.loadIcon(pm),
                    v -> launch(new Intent(Intent.ACTION_MAIN).setComponent(component(app.activityInfo))));
        }
        root.addView(apps);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(TvUi.BACKGROUND);
        scroll.setClipChildren(false);
        scroll.addView(root);
        setContentView(scroll);
        back.requestFocus();
    }

    private GridLayout grid() {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(COLUMNS);
        // Room for the focus scale-up, which would otherwise be clipped.
        grid.setClipChildren(false);
        grid.setPadding(0, ui.dp(12), 0, 0);
        return grid;
    }

    private View addTile(GridLayout grid, String label, Drawable image, View.OnClickListener action) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        tile.setBackground(ui.tileBackground(TvUi.TILE, TvUi.TILE_FOCUSED));
        ui.makeFocusable(tile, action);

        if (image != null) {
            ImageView picture = new ImageView(this);
            picture.setImageDrawable(image);
            picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.addView(picture, new LinearLayout.LayoutParams(-1, ui.dp(64)));
        }
        TextView text = ui.text(label, 16, TvUi.TEXT, image == null);
        text.setGravity(Gravity.CENTER);
        text.setMaxLines(2);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setPadding(0, image == null ? 0 : ui.dp(8), 0, 0);
        tile.addView(text, new LinearLayout.LayoutParams(-1, -2));

        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = tileWidth;
        params.height = ui.dp(image == null ? 88 : 120);
        params.setMargins(0, 0, ui.dp(14), ui.dp(14));
        grid.addView(tile, params);
        return tile;
    }

    private void launch(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(this, "Nelze otevřít", Toast.LENGTH_SHORT).show();
        }
    }

    private static ComponentName component(ActivityInfo info) {
        return new ComponentName(info.packageName, info.name);
    }

    /** The stock launcher stays reachable while the kiosk is the default Home app. */
    private List<ResolveInfo> otherHomeScreens(PackageManager pm) {
        List<ResolveInfo> result = new ArrayList<>();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
            if (getPackageName().equals(info.activityInfo.packageName)) continue;
            // Settings registers a placeholder Home shown only while booting.
            if (info.activityInfo.name.endsWith("FallbackHome")) continue;
            result.add(info);
        }
        return result;
    }

    private List<ResolveInfo> findLaunchableApps(PackageManager pm) {
        ArrayList<ResolveInfo> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        addActivities(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, result, seen);
        addActivities(pm, Intent.CATEGORY_LAUNCHER, result, seen);
        Collections.sort(result, Comparator.comparing(info -> info.loadLabel(pm).toString(), String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private void addActivities(PackageManager pm, String category, List<ResolveInfo> into, Set<String> seen) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
            if (getPackageName().equals(info.activityInfo.packageName)) continue;
            // One tile per app, preferring its TV (leanback) entry point.
            if (seen.add(info.activityInfo.packageName)) into.add(info);
        }
    }
}
