package com.kreston8.islandcn;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.graphics.PixelFormat;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Owns the overlay window for the lifetime of the island.
 *
 * <p>A foreground service is required because the island has to stay on screen while other apps are
 * in front. The window is {@code TYPE_APPLICATION_OVERLAY}, so it draws above app windows but below
 * the status bar, which is why the pill is anchored just under the status bar instead of on top of
 * the clock.
 */
public final class IslandService extends Service implements IslandBridge.Sink {

    static final String ACTION_STOP = "com.kreston8.islandcn.STOP";

    /** Repaint request from the settings screen: the colour or the opacity changed. */
    static final String ACTION_LOOK = "com.kreston8.islandcn.LOOK";

    private static final String CHANNEL_ID = "island_status";
    private static final int NOTIFICATION_ID = 42;

    /** The notification card and the standby pill take turns for as long as both are available. */
    private static final long FLIP_MS = 3000L;
    /** How long one notification may interrupt a lyric line before the line comes back. */
    private static final long NOTIFY_HOLD_MS = 3000L;

    private static volatile boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private IslandView island;

    /** How long one alarm reminder holds the pill; the clock app does the ringing itself. */
    private static final long ALARM_HOLD_MS = 5000L;
    /** How long an upward swipe keeps the notification card in front of the lyric line. */
    private static final long HOLD_OPEN_MS = 60_000L;
    /** How long a long-press swipe silences everything, including the standby pill. */
    private static final long HIDE_MS = 180_000L;

    /** Moment the pill may come back after a long-press swipe hid it; 0 when it is not hidden. */
    private long hideUntil;

    /**
     * Takes an alarm card off the pill on its own.
     *
     * <p>A reminder is a five second look at the clock, not a card that stays: once it is over the
     * pill goes back to the standby line, or to the lyric line when a track is playing. It is a
     * method reference rather than a lambda because the fields it touches are declared further down
     * the file, and a lambda here would be an illegal forward reference.
     */
    private final Runnable alarmTask = this::clearAlarmCard;

    /** Brings the pill back when the three hidden minutes are over. */
    private final Runnable hideTask = this::unhide;

    /** Body of {@link #alarmTask}. */
    private void clearAlarmCard() {
        if (current != null && current.kind == IslandEvent.KIND_ALARM) {
            notifyCard = null;
            hideIsland();
        }
    }

    /** Body of {@link #hideTask}. */
    private void unhide() {
        hideUntil = 0L;
        applyPhase();
    }

    private PhoneWatcher phoneWatcher;
    private AlarmWatcher alarmWatcher;
    private boolean alarmRegistered;
    private MediaWatcher mediaWatcher;

    private BtWatcher btWatcher;
    private boolean btRegistered;
    private StandbyView standby;

    private static final long STANDBY_POLL_MS = 4000L;

    private final Runnable standbyTask = new Runnable() {
        @Override
        public void run() {
            updateStandby();
            handler.postDelayed(this, STANDBY_POLL_MS);
        }
    };

    /**
     * Ticks the 3 s rotation between the notification card and the standby pill, and hands the pill
     * back to the lyric line once a notification interruption has had its turn.
     */
    private final Runnable flipTask = new Runnable() {
        @Override
        public void run() {
            boolean expired = notifyHoldUntil > 0L
                    && notifyHoldUntil <= SystemClock.elapsedRealtime();
            if (expired) {
                notifyHoldUntil = 0L;
            }
            boolean rotating = notifyCard != null && musicCard == null && standbyHas
                    && Prefs.opt(IslandService.this, Prefs.KEY_STANDBY);
            if (rotating) {
                notifyShowing = !notifyShowing;
            } else if (notifyCard != null) {
                notifyShowing = true;
            }
            if (expired || rotating) {
                applyPhase();
            }
            handler.postDelayed(this, FLIP_MS);
        }
    };

    private IslandEvent current;
    private String currentKey;
    /** Latest non-music card: it takes turns with the standby pill. */
    private IslandEvent notifyCard;
    /** Latest music card: it owns the pill until the track stops. */
    private IslandEvent musicCard;
    private long musicPos;
    private long musicAt;
    /** True while the notification card holds the pill, which drives the rotation. */
    private boolean notifyShowing;
    /** Moment the lyric line may take the pill back after a notification cut in. */
    private long notifyHoldUntil;
    private boolean standbyHas;
    private boolean standbyHome;

