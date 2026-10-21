package cz.kiosek.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** D-pad friendly settings: focusable tiles, values on the right, edits in dialogs. */
public final class SettingsActivity extends Activity {
    private static final int REQUEST_HOME_ROLE = 1001;
    private static final int BACKGROUND = 0xFF0E1116;
    private static final int TILE = 0xFF1A1E26;
    private static final int TILE_FOCUSED = 0xFF233A4D;
    private static final int ACCENT = 0xFF40C4FF;
    private static final int TEXT = 0xFFF2F4F7;
    private static final int MUTED = 0xFF9AA4B2;
    private static final int GOOD = 0xFF66BB6A;
    private static final int WARNING = 0xFFFFB74D;

    private static final class Tile {
        final LinearLayout view;
        final TextView value;
        Tile(LinearLayout view, TextView value) { this.view = view; this.value = value; }
    }

    private float density;
    private String url;
    private boolean gecko;
    private boolean startOnBoot;
    private Tile urlTile, engineTile, bootTile, overlayTile, homeTile, webTile, installTile;
    private TextView autostartStatus;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        url = Preferences.url(this);
        gecko = Preferences.GECKO.equals(Preferences.get(this).getString(Preferences.ENGINE, Preferences.GECKO));
        startOnBoot = Preferences.get(this).getBoolean(Preferences.START_ON_BOOT, true);

