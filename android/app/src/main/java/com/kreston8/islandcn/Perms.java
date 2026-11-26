package com.kreston8.islandcn;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

/** The two permissions this app lives on, and the settings screens that grant them. */
final class Perms {

    private Perms() {
    }

    static boolean canOverlay(Context context) {
        return Settings.canDrawOverlays(context);
    }

    static void openOverlaySettings(Context context) {
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Throwable ignored) {
            openFallback(context, new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    static boolean hasNotificationAccess(Context context) {
        String flat = Settings.Secure.getString(
                context.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(context.getPackageName());
    }

    static void openNotificationAccessSettings(Context context) {
        openFallback(context, new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }

    /** True when the user granted "usage access", the only way to learn which app is in front. */
    static boolean hasUsageAccess(Context context) {
        try {
            AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) {
                return false;
            }
            int mode = ops.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void openUsageAccessSettings(Context context) {
        openFallback(context, new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
    }

    private static void openFallback(Context context, Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Throwable ignored) {
            // Some ROMs hide these screens; the in-app text still explains what to enable.
        }
    }
}
