package com.kreston8.islandcn;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Telephony;
import android.service.notification.StatusBarNotification;
import android.text.format.DateUtils;

/** One notification, reduced to what the island can draw. */
final class IslandEvent {

    static final int KIND_GENERIC = 0;
    static final int KIND_OTP = 1;
    static final int KIND_PICKUP = 2;
    static final int KIND_DELIVERY = 3;
    static final int KIND_TODO = 4;
    static final int KIND_CALL = 5;
    static final int KIND_ALARM = 6;
    static final int KIND_NAV = 7;
    static final int KIND_MUSIC = 8;

    private static final int ICON_PX = 96;

    final String key;
    final String pkg;
    final String appLabel;
    final Bitmap icon;
    final CharSequence title;
    final CharSequence text;
    final PendingIntent contentIntent;
    final long postTime;
    final int kind;
    /** Text the copy button puts on the clipboard, or null when there is nothing to copy. */
    final String copyText;
    /** When the delivery is expected, or 0 when the card has no countdown. */
    final long etaMillis;
    /** Extra lines the card cycles through, e.g. title then artist. Null means "use text". */
    CharSequence cycle[];

    IslandEvent cycle(CharSequence... lines) {
        this.cycle = lines;
        return this;
    }

    /** Timed lyric lines of the current track, or null when none could be found. */
    Lyric lyric;
    /** Playback position, in ms, at the moment this event was built. */
    long position;
    /** Sender address of an SMS card, so a tap can open that conversation; null for other cards. */
    String smsAddress;

    IslandEvent lyric(Lyric value, long positionMs) {
        this.lyric = value;
        this.position = positionMs;
        return this;
    }

    /** One connected Bluetooth device and the battery level it last reported. */
    static final class Bt {
        /** Device name, or a generic label when the name could not be read. */
        final String name;
        /** 0..100, or -1 when the device has never reported a level. */
        final int level;
        /** One of {@code ic_bt_watch}, {@code ic_bt_headset}, {@code ic_bt_generic}. */
        final int icon;

        Bt(String name, int level, int icon) {
            this.name = name;
            this.level = level;
            this.icon = icon;
        }
    }

    /** Battery chips for the right end of the pill; null or empty means none. */
    java.util.List<Bt> bt;

    IslandEvent bt(java.util.List<Bt> chips) {
        this.bt = chips;
        return this;
    }

    private IslandEvent(
            String key,
            String pkg,
            String appLabel,
            Bitmap icon,
            CharSequence title,
            CharSequence text,
            PendingIntent contentIntent,
            long postTime,
            int kind,
            String copyText,
            long etaMillis) {
        this.key = key;
        this.pkg = pkg;
        this.appLabel = appLabel;
        this.icon = icon;
        this.title = title;
        this.text = text;
        this.contentIntent = contentIntent;
        this.postTime = postTime;
        this.kind = kind;
        this.copyText = copyText;
        this.etaMillis = etaMillis;
    }

    static IslandEvent from(Context context, StatusBarNotification sbn) {
        return build(context, sbn, false);
    }

    /**
     * A navigation prompt.
     *
     * <p>Map apps only publish their turn-by-turn line through an ongoing notification, which the
     * listener normally drops, so the whitelisted ones come through this path and keep the
     * navigation tag even when the wording happens to look like a delivery notice.
     */
    static IslandEvent navigation(Context context, StatusBarNotification sbn) {
        return build(context, sbn, true);
    }

    private static IslandEvent build(Context context, StatusBarNotification sbn, boolean nav) {
        Notification notification = sbn.getNotification();
        if (notification == null) {
            return null;
        }
        CharSequence title = null;
        CharSequence text = null;
        Bundle extras = notification.extras;
        if (extras != null) {
            title = extras.getCharSequence(Notification.EXTRA_TITLE);
            text = extras.getCharSequence(Notification.EXTRA_TEXT);
            if (isEmpty(text)) {
                text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            }
            if (isEmpty(text)) {
                text = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
            }
        }
        if (isEmpty(title) && isEmpty(text)) {
            return null;
        }
        String pkg = sbn.getPackageName();
        String label = labelOf(context, pkg);
        ContentParser.Result parsed = ContentParser.classify(
                pkg,
                label,
                title == null ? null : title.toString(),
                text == null ? null : text.toString());
        int kind = nav ? KIND_NAV : parsed.kind;
        // A nav line never carries a copyable code and has no countdown of its own.
        String copy = nav ? null : parsed.code;
        long eta = nav ? 0L : parsed.etaMillis;
        return new IslandEvent(
                sbn.getKey(),
                pkg,
                label,
                iconOf(context, notification, pkg),
                title,
                text,
                notification.contentIntent,
                sbn.getPostTime(),
                kind,
                copy,
                eta);
    }

