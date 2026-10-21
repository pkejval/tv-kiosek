package cz.kiosek.tv;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Self-update from an APK stream; the session accepts only this app's package. */
public final class Updater {
    static final long MAX_APK_BYTES = 400L * 1024 * 1024;
    /** Published by .github/workflows/release.yml as tv-kiosek-<versionCode>-<abi>.apk */
    private static final String LATEST_RELEASE = "https://api.github.com/repos/pkejval/tv-kiosek/releases/latest";
    private static final Pattern ASSET_VERSION = Pattern.compile("^tv-kiosek-(\\d+)-");
    private static volatile String status = "Žádná instalace neprobíhá";

    private Updater() { }

    public static String status() { return status; }

    public static boolean canInstall(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || context.getPackageManager().canRequestPackageInstalls();
    }

    /** @param length the APK size, or -1 when unknown. */
    public static void install(Context context, InputStream apk, long length) throws IOException {
        if (length > MAX_APK_BYTES) throw new IOException("APK je příliš velké");
        status = "Přijímám APK…";
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        // The system rejects any APK whose package name or signature differs.
        params.setAppPackageName(context.getPackageName());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (OutputStream out = session.openWrite("update.apk", 0, length)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                for (int n; (n = apk.read(buffer)) != -1; ) {
                    total += n;
                    if (total > MAX_APK_BYTES) throw new IOException("APK je příliš velké");
                    if (length >= 0 && total > length) throw new IOException("Neočekávaná délka APK");
                    out.write(buffer, 0, n);
                }
                if (length >= 0 && total != length) throw new IOException("APK nebylo přeneseno celé");
                session.fsync(out);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            // The installer adds status extras, so the intent has to be mutable.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent result = PendingIntent.getBroadcast(context, id,
                    new Intent(context, ResultReceiver.class), flags);
            session.commit(result.getIntentSender());
            status = "Čeká na potvrzení na TV";
        } catch (IOException | RuntimeException e) {
            installer.abandonSession(id);
            status = "Chyba: " + e.getMessage();
            throw e;
        }
    }

    public static void installFromUrl(Context context, String address) throws IOException {
        status = "Stahuji " + address;
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("Server vrátil HTTP " + code);
            try (InputStream in = connection.getInputStream()) {
                install(context, in, connection.getContentLengthLong());
            }
        } catch (IOException e) {
            status = "Chyba: " + e.getMessage();
            throw e;
        } finally {
            connection.disconnect();
        }
    }

    public static void installLatestRelease(Context context) throws IOException, JSONException {
        status = "Hledám nejnovější verzi na GitHubu…";
        try {
            JSONArray assets = new JSONObject(readText(LATEST_RELEASE)).getJSONArray("assets");
            // The first supported ABI is the best one; 64-bit TVs also run 32-bit builds.
            for (String abi : Build.SUPPORTED_ABIS) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    String name = asset.getString("name");
                    Matcher version = ASSET_VERSION.matcher(name);
                    if (!name.endsWith("-" + abi + ".apk") || !version.find()) continue;
                    if (Long.parseLong(version.group(1)) <= installedVersion(context)) {
                        status = "Nainstalovaná verze je aktuální";
                        return;
                    }
                    installFromUrl(context, asset.getString("browser_download_url"));
                    return;
                }
            }
            status = "Release neobsahuje APK pro tuto TV";
        } catch (IOException | JSONException e) {
            status = "Chyba: " + e.getMessage();
            throw e;
        }
    }

    @SuppressWarnings("deprecation")
    private static long installedVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    private static String readText(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        // GitHub's API rejects requests without a User-Agent.
        connection.setRequestProperty("User-Agent", "tv-kiosek");
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("GitHub vrátil HTTP " + code);
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream text = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) != -1; ) text.write(buffer, 0, n);
                return text.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }

    public static final class ResultReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            int code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (code == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    status = "Potvrďte instalaci ovladačem na TV";
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
                return;
            }
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            status = code == PackageInstaller.STATUS_SUCCESS
                    ? "Nainstalováno"
                    : "Instalace selhala (" + code + "): " + message;
            Log.i(BootReceiver.TAG, "Install result " + code + " " + message);
        }
    }
}