    /** Every music card key starts with this, which is how a stopped track is recognised. */
    private static final String MUSIC_KEY = "music:";
    /** WeChat's package name, the one source the notification filter can name. */
    private static final String WECHAT = "com.tencent.mm";
    private static final String QQ = "com.tencent.mobileqq";

    static boolean isRunning() {
        return running;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        IslandBridge.attach(this);
    }

    /**
     * The settings screen was swiped out of the recent tasks.
     *
     * <p>Nothing is torn down here on purpose. The island lives in a foreground service with
     * {@code stopWithTask="false"}; only the activity is gone, so dropping the windows here would
     * turn "close the app" into "the island is dead until the app is opened again". The watchdog
     * alarm is re-armed as a second line of defence for the vendor killer.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (running) {
            KeepAlive.start(this);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // A look change repaints both windows on the spot: the standby pill would otherwise keep the
        // old colour until its next four-second tick.
        if (intent != null && ACTION_LOOK.equals(intent.getAction())) {
            if (island != null) {
                island.refreshLook();
            }
            if (standby != null) {
                standby.refreshLook();
            }
            if (running) {
                return START_STICKY;
            }
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Prefs.setRunning(this, false);
            KeepAlive.stop(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        startInForeground();
        running = true;
        // Remembered for the reboot path and for the watchdog: only a deliberate stop clears it.
        Prefs.setRunning(this, true);
        KeepAlive.start(this);
        if (!Perms.canOverlay(this)) {
            Toast.makeText(this, R.string.need_overlay, Toast.LENGTH_LONG).show();
            // No overlay permission means no island, so a watchdog restart would loop forever.
            Prefs.setRunning(this, false);
            KeepAlive.stop(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        attachOverlay();
        startWatchers();

        startStandby();
        return START_STICKY;
    }

    @Override

    public void onIslandEvent(IslandEvent event) {
        if (event == null) {
            return;
        }
        if (!allow(event)) {
            return;
        }
        if (!Prefs.enabled(this, event.kind)) {
            return;
        }
        if (dropFor(event)) {
            return;
        }
        event.bt(chipsFor(event.kind));
        if (island == null) {
            attachOverlay();
        }
        if (island == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (event.kind == IslandEvent.KIND_MUSIC) {
            // A playing track holds the pill; only its clock is remembered for a later resume.
            musicCard = event;
            musicPos = event.position;
            musicAt = now;
            notifyHoldUntil = 0L;
            notifyShowing = false;
            applyPhase();
            return;
        }
        notifyCard = event;
        notifyShowing = true;
        if (musicCard != null) {
            notifyHoldUntil = now + NOTIFY_HOLD_MS;
        }
        applyPhase();
        handler.removeCallbacks(alarmTask);
        if (event.kind == IslandEvent.KIND_ALARM) {
            // Three reminders of five seconds each; the clock app rings at T, not us.
            handler.postDelayed(alarmTask, ALARM_HOLD_MS);
        }
    }

    @Override
    public void onIslandRemoved(String key) {
        if (key == null) {
            return;
        }
        if (key.startsWith(MUSIC_KEY)) {
            musicCard = null;
            notifyHoldUntil = 0L;
        }
        if (notifyCard != null && key.equals(notifyCard.key)) {
            notifyCard = null;
        }
        if (key.equals(currentKey)) {
            current = null;
            currentKey = null;
            if (island != null) {
                island.stopTicker();
                island.setVisibility(View.INVISIBLE);
            }
        }
        notifyShowing = notifyCard != null;
        applyPhase();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(alarmTask);
        handler.removeCallbacks(hideTask);
        handler.removeCallbacks(flipTask);
        IslandBridge.detach(this);
        running = false;
        if (island != null && windowManager != null) {
            try {
                windowManager.removeView(island);
            } catch (Throwable ignored) {
                // The window is already gone with the process.
            }
        }
        stopWatchers();

        stopStandby();
        if (standby != null && windowManager != null) {
            try {
                windowManager.removeView(standby);
            } catch (Throwable ignored) {
                // The window is already gone with the process.
            }
        }
        standby = null;
        island = null;
        super.onDestroy();
    }

    private void attachOverlay() {
        if (island != null || windowManager == null) {
            return;
        }
        IslandView view = new IslandView(this);
        view.setActions(new IslandView.Actions() {
            @Override
            public void onOpen() {
                openCurrent();
            }

            @Override
            public void onDismiss() {
                dismissCurrent();
            }

            @Override
            public void onCopy(String value) {
                copyCode(value);
            }

            @Override
            public void onNext() {
                nextCard();
            }

            @Override
            public void onHideFor(long millis) {
                hideFor(millis);
            }
        });

        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.width = WindowManager.LayoutParams.WRAP_CONTENT;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        params.format = PixelFormat.TRANSLUCENT;
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = Math.max(0, statusBarHeight() - dp(2));
        params.setTitle(getString(R.string.app_name));

        try {
            windowManager.addView(view, params);
        } catch (Throwable failure) {
            Toast.makeText(this, R.string.need_overlay, Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }
        view.setVisibility(View.INVISIBLE);
        island = view;
    }

    /**
     * Starts the passive watchers: the call state, the next clock alarm, and the playing track.
     *
     * <p>All three are optional. Without {@code READ_PHONE_STATE} the phone half stays dormant, a
     * missing alarm clock provider is treated the same way, and the music half simply never sees a
     * session.
     */
    private void startWatchers() {
        if (phoneWatcher == null) {
            phoneWatcher = new PhoneWatcher(this);
        }
        phoneWatcher.start();

        if (alarmWatcher == null) {
            alarmWatcher = new AlarmWatcher();
        }
        if (!alarmRegistered) {
            try {
                IntentFilter filter = new IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(alarmWatcher, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(alarmWatcher, filter);
                }
                alarmRegistered = true;
            } catch (Throwable ignored) {
                // Some ROMs reject the registration; the island keeps working without alarm cards.
            }
        }
        try {
            alarmWatcher.schedule(this);
        } catch (Throwable ignored) {
            // Nothing scheduled, or the clock service is unavailable.
        }

        // The receiver is passive, so it stays registered even while the chips are switched off.
        startBt();
        // The music watcher is the only part that polls, so it is skipped entirely when off.
        if (!Prefs.enabled(this, IslandEvent.KIND_MUSIC)) {
            return;
        }
        if (mediaWatcher == null) {
            mediaWatcher = new MediaWatcher(this);
        }
        try {
            mediaWatcher.start();
        } catch (Throwable ignored) {
            // Without notification access there are no media sessions to read.
        }
    }

