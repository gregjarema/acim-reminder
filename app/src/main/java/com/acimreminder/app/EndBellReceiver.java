package com.acimreminder.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Fired by an exact alarm at the precise end-of-meditation minute. We schedule
 * this alarm explicitly (rather than trusting an in-app timer, which the OS can
 * pause during Doze) so the closing bell always rings on time — even with the
 * screen off.
 *
 * It rings the bell right here, by posting the "Practice complete" notification
 * whose channel sound the system plays. It deliberately does NOT start a
 * foreground service: starting one from the background with the screen locked is
 * exactly what some phones refuse or kill, which was silently dropping the end
 * bell. A receiver runs from the alarm even if the app process was reclaimed.
 */
public class EndBellReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        MeditationService.endNow(ctx);
    }
}
