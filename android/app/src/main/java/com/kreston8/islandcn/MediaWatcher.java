package com.kreston8.islandcn;

import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.pm.ResolveInfo;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Shows a now-playing strip while music plays and the desktop is what is on screen.
 *
 * <p>The strip belongs to the launcher. Over any app it would sit on top of content the user is
 * actually reading, so the rule is simply "hide unless the desktop is in front".
 *
 * <p>Cost discipline, because this runs for as long as the service does:
 * <ul>
 *   <li>Track changes arrive through {@link MediaController.Callback}; nothing is polled for them.
 *   <li>The only polling is "is the desktop in front", one cheap query every 2.5 s, or every second
 *       while a timed lyric is on the card so the karaoke line cannot drift, and only while a
 *       session is actually playing. It stops on pause, on screen off, and when the usage-access
 *       permission is missing.
 *   <li>One card is posted per track, not per second. A lookup that comes back empty is retried at
 *       most every 15 s, so one dropped request cannot silence a whole song.
 * </ul>
 */
final class MediaWatcher {

    private static final long POLL_MS = 2500L;
    /**
     * Tighter cadence while the card carries a timed lyric: the strip counts the position up on its
     * own clock, so every millisecond of poll interval is a millisecond the karaoke line can drift
     * against the player and jump a word or a line.
     */
    private static final long LYRIC_POLL_MS = 1000L;
    /** How long an empty lyric answer is final: the lookup is retried once per window. */
    private static final long LYRIC_RETRY_MS = 15_000L;
    /** Small window: we only need the most recent foreground change. */
    /** Small window: we only need the most recent foreground change. */
    private static final long WINDOW_MS = 8000L;
    /** The first look after start needs history, because a resting desktop emits no events at all. */
    private static final long PRIME_MS = 120_000L;
    /** How far the player's own clock may drift before the strip is re-anchored. */
    private static final long DRIFT_MS = 400L;
    /** A playback-state stamp older than this is not a usable clock. */
    private static final long MAX_EXTRAPOLATE_MS = 120_000L;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ComponentName component;
    private final UsageEvents.Event event = new UsageEvents.Event();

    private MediaSessionManager sessions;
    private MediaController controller;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;
    private MediaController.Callback controllerCallback;