    private void stopWatchers() {
        if (phoneWatcher != null) {
            phoneWatcher.stop();
            phoneWatcher = null;
        }
        if (alarmRegistered && alarmWatcher != null) {
            try {
                unregisterReceiver(alarmWatcher);
            } catch (Throwable ignored) {
                // Already unregistered with the process.
            }
            alarmRegistered = false;
        }
        alarmWatcher = null;

        if (btRegistered && btWatcher != null) {
            btWatcher.stop(this);
            btRegistered = false;
        }
        btWatcher = null;

        if (mediaWatcher != null) {
            mediaWatcher.stop();
            mediaWatcher = null;
        }
    }

    private boolean attachStandby() {
        if (standby != null) {
            return true;
        }
        if (windowManager == null) {
            return false;
        }
        StandbyView view = new StandbyView(this);
        // Long press, then an upward swipe on the pill itself: the same gesture the card has.
        view.setHideAction(() -> hideFor(HIDE_MS));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.width = WindowManager.LayoutParams.WRAP_CONTENT;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        params.format = PixelFormat.TRANSLUCENT;
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = Math.max(0, statusBarHeight() - dp(2));
        params.setTitle(getString(R.string.standby_order_title));
        try {
            windowManager.addView(view, params);
        } catch (Throwable failure) {
            return false;
        }
        standby = view;
        return true;
    }

