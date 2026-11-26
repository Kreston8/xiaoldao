package com.kreston8.islandcn;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Fourth keep-alive layer, and the only one that can legally start the foreground service from the
 * background on Android 12 and later.
 *
 * <p>{@link KeepAlive} rings an {@link android.app.AlarmManager} alarm every fifteen minutes, but a
 * broadcast receiver is not on the temporary allow-list, so on Android 12+ the
 * {@code startForegroundService} it issues is refused with
 * {@code ForegroundServiceStartNotAllowedException} unless the user exempted the app from battery
 * optimisations. A job scheduled with {@link JobInfo.Builder#setPersisted(boolean)} runs inside a
 * {@link JobService}, which <em>is</em> allowed to start a foreground service, so the same watchdog
 * gets through on a stock ROM.
 *
 * <p>Limits worth writing down rather than hiding: the platform batches periodic jobs, so fifteen
 * minutes means "roughly", and neither this job nor any other mechanism survives a user-initiated
 * force-stop — the system cancels every job of a stopped package by design.
 */
public final class RestoreJob extends JobService {

    /** Stable id: re-scheduling replaces the pending job instead of stacking a second one. */
    private static final int JOB_ID = 72;

    /** The platform floor for periodic jobs. */
    private static final long PERIOD_MS = 15 * 60_000L;

    /** Id of the one-shot nudge, kept apart so it never replaces the periodic watchdog. */
    private static final int NOW_ID = 73;

    /** How soon the fast attempt runs after the process lost its task. */
    private static final long NOW_MS = 10_000L;

    /**
     * Idempotent; safe to call on every start. Silently does nothing if the platform refuses the
     * job, because a missing watchdog must never break the actual island.
     */
    static void schedule(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) {
            return;
        }
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, RestoreJob.class))
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build();
        try {
            scheduler.schedule(job);
        } catch (RuntimeException ignored) {
            // Some MIUI builds reject persisted jobs; KeepAlive still covers the alarm path.
        }
    }

    /**
     * One attempt about ten seconds from now, for the moment the settings screen is swiped away.
     *
     * <p>The periodic job above is the safety net that survives a reboot; this is the one that
     * matters in the common case, where the vendor killer takes the process right after the task
     * disappears. Ten seconds is late enough that the system has finished tearing the task down and
     * early enough that the person still sees the pill come back instead of an empty home screen.
     */
    static void scheduleNow(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) {
            return;
        }
        Diag.note(context, "排一次 10 秒 job");
        JobInfo job = new JobInfo.Builder(NOW_ID, new ComponentName(context, RestoreJob.class))
                .setMinimumLatency(NOW_MS)
                .setOverrideDeadline(NOW_MS + 5_000L)
                .setPersisted(true)
                .build();
        try {
            scheduler.schedule(job);
        } catch (RuntimeException ignored) {
            // Quota or a vendor block: the periodic job and the settings screen still cover it.
            Diag.note(context, "10 秒 job 被拒");
        }
    }

    static void cancel(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) {
            return;
        }
        try {
            scheduler.cancel(JOB_ID);
            scheduler.cancel(NOW_ID);
        } catch (RuntimeException ignored) {
            // Nothing scheduled is the same outcome as a successful cancel.
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        // The user's intent, not the current process state, decides: a person who switched the
        // island off must not find it back after the next window.
        boolean running = IslandService.isRunning();
        Diag.note(this, "job 触发（服务" + (running ? "在" : "不在") + "）");
        if (!running && Prefs.running(this) && Perms.canOverlay(this)) {
            // Shared with the alarm path so both leave the same kind of line in the log.
            KeepAlive.startService(this);
        }
        // Nothing runs asynchronously, so the job is finished as soon as this returns.
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // The system interrupted us before the service came up; ask for another window.
        return true;
    }
}
