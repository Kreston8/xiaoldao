package com.kreston8.islandcn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

/**
 * Catches incoming SMS so verification codes never depend on the messaging app's notification.
 *
 * <p>Only messages the parser can classify reach the island; everything else is dropped, which
 * keeps ordinary texts off the screen.
 */
public final class SmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
            return;
        }
        SmsMessage[] parts;
        try {
            parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        } catch (Throwable ignored) {
            return;
        }
        if (parts == null || parts.length == 0) {
            return;
        }
        StringBuilder body = new StringBuilder();
        String sender = null;
        for (SmsMessage part : parts) {
            if (part == null) {
                continue;
            }
            if (sender == null) {
                sender = part.getDisplayOriginatingAddress();
            }
            String piece = part.getDisplayMessageBody();
            if (piece != null) {
                body.append(piece);
            }
        }
        IslandEvent event = IslandEvent.sms(context, sender, body.toString());
        if (event != null) {
            IslandBridge.post(event);
        }
    }
}
