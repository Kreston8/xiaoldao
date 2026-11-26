package com.kreston8.islandcn;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

/**
 * Watches the call state so a ringing phone becomes a card.
 *
 * <p>Android 12 removed the caller number from the callback third-party apps receive, so on
 * Android 12+ the card can only say that a call is coming in. Below that the number is included
 * when {@code READ_PHONE_STATE} is granted.
 */
final class PhoneWatcher {

    private final Context context;
    private TelephonyManager manager;
    private Object callbacks;
    private boolean modern;
    private boolean started;

    PhoneWatcher(Context context) {
        this.context = context;
    }

    void start() {
        if (started) {
            return;
        }
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        manager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                TelephonyCallback callback = new StateCallback();
                manager.registerTelephonyCallback(context.getMainExecutor(), callback);
                callbacks = callback;
                modern = true;
            } else {
                PhoneStateListener listener = new LegacyListener();
                manager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);
                callbacks = listener;
            }
            started = true;
        } catch (Throwable ignored) {
            // A missing SIM or a vendor policy can reject the registration; the island still works.
        }
    }

    void stop() {
        if (!started || manager == null) {
            return;
        }
        started = false;
        try {
            if (modern && callbacks instanceof TelephonyCallback) {
                manager.unregisterTelephonyCallback((TelephonyCallback) callbacks);
            } else if (callbacks instanceof PhoneStateListener) {
                manager.listen((PhoneStateListener) callbacks, PhoneStateListener.LISTEN_NONE);
            }
        } catch (Throwable ignored) {
            // Nothing to clean up.
        }
        callbacks = null;
    }

    private void post(int state, String number) {
        if (state == TelephonyManager.CALL_STATE_RINGING) {
            IslandBridge.post(IslandEvent.call(context, number, false));
        } else if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
            IslandBridge.post(IslandEvent.call(context, number, true));
        }
    }

    private final class StateCallback extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {

        @Override
        public void onCallStateChanged(int state) {
            // The number is no longer part of the callback on Android 12+.
            post(state, null);
        }
    }

    @SuppressWarnings("deprecation")
    private final class LegacyListener extends PhoneStateListener {

        @Override
        public void onCallStateChanged(int state, String phoneNumber) {
            post(state, phoneNumber);
        }
    }
}
