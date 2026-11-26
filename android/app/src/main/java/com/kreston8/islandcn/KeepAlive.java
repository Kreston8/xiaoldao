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
 * <p>Four cheap ways back, none of them a wake lock and none of them a polling service:
 * <ul>
 *   <li>a broadcast after a reboot or after an update, which is free;</li>
 *   <li>a single {@link AlarmManager} alarm every fifteen minutes, re-armed from inside the
 *       receiver so only one is ever pending, asking whether the service is still there;</li>
 *   <li>a one-shot alarm about eight seconds after the task disappears, which is the case that
 *       actually matters — see {@link #startNow(Context)};</li>
 *   <li>and nothing else. A killed process does not burn any battery.</li>
 * </ul>
 *
 * <p>Why an alarm and not only the job in {@link RestoreJob}: on Android 12 and later a background
 * app may not start a foreground service, and being refused is silent. Delivering a broadcast —
 * an alarm — puts the app on the temporary allow-list for a few seconds, which is exactly what the
 * restart needs; a job is not promised that treatment on every ROM. Both are kept: whichever fires
 * first wins, and the other one then sees {@link IslandService#isRunning()} and does nothing.
 *
 * <p>A failed attempt is not dropped: it re-arms itself after 30 s, 60 s, 3 min, 10 min and then
 * every 15 minutes, and every attempt leaves one line in {@link Diag} so the settings screen can say
 * what happened instead of leaving the person guessing.
 */
public final class KeepAlive extends BroadcastReceiver {

    /** Private action of the watchdog alarm. */
    private static final String ACTION_TICK = "com.kreston8.islandcn.KEEP_ALIVE_TICK";

    /** Fifteen minutes: often enough to be useful, rare enough to stay invisible in the battery list. */
    private static final long TICK_MS = 15L * 60_000L;

    /** Fast attempt after the task is swiped away: after the teardown, before the person gives up. */
    private static final long NOW_MS = 8_000L;

    /** Retry ladder after a refused restart; the last value is then repeated. */
    private static final long[] RETRY_MS = {30_000L, 60_000L, 3L * 60_000L, 10L * 60_000L};

    private static final int REQUEST = 71;
    private static final int REQUEST_NOW = 74;

    /** How many restarts have failed in a row, so the ladder knows which rung to use. */
    private static final String KEY_TRIES = "ka_tries";

    /** Arms the slow watchdog. Safe to call on every service start and every task removal. */
    static void start(Context context) {
        arm(context, TICK_MS, REQUEST);
    }

    /**
     * Arms a one-shot attempt about eight seconds from now.
     *
     * <p>This is the honest answer to "I swiped the app away and the island vanished": the periodic
     * watchdog would bring it back, but up to fifteen minutes later, which looks like a broken app.
     * Eight seconds is late enough for the system to finish tearing the task down and early enough
     * that the pill reappearing still reads as "it stayed", not as a restart.
     */
    static void startNow(Context context) {
        Diag.note(context, "排一次 8 秒闹钟");
        arm(context, NOW_MS, REQUEST_NOW);
    }

    /** Disarms everything: the person switched the island off, so nothing may bring it back. */
    static void stop(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Prefs.setNumber(context, KEY_TRIES, 0);
        if (manager == null) {
            return;
        }
        try {
            manager.cancel(pending(context, REQUEST));
            manager.cancel(pending(context, REQUEST_NOW));
        } catch (Throwable ignored) {
            // Nothing to cancel.
        }
    }

    private static void arm(Context context, long delay, int request) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            return;
        }
        long at = System.currentTimeMillis() + delay;
        // Exact wherever the ROM allows it, inexact-but-allowed everywhere else: both survive doze,
        // and a refused exact alarm must not stop the island from coming back at all.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms())) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at,
                        pending(context, request));
                return;
            }
        } catch (Throwable ignored) {
            // Fall through to the inexact form below.
        }
        try {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, request));
        } catch (Throwable ignored) {
            // Some vendor builds restrict alarms; the job and the reboot path still work.
        }
    }

    private static PendingIntent pending(Context context, int request) {
        Intent intent = new Intent(context, KeepAlive.class).setAction(ACTION_TICK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, request, intent, flags);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_TICK.equals(action)) {
            // Re-arm first: if the restart below throws, the watchdog must not lose its slot.
            start(context);
            if (!IslandService.isRunning()) {
                Diag.note(context, "闹钟发现服务不在，尝试拉起");
                if (!startService(context)) {
                    int tries = (int) Prefs.number(context, KEY_TRIES);
                    long delay = RETRY_MS[Math.min(tries, RETRY_MS.length - 1)];
                    Prefs.setNumber(context, KEY_TRIES, tries + 1);
                    Diag.note(context, "拉起失败，第 " + (tries + 1) + " 次，稍后重试");
                    arm(context, delay, REQUEST_NOW);
                } else {
                    Prefs.setNumber(context, KEY_TRIES, 0);
                }
            }
            return;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            // Only when the island was on when the phone went down: a person who switched it off
            // must not find it running again after every reboot.
            Diag.note(context, "开机/更新广播");
            if (Prefs.running(context) && !IslandService.isRunning()) {
                startService(context);
            }
            start(context);
        }
    }

    /**
     * Asks for the service to come back, and says whether the platform accepted the request.
     *
     * <p>The answer is only "the request was not refused", not "the service is up": Android 12 may
     * still refuse the foreground part a moment later. That is what the retry ladder and the
     * diagnostic log are for. Every failure is swallowed on purpose — a receiver that crashes turns
     * into "the app keeps stopping" on some ROMs, which is far worse than an island that needs one
     * tap to come back.
     */
    static boolean startService(Context context) {
        if (!Prefs.running(context) || !Perms.canOverlay(context)) {
            Diag.note(context, "不拉起：开关关着或没有悬浮窗权限");
            return false;
        }
        Intent intent = new Intent(context, IslandService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            Diag.note(context, "已请求启动前台服务");
            return true;
        } catch (Throwable error) {
            Diag.note(context, "启动被拒：" + error.getClass().getSimpleName());
            return false;
        }
    }
}
