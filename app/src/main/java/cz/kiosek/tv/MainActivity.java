package cz.kiosek.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.content.Context;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.text.Html;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebRequestError;

public final class MainActivity extends Activity {
    private static final int REQUEST_SETTINGS = 100;
    private static final long LONG_PRESS_MS = 700;
    private static final long WATCHDOG_MS = 30_000;
    private static final long LOAD_TIMEOUT_MS = 60_000;
    // A white page is reloaded after this many consecutive blank checks, and the
    // whole browser engine is recreated after twice as many.
    private static final int BLANK_RELOAD_CHECKS = 2;
    private static GeckoRuntime geckoRuntime;
    private FrameLayout container;
    private GeckoSession geckoSession;
    private GeckoView geckoView;
    private WebView systemWebView;
    private final Handler handler = new Handler();
    private boolean geckoCanGoBack;
    private boolean menuOpened;
    // Page health, watched by watchdogTick().
    private boolean pageLoading;
    private boolean errorShown;
    private long loadStartedAt;
    private int blankChecks;
    private final Runnable watchdog = this::watchdogTick;
    private final Runnable openMenu = () -> {
        menuOpened = true;
        startActivity(new Intent(this, AppDrawerActivity.class));
    };
    // Settings saved from the web administration apply immediately.
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener = (preferences, key) -> {
        if (Preferences.URL.equals(key) || Preferences.ENGINE.equals(key)) handler.post(this::loadEngine);
    };
    private final View.OnGenericMotionListener mouseListener = (view, event) -> {
        if (isSecondaryMouseClick(event)) {
            openSettings();
            return true;
        }
        return false;
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        recordStart(getIntent());
        WebAdmin.start(this);
        Preferences.get(this).registerOnSharedPreferenceChangeListener(settingsListener);
        if (!Preferences.get(this).contains(Preferences.URL)) {
            startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_SETTINGS);
            return;
        }
        showKiosk();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        recordStart(intent);
    }

    /** Shows in the web administration which path actually brought the kiosk up. */
    private void recordStart(Intent intent) {
        String source = intent.getAction() != null ? intent.getAction() : "BootReceiver";
        if (intent.hasCategory(Intent.CATEGORY_HOME)) source = "HOME (výchozí launcher)";
        else if (intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER) || intent.hasCategory(Intent.CATEGORY_LAUNCHER)) source = "ikona aplikace";
        Preferences.recordEvent(this, Preferences.LAST_START, source);
    }

    private void showKiosk() {
        Window window = getWindow();
        window.setStatusBarColor(Color.BLACK);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        container = new FrameLayout(this);
        container.setOnGenericMotionListener(mouseListener);
        setContentView(container);
        loadEngine();
    }

    @Override protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, WATCHDOG_MS);
    }

    // Not watched while another screen (menu, settings) covers the kiosk.
    @Override protected void onPause() {
        handler.removeCallbacks(watchdog);
        super.onPause();
    }

    private void loadEngine() {
        if (container == null) return;
        container.removeAllViews();
        if (geckoSession != null) {
            geckoSession.close();
            geckoSession = null;
        }
        geckoCanGoBack = false;
        geckoView = null;
        pageLoading = false;
        errorShown = false;
        blankChecks = 0;
        if (systemWebView != null) {
            systemWebView.stopLoading();
            systemWebView.destroy();
            systemWebView = null;
        }
        String engine = Preferences.get(this).getString(Preferences.ENGINE, Preferences.GECKO);
        if (Preferences.SYSTEM_WEBVIEW.equals(engine)) loadSystemWebView(); else loadGeckoView();
    }

    private void loadGeckoView() {
        // Only one GeckoRuntime may exist per process; creating a second one
        // (activity recreation, settings reload, second launch) throws.
        if (geckoRuntime == null) {
            GeckoRuntimeSettings runtimeSettings = new GeckoRuntimeSettings.Builder()
                    .javaScriptEnabled(true)
                    .remoteDebuggingEnabled(false)
                    .build();
            geckoRuntime = GeckoRuntime.create(getApplicationContext(), runtimeSettings);
        }
        geckoSession = new GeckoSession();
        geckoSession.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public void onCanGoBack(GeckoSession session, boolean canGoBack) {
                geckoCanGoBack = canGoBack;
            }

            // Shown instead of a blank page; the watchdog retries.
            @Override public GeckoResult<String> onLoadError(GeckoSession session, String uri, WebRequestError error) {
                errorShown = true;
                return GeckoResult.fromValue("data:text/html;charset=utf-8," + Uri.encode(errorPage(geckoErrorText(error))));
            }
        });
        geckoSession.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStart(GeckoSession session, String url) { markLoadStarted(); }
            @Override public void onPageStop(GeckoSession session, boolean success) { pageLoading = false; }
        });
        geckoSession.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onCrash(GeckoSession session) { restartEngine("pád prohlížeče"); }
            @Override public void onKill(GeckoSession session) { restartEngine("prohlížeč ukončen systémem"); }
        });
        geckoSession.open(geckoRuntime);
        GeckoView view = new GeckoView(this);
        geckoView = view;
        view.setOnGenericMotionListener(mouseListener);
        view.setSession(geckoSession);
        container.addView(view, new FrameLayout.LayoutParams(-1, -1));
        geckoSession.loadUri(Preferences.url(this));
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void loadSystemWebView() {
        WebView view = new WebView(this);
        systemWebView = view;
        view.setOnGenericMotionListener(mouseListener);
        view.getSettings().setJavaScriptEnabled(true);
        view.getSettings().setDomStorageEnabled(true);
        view.getSettings().setMediaPlaybackRequiresUserGesture(false);
        view.setWebChromeClient(new WebChromeClient());
        view.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView v, String url, Bitmap favicon) { markLoadStarted(); }
            @Override public void onPageFinished(WebView v, String url) { pageLoading = false; }
            @Override public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                errorShown = true;
                v.loadDataWithBaseURL(null, errorPage(webViewErrorText(error)), "text/html", "utf-8", null);
            }
            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                restartEngine("pád prohlížeče");
                return true;
            }
        });
        container.addView(view, new FrameLayout.LayoutParams(-1, -1));
        view.loadUrl(Preferences.url(this));
    }

    /** Error page shown instead of a blank screen; {@code detail} is the exact cause. */
    private String errorPage(String detail) {
        String reason = isOnline() ? detail : "Bez připojení k síti (" + detail + ")";
        return "<!doctype html><meta charset=utf-8>"
                + "<meta name=viewport content=\"width=device-width\">"
                + "<body style=\"margin:0;height:100vh;display:flex;flex-direction:column;align-items:center;"
                + "justify-content:center;background:#111;color:#fff;font:5vh sans-serif;text-align:center\">"
                + "<div>Stránku se nepodařilo načíst</div>"
                + "<div style=\"font-size:3.5vh;color:#f88;margin-top:2vh\">" + Html.escapeHtml(reason) + "</div>"
                + "<div style=\"font-size:3vh;opacity:.7;margin-top:2vh\">" + Html.escapeHtml(Preferences.url(this)) + "</div>"
                + "<div style=\"font-size:3vh;opacity:.7;margin-top:2vh\">Zkouším to znovu…</div>";
    }

    private boolean isOnline() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        return manager != null && manager.getActiveNetwork() != null;
    }

    private static String geckoErrorText(WebRequestError error) {
        switch (error.code) {
            case WebRequestError.ERROR_UNKNOWN_HOST: return "DNS: adresa serveru nenalezena";
            case WebRequestError.ERROR_CONNECTION_REFUSED: return "Server odmítl spojení";
            case WebRequestError.ERROR_NET_TIMEOUT: return "Server neodpovídá (vypršel čas)";
            case WebRequestError.ERROR_OFFLINE: return "Zařízení je offline";
            case WebRequestError.ERROR_NET_RESET:
            case WebRequestError.ERROR_NET_INTERRUPT: return "Spojení se serverem bylo přerušeno";
            case WebRequestError.ERROR_SECURITY_SSL:
            case WebRequestError.ERROR_SECURITY_BAD_CERT: return "Chyba zabezpečeného spojení (certifikát)";
            default: return "Chyba načtení, kód " + error.code;
        }
    }

    private static String webViewErrorText(WebResourceError error) {
        switch (error.getErrorCode()) {
            case WebViewClient.ERROR_HOST_LOOKUP: return "DNS: adresa serveru nenalezena";
            case WebViewClient.ERROR_CONNECT: return "Server nedostupný nebo odmítl spojení";
            case WebViewClient.ERROR_TIMEOUT: return "Server neodpovídá (vypršel čas)";
            case WebViewClient.ERROR_FAILED_SSL_HANDSHAKE: return "Chyba zabezpečeného spojení (certifikát)";
            default: return "Chyba načtení: " + error.getDescription();
        }
    }

    private void markLoadStarted() {
        pageLoading = true;
        loadStartedAt = SystemClock.elapsedRealtime();
    }

    private void restartEngine(String reason) {
        Preferences.recordEvent(this, Preferences.LAST_RELOAD, reason);
        handler.post(this::loadEngine);
    }

    private void reloadPage(String reason) {
        Preferences.recordEvent(this, Preferences.LAST_RELOAD, reason);
        errorShown = false;
        String url = Preferences.url(this);
        if (geckoSession != null) geckoSession.loadUri(url);
        else if (systemWebView != null) systemWebView.loadUrl(url);
    }

    /**
     * Keeps the kiosk from sitting on a dead page: retries after a load error or
     * a stuck load, and reloads (then restarts the engine) when GeckoView keeps
     * showing a plain white page. The system WebView has no blank-page check.
     */
    private void watchdogTick() {
        handler.postDelayed(watchdog, WATCHDOG_MS);
        if (container == null) return;
        if (errorShown) {
            reloadPage("chyba načtení, zkouším znovu");
        } else if (pageLoading) {
            if (SystemClock.elapsedRealtime() - loadStartedAt > LOAD_TIMEOUT_MS) reloadPage("načítání trvalo příliš dlouho");
        } else if (geckoView != null) {
            GeckoView checked = geckoView;
            checked.capturePixels().accept(bitmap -> {
                if (checked != geckoView || pageLoading || errorShown || bitmap == null) return;
                if (!isBlank(bitmap)) {
                    blankChecks = 0;
                } else if (++blankChecks >= 2 * BLANK_RELOAD_CHECKS) {
                    restartEngine("prázdná bílá stránka, restart prohlížeče");
                } else if (blankChecks == BLANK_RELOAD_CHECKS) {
                    reloadPage("prázdná bílá stránka");
                }
            }, error -> { });
        }
    }

    /** True when every sampled pixel is (nearly) white. */
    private static boolean isBlank(Bitmap bitmap) {
        for (int y = 0; y < bitmap.getHeight(); y += 4) {
            for (int x = 0; x < bitmap.getWidth(); x += 4) {
                int p = bitmap.getPixel(x, y);
                if (Color.red(p) < 248 || Color.green(p) < 248 || Color.blue(p) < 248) return false;
            }
        }
        return true;
    }

    /**
     * Handled before the browser view sees the key. Holding BACK or MENU opens
     * the menu; most Android TV remotes have no MENU key, but all have BACK.
     */
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key != KeyEvent.KEYCODE_BACK && key != KeyEvent.KEYCODE_MENU) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            menuOpened = false;
            handler.postDelayed(openMenu, LONG_PRESS_MS);
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(openMenu);
            if (!menuOpened && !event.isCanceled()) {
                if (key == KeyEvent.KEYCODE_MENU) openSettings(); else goBack();
            }
        }
        return true;
    }

    /** Page history only: BACK never leaves the kiosk. */
    private void goBack() {
        if (geckoSession != null && geckoCanGoBack) {
            geckoSession.goBack();
        } else if (systemWebView != null && systemWebView.canGoBack()) {
            systemWebView.goBack();
        }
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (isSecondaryMouseClick(event)) {
            openSettings();
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    private boolean isSecondaryMouseClick(MotionEvent event) {
        return event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)
                && event.getAction() == MotionEvent.ACTION_BUTTON_PRESS
                && (event.getActionButton() & MotionEvent.BUTTON_SECONDARY) != 0;
    }

    private void openSettings() {
        handler.removeCallbacks(openMenu);
        startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_SETTINGS);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SETTINGS) return;
        // A changed URL or engine is applied by settingsListener.
        if (container == null) {
            // First run: construct the kiosk only after configuration closes.
            showKiosk();
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(openMenu);
        handler.removeCallbacks(watchdog);
        Preferences.get(this).unregisterOnSharedPreferenceChangeListener(settingsListener);
        if (geckoSession != null) geckoSession.close();
        if (systemWebView != null) systemWebView.destroy();
        super.onDestroy();
    }
}
