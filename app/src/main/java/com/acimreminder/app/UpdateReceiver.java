package com.acimreminder.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;

/**
 * The daily "is there a new build?" check.
 *
 * Inexact on purpose: unlike a practice reminder, nothing depends on this
 * landing at a particular minute, and an inexact alarm lets Android batch it
 * with other work rather than waking the phone on its own.
 */
public class UpdateReceiver extends BroadcastReceiver {

    private static final int REQUEST = 7001;
    private static final int CHECK_HOUR = 4;   // small hours, out of the way

    @Override
    public void onReceive(Context ctx, Intent intent) {
        // The network call and download must not run on the main thread; a
        // pending result keeps the receiver alive while they do.
        final PendingResult pending = goAsync();
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                Updater.checkAndFetch(app);
            } finally {
                schedule(app);      // re-arm regardless of the outcome
                pending.finish();
            }
        }).start();
    }

    /** Arm (or re-arm) tomorrow's check. Safe to call repeatedly. */
    public static void schedule(Context ctx) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;

        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, CHECK_HOUR);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        am.set(AlarmManager.RTC, c.getTimeInMillis(), pendingIntent(ctx));
    }

    private static PendingIntent pendingIntent(Context ctx) {
        return PendingIntent.getBroadcast(ctx, REQUEST,
                new Intent(ctx, UpdateReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
