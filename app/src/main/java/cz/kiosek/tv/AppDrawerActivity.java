package cz.kiosek.tv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** TV-friendly list of activities which their apps declare launchable. */
public final class AppDrawerActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        int pad = (int) (28 * getResources().getDisplayMetrics().density);
        LinearLayout list = new LinearLayout(this);
        list.setPadding(pad, pad, pad, pad);
        list.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("Aplikace TV"); title.setTextSize(28);
        list.addView(title);

        List<ResolveInfo> apps = findLaunchableApps();
        PackageManager pm = getPackageManager();
        for (ResolveInfo app : apps) {
            Button button = new Button(this);
            button.setAllCaps(false);
            button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            button.setText(app.loadLabel(pm));
            Drawable icon = app.loadIcon(pm);
            int size = (int) (40 * getResources().getDisplayMetrics().density);
            icon.setBounds(0, 0, size, size);
            button.setCompoundDrawables(icon, null, null, null);
            button.setCompoundDrawablePadding(pad / 2);
            ComponentName component = new ComponentName(app.activityInfo.packageName, app.activityInfo.name);
            button.setOnClickListener(v -> startActivity(new Intent().setComponent(component)));
            list.addView(button, new LinearLayout.LayoutParams(-1, -2));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        setContentView(scroll);
    }

    private List<ResolveInfo> findLaunchableApps() {
        PackageManager pm = getPackageManager();
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
            String key = info.activityInfo.packageName + "/" + info.activityInfo.name;
            if (seen.add(key)) into.add(info);
        }
    }
}
