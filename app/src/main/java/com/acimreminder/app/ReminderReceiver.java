package com.acimreminder.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * Fires at each scheduled time. It posts the "Time to practice" notification
 * and immediately re-arms the same hour for tomorrow so the schedule rolls on.
 */
public class ReminderReceiver extends BroadcastReceiver {

    /** Base notification id; each hour gets BASE + hour so they don't collide. */
    static final int NOTIF_BASE = 2000;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        int hour = intent.getIntExtra(Scheduler.EXTRA_HOUR, -1);

        Notify.ensureChannels(ctx);
        postReminder(ctx, hour);

        // Keep the daily cycle going: re-arm this hour for tomorrow.
        if (hour >= 0) {
            Scheduler.scheduleOne(ctx, hour);
        }
    }

    private void postReminder(Context ctx, int hour) {
        int notifId = NOTIF_BASE + (hour >= 0 ? hour : 0);

        // Tapping the body opens the app to the full lesson.
        Intent open = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(
                ctx, 5000 + hour, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // "Begin" starts the meditation directly, without opening the app.
        Intent begin = new Intent(ctx, BeginReceiver.class)
                .putExtra(Scheduler.EXTRA_HOUR, hour);
        PendingIntent beginPi = PendingIntent.getBroadcast(
                ctx, 4000 + hour, begin,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, Notify.CH_REMINDERS)
                .setSmallIcon(R.drawable.ic_stat_bell)
                .setContentTitle("Time to practice")
                .setContentText(Lesson.PHRASE)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(Lesson.PHRASE))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .addAction(R.drawable.ic_stat_bell, "Begin", beginPi);

        try {
            NotificationManagerCompat.from(ctx).notify(notifId, b.build());
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS not granted yet; nothing we can do here.
        }
    }
}
