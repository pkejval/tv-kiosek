package cz.kiosek.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

public final class MainActivity extends Activity {
    private static final int REQUEST_SETTINGS = 100;
    private static GeckoRuntime geckoRuntime;
    private FrameLayout container;
    private GeckoSession geckoSession;
    private WebView systemWebView;
    private final Handler handler = new Handler();
    private boolean appDrawerOpened;
    private final Runnable openAppDrawer = () -> {
        appDrawerOpened = true;
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
        WebAdmin.start(this);
        Preferences.get(this).registerOnSharedPreferenceChangeListener(settingsListener);
        if (!Preferences.get(this).contains(Preferences.URL)) {
            startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_SETTINGS);
            return;
        }
        showKiosk();
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
    }

    private void loadEngine() {
        if (container == null) return;
        container.removeAllViews();
        if (geckoSession != null) {
            geckoSession.close();
            geckoSession = null;
        }
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
        geckoSession.open(geckoRuntime);
        GeckoView view = new GeckoView(this);
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
        view.setWebViewClient(new WebViewClient());
        container.addView(view, new FrameLayout.LayoutParams(-1, -1));
        view.loadUrl(Preferences.url(this));
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && event.getRepeatCount() == 0) {
            appDrawerOpened = false;
            handler.postDelayed(openAppDrawer, 700);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            handler.removeCallbacks(openAppDrawer);
            if (!appDrawerOpened) {
                openSettings();
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
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
        handler.removeCallbacks(openAppDrawer);
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
        handler.removeCallbacks(openAppDrawer);
        Preferences.get(this).unregisterOnSharedPreferenceChangeListener(settingsListener);
        if (geckoSession != null) geckoSession.close();
        if (systemWebView != null) systemWebView.destroy();
        super.onDestroy();
    }
}
