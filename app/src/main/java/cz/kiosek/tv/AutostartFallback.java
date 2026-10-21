package cz.kiosek.tv;

import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

/** A second, system-delivered launch attempt for TVs which are slow after boot. */
public final class AutostartFallback {
    private static final int REQUEST_CODE = 7411;
    private static final long DELAY_MS = 8_000L;

    private AutostartFallback() { }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, MainActivity.class)
                .setAction("cz.kiosek.tv.action.AUTOSTART_FALLBACK")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Target SDK 35 requires the creator to opt in when the system sends
            // a PendingIntent that may bring an activity to the foreground.
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            return PendingIntent.getActivity(context, REQUEST_CODE, intent, flags, options.toBundle());
        }
        return PendingIntent.getActivity(context, REQUEST_CODE, intent, flags);
    }

    public static void schedule(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        // This is deliberately inexact: it requires no exact-alarm permission
        // and lets Android choose a safe moment shortly after boot.
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + DELAY_MS, pendingIntent(context));
    }

    public static void cancel(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms != null) alarms.cancel(pendingIntent(context));
    }
}
