# TV Kiosek

Android TV kiosk that opens a configurable URL and starts after boot. It brings a current browser engine to older Android TVs whose system WebView is outdated.

## Why GeckoView

On an Android TV 10 device with Chromium/WebView 82, the system WebView cannot make modern browser features available. The default **GeckoView** engine is packaged with the app and is independent of that old component. It increases the APK size substantially; the System WebView option is available when footprint matters more than browser compatibility.

Chromium is not distributed as an embeddable Android library (Crosswalk ended at Chromium 53), so the only way to a newer Blink engine is updating the device's system WebView provider. That works only when the TV uses `com.google.android.webview` (check `adb shell dumpsys webviewupdate`); then sideloading a current "Android System WebView" APK makes the **Systémový WebView** option modern. Many TV firmwares lock the provider, so GeckoView remains the default.

## Releases

Every push to `main` runs `.github/workflows/release.yml`: it builds signed APKs, sets `versionCode` to the workflow run number and publishes them to [Releases](https://github.com/pkejval/tv-kiosek/releases) as `tv-kiosek-<versionCode>-<abi>.apk`. Install `arm64-v8a` on 64-bit TVs and `armeabi-v7a` on 32-bit ones.

The release keystore is stored in the repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. Keep an offline backup of it: an APK signed with another key can never update an installed kiosk, so a lost key means reinstalling every TV by hand.

For local development, open the folder in Android Studio (JDK 17) and build the debug variant. A debug build has a different signature, so it cannot be updated by a release (uninstall it first).

## First installation

```
adb connect <TV IP>
adb install tv-kiosek-<versionCode>-arm64-v8a.apk
adb shell am start -n cz.kiosek.tv/.MainActivity
```

At the first launch, the app shows the configuration screen. Later, press **MENU** on the TV remote to open it again; hold **MENU** to open the list of installed apps.

## Autostart on Android 10+

Android 10 silently blocks activity starts from a boot receiver. The app comes to the foreground after boot only when one of these holds (the settings screen shows both states):

1. **Výchozí launcher**: the app is the Home app, so Android itself opens it at boot. Most reliable. Many Android TV builds have no screen for choosing the launcher; set it over adb:

   ```
   adb shell cmd package set-home-activity cz.kiosek.tv/.MainActivity
   ```

   If the stock launcher still opens after a reboot, disable it (stock Android TV: `com.google.android.tvlauncher`; Google TV: `com.google.android.apps.tv.launcherx`):

   ```
   adb shell pm disable-user --user 0 com.google.android.tvlauncher
   ```

   Undo with `adb shell pm enable com.google.android.tvlauncher`. Keep adb access to the TV before doing this: with the stock launcher disabled, the kiosk is the only home screen.

2. **Zobrazení přes jiné aplikace** (`SYSTEM_ALERT_WINDOW`) lifts the background-start restriction. TVs often hide this screen; grant it over adb:

   ```
   adb shell appops set cz.kiosek.tv SYSTEM_ALERT_WINDOW allow
   ```

Launch the app once after installation: Android does not deliver the boot broadcast to an app that has never been started. Diagnose a failed autostart with `adb logcat -s TvKiosk`.

## Web administration

While the kiosk runs, `http://<TV IP>:8080` serves an administration page for the URL, engine and autostart. Changes apply immediately. The address is shown in the TV settings screen (**MENU** → *Webová správa*). There is no password: use it only on a trusted LAN. Requests must carry the `X-Kiosek: 1` header, which keeps other web pages (including the one shown in the kiosk) from changing the settings.

## Updates

The administration page installs a new version in one of three ways:

- **Aktualizovat z GitHubu** downloads the latest release for the TV's architecture when its `versionCode` is newer.
- Uploading an APK from the computer.
- Downloading an APK from an `https://` address.

On the TV, allow *Instalace aktualizací* (unknown sources for TV Kiosek) once. Android requires a confirmation on the TV for each install by an ordinary app; confirm it with the remote. After the update, the app restarts itself (`MY_PACKAGE_REPLACED`).

For updates without anyone at the TV, use network adb: `adb install -r tv-kiosek-<versionCode>-<abi>.apk`.
