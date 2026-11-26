package com.kreston8.islandcn;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

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

    static void cancel(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) {
            return;
        }
        try {
            scheduler.cancel(JOB_ID);
        } catch (RuntimeException ignored) {
            // Nothing scheduled is the same outcome as a successful cancel.
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        // The user's intent, not the current process state, decides: a person who switched the
        // island off must not find it back after the next window.
        if (Prefs.running(this) && !IslandService.isRunning() && Perms.canOverlay(this)) {
            Context app = getApplicationContext();
            try {
                app.startForegroundService(new Intent(app, IslandService.class));
            } catch (RuntimeException ignored) {
                // Still refused: the next window tries again, and MainActivity.onResume restores it
                // the moment the app is opened, which needs no allow-list at all.
            }
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
