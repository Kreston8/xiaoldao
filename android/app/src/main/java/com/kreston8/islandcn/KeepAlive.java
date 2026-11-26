package com.kreston8.islandcn;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Brings the island back after the process died, and after a reboot.
 *
 * <p>The service itself is a foreground service with {@code START_STICKY} and
 * {@code stopWithTask="false"}, so swiping the settings screen out of the recent tasks changes
 * nothing. What still happens on a phone like this one is the vendor killer: MIUI and friends call
 * {@code force-stop} on an app that is idle for a while, and no sticky flag survives that.
 *
 * <p>Three cheap ways back, none of them a wake lock and none of them a polling service:
 * <ul>
 *   <li>a broadcast after a reboot or after an update, which is free;</li>
 *   <li>a single {@link AlarmManager} alarm every fifteen minutes, re-armed from inside the
 *       receiver so only one is ever pending, asking whether the service is still there;</li>
 *   <li>and nothing else. A killed process does not burn any battery.</li>
 * </ul>
 *
 * <p>Android 12 and later refuse a foreground service start from the background unless the app is
 * exempt from battery optimisations. The settings screen offers that exemption, and everything here
 * fails silently when it is missing, because a failed restart must never be a crash.
 */
public final class KeepAlive extends BroadcastReceiver {

    /** Private action of the watchdog alarm. */
    private static final String ACTION_TICK = "com.kreston8.islandcn.KEEP_ALIVE_TICK";

    /** Fifteen minutes: often enough to be useful, rare enough to stay invisible in the battery list. */
    private static final long TICK_MS = 15L * 60_000L;

    private static final int REQUEST = 71;

    /** Arms the watchdog. Safe to call on every service start and every task removal. */
    static void start(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + TICK_MS, pending(context));
        } catch (Throwable ignored) {
            // Some vendor builds restrict exact alarms; the reboot path still works.
        }
    }

    /** Disarms it: the person switched the island off, so nothing may bring it back. */
    static void stop(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            manager.cancel(pending(context));
        } catch (Throwable ignored) {
            // Nothing to cancel.
        }
    }

    private static PendingIntent pending(Context context) {
        Intent intent = new Intent(context, KeepAlive.class).setAction(ACTION_TICK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, REQUEST, intent, flags);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_TICK.equals(action)) {
            // Re-arm first: if the restart below throws, the watchdog must not lose its slot.
            start(context);
            if (!IslandService.isRunning()) {
                launch(context);
            }
            return;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            // Only when the island was on when the phone went down: a person who switched it off
            // must not find it running again after every reboot.
            if (Prefs.running(context) && !IslandService.isRunning()) {
                launch(context);
            }
            start(context);
        }
    }

    /**
     * Asks for the service to come back.
     *
     * <p>Every failure is swallowed on purpose. A receiver that crashes turns into "the app keeps
     * stopping" on some ROMs, which is far worse than an island that needs one tap to come back.
     */
    private static void launch(Context context) {
        Intent intent = new Intent(context, IslandService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable ignored) {
            // Android 12+ background start restriction, or a vendor block. Nothing else to try.
        }
    }
}
