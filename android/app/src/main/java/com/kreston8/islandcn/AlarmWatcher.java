package com.kreston8.islandcn;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Turns the system clock alarm into island cards: three heads-ups, ten, five and one minute early.
 *
 * <p>The island cannot show anything when the clock app actually rings: that is a full screen
 * intent owned by the clock, and it covers every overlay. So this receiver schedules its own
 * heads-ups from the alarm time the AlarmManager exposes through {@code getNextAlarmClock()}: one
 * at T-10, one at T-5 and one at T-1, and nothing at all after T, because by then the clock speaks
 * for itself. How long each card stays on screen is {@link IslandService}'s business, not this
 * class's.
 *
 * <p>{@link #schedule(Context)} is called by {@link IslandService} both at start-up and whenever the
 * system reports a changed alarm, because this broadcast is only delivered to receivers that are
 * registered at runtime. Which of the three heads-ups were already said lives in
 * {@link Prefs#KEY_ALARM_STAGE} as {@code "<triggerMillis>|<bitmask>"}, so a service restart cannot
 * repeat a stage or skip one, and the bits are zeroed as soon as a different alarm is queued.
 *
 * <p>A card that outlives its alarm is the failure people actually notice: a reminder kept sitting
 * on screen long after the alarm had rung, been moved or been switched off, which reads as a
 * phantom alarm. Three guards prevent it. Every call to {@link #schedule(Context)} reconciles the
 * card against the alarm the system currently reports and withdraws it as soon as the two disagree;
 * every announcement arms a second pending intent a minute past the alarm time that removes the card
 * even if nothing else ever runs; and one stage is announced at most once, so a service restart
 * cannot produce a duplicate.
 */
public final class AlarmWatcher extends BroadcastReceiver {

    private static final String ACTION_ALARM_SOON = "com.kreston8.islandcn.ALARM_SOON";
    private static final String ACTION_ALARM_DONE = "com.kreston8.islandcn.ALARM_DONE";
    private static final String EXTRA_TRIGGER = "trigger";

    /** The three heads-up points, counted back from the alarm time: ten, five and one minute. */
    private static final long[] STAGE_LEAD_MS = {
            10L * 60L * 1000L, 5L * 60L * 1000L, 1L * 60L * 1000L
    };
    /** One bit per stage above, in the same order: 1 = T-10, 2 = T-5, 4 = T-1. */
    private static final int[] STAGE_BIT = {1, 2, 4};
    /** All three stages said, which is also what {@link #silence} records. */
    private static final int STAGE_ALL = 7;
    /** Separator between the alarm time and the bitmask inside {@link Prefs#KEY_ALARM_STAGE}. */
    private static final char STAGE_SEPARATOR = '|';

    private static final long DONE_DELAY_MS = 60L * 1000L;
    /** Must match the key {@link IslandEvent#alarm(Context)} builds, so the card can be withdrawn. */
    private static final String KEY_PREFIX = "alarm:";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (ACTION_ALARM_SOON.equals(action)) {
            tick(context, intent.getLongExtra(EXTRA_TRIGGER, 0L));
            return;
        }
        if (ACTION_ALARM_DONE.equals(action)) {
            withdraw(context, intent.getLongExtra(EXTRA_TRIGGER, 0L));
            return;
        }
        schedule(context);
    }

    /** Arms (or cancels) the next heads-up for whatever alarm the clock has queued next. */
    public void schedule(Context context) {
        AlarmManager manager = alarmManager(context);
        if (manager == null) {
            return;
        }
        cancel(context, ACTION_ALARM_SOON, REQUEST_SOON);
        long trigger = triggerOf(manager);
        long shown = Prefs.alarmShown(context);
        if (shown != 0L && shown != trigger) {
            // Moved, switched off or deleted since it was announced, so the card is no longer true.
            withdraw(context, shown);
        }
        long now = System.currentTimeMillis();
        if (trigger <= now) {
            return;
        }
        if (stageTrigger(Prefs.getAlarmStage(context)) != trigger) {
            // A different alarm is queued now, so the record of the previous one is worthless.
            Prefs.setAlarmStage(context, stage(trigger, 0));
        }
        if ((dueMask(trigger, now) & ~stageMask(context, trigger)) != 0) {
            // The window is already open (start-up late in the window), so say it right away.
            tick(context, trigger);
            return;
        }
        armNext(context, manager, trigger, now);
    }

    /**
     * Says whichever heads-up is due and not yet said, then arms the one after it.
     *
     * <p>Several stages can be due at once when the device slept through a wake-up. Only the
     * newest of them produces a card and the older ones are marked as said, because three cards
     * arriving together would be noise rather than three warnings.
     */
    private void tick(Context context, long trigger) {
        if (trigger <= 0L) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= trigger) {
            // Past the alarm time the clock itself takes over; there is no fourth heads-up.
            return;
        }
        AlarmManager manager = alarmManager(context);
        if (manager == null) {
            return;
        }
        if (triggerOf(manager) != trigger) {
            // Changed between arming and firing, so this heads-up belongs to an alarm that is gone.
            return;
        }
        int said = stageMask(context, trigger);
        int due = dueMask(trigger, now);
        if ((due & ~said) == 0) {
            armNext(context, manager, trigger, now);
            return;
        }
        // Every stage that is due counts as said now, so a late wake cannot replay the backlog and
        // a stage skipped for a muted phone is never repeated once the filter is back to normal.
        Prefs.setAlarmStage(context, stage(trigger, said | due));
        if (!quiet(context)) {
            announce(context, manager, trigger);
        }
        armNext(context, manager, trigger, now);
    }

    /** Posts the card and arms the follow-up that withdraws it a minute past the alarm. */
    private static void announce(Context context, AlarmManager manager, long trigger) {
        IslandBridge.post(IslandEvent.alarm(context));
        Prefs.setAlarmShown(context, trigger);
        try {
            manager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, trigger + DONE_DELAY_MS,
                    pending(context, ACTION_ALARM_DONE, REQUEST_DONE, trigger));
        } catch (Throwable ignored) {
            // Without the follow-up the schedule() reconciliation still removes the card.
        }
    }

    /**
     * True while the phone is set to silent, vibrate or a starred-contacts-only filter.
     *
     * <p>The card is skipped rather than queued: the alarm is still going to ring, so a card that
     * turned up after the filter was lifted would only be noise. An unreadable policy counts as
     * normal, which is what the user asked for by leaving the alarm card switched on.
     */
    private static boolean quiet(Context context) {
        try {
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) {
                return false;
            }
            return manager.getCurrentInterruptionFilter()
                    != NotificationManager.INTERRUPTION_FILTER_ALL;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Arms the earliest stage that is still ahead and not yet said. */
    private static void armNext(Context context, AlarmManager manager, long trigger, long now) {
        int said = stageMask(context, trigger);
        for (int i = 0; i < STAGE_LEAD_MS.length; i++) {
            if ((said & STAGE_BIT[i]) != 0) {
                continue;
            }
            long at = trigger - STAGE_LEAD_MS[i];
            if (at <= now) {
                // Already due, so tick() has just said it; nothing is armed for this one.
                continue;
            }
            try {
                manager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, at,
                        pending(context, ACTION_ALARM_SOON, REQUEST_SOON, trigger));
            } catch (Throwable ignored) {
                // Inexact scheduling can be refused by aggressive power savers; nothing else to do.
            }
            return;
        }
    }

    /**
     * Drops the rest of the heads-up sequence for one alarm, so closing its card ends the run.
     *
     * <p>The remaining stages are written as said rather than only cancelled, so a later
     * {@link #schedule(Context)} cannot decide they are still owed. A key that belongs to another
     * alarm than the one on record leaves that record alone.
     */
    public static void silence(Context context, String eventKey) {
        if (context == null) {
            return;
        }
        long keyed = triggerOfKey(eventKey);
        String raw = Prefs.getAlarmStage(context);
        long stored = stageTrigger(raw);
        if (keyed != 0L && stored != 0L && keyed != stored) {
            // The record already belongs to the next alarm, which must keep its own stages.
            return;
        }
        long trigger = keyed != 0L ? keyed : stored;
        if (trigger == 0L) {
            return;
        }
        int said = trigger == stored ? stageMask(raw, trigger) : 0;
        Prefs.setAlarmStage(context, stage(trigger, said | STAGE_ALL));
        cancel(context, ACTION_ALARM_SOON, REQUEST_SOON);
    }

    /** Removes an alarm card that no longer matches reality and forgets that it was shown. */
    private static void withdraw(Context context, long trigger) {
        if (trigger == 0L) {
            return;
        }
        cancel(context, ACTION_ALARM_DONE, REQUEST_DONE);
        if (Prefs.alarmShown(context) == trigger) {
            Prefs.setAlarmShown(context, 0L);
        }
        IslandBridge.removed(KEY_PREFIX + trigger);
    }

    private static final int REQUEST_SOON = 0;
    private static final int REQUEST_DONE = 1;

    /** The stage record for one alarm: {@code "<triggerMillis>|<bitmask>"}. */
    private static String stage(long trigger, int mask) {
        return trigger + String.valueOf(STAGE_SEPARATOR) + (mask & STAGE_ALL);
    }

    /** The alarm time inside a stage record, or 0 when the record is absent or broken. */
    private static long stageTrigger(String raw) {
        if (raw == null) {
            return 0L;
        }
        int cut = raw.indexOf(STAGE_SEPARATOR);
        String head = cut < 0 ? raw : raw.substring(0, cut);
        try {
            return Long.parseLong(head.trim());
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * Which stages of this alarm the record says were already handled.
     *
     * <p>Zero for an absentee, broken or stale record, which is what makes a changed alarm start
     * over from T-10 without any extra bookkeeping.
     */
    private static int stageMask(Context context, long trigger) {
        return stageMask(Prefs.getAlarmStage(context), trigger);
    }

    private static int stageMask(String raw, long trigger) {
        if (raw == null || trigger == 0L) {
            return 0;
        }
        int cut = raw.indexOf(STAGE_SEPARATOR);
        if (cut < 0) {
            return 0;
        }
        try {
            if (Long.parseLong(raw.substring(0, cut).trim()) != trigger) {
                return 0;
            }
            return Integer.parseInt(raw.substring(cut + 1).trim()) & STAGE_ALL;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** The stages whose time has come by {@code now}. */
    private static int dueMask(long trigger, long now) {
        int due = 0;
        for (int i = 0; i < STAGE_LEAD_MS.length; i++) {
            if (now >= trigger - STAGE_LEAD_MS[i]) {
                due |= STAGE_BIT[i];
            }
        }
        return due;
    }

    /** The alarm time inside a card key, or 0 when the key is not an alarm key at all. */
    private static long triggerOfKey(String key) {
        if (key == null) {
            return 0L;
        }
        String text = key.trim();
        if (!text.startsWith(KEY_PREFIX)) {
            return 0L;
        }
        try {
            return Long.parseLong(text.substring(KEY_PREFIX.length()).trim());
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static AlarmManager alarmManager(Context context) {
        try {
            return (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        } catch (Throwable failure) {
            return null;
        }
    }

    private static long triggerOf(AlarmManager manager) {
        try {
            AlarmManager.AlarmClockInfo info = manager.getNextAlarmClock();
            return info == null ? 0L : info.getTriggerTime();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static Intent build(Context context, String action, long trigger) {
        return new Intent(context, AlarmWatcher.class)
                .setAction(action)
                .putExtra(EXTRA_TRIGGER, trigger);
    }

    private static PendingIntent pending(Context context, String action, int request, long trigger) {
        return PendingIntent.getBroadcast(context, request, build(context, action, trigger),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void cancel(Context context, String action, int request) {
        PendingIntent pending = PendingIntent.getBroadcast(context, request,
                build(context, action, 0L),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE);
        if (pending == null) {
            return;
        }
        AlarmManager manager = alarmManager(context);
        if (manager != null) {
            try {
                manager.cancel(pending);
            } catch (Throwable ignored) {
                // Nothing scheduled is the same outcome as a failed cancel.
            }
        }
        pending.cancel();
    }
}