    /**
     * A call in progress or ringing.
     *
     * <p>{@code number} is null when the platform withholds it, which is always the case on
     * Android 12 and newer: the call-state callback no longer includes the caller id.
     */
    static IslandEvent call(Context context, String number, boolean active) {
        String known = number == null ? "" : number.trim();
        String shown = known.isEmpty() ? context.getString(R.string.call_unknown) : known;
        Intent dial = new Intent(Intent.ACTION_DIAL);
        if (!known.isEmpty()) {
            dial.setData(Uri.parse("tel:" + Uri.encode(known)));
        }
        PendingIntent open = null;
        try {
            open = PendingIntent.getActivity(
                    context, 2, dial, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } catch (Throwable ignored) {
            // Without a pending intent the tap falls back to opening the phone app by package.
        }
        return new IslandEvent(
                "call:" + shown + ":" + active,
                null,
                context.getString(R.string.tag_call),
                bundledIcon(context, R.drawable.ic_phone),
                shown,
                context.getString(active ? R.string.call_active : R.string.call_incoming),
                open,
                System.currentTimeMillis(),
                KIND_CALL,
                null,
                0L);
    }

    /** The next clock alarm; null when the clock has nothing scheduled. */
    static IslandEvent alarm(Context context) {
        AlarmManager manager;
        try {
            manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        } catch (Throwable failure) {
            return null;
        }
        if (manager == null) {
            return null;
        }
        long trigger;
        try {
            AlarmManager.AlarmClockInfo info = manager.getNextAlarmClock();
            trigger = info == null ? 0L : info.getTriggerTime();
        } catch (Throwable failure) {
            // Vendor ROMs without the alarm clock provider throw here.
            return null;
        }
        long now = System.currentTimeMillis();
        if (trigger <= now) {
            return null;
        }
        String clock = DateUtils.formatDateTime(
                context, trigger, DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_ALL);
        return new IslandEvent(
                "alarm:" + trigger,
                context.getPackageName(),
                context.getString(R.string.tag_alarm),
                bundledIcon(context, R.drawable.ic_alarm),
                context.getString(R.string.alarm_title, clock),
                context.getString(R.string.alarm_text_soon),
                null,
                now,
                KIND_ALARM,
                null,
                trigger);
    }

    /**
     * The track playing while the desktop is in front.
     *
     * <p>The strip shows only the cover and one line. {@link #lyric} carries the timed lines when the
     * lyric lookup found them, so the view can light up the characters as they are sung; without it
     * the line falls back to the song name. Album art is downscaled to {@link #ICON_PX} because a
     * full-size cover (often 1024x1024, 4 MB) would otherwise stay in memory the whole time.
     */
    static IslandEvent music(
            Context context,
            String pkg,
            CharSequence title,
            CharSequence artist,
            Bitmap art,
            PendingIntent open) {
        if (pkg == null) {
            return null;
        }
        CharSequence name = isEmpty(title) ? context.getString(R.string.music_unknown) : title;
        CharSequence singer = isEmpty(artist) ? null : artist;
        Bitmap icon = scaleIcon(art);
        if (icon == null) {
            try {
                icon = toBitmap(context.getPackageManager().getApplicationIcon(pkg), ICON_PX);
            } catch (Throwable ignored) {
                // A missing icon is cosmetic only; the label still identifies the player.
                icon = null;
            }
        }
        IslandEvent event = new IslandEvent(
                "music:" + pkg,
                pkg,
                labelOf(context, pkg),
                icon,
                name,
                singer == null ? context.getString(R.string.tag_music) : singer,
                open,
                System.currentTimeMillis(),
                KIND_MUSIC,
                null,
                0L);
        return singer == null ? event.cycle(name) : event.cycle(name, singer);
    }

    private static Bitmap bundledIcon(Context context, int resId) {
        try {
            return toBitmap(context.getDrawable(resId), ICON_PX);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * An SMS the receiver classified as a pickup code or a verification code; null for the rest.
     *
     * <p>A pickup message reads "菜鸟驿站：1-2-3456" on the island: the shop name is the heading and
     * the code is the body. The sender address is kept on {@link #smsAddress} so that tapping the
     * card opens that conversation instead of only dismissing the card.
     */
    static IslandEvent sms(Context context, String sender, String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        ContentParser.Result parsed = ContentParser.classify(null, null, sender, body);
        if (parsed.kind != KIND_PICKUP && parsed.kind != KIND_OTP) {
            return null;
        }
        String smsPackage = null;
        try {
            smsPackage = Telephony.Sms.getDefaultSmsPackage(context);
        } catch (Throwable ignored) {
            // No default messaging app on some ROMs.
        }
        Bitmap icon = null;
        if (smsPackage != null) {
            try {
                icon = toBitmap(context.getPackageManager().getApplicationIcon(smsPackage), ICON_PX);
            } catch (Throwable ignored) {
                // Fall back to the bundled bubble below.
            }
        }
        if (icon == null) {
            icon = toBitmap(context.getDrawable(R.drawable.ic_message), ICON_PX);
        }
        String fallback = sender == null || sender.isEmpty() ? context.getString(R.string.sms_label) : sender;
        String heading = fallback;
        CharSequence text = body;
        if (parsed.kind == KIND_PICKUP && parsed.code != null) {
            // "菜鸟驿站：1-2-3456": the place leads, the code follows on the same line.
            heading = parsed.station != null ? parsed.station : fallback;
            text = parsed.code;
        }
        IslandEvent event = new IslandEvent(
                "sms:" + System.currentTimeMillis(),
                smsPackage,
                fallback,
                icon,
                heading,
                text,
                null,
                System.currentTimeMillis(),
                parsed.kind,
                parsed.code,
                parsed.etaMillis);
        event.smsAddress = sender;
        return event;
    }

    /** A fake event so the user can see the animation before a real notification arrives. */
    static IslandEvent preview(Context context) {
        Bitmap icon = null;
        try {
            icon = toBitmap(context.getPackageManager().getApplicationIcon(context.getPackageName()), ICON_PX);
        } catch (Throwable ignored) {
            // A missing icon is cosmetic only.
        }
        String code = "483920";
        return new IslandEvent(
                null,
                context.getPackageName(),
                labelOf(context, context.getPackageName()),
                icon,
                context.getString(R.string.preview_title),
                context.getString(R.string.preview_text),
                null,
                System.currentTimeMillis(),
                KIND_OTP,
                code,
                0L);
    }

    private static String labelOf(Context context, String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable ignored) {
            return pkg;
        }
    }

    /**
     * The picture a card shows, chosen the way a user reads a notification.
     *
     * <p>First the image the posting app attached, which is the contact's photo for WeChat, QQ and
     * anything else that talks to people; then the avatar inside a messaging-style notification; then
     * the app's own launcher icon in colour; and only then the small icon, which is monochrome on
     * modern Android but at least identifies the app.
     */
    private static Bitmap iconOf(Context context, Notification notification, String pkg) {
        Bitmap large = toBitmap(notification.getLargeIcon() == null
                ? null
                : notification.getLargeIcon().loadDrawable(context), ICON_PX);
        if (large != null) {
            return large;
        }
        Bitmap person = personIcon(context, notification);
        if (person != null) {
            return person;
        }
        try {
            Bitmap app = toBitmap(context.getPackageManager().getApplicationIcon(pkg), ICON_PX);
            if (app != null) {
                return app;
            }
        } catch (Throwable ignored) {
            // A package without a readable icon falls through to the small one.
        }
        return toBitmap(notification.getSmallIcon() == null
                ? null
                : notification.getSmallIcon().loadDrawable(context), ICON_PX);
    }

    /** The sender avatar from a messaging-style notification, or null when there is none. */
    private static Bitmap personIcon(Context context, Notification notification) {
        try {
            Bundle extras = notification.extras;
            if (android.os.Build.VERSION.SDK_INT < 28) {
                // Person only exists from Android 9, and nothing older can put one in the bundle.
                return null;
            }
            if (extras == null) {
                return null;
            }
            java.util.ArrayList<android.app.Person> people =
                    extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST);
            if (people == null) {
                return null;
            }
            for (android.app.Person person : people) {
                if (person == null) {
                    continue;
                }
                String uri = person.getUri();
                if (uri == null || uri.length() == 0) {
                    continue;
                }
                java.io.InputStream in = context.getContentResolver()
                        .openInputStream(Uri.parse(uri));
                if (in == null) {
                    continue;
                }
                try {
                    Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(in);
                    if (bitmap != null) {
                        return scaleIcon(bitmap);
                    }
                } finally {
                    in.close();
                }
            }
        } catch (Throwable ignored) {
            // Content URIs from another process can be revoked; the app icon is the fallback.
        }
        return null;
    }

    private static Bitmap toBitmap(Drawable drawable, int size) {
        if (drawable == null || size <= 0) {
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, size, size);
        drawable.draw(canvas);
        return bitmap;
    }

    /** Shrinks album art to icon size; the full-resolution cover is never kept. */
    private static Bitmap scaleIcon(Bitmap source) {
        if (source == null || source.isRecycled()) {
            return null;
        }
        try {
            if (source.getWidth() <= ICON_PX && source.getHeight() <= ICON_PX) {
                return source;
            }
            return Bitmap.createScaledBitmap(source, ICON_PX, ICON_PX, true);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isEmpty(CharSequence value) {
        return value == null || value.length() == 0;
    }
}
