package com.kreston8.islandcn;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The small ring buffer behind the 保活诊断 card.
 *
 * <p>Everything that can go wrong with "the island did not come back" happens outside the app's own
 * screen: the process is killed, a job asks for a foreground service and is refused, an alarm never
 * fires, or the pill is hidden because the launcher test says "some other app is in front". None of
 * that is visible from the UI, and none of it survives in logcat after the user reboots. So each of
 * those moments writes one short line here, and the settings screen prints the last lines with a copy
 * button.
 *
 * <p>Deliberately dumb: one {@link SharedPreferences} string, newest first, capped at
 * {@link #MAX} lines, and every entry is a timestamp plus a few words. It is written from the main
 * thread of a process that may be about to die, so nothing here is allowed to throw.
 */
final class Diag {

    private static final String FILE = "diag";
    private static final String KEY = "log";

    /** Enough to cover several kill/revive cycles, small enough to print in a TextView. */
    private static final int MAX = 60;

    private Diag() {
    }

    /** Adds one line. Safe from any thread, and never throws. */
    static void note(Context context, String what) {
        if (context == null || what == null) {
            return;
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            String stamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
            StringBuilder out = new StringBuilder(stamp).append(' ').append(what);
            String old = prefs.getString(KEY, "");
            if (old != null && old.length() > 0) {
                String[] parts = old.split("\n");
                int keep = Math.min(parts.length, MAX - 1);
                for (int i = 0; i < keep; i++) {
                    out.append('\n').append(parts[i]);
                }
            }
            prefs.edit().putString(KEY, out.toString()).apply();
        } catch (Throwable ignored) {
            // A diagnostic that crashes is worse than a missing diagnostic.
        }
    }

    /** The log, newest first, or a placeholder when nothing has been written yet. */
    static String text(Context context) {
        try {
            String old = context
                    .getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .getString(KEY, "");
            if (old == null || old.length() == 0) {
                return "还没有记录。开关打开着、退出应用之后这里应该出现几行。";
            }
            return old;
        } catch (Throwable ignored) {
            return "读不到记录。";
        }
    }

    static void clear(Context context) {
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .edit().remove(KEY).apply();
        } catch (Throwable ignored) {
            // Nothing to clear.
        }
    }
}
