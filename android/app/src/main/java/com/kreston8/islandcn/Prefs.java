package com.kreston8.islandcn;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The per-feature switches shown in the setup screen.
 *
 * <p>Card switches default to on, so an existing install keeps behaving as before; the two additions
 * that change the desktop look (battery chips, standby pill) default to off and go through
 * {@link #opt}. Reads go through the framework's in-memory preference map after the first load,
 * which keeps the hot path (every incoming card) free of disk I/O.
 */
final class Prefs {

    private static final String FILE = "island";

    static final String KEY_GENERIC = "on_generic";
    static final String KEY_OTP = "on_otp";
    static final String KEY_PICKUP = "on_pickup";
    static final String KEY_DELIVERY = "on_delivery";
    static final String KEY_TODO = "on_todo";
    static final String KEY_CALL = "on_call";
    static final String KEY_ALARM = "on_alarm";
    static final String KEY_NAV = "on_nav";
    static final String KEY_MUSIC = "on_music";
    /** Not a card kind: the karaoke line itself, which is the only feature that opens a socket. */
    static final String KEY_LYRICS = "on_lyrics";
    static final String KEY_ALARM_SHOWN = "alarm_shown";
    /**
     * Which of the three alarm heads-ups were already said, written by {@link AlarmWatcher} as
     * {@code "<triggerMillis>|<bitmask>"}. Stored as text because the pair means nothing to this
     * class: only its writer parses it back.
     */
    public static final String KEY_ALARM_STAGE = "alarm_stage";
    /** Card keys the user closed by hand, newest last, one per line. */
    public static final String KEY_DISMISSED = "dismissed";

    /** Bluetooth battery chips: on by default, and each accessory has its own switch below it. */
    static final String KEY_BT = "on_bt";
    /** Desktop standby island and its three selectable items (items default to on). */
    static final String KEY_STANDBY = "on_standby";
    static final String KEY_STANDBY_WEATHER = "sb_weather";
    static final String KEY_STANDBY_PUPPY = "sb_puppy";
    static final String KEY_STANDBY_AQI = "sb_aqi";
    static final String KEY_STANDBY_QUOTE = "sb_quote";
    static final String KEY_STANDBY_BT = "sb_bt";
    static final String KEY_STANDBY_ORDER = "sb_order";
    /** City name typed by the user; the only reason the weather request is ever made. */
    static final String KEY_CITY = "weather_city";
    /** Look: the pill tint and its opacity, both stored as text so they need no typed reader. */
    static final String KEY_LOOK_COLOR = "look_color";
    static final String KEY_LOOK_ALPHA = "look_alpha";
    /**
     * Notification detail switches: one master ("only WeChat plus SMS pickup codes") and one switch
     * per source, so an OTP from a shopping app or a group-chat summary never reaches the island.
     * The master is off by default ({@link #opt}); the two sources are on by default ({@link #get}).
     */
    static final String KEY_NOTIFY_ONLY = "on_only";
    static final String KEY_NOTIFY_WECHAT = "on_wechat";
    static final String KEY_NOTIFY_PICKUP = "on_pickup";
    /** QQ: the third per-source switch, on by default like the other two. */
    static final String KEY_NOTIFY_QQ = "on_qq";
    /**
     * One switch per delivery platform, shown under the 外卖进度 master. All four start on, so the
     * master alone decides until a single platform proves noisy.
     */
    static final String KEY_DELIVERY_MEITUAN = "dl_meituan";
    static final String KEY_DELIVERY_SHANGOU = "dl_shangou";
    static final String KEY_DELIVERY_JD = "dl_jd";
    static final String KEY_DELIVERY_QIXIAN = "dl_qixian";
    /** Prefix for the per-accessory switch; the device address is appended to it. */
    static final String KEY_BT_DEVICE = "bt_ok:";
    /**
     * Whether the island was on the last time the process was alive.
     *
     * <p>Written by {@link IslandService}, read by {@link KeepAlive} after a reboot: a person who
     * switched the island off must not find it running again after every restart.
     */
    static final String KEY_RUNNING = "running";


    /** The order a fresh install starts with, and the source of every known item. */
    private static final String[] ORDER_DEFAULT = {"weather", "aqi", "puppy", "quote", "bt"};

    private Prefs() {
    }

    private static SharedPreferences store(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean get(Context context, String key) {
        try {
            return store(context).getBoolean(key, true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    static void set(Context context, String key, boolean value) {
        try {
            store(context).edit().putBoolean(key, value).apply();
        } catch (Throwable ignored) {
            // A read-only profile keeps the previous value; nothing to recover from.
        }
    }

    /**
     * True when the island was switched on and has not been switched off since.
     *
     * <p>Defaults to false ({@link #opt}) rather than true, so a freshly installed app does not start
     * itself after a reboot before the person ever opened it.
     */
    static boolean running(Context context) {
        return opt(context, KEY_RUNNING);
    }

    static void setRunning(Context context, boolean running) {
        set(context, KEY_RUNNING, running);
    }

    /** True when a card of this kind is allowed. */
    static boolean enabled(Context context, int kind) {
        switch (kind) {
            case IslandEvent.KIND_OTP:
                return get(context, KEY_OTP);
            case IslandEvent.KIND_PICKUP:
                return get(context, KEY_NOTIFY_PICKUP);
            case IslandEvent.KIND_DELIVERY:
                return get(context, KEY_DELIVERY);
            case IslandEvent.KIND_TODO:
                return get(context, KEY_TODO);
            case IslandEvent.KIND_CALL:
                return get(context, KEY_CALL);
            case IslandEvent.KIND_ALARM:
                return get(context, KEY_ALARM);
            case IslandEvent.KIND_NAV:
                return get(context, KEY_NAV);
            case IslandEvent.KIND_MUSIC:
                return get(context, KEY_MUSIC);
            default:
                return get(context, KEY_GENERIC);
        }
    }

    /**
     * The alarm time the island last announced.
     *
     * <p>Kept so one alarm produces one card even when the service restarts, and so the card can be
     * withdrawn the moment the system stops reporting that alarm.
     */
    static long alarmShown(Context context) {
        try {
            return store(context).getLong(KEY_ALARM_SHOWN, 0L);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    static void setAlarmShown(Context context, long trigger) {
        try {
            store(context).edit().putLong(KEY_ALARM_SHOWN, trigger).apply();
        } catch (Throwable ignored) {
            // A read-only profile keeps the previous value; the card is still withdrawn by hand.
        }
    }

    /** How many closed cards are remembered before the oldest one is forgotten. */
    private static final int DISMISSED_MAX = 40;

    /**
     * The heads-up stage record as written by {@link AlarmWatcher}.
     *
     * <p>Never null: a fresh install, a wiped preference store and a read-only profile all read
     * back {@code ""}, which the watcher treats as "nothing said yet".
     */
    public static String getAlarmStage(Context context) {
        try {
            String value = store(context).getString(KEY_ALARM_STAGE, "");
            return value == null ? "" : value;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Stores the record verbatim; null is stored as empty so a later read stays non-null. */
    public static void setAlarmStage(Context context, String value) {
        try {
            store(context).edit().putString(KEY_ALARM_STAGE, value == null ? "" : value).apply();
        } catch (Throwable ignored) {
            // A read-only profile keeps the old record; the watcher then repeats at most one stage.
        }
    }

    /**
     * The card keys the user closed by hand, oldest first.
     *
     * <p>Only this list is needed to keep a closed card from coming back: it holds at most
     * {@link #DISMISSED_MAX} entries, so it cannot grow without bound.
     */
    public static java.util.Set<String> dismissed(Context context) {
        java.util.Set<String> keys = new java.util.LinkedHashSet<String>();
        String raw = text(context, KEY_DISMISSED);
        if (raw.isEmpty()) {
            return keys;
        }
        for (String part : raw.split("\n")) {
            String key = part == null ? "" : part.trim();
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** True when this exact card was closed by the user, so it must not be shown again. */
    public static boolean isDismissed(Context context, String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        return dismissed(context).contains(key);
    }

    /** Remembers one closed card, keeping the newest {@link #DISMISSED_MAX} of them. */
    public static void addDismissed(Context context, String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        java.util.Set<String> keys = dismissed(context);
        keys.remove(key);
        keys.add(key);
        while (keys.size() > DISMISSED_MAX) {
            java.util.Iterator<String> oldest = keys.iterator();
            if (!oldest.hasNext()) {
                break;
            }
            oldest.next();
            oldest.remove();
        }
        StringBuilder raw = new StringBuilder();
        for (String item : keys) {
            if (raw.length() > 0) {
                raw.append('\n');
            }
            raw.append(item);
        }
        setText(context, KEY_DISMISSED, raw.toString());
    }

    /** Forgets every closed card, e.g. when the switches are reset. */
    public static void clearDismissed(Context context) {
        try {
            store(context).edit().remove(KEY_DISMISSED).apply();
        } catch (Throwable ignored) {
            // A read-only profile keeps the old list; nothing else depends on it being empty.
        }
    }

    /** The switch that owns a kind, for the settings screen. */
    static String keyOf(int kind) {
        switch (kind) {
            case IslandEvent.KIND_OTP:
                return KEY_OTP;
            case IslandEvent.KIND_PICKUP:
                return KEY_PICKUP;
            case IslandEvent.KIND_DELIVERY:
                return KEY_DELIVERY;
            case IslandEvent.KIND_TODO:
                return KEY_TODO;
            case IslandEvent.KIND_CALL:
                return KEY_CALL;
            case IslandEvent.KIND_ALARM:
                return KEY_ALARM;
            case IslandEvent.KIND_NAV:
                return KEY_NAV;
            case IslandEvent.KIND_MUSIC:
                return KEY_MUSIC;
            default:
                return KEY_GENERIC;
        }
    }
    /**
     * True only when the user turned this on.
     *
     * <p>Used for the two additions that change what the island looks like on the desktop (battery
     * chips, standby), so a fresh install keeps the old single-pill behaviour until asked.
     */
    static boolean opt(Context context, String key) {
        try {
            return store(context).getBoolean(key, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static String text(Context context, String key) {
        try {
            String value = store(context).getString(key, "");
            return value == null ? "" : value;
        } catch (Throwable ignored) {
            return "";
        }
    }

    static void setText(Context context, String key, String value) {
        try {
            store(context).edit().putString(key, value).apply();
        } catch (Throwable ignored) {
            // A read-only profile keeps the previous value; the standby order still renders.
        }
    }

    /**
     * The standby order as written by the settings screen, e.g. {@code "puppy,weather,quote"}.
     *
     * <p>Items that a later version added are appended rather than dropped, so an install that
     * predates the air-quality chip keeps its old order and gains the new item at the end.
     */
    static String[] order(Context context) {
        java.util.List<String> items = new java.util.ArrayList<String>();
        String raw = text(context, KEY_STANDBY_ORDER);
        if (!raw.isEmpty()) {
            for (String part : raw.split(",")) {
                String item = part == null ? "" : part.trim();
                if (!item.isEmpty() && !items.contains(item)) {
                    items.add(item);
                }
            }
        }
        for (String item : ORDER_DEFAULT) {
            if (!items.contains(item)) {
                items.add(item);
            }
        }
        return items.toArray(new String[0]);
    }
}