        // TVs may overscan, so keep content well inside the screen edges.
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(BACKGROUND);
        root.setPadding(dp(56), dp(36), dp(56), dp(36));

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.CENTER_VERTICAL);
        side.setPadding(0, 0, dp(40), 0);
        side.addView(text("TV Kiosek", 38, TEXT, true));
        side.addView(text("Nastavení", 20, ACCENT, false));
        TextView help = text("MENU v kiosku otevře nastavení.\nPodržení MENU otevře aplikace TV.\nZpět zavře nastavení bez uložení.", 16, MUTED, false);
        help.setLineSpacing(0, 1.3f);
        side.addView(help, margins(0, dp(28), 0, 0));
        autostartStatus = text("", 16, WARNING, false);
        autostartStatus.setLineSpacing(0, 1.3f);
        side.addView(autostartStatus, margins(0, dp(24), 0, 0));
        root.addView(side, new LinearLayout.LayoutParams(0, -1, 2));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        // Room for the focus scale-up, which would otherwise be clipped.
        list.setPadding(dp(12), dp(12), dp(12), dp(12));

        list.addView(section("Kiosk"));
        urlTile = tile(list, "Webová adresa", "Stránka, která se v kiosku otevře", v -> editUrl());
        engineTile = tile(list, "Prohlížeč", "GeckoView zvládá moderní web i na staré TV", v -> {
            gecko = !gecko;
            refresh();
        });

        list.addView(section("Spouštění"));
        bootTile = tile(list, "Spustit po zapnutí TV", "Otevře kiosk automaticky po startu", v -> {
            startOnBoot = !startOnBoot;
            refresh();
        });
        overlayTile = tile(list, "Zobrazení přes jiné aplikace", "Android 10+ to vyžaduje pro start po zapnutí", v -> requestOverlay());
        homeTile = tile(list, "Výchozí launcher", "Nejspolehlivější: TV otevře kiosk místo domovské obrazovky",
                v -> {
                    if (Autostart.isHomeApp(this)) {
                        Toast.makeText(this, "Kiosek už je výchozí launcher", Toast.LENGTH_SHORT).show();
                    } else if (!Autostart.requestHomeRole(this, REQUEST_HOME_ROLE)) {
                        showHomeAdbHelp();
                    }
                });

        list.addView(section("Správa"));
        webTile = tile(list, "Webová správa", "Nastavení a aktualizace z počítače ve stejné síti", v -> refresh());
        installTile = tile(list, "Instalace aktualizací", "Povolí instalaci APK nahraného přes webovou správu",
                v -> openUnknownSources());
        webTile.value.setMaxWidth(dp(480));
        tile(list, "Aplikace TV", "Seznam nainstalovaných aplikací",
                v -> startActivity(new Intent(this, AppDrawerActivity.class))).value.setText("›");

        TextView save = text("Uložit a otevřít kiosk", 20, BACKGROUND, true);
        save.setGravity(Gravity.CENTER);
        save.setPadding(dp(24), dp(18), dp(24), dp(18));
        save.setBackground(tileBackground(0xFF2B9BD1, ACCENT));
        makeFocusable(save, v -> save());
        list.addView(save, margins(0, dp(20), 0, 0));

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(0, -1, 3);
        scrollParams.gravity = Gravity.CENTER_VERTICAL;
        root.addView(scroll, scrollParams);

        setContentView(root);
        urlTile.view.requestFocus();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // TV firmwares often close the role dialog at once without asking.
        if (requestCode == REQUEST_HOME_ROLE && !Autostart.isHomeApp(this)) showHomeAdbHelp();
    }

    private void showHomeAdbHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Výchozí launcher přes adb")
                .setMessage("Tato TV neumožňuje výchozí launcher vybrat. Nastavte ho z počítače:\n\n"
                        + Autostart.ADB_SET_HOME
                        + "\n\nPokud se po restartu stále otevírá původní domovská obrazovka, postup je v README projektu.")
                .setPositiveButton("OK", null)
                .show();
    }

    @Override protected void onResume() {
        super.onResume();
        // Permissions may have changed in system settings meanwhile.
        refresh();
    }

    private void refresh() {
        urlTile.value.setText(url);
        engineTile.value.setText(gecko ? "GeckoView" : "Systémový WebView");
        chip(bootTile.value, startOnBoot ? "Zapnuto" : "Vypnuto", startOnBoot ? GOOD : MUTED);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            chip(overlayTile.value, "Není potřeba", MUTED);
        } else if (Autostart.canDrawOverlays(this)) {
            chip(overlayTile.value, "Povoleno", GOOD);
        } else {
            chip(overlayTile.value, "Chybí", WARNING);
        }
        boolean home = Autostart.isHomeApp(this);
        chip(homeTile.value, home ? "Nastaveno" : "Ne", home ? GOOD : MUTED);
        List<String> addresses = WebAdmin.addresses();
        webTile.value.setText(addresses.isEmpty() ? "Bez sítě" : addresses.get(0));
        boolean canInstall = Updater.canInstall(this);
        chip(installTile.value, canInstall ? "Povoleno" : "Zakázáno", canInstall ? GOOD : WARNING);

        boolean blocked = startOnBoot && !Autostart.canStartFromBackground(this);
        autostartStatus.setText(blocked
                ? "⚠ Autostart teď nebude fungovat.\nPovolte zobrazení přes jiné aplikace, nebo nastavte kiosk jako výchozí launcher."
                : "");
        autostartStatus.setVisibility(blocked ? View.VISIBLE : View.GONE);
    }

    private void editUrl() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setSingleLine(true);
        input.setText(url);
        input.setSelection(input.length());
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Webová adresa")
                .setView(box)
                .setPositiveButton("OK", null)
                .setNegativeButton("Zrušit", null)
                .create();
        // Validate before closing, so a typo does not discard the dialog.
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (applyUrl(input.getText().toString())) dialog.dismiss();
        }));
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (applyUrl(input.getText().toString())) dialog.dismiss();
            return true;
        });
        dialog.show();
    }

    private boolean applyUrl(String raw) {
        String value = Preferences.normalizeUrl(raw);
        if (value == null) {
            Toast.makeText(this, "Neplatná adresa", Toast.LENGTH_SHORT).show();
            return false;
        }
        url = value;
        refresh();
        return true;
    }

    private void requestOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Autostart.canDrawOverlays(this)) {
            Toast.makeText(this, "Oprávnění není potřeba měnit", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Autostart.openOverlaySettings(this)) return;
        new AlertDialog.Builder(this)
                .setTitle("Povolení není v TV dostupné")
                .setMessage("Tato TV nenabízí obrazovku pro toto oprávnění. Povolte ho z počítače přes adb:\n\n"
                        + Autostart.ADB_GRANT_OVERLAY
                        + "\n\nNebo místo toho nastavte kiosk jako výchozí launcher.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void openUnknownSources() {
        if (Updater.canInstall(this)) {
            Toast.makeText(this, "Instalace je již povolena", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            // Android TV builds may hide the per-app screen; fall back to security settings.
            try {
                startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            } catch (RuntimeException ignored) {
                Toast.makeText(this, "Povolte neznámé zdroje v nastavení TV", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void save() {
        Preferences.get(this).edit()
                .putString(Preferences.URL, url)
                .putString(Preferences.ENGINE, gecko ? Preferences.GECKO : Preferences.SYSTEM_WEBVIEW)
                .putBoolean(Preferences.START_ON_BOOT, startOnBoot).apply();
        if (!startOnBoot) AutostartFallback.cancel(this);
        setResult(RESULT_OK);
        finish();
    }

    private Tile tile(LinearLayout parent, String title, String description, View.OnClickListener action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(24), dp(16), dp(24), dp(16));
        row.setBackground(tileBackground(TILE, TILE_FOCUSED));
        makeFocusable(row, action);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 20, TEXT, false));
        labels.addView(text(description, 14, MUTED, false));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        TextView value = text("", 18, TEXT, false);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        value.setMaxWidth(dp(300));
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(-2, -2);
        valueParams.leftMargin = dp(16);
        row.addView(value, valueParams);

        parent.addView(row, margins(0, 0, 0, dp(10)));
        return new Tile(row, value);
    }

    private void makeFocusable(View view, View.OnClickListener action) {
        view.setFocusable(true);
        view.setClickable(true);
        view.setOnClickListener(action);
        view.setOnFocusChangeListener((v, focused) ->
                v.animate().scaleX(focused ? 1.03f : 1f).scaleY(focused ? 1.03f : 1f).setDuration(120).start());
    }

    private Drawable tileBackground(int normal, int focused) {
        StateListDrawable states = new StateListDrawable();
        GradientDrawable on = rounded(focused);
        on.setStroke(dp(3), ACCENT);
        states.addState(new int[] { android.R.attr.state_pressed }, on);
        states.addState(new int[] { android.R.attr.state_focused }, on);
        states.addState(new int[0], rounded(normal));
        return states;
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(14));
        return shape;
    }

    private void chip(TextView view, String label, int color) {
        view.setText(label);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(dp(14), dp(4), dp(14), dp(4));
        GradientDrawable shape = rounded((color & 0x00FFFFFF) | 0x33000000);
        shape.setCornerRadius(dp(20));
        view.setBackground(shape);
    }

    private TextView section(String label) {
        TextView view = text(label.toUpperCase(), 13, ACCENT, true);
        view.setLetterSpacing(0.12f);
        view.setPadding(dp(8), dp(18), 0, dp(8));
        return view;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private int dp(int value) {
        return (int) (value * density);
    }
}
