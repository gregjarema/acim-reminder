package com.acimreminder.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

/**
 * Fired by an exact alarm at the precise end-of-meditation minute. We schedule
 * this alarm explicitly (rather than trusting an in-app timer, which the OS can
 * pause during Doze) so the closing bell always rings on time — even with the
 * screen off. It simply tells the meditation service to play the end bell.
 */
public class EndBellReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Intent end = new Intent(ctx, MeditationService.class)
                .setAction(MeditationService.ACTION_END);
        ContextCompat.startForegroundService(ctx, end);
    }
}
