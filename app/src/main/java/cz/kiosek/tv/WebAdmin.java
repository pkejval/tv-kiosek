package cz.kiosek.tv;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Minimal LAN administration: settings and self-update. Meant for a trusted LAN only. */
public final class WebAdmin {
    public static final int PORT = 8080;
    private static final int MAX_JSON_BYTES = 16 * 1024;

    private static Context app;
    private static Thread thread;

    private WebAdmin() { }

    public static synchronized void start(Context context) {
        if (thread != null) return;
        app = context.getApplicationContext();
        thread = new Thread(WebAdmin::serve, "web-admin");
        thread.setDaemon(true);
        thread.start();
    }

    /** Addresses under which the administration is reachable, e.g. http://192.168.1.20:8080 */
    public static List<String> addresses() {
        List<String> result = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address instanceof Inet4Address) result.add("http://" + address.getHostAddress() + ":" + PORT);
                }
            }
        } catch (IOException ignored) {
            // No network yet.
        }
        return result;
    }

    private static void serve() {
        try (ServerSocket server = new ServerSocket(PORT)) {
            Log.i(BootReceiver.TAG, "Web admin listening on " + PORT);
            while (true) {
                Socket socket = server.accept();
                new Thread(() -> handle(socket), "web-admin-request").start();
            }
        } catch (IOException e) {
            Log.e(BootReceiver.TAG, "Web admin stopped", e);
            synchronized (WebAdmin.class) { thread = null; }
        }
    }

    private static void handle(Socket socket) {
        try {
            socket.setSoTimeout(120_000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            String[] request = readLine(in).split(" ");
            if (request.length < 2) return;
            Map<String, String> headers = new HashMap<>();
            for (String line; !(line = readLine(in)).isEmpty(); ) {
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
            String method = request[0];
            String path = request[1].split("\\?", 2)[0];
            long length = parseLength(headers.get("content-length"));

            if (method.equals("GET") && path.equals("/")) {
                respond(out, 200, "text/html; charset=utf-8", page());
                return;
            }
            if (!path.startsWith("/api/")) {
                respond(out, 404, "text/plain; charset=utf-8", "Nenalezeno");
                return;
            }
            // Browsers send a custom header cross-origin only after a CORS preflight,
            // which this server never grants, so other pages cannot post here.
            if (method.equals("POST") && !"1".equals(headers.get("x-kiosek"))) {
                json(out, 403, error("Chybí hlavička X-Kiosek"));
                return;
            }
            switch (method + " " + path) {
                case "GET /api/settings":
                    json(out, 200, settings());
                    break;
                case "POST /api/settings":
                    json(out, 200, saveSettings(new JSONObject(readBody(in, length))));
                    break;
                case "POST /api/install":
                    if (!Updater.canInstall(app)) {
                        json(out, 409, error("Na TV povolte instalaci neznámých aplikací pro TV Kiosek"));
                        break;
                    }
                    if (length < 0) {
                        json(out, 411, error("Chybí délka souboru"));
                        break;
                    }
                    Updater.install(app, new LimitedInputStream(in, length), length);
                    json(out, 200, status());
                    break;
                case "POST /api/install-url": {
                    String address = new JSONObject(readBody(in, length)).optString("url");
                    if (!address.startsWith("https://")) {
                        json(out, 400, error("Adresa APK musí začínat https://"));
                        break;
                    }
                    if (!Updater.canInstall(app)) {
                        json(out, 409, error("Na TV povolte instalaci neznámých aplikací pro TV Kiosek"));
                        break;
                    }
                    // Downloading takes a while; the page polls the status.
                    new Thread(() -> {
                        try {
                            Updater.installFromUrl(app, address);
                        } catch (IOException e) {
                            Log.w(BootReceiver.TAG, "Download failed", e);
                        }
                    }, "web-admin-download").start();
                    json(out, 200, status());
                    break;
                }
                case "POST /api/install-github":
                    if (!Updater.canInstall(app)) {
                        json(out, 409, error("Na TV povolte instalaci neznámých aplikací pro TV Kiosek"));
                        break;
                    }
                    new Thread(() -> {
                        try {
                            Updater.installLatestRelease(app);
                        } catch (IOException | JSONException e) {
                            Log.w(BootReceiver.TAG, "GitHub update failed", e);
                        }
                    }, "web-admin-github").start();
                    json(out, 200, status());
                    break;
                case "GET /api/status":
                    json(out, 200, status());
                    break;
                default:
                    json(out, 404, error("Nenalezeno"));
            }
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(BootReceiver.TAG, "Web admin request failed", e);
            try {
                json(socket.getOutputStream(), 500, error(String.valueOf(e.getMessage())));
            } catch (IOException | JSONException ignored) {
                // The client is gone.
            }
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }

    private static String page() throws IOException {
        try (InputStream in = app.getAssets().open("admin.html")) {
            ByteArrayOutputStream page = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) != -1; ) page.write(buffer, 0, n);
            return page.toString("UTF-8");
        }
    }

    private static JSONObject settings() throws JSONException {
        JSONObject result = status();
        result.put("url", Preferences.url(app));
        result.put("engine", Preferences.get(app).getString(Preferences.ENGINE, Preferences.GECKO));
        result.put("startOnBoot", Preferences.get(app).getBoolean(Preferences.START_ON_BOOT, true));
        result.put("autostartReady", Autostart.canStartFromBackground(app));
        result.put("homeApp", Autostart.isHomeApp(app));
        result.put("lastBoot", Preferences.get(app).getString(Preferences.LAST_BOOT, "zatím nezaznamenán"));
        result.put("lastStart", Preferences.get(app).getString(Preferences.LAST_START, "zatím nezaznamenáno"));
        return result;
    }

    private static JSONObject saveSettings(JSONObject body) throws JSONException {
        String url = Preferences.normalizeUrl(body.optString("url"));
        if (url == null) return error("Neplatná adresa");
        String engine = Preferences.SYSTEM_WEBVIEW.equals(body.optString("engine"))
                ? Preferences.SYSTEM_WEBVIEW : Preferences.GECKO;
        boolean startOnBoot = body.optBoolean("startOnBoot", true);
        // MainActivity listens for these changes and reloads the page.
        Preferences.get(app).edit()
                .putString(Preferences.URL, url)
                .putString(Preferences.ENGINE, engine)
                .putBoolean(Preferences.START_ON_BOOT, startOnBoot).apply();
        if (!startOnBoot) AutostartFallback.cancel(app);
        return settings();
    }

    private static JSONObject status() throws JSONException {
        JSONObject result = new JSONObject();
        try {
            PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            result.put("version", info.versionName + " (" + info.versionCode + ")");
        } catch (PackageManager.NameNotFoundException ignored) {
            // Cannot happen for the running package.
        }
        result.put("abi", Build.SUPPORTED_ABIS[0]);
        result.put("canInstall", Updater.canInstall(app));
        result.put("installStatus", Updater.status());
        return result;
    }

    private static JSONObject error(String message) throws JSONException {
        return new JSONObject().put("error", message);
    }

    private static long parseLength(String value) {
        try {
            return value == null ? -1 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != -1 && c != '\n'; ) {
            if (line.size() > 8192) throw new IOException("Header too long");
            if (c != '\r') line.write(c);
        }
        return line.toString("UTF-8");
    }

    private static String readBody(InputStream in, long length) throws IOException {
        if (length < 0 || length > MAX_JSON_BYTES) throw new IOException("Invalid body length");
        byte[] body = new byte[(int) length];
        int read = 0;
        while (read < body.length) {
            int n = in.read(body, read, body.length - read);
            if (n == -1) throw new IOException("Body truncated");
            read += n;
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private static void json(OutputStream out, int code, JSONObject body) throws IOException {
        respond(out, code, "application/json; charset=utf-8", body.toString());
    }

    private static void respond(OutputStream out, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " " + (code < 400 ? "OK" : "Error") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    /** Lets the installer read exactly the request body, not past it. */
    private static final class LimitedInputStream extends InputStream {
        private final InputStream in;
        private long remaining;

        LimitedInputStream(InputStream in, long length) { this.in = in; this.remaining = length; }

        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int c = in.read();
            if (c != -1) remaining--;
            return c;
        }

        @Override public int read(byte[] buffer, int offset, int count) throws IOException {
            if (remaining <= 0) return -1;
            int n = in.read(buffer, offset, (int) Math.min(count, remaining));
            if (n != -1) remaining -= n;
            return n;
        }
    }
}