    /**
     * Refreshes the standby pill and tells the phase machine whether it has anything to show.
     *
     * <p>The check runs on a timer instead of watching for changes because "the launcher is in
     * front" is only knowable from usage events, which have to be polled anyway.
     */
    private void updateStandby() {
        boolean on = Prefs.opt(this, Prefs.KEY_STANDBY);
        boolean home = on && Perms.hasUsageAccess(this) && StandbyView.homeInFront(this);
        boolean has = false;
        if (home && attachStandby()) {
            try {
                has = standby.refresh();
            } catch (Throwable ignored) {
                has = standby.hasContent();
            }
        }
        standbyHome = home;
        standbyHas = has;
        applyPhase();
    }

    private void startStandby() {
        handler.removeCallbacks(standbyTask);
        handler.post(standbyTask);
        handler.removeCallbacks(flipTask);
        handler.postDelayed(flipTask, FLIP_MS);
    }

    private void stopStandby() {
        handler.removeCallbacks(standbyTask);
        handler.removeCallbacks(flipTask);
        if (standby != null) {
            standby.pause();
        }
    }

    /**
     * Battery chips for the card about to be drawn.
     *
     * <p>The switch normally gates them, but a lyric line always carries them: seeing the headset
     * level without hunting for a setting is the point of the feature.
     */
    private java.util.List<IslandEvent.Bt> chipsFor(int kind) {
        if (kind != IslandEvent.KIND_MUSIC && !Prefs.get(this, Prefs.KEY_BT)) {
            return null;
        }
        if (btWatcher == null) {
            startBt();
        }
        if (btWatcher == null) {
            return null;
        }
        try {
            return btWatcher.chips(this);
        } catch (Throwable failure) {
            return null;
        }
    }

    /**
     * Registers the battery receiver.
     *
     * <p>It only listens: the accessory pushes levels on its own, so this costs nothing while the
     * chips stay hidden.
     */
    private void startBt() {
        if (btRegistered) {
            return;
        }
        if (btWatcher == null) {
            btWatcher = new BtWatcher();
            // A level read over GATT arrives later, possibly on another thread; redraw there.
            btWatcher.levelListener(this::onBtLevels);
        }
        try {
            btWatcher.start(this);
            btRegistered = true;
        } catch (Throwable ignored) {
            // Without BLUETOOTH_CONNECT there is nothing to read, so no chips ever appear.
        }
    }
    /**
     * The notification whitelist.
     *
     * <p>v2.10 dropped the old master switch, because the settings screen now lists exactly the
     * features that were asked for: anything not on that list is not shown at all. Call, alarm and
     * music come from watchers rather than from notifications; todo, pickup codes, WeChat, QQ and
     * delivery each obey their own switch, and delivery narrows further to one switch per platform.
     */
    private boolean allow(IslandEvent event) {
        switch (event.kind) {
            case IslandEvent.KIND_CALL:
            case IslandEvent.KIND_ALARM:
            case IslandEvent.KIND_MUSIC:
                return true;
            case IslandEvent.KIND_TODO:
                return Prefs.get(this, Prefs.KEY_TODO);
            case IslandEvent.KIND_PICKUP:
                return Prefs.get(this, Prefs.KEY_NOTIFY_PICKUP);
            case IslandEvent.KIND_DELIVERY:
                return Delivery.allowed(this, event);
            default:
                break;
        }
        if (WECHAT.equals(event.pkg)) {
            return Prefs.get(this, Prefs.KEY_NOTIFY_WECHAT);
        }
        if (QQ.equals(event.pkg)) {
            return Prefs.get(this, Prefs.KEY_NOTIFY_QQ);
        }
        // Plain notifications, OTP and navigation are off by design: they are not on the list.
        return false;
    }

    /**
     * A level the Bluetooth watcher just read, possibly from its own thread; the chips on the card
     * are redrawn in place instead of waiting for the next notification.
     */
    private void onBtLevels() {
        handler.post(() -> {
            IslandEvent event = current;
            if (event == null) {
                return;
            }
            event.bt(chipsFor(event.kind));
            if (island != null) {
                island.setChips(event.bt);
            }
        });
    }

