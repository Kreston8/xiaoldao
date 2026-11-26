package com.kreston8.islandcn;

import android.Manifest;
import android.app.Notification;
import android.content.pm.PackageManager;
import android.provider.Telephony;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Feeds posted notifications to the island.
 *
 * <p>Only what a user would call "a new message" gets through: group summaries, ongoing progress
 * notifications and our own status notification are dropped so the island does not flash on every
 * background update.
 *
 * <p>Map apps are the exception. Turn-by-turn guidance is only published as an ongoing notification,
 * so the whitelisted packages below skip that filter and keep the navigation tag instead.
 *
 * <p>When {@link SmsReceiver} is enabled it already shows the code SMS, so the messaging app's own
 * notification is skipped; otherwise the same text would appear twice.
 */
public final class IslandListener extends NotificationListenerService {

    /** Packages whose ongoing notification carries the next turn. */
    private static final Set<String> NAV_PACKAGES = new HashSet<>(Arrays.asList(
            "com.autonavi.minimap",
            "com.autonavi.amapauto",
            "com.baidu.BaiduMap",
            "com.tencent.map",
            "com.google.android.apps.maps"));

    /** A single route refreshes the notification every second; keep the island calm. */
    private static final long NAV_GAP_MS = 4000L;

    private final Map<String, String> lastNavText = new HashMap<>();
    private final Map<String, Long> lastNavAt = new HashMap<>();

    @Override
    public void onListenerConnected() {
        IslandBridge.attachListener(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || getPackageName().equals(sbn.getPackageName())) {
            return;
        }
        Notification notification = sbn.getNotification();
        if (notification == null) {
            return;
        }
        int flags = notification.flags;
        if ((flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            return;
        }
        if (NAV_PACKAGES.contains(sbn.getPackageName())) {
            postNavigation(sbn, notification);
            return;
        }
        if ((flags & Notification.FLAG_ONGOING_EVENT) != 0) {
            return;
        }
        if (handledByReceiver(sbn)) {
            return;
        }
        IslandBridge.post(IslandEvent.from(this, sbn));
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        IslandBridge.removed(sbn.getKey());
    }

    /** Posts a navigation card unless the line is unchanged or the last one arrived a moment ago. */
    private void postNavigation(StatusBarNotification sbn, Notification notification) {
        CharSequence line = notification.extras == null
                ? null
                : notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (line == null || line.length() == 0) {
            return;
        }
        String pkg = sbn.getPackageName();
        String text = line.toString();
        long now = System.currentTimeMillis();
        Long at = lastNavAt.get(pkg);
        if (text.equals(lastNavText.get(pkg))) {
            return;
        }
        if (at != null && now - at < NAV_GAP_MS) {
            return;
        }
        lastNavText.put(pkg, text);
        lastNavAt.put(pkg, now);
        IslandBridge.post(IslandEvent.navigation(this, sbn));
    }

    private boolean handledByReceiver(StatusBarNotification sbn) {
        if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        try {
            String smsPackage = Telephony.Sms.getDefaultSmsPackage(this);
            return smsPackage != null && smsPackage.equals(sbn.getPackageName());
        } catch (Throwable ignored) {
            return false;
        }
    }
}
