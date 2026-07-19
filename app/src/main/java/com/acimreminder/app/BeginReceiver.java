package com.acimreminder.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/**
 * Handles the "Begin" button on a reminder notification. Tapping a notification
 * action counts as a user interaction, so we are allowed to start the
 * foreground meditation service from here even while the app is in the background.
 */
public class BeginReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        int hour = intent.getIntExtra(Scheduler.EXTRA_HOUR, -1);

        // Clear the reminder notification that launched us.
        if (hour >= 0) {
            NotificationManagerCompat.from(ctx).cancel(ReminderReceiver.NOTIF_BASE + hour);
        }

        Intent start = new Intent(ctx, MeditationService.class)
                .setAction(MeditationService.ACTION_START);
        ContextCompat.startForegroundService(ctx, start);
    }
}