    /**
     * Whether a card should never reach the pill at all.
     *
     * <p>Dropping means dropping: nothing is held back and nothing is replayed when the mode ends or
     * when the desktop comes back. A card the user swiped away is also in here, which is what makes
     * "closed for good" survive a restart. Music is never gated: a track and its lyric line are not
     * a notification, and the standby line has its own switches.
     */
    private boolean dropFor(IslandEvent event) {
        String key = event.key;
        if (key != null && !key.startsWith(MUSIC_KEY) && Prefs.isDismissed(this, key)) {
            return true;
        }
        if (event.kind == IslandEvent.KIND_MUSIC) {
            return false;
        }
        if (hideUntil > SystemClock.elapsedRealtime()) {
            return true;
        }
        if (isClockApp(event.pkg)) {
            // The clock app's own bedtime notices say what the alarm reminder already said.
            return true;
        }
        if (dndOn()) {
            return true;
        }
        return Perms.hasUsageAccess(this) && !StandbyView.homeInFront(this);
    }

    /** True while the system is in do-not-disturb, priority-only or silent. */
    private boolean dndOn() {
        try {
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            return manager != null && manager.getCurrentInterruptionFilter()
                    != NotificationManager.INTERRUPTION_FILTER_ALL;
        } catch (Throwable failure) {
            return false;
        }
    }

    /** The clock packages whose own notices stay off the island. */
    private static boolean isClockApp(String pkg) {
        return pkg != null && (pkg.equals("com.android.deskclock")
                || pkg.equals("com.miui.deskclock")
                || pkg.contains("deskclock"));
    }

