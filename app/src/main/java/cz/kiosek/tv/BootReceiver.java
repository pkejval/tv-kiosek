package cz.kiosek.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {
    static final String TAG = "TvKiosk";

    @Override public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Received " + intent.getAction());
        if (!Preferences.get(context).getBoolean(Preferences.START_ON_BOOT, true)) {
            AutostartFallback.cancel(context);
            return;
        }
        if (!Autostart.canStartFromBackground(context)) {
            // Android 10+ silently drops the activity start below. Only the
            // Home role or the overlay permission lifts the restriction.
            Log.w(TAG, "Background activity start is blocked: grant 'display over other apps' or set as Home app");
        }
        Intent launch = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            context.startActivity(launch);
        } catch (RuntimeException e) {
            // Some TV firmwares reject the first background launch during boot.
            Log.w(TAG, "Launch after boot failed", e);
        }
        // A PendingIntent sent by AlarmManager is a separate, system-delivered
        // attempt after the TV has finished initializing its launcher process.
        AutostartFallback.schedule(context);
    }
}
