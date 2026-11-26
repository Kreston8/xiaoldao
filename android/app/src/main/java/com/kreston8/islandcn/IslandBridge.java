package com.kreston8.islandcn;

import java.lang.ref.WeakReference;

/**
 * The wire between the notification listener and the overlay service.
 *
 * <p>Both components live in the same process, so a static bus is enough and avoids binding a
 * service just to pass a notification across. References are weak so a stopped service cannot be
 * held alive by the listener.
 */
final class IslandBridge {

    interface Sink {
        void onIslandEvent(IslandEvent event);

        void onIslandRemoved(String key);
    }

    private static WeakReference<Sink> sink = new WeakReference<Sink>(null);
    private static WeakReference<IslandListener> listener = new WeakReference<IslandListener>(null);

    private IslandBridge() {
    }

    static void attach(Sink target) {
        sink = new WeakReference<Sink>(target);
    }

    static void detach(Sink target) {
        if (sink.get() == target) {
            sink = new WeakReference<Sink>(null);
        }
    }

    static void attachListener(IslandListener target) {
        listener = new WeakReference<IslandListener>(target);
    }

    static void post(IslandEvent event) {
        if (event == null) {
            return;
        }
        Sink target = sink.get();
        if (target != null) {
            target.onIslandEvent(event);
        }
    }

    static void removed(String key) {
        Sink target = sink.get();
        if (target != null) {
            target.onIslandRemoved(key);
        }
    }

    static void cancel(String key) {
        IslandListener target = listener.get();
        if (target == null || key == null) {
            return;
        }
        try {
            target.cancelNotification(key);
        } catch (Throwable ignored) {
            // The notification is gone already, or the listener lost its access.
        }
    }
}