    /**
     * Upward swipe without a long press: look at whatever else is taking turns.
     *
     * <p>With a track playing this hands the pill between the lyric line and the newest
     * notification; otherwise it hands it between the notification and the standby line.
     */
    private void nextCard() {
        if (island == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (musicCard != null) {
            if (notifyHoldUntil <= now && notifyCard != null) {
                notifyHoldUntil = now + HOLD_OPEN_MS;
            } else {
                notifyHoldUntil = 0L;
            }
            applyPhase();
            return;
        }
        if (notifyCard != null && standbyHas && Prefs.opt(this, Prefs.KEY_STANDBY)) {
            notifyShowing = !notifyShowing;
            applyPhase();
        }
    }

    /** Long press, then upward swipe: the pill disappears for a while and comes back by itself. */
    private void hideFor(long millis) {
        long span = Math.max(1000L, millis);
        hideUntil = SystemClock.elapsedRealtime() + span;
        handler.removeCallbacks(hideTask);
        handler.postDelayed(hideTask, span);
        hideIsland();
        applyStandbyVisibility();
        // Without this the gesture looks like a miss: the pill is simply gone and nothing said why.
        Toast.makeText(this, R.string.hidden_temporarily, Toast.LENGTH_SHORT).show();
    }

    private void openCurrent() {
        if (current != null && current.kind == IslandEvent.KIND_ALARM) {
            // No public API opens one alarm; the clock app's own list is the closest thing.
            try {
                Intent alarms = new Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS);
                alarms.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(alarms);
                return;
            } catch (Throwable ignored) {
                // No clock app answered; fall through to the app itself.
            }
        }
        IslandEvent smsTarget = current;
        if (smsTarget != null && smsTarget.smsAddress != null) {
            // Android has no public way to open one message; the conversation that holds it is the
            // closest thing, and the code is the newest message in it.
            try {
                Intent sms = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("sms:" + Uri.encode(smsTarget.smsAddress)));
                sms.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(sms);
                return;
            } catch (Throwable ignored) {
                // No messaging app claimed the address; fall through to the app itself.
            }
        }
        IslandEvent event = current;
        if (event != null && event.contentIntent != null) {
            try {
                event.contentIntent.send();
                return;
            } catch (Throwable ignored) {
                // The notification went stale; fall back to the app itself.
            }
        }
        // No content intent (SMS, or a notification without a tap target): open the app itself.
        if (event != null && event.pkg != null) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(event.pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(launch);
                    return;
                } catch (Throwable ignored) {
                    // Fall through to our own screen.
                }
            }
        }
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(open);
        } catch (Throwable ignored) {
            // Nothing sensible left to do.
        }
    }

    private void copyCode(String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText(getString(R.string.tag_otp), value));
            }
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // The clipboard can be unavailable in restricted profiles.
        }
    }

    /**
     * Left swipe: this card is closed for good.
     *
     * <p>Its key goes into the dismissed list, so the same notification can never come back — not
     * even after the service restarts. A track is the exception: only the card on screen is dropped,
     * because the next song should still show. An alarm also tells its watcher to skip the remaining
     * stages of that one alarm.
     */
    private void dismissCurrent() {
        IslandEvent event = current;
        if (event != null) {
            if (event.kind == IslandEvent.KIND_MUSIC) {
                musicCard = null;
            } else {
                if (event.key != null) {
                    Prefs.addDismissed(this, event.key);
                }
                notifyCard = null;
            }
            if (event.kind == IslandEvent.KIND_ALARM && event.key != null) {
                AlarmWatcher.silence(this, event.key);
            }
        }
        IslandBridge.cancel(currentKey);
        // The standby pill is left alone, and the track only comes back when it updates again.
        hideIsland();
    }

    /** Removes every card from the pill, then lets the phase machine pick what is left. */
    private void hideIsland() {
        notifyCard = null;
        notifyHoldUntil = 0L;
        notifyShowing = false;
        current = null;
        currentKey = null;
        if (island != null) {
            island.stopTicker();
            island.setVisibility(View.INVISIBLE);
        }
        applyPhase();
    }

    /**
     * Puts one card on the pill.
     *
     * <p>A card already on screen is left alone: re-binding it would restart the expand animation
     * and the karaoke line for nothing.
     */
    private void show(IslandEvent event) {
        if (island == null) {
            return;
        }
        if (current == event && island.getVisibility() == View.VISIBLE) {
            return;
        }
        if (event == musicCard) {
            // The track kept playing while the card was away, so move its clock forward.
            event.position = musicPos + (SystemClock.elapsedRealtime() - musicAt);
        }
        current = event;
        currentKey = event.key;
        if (island.getVisibility() != View.VISIBLE) {
            island.setVisibility(View.VISIBLE);
        }
        island.bind(event);
    }

    /**
     * Decides which window is on screen.
     *
     * <p>A playing track owns the pill: it yields only for one short notification interruption
     * ({@link #NOTIFY_HOLD_MS}) and then takes the lyric line back where it should be by now.
     * Without music the notification card and the standby pill take turns every {@link #FLIP_MS},
     * and once the notification is closed by hand only the standby pill is left.
     */
    private void applyPhase() {
        IslandEvent target = null;
        boolean rotate = false;
        if (musicCard != null) {
            target = notifyHoldUntil > SystemClock.elapsedRealtime() && notifyCard != null
                    ? notifyCard
                    : musicCard;
        } else if (notifyCard != null) {
            rotate = standbyHas && Prefs.opt(this, Prefs.KEY_STANDBY);
            if (!rotate || notifyShowing) {
                target = notifyCard;
            }
        }
        if (target == null) {
            if (island != null) {
                island.stopTicker();
                island.setVisibility(View.INVISIBLE);
            }
        } else {
            show(target);
        }
        applyStandbyVisibility();
    }

    /**
     * The standby pill gives way to every card, but it is the only thing left once nothing else is
     * showing, which is what makes the 3 s rotation visible.
     */
    private void applyStandbyVisibility() {
        boolean show = Prefs.opt(this, Prefs.KEY_STANDBY) && standbyHas && standbyHome
                && hideUntil <= SystemClock.elapsedRealtime()
                && musicCard == null && (notifyCard == null || !notifyShowing);
        if (!show) {
            if (standby != null) {
                standby.pause();
                standby.setVisibility(View.INVISIBLE);
            }
            return;
        }
        if (!attachStandby()) {
            return;
        }
        standby.setVisibility(standby.hasContent() ? View.VISIBLE : View.INVISIBLE);
        standby.resume();
    }

    private void startInForeground() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_status),
                    NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, IslandService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(
                this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_status)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.running_hint))
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(
                        (Icon) null, getString(R.string.stop_island), stopIntent).build());

        Notification notification = builder.build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            return getResources().getDimensionPixelSize(id);
        }
        return dp(28);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
