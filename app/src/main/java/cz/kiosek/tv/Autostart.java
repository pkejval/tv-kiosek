package cz.kiosek.tv;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/** What Android requires before a boot receiver may bring the kiosk to the foreground. */
public final class Autostart {
    public static final String ADB_GRANT_OVERLAY = "adb shell appops set cz.kiosek.tv SYSTEM_ALERT_WINDOW allow";
    // Android 10+ resets a plain preferred Home activity to the role holder at boot.
    public static final String ADB_SET_HOME = "adb shell cmd role add-role-holder android.app.role.HOME cz.kiosek.tv";

    private Autostart() { }

    public static boolean canStartFromBackground(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || isHomeApp(context) || canDrawOverlays(context);
    }

    public static boolean canDrawOverlays(Context context) {
        return Settings.canDrawOverlays(context);
    }

    public static boolean isHomeApp(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roles = context.getSystemService(RoleManager.class);
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                return roles.isRoleHeld(RoleManager.ROLE_HOME);
            }
        }
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo info = context.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return info != null && context.getPackageName().equals(info.activityInfo.packageName);
    }

    /** @return false when the TV firmware hides the overlay permission screen. */
    public static boolean openOverlaySettings(Activity activity) {
        Uri pkg = Uri.parse("package:" + activity.getPackageName());
        Intent[] candidates = {
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg),
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
        };
        for (Intent intent : candidates) {
            try {
                activity.startActivity(intent);
                return true;
            } catch (RuntimeException ignored) {
                // Android TV builds often declare but disable this screen.
            }
        }
        return false;
    }

    /** @return false when the TV offers no screen for choosing the Home app. */
    public static boolean requestHomeRole(Activity activity, int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roles = activity.getSystemService(RoleManager.class);
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                try {
                    activity.startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), requestCode);
                    return true;
                } catch (RuntimeException ignored) {
                    // Android TV builds may lack the role request dialog.
                }
            }
        }
        try {
            activity.startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