    private android.content.BroadcastReceiver screenReceiver;
    private boolean registered;
    private boolean polling;
    private boolean shown;
    private boolean screenOn = true;
    private String shownKey;
    private String lastTrack;
    private String lyricKey;
    private Lyric lyric;
    /** True once a lookup for the current key has run and come back without a usable lyric. */
    private boolean lyricAsked;
    /** When that lookup was issued, so an empty answer is retried on a slow, bounded cadence. */
    private long lyricTriedAt;
    /** How many answers in a row carried nothing, which stretches the retry window. */
    private int lyricMisses;
    private Set<String> homePackages;
    private String lastForeground;
    private long anchorPos;
    private long anchorAt;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            polling = false;
            refresh();
        }
    };

    MediaWatcher(Context context) {
        this.context = context.getApplicationContext();
        this.component = new ComponentName(context, IslandListener.class);
    }

    void start() {
        if (registered) {
            return;
        }
        try {
            sessions = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        } catch (Throwable failure) {
            sessions = null;
        }
        if (sessions == null) {
            return;
        }
        sessionsListener = this::onSessionsChanged;
        controllerCallback = new MediaController.Callback() {
            @Override
            public void onMetadataChanged(MediaMetadata metadata) {
                refresh();
            }

            @Override
            public void onPlaybackStateChanged(PlaybackState state) {
                refresh();
            }

            @Override
            public void onSessionDestroyed() {
                bind(null);
            }
        };
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, component);
            registered = true;
        } catch (SecurityException denied) {
            // Notification access was revoked; media cards stay off until it comes back.
            return;
        } catch (Throwable ignored) {
            return;
        }
        registerScreenReceiver();
        onSessionsChanged(readActiveSessions());
    }

    void stop() {
        handler.removeCallbacks(poll);
        polling = false;
        hide();
        LyricSource.idle();
        if (registered && sessions != null) {
            try {
                sessions.removeOnActiveSessionsChangedListener(sessionsListener);
            } catch (Throwable ignored) {
                // Already detached.
            }
        }
        registered = false;
        if (screenReceiver != null) {
            try {
                context.unregisterReceiver(screenReceiver);
            } catch (Throwable ignored) {
                // Not registered.
            }
            screenReceiver = null;
        }
        bind(null);
        sessions = null;
    }

    /** Drops the card and forgets the track, e.g. when the user switches the feature off. */
    void reset() {
        hide();
        lastTrack = null;
        forgetLyric();
        refresh();
    }

    private List<MediaController> readActiveSessions() {
        try {
            return sessions == null ? null : sessions.getActiveSessions(component);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void onSessionsChanged(List<MediaController> list) {
        MediaController pick = null;
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                MediaController candidate = list.get(i);
                if (candidate == null) {
                    continue;
                }
                if (isPlaying(candidate.getPlaybackState())) {
                    pick = candidate;
                    break;
                }
                if (pick == null) {
                    pick = candidate;
                }
            }
        }
        bind(pick);
    }

    private void bind(MediaController next) {
        if (controller == next) {
            refresh();
            return;
        }
        if (controller != null) {
            try {
                controller.unregisterCallback(controllerCallback);
            } catch (Throwable ignored) {
                // The session died first.
            }
        }
        controller = next;
        if (controller == null) {
            hide();
            return;
        }
        try {
            controller.registerCallback(controllerCallback, handler);
        } catch (Throwable ignored) {
            // A vendor controller can refuse callbacks; the poll below still covers it.
        }
        refresh();
    }

    /** One decision point: post, keep, or hide. */
    private void refresh() {
        MediaController active = controller;
        boolean playing = active != null && isPlaying(active.getPlaybackState());
        if (!playing || !Prefs.enabled(context, IslandEvent.KIND_MUSIC)) {
            hide();
            return;
        }
        String pkg = active.getPackageName();
        if (pkg == null) {
            hide();
            return;
        }
        if (hasUsageAccess() && !isHomeForeground()) {
            // Desktop only: over any app the strip would just cover what the user is reading.
            hide();
            schedulePoll();
            return;
        }
        MediaMetadata metadata = active.getMetadata();
        if (metadata == null) {
            hide();
            return;
        }
        CharSequence title = metadata.getText(MediaMetadata.METADATA_KEY_TITLE);
        CharSequence artist = metadata.getText(MediaMetadata.METADATA_KEY_ARTIST);
        String track = (title == null ? "" : title.toString()) + '|' + (artist == null ? "" : artist.toString());
        if (shown && track.equals(lastTrack)) {
            // The strip stays up; this only checks that its karaoke clock still matches the player.
            resync(active);
            if (lyric == null) {
                // Nothing usable yet: lyricFor() retries on its own cadence, and the answer then
                // forces the re-post that starts drawing the lines.
                String nowSong = title == null ? "" : title.toString();
                String nowSinger = artist == null ? "" : artist.toString();
                lyricFor(LyricSource.keyOf(nowSong, nowSinger), nowSong, nowSinger);
            }
            schedulePoll();
            return;
        }
        Bitmap art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) {
            art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        }
        IslandEvent card = IslandEvent.music(
                context, pkg, title, artist, art, active.getSessionActivity());
        if (card == null) {
            hide();
            return;
        }
        String song = title == null ? "" : title.toString();
        String singer = artist == null ? "" : artist.toString();
        long position = positionOf(active);
        card.lyric(lyricFor(LyricSource.keyOf(song, singer), song, singer), position);
        anchorPos = position;
        anchorAt = SystemClock.elapsedRealtime();
        shown = true;
        shownKey = card.key;
        lastTrack = track;
        IslandBridge.post(card);
        schedulePoll();
    }

    private void hide() {
        if (shown && shownKey != null) {
            IslandBridge.removed(shownKey);
        }
        shown = false;
        shownKey = null;
        lastTrack = null;
        anchorAt = 0L;
        // The lyric and its retry bookkeeping are kept on purpose: both are keyed by track, so an
        // answer that arrives while the card is hidden (another app in front, a notification cut in)
        // still lands here and is drawn the moment the card comes back, instead of being looked up -
        // and lost - again. They are forgotten by forgetLyric(), that is when the track changes or
        // the feature is switched off.
    }

    private void schedulePoll() {
        if (polling || !screenOn) {
            return;
        }
        if (!hasUsageAccess()) {
            // Without usage access the foreground check always says "not on screen", so there is
            // nothing to watch for and polling would only burn cycles.
            return;
        }
        polling = true;
        handler.postDelayed(poll, lyric != null && !lyric.isEmpty() ? LYRIC_POLL_MS : POLL_MS);
    }

    /**
     * Lyric for this track, starting the lookup when it is not known yet.
     *
     * <p>Only one request runs at a time, so skipping through a playlist cannot pile up
     * connections, and an answer for a track that is no longer playing is dropped. An empty answer
     * is retried, but only once per {@link #LYRIC_RETRY_MS}, so one dropped request cannot leave the
     * card without lines for the rest of the song.
     */
    private Lyric lyricFor(String key, String title, String artist) {
        if (!key.equals(lyricKey)) {
            forgetLyric();
            lyricKey = key;
            requestLyric(title, artist);
        } else if (lyric == null) {
            // An answer that carried nothing is not final: retry it, backing off so a song with no
            // subtitles anywhere does not keep asking every fifteen seconds on mobile data.
            long now = SystemClock.elapsedRealtime();
            if (!lyricAsked || now - lyricTriedAt >= retryDelay()) {
                requestLyric(title, artist);
            }
        }
        return lyric;
    }

    /** 15 s, then 30 s, then a minute between attempts; a real answer clears the count. */
    private long retryDelay() {
        if (lyricMisses <= 1) {
            return LYRIC_RETRY_MS;
        }
        return lyricMisses == 2 ? LYRIC_RETRY_MS * 2L : LYRIC_RETRY_MS * 4L;
    }

    /** Starts one lookup, unless the user turned timed lyrics off. */
    private void requestLyric(String title, String artist) {
        if (!Prefs.get(context, Prefs.KEY_LYRICS)) {
            return;
        }
        lyricAsked = true;
        lyricTriedAt = SystemClock.elapsedRealtime();
        lyricMisses++;
        LyricSource.get(title, artist, this::onLyric);
    }

    /** Forgets the remembered lyric, so the next card asks for its track again. */
    private void forgetLyric() {
        lyricKey = null;
        lyric = null;
        lyricAsked = false;
        lyricTriedAt = 0L;
        lyricMisses = 0;
    }

    private void onLyric(String key, Lyric found) {
        if (!key.equals(lyricKey)) {
            // The track changed while the request was in flight; the new one has its own lookup.
            return;
        }
        // Stored even while the card is hidden: the key still matches this track, and an answer
        // thrown away here is exactly what left the card without lines until the next track, as
        // nothing else would ever ask for them again.
        boolean usable = plausible(found);
        lyric = usable ? found : null;
        if (usable) {
            // A real answer resets the backoff, so the next song starts from a quick retry again.
            lyricMisses = 0;
        }
        // An empty answer only buys a pause; lyricFor() retries it on its own slow cadence.
        lyricAsked = true;
        lyricTriedAt = SystemClock.elapsedRealtime();
        if (usable && shown) {
            // Force exactly one re-post; the card key is unchanged so nothing re-expands.
            lastTrack = null;
            refresh();
        }
    }

    /**
     * Where the player is right now, in ms.
     *
     * <p>The position is only extrapolated when the state carries a plausible update stamp: several
     * players leave it at 0, and adding the whole uptime to that would put the lyric minutes off.
     */
    private long positionOf(MediaController active) {
        PlaybackState state = active.getPlaybackState();
        if (state == null) {
            return 0L;
        }
        long position = Math.max(0L, state.getPosition());
        long stamp = state.getLastPositionUpdateTime();
        long now = SystemClock.elapsedRealtime();
        if (state.getState() == PlaybackState.STATE_PLAYING
                && stamp > 0L && stamp <= now && now - stamp < MAX_EXTRAPOLATE_MS) {
            position += now - stamp;
        }
        return position;
    }

    /**
     * Re-anchors the karaoke line when the player's own clock no longer matches what the strip has
     * been counting up, which is what makes a lazy player drift several seconds behind.
     */
    private void resync(MediaController active) {
        if (anchorAt == 0L) {
            return;
        }
        PlaybackState state = active.getPlaybackState();
        long stamp = state.getLastPositionUpdateTime();
        if (stamp <= 0L || SystemClock.elapsedRealtime() - stamp >= MAX_EXTRAPOLATE_MS) {
            // No trustworthy clock to compare against, so the raw position stays authoritative and a
            // comparison here would only re-post the same stale number on every poll.
            return;
        }
        long expected = anchorPos + (SystemClock.elapsedRealtime() - anchorAt);
        if (Math.abs(positionOf(active) - expected) > DRIFT_MS) {
            // One re-post with the fresh position; the card key is unchanged so it does not reopen.
            lastTrack = null;
            refresh();
        }
    }

    /** Length of the current track in ms, or 0 when the player does not publish it. */
    private long trackDuration() {
        MediaController active = controller;
        MediaMetadata metadata = active == null ? null : active.getMetadata();
        return metadata == null ? 0L : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
    }

    /**
     * A lyric whose total length is wildly off the track's came from another recording (a live take,
     * a cover, a remix), and would stay out of step for the whole song.
     */
    private boolean plausible(Lyric candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        long track = trackDuration();
        long lyricEnd = candidate.duration();
        if (track <= 0L || lyricEnd <= 0L) {
            return true;
        }
        return Math.abs(lyricEnd - track) <= Math.max(15_000L, track / 8L);
    }

    private boolean hasUsageAccess() {
        try {
            AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) {
                return false;
            }
            int mode = ops.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * True when the launcher, that is the desktop, is the app in front.
     *
     * <p>{@code UsageEvents} only report switches. A window without events means "nothing changed",
     * not "nothing is in front", so the last known foreground is cached: the first call primes it
     * with a wide window and later calls only move it forward.
     */
    private boolean isHomeForeground() {
        if (!hasUsageAccess()) {
            return false;
        }
        UsageStatsManager usage;
        try {
            usage = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        } catch (Throwable failure) {
            usage = null;
        }
        if (usage == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        String latest = null;
        try {
            long since = lastForeground == null ? PRIME_MS : WINDOW_MS;
            UsageEvents events = usage.queryEvents(now - since, now);
            if (events != null) {
                while (events.hasNextEvent()) {
                    events.getNextEvent(event);
                    int type = event.getEventType();
                    if (type == UsageEvents.Event.MOVE_TO_FOREGROUND
                            || type == UsageEvents.Event.ACTIVITY_RESUMED) {
                        String name = event.getPackageName();
                        if (name != null) {
                            latest = name;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // Keep whatever was cached before.
        }
        if (latest != null) {
            lastForeground = latest;
        }
        if (lastForeground == null) {
            // Nothing to go on at all, and hiding forever is worse than showing over an app.
            return true;
        }
        return homePackages().contains(lastForeground);
    }

    /** Every package that can act as HOME; MIUI images usually resolve more than one. */
    private Set<String> homePackages() {
        if (homePackages != null) {
            return homePackages;
        }
        homePackages = new HashSet<>();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> handlers = context.getPackageManager().queryIntentActivities(home, 0);
            for (ResolveInfo info : handlers) {
                if (info.activityInfo != null) {
                    homePackages.add(info.activityInfo.packageName);
                }
            }
        } catch (Throwable ignored) {
            // Nothing resolves: nothing counts as the desktop, so the strip stays hidden.
        }
        return homePackages;
    }

    private void registerScreenReceiver() {
        if (screenReceiver != null) {
            return;
        }
        screenReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                String action = intent == null ? null : intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    screenOn = false;
                    handler.removeCallbacks(poll);
                    polling = false;
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    screenOn = true;
                    refresh();
                }
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(screenReceiver, filter);
            }
        } catch (Throwable ignored) {
            screenReceiver = null;
        }
    }

    private static boolean isPlaying(PlaybackState state) {
        return state != null && state.getState() == PlaybackState.STATE_PLAYING;
    }
}
