package cz.kiosek.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

public final class Preferences {
    private static final String FILE = "kiosk_settings";
    public static final String URL = "url";
    public static final String ENGINE = "engine";
    public static final String START_ON_BOOT = "start_on_boot";
    public static final String DEFAULT_URL = "https://example.com";
    public static final String GECKO = "gecko";
    public static final String SYSTEM_WEBVIEW = "webview";

    private Preferences() { }
    public static SharedPreferences get(Context context) {
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return app.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        }

        // Settings live in device-protected storage. Copy an installation made
        // by an earlier version from credential-protected storage when available.
        Context deviceProtected = app.createDeviceProtectedStorageContext();
        SharedPreferences preferences = deviceProtected.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        if (preferences.getAll().isEmpty()) {
            try {
                deviceProtected.moveSharedPreferencesFrom(app, FILE);
            } catch (IllegalStateException ignored) {
                // Credential-protected storage is unavailable before unlock.
            }
        }
        return deviceProtected.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
    public static String url(Context context) {
        return get(context).getString(URL, DEFAULT_URL);
    }

    /** @return the URL with a scheme, or null when it has no host. */
    public static String normalizeUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!value.startsWith("https://") && !value.startsWith("http://")) value = "https://" + value;
        return TextUtils.isEmpty(Uri.parse(value).getHost()) ? null : value;
    }
}
