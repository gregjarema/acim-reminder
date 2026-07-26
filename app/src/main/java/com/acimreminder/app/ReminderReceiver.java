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

    /** Base notification id; each slot gets its own so they don't collide. */
    static final int NOTIF_BASE = 2000;

    /** Carries the posted notification's id to "Begin", so it can clear it. */
    static final String EXTRA_NOTIF_ID = "notif_id";

    /**
     * The id a slot's reminder is posted under. Slots are not always on the
     * hour — a lesson asking for practice every half hour has two a hour — so
     * the minute has to be part of the id, and anything wanting to cancel a
     * reminder must compute it the same way.
     */
    static int notifId(int hour, int minute) {
        return NOTIF_BASE + (hour >= 0 ? hour * 100 + minute : 0);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        int hour = intent.getIntExtra(Scheduler.EXTRA_HOUR, -1);
        int minute = intent.getIntExtra(Scheduler.EXTRA_MINUTE, 0);
        boolean sitting = intent.getBooleanExtra(Scheduler.EXTRA_SITTING, true);

        Notify.ensureChannels(ctx);
        postReminder(ctx, hour, minute, sitting);

        // Keep the cycle going. Re-arm the whole day rather than just this slot:
        // the lesson changes at midnight and the next one may want entirely
        // different times, so this is where the schedule follows it.
        Scheduler.scheduleAll(ctx);
    }

    private void postReminder(Context ctx, int hour, int minute, boolean sitting) {
        int notifId = notifId(hour, minute);
        Lesson lesson = Lessons.today(ctx);
        // The heading is the lesson's own idea; the verse to hold during
        // practice goes beneath it. Conflating the two put the practice text
        // where the lesson's title belongs.
        String headline = lesson.idea();
        String rest = lesson.meditationText();
        // A lesson with no separate verse falls back to its idea — don't print
        // the same line twice. Compare ignoring the line breaks a verse adds.
        if (rest.replace("\n", " ").equals(headline)) rest = "";

        // Tapping the body opens the app to the full lesson.
        Intent open = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int slot = hour * 100 + minute;
        PendingIntent openPi = PendingIntent.getActivity(
                ctx, 50000 + slot, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // "Begin" starts the practice directly, without opening the app.
        // Hand over the exact id rather than the hour: it is the one thing that
        // has to match what we posted, and deriving it twice is how the two
        // drifted apart when slots gained minutes.
        Intent begin = new Intent(ctx, BeginReceiver.class)
                .putExtra(EXTRA_NOTIF_ID, notifId);
        PendingIntent beginPi = PendingIntent.getBroadcast(
                ctx, 40000 + slot, begin,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // The lesson idea itself is the headline — no generic "Time to practice"
        // line. A two-line idea puts its second line in the body (and expands via
        // BigTextStyle); a one-line idea just wraps.
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, Notify.CH_REMINDERS)
                .setSmallIcon(R.drawable.ic_stat_bell)
                .setContentTitle(headline)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openPi);

        // Say which of the day's two tracks this is. A lesson asking for
        // fifteen minutes morning and evening plus hourly remembrances sends
        // both kinds, and they ask very different things of you.
        if (lesson.hasTimedPractice() && sitting) {
            b.setSubText(lesson.practiceMinutes + " min practice");
        } else if (lesson.hourlyRemembrance && !sitting) {
            b.setSubText("A moment's remembrance");
        }

        // "Begin" belongs on a sitting. On a passing remembrance, or a lesson
        // that asks for no sitting at all, offering it would invent a practice
        // the workbook didn't ask for.
        if (lesson.hasTimedPractice() && sitting) {
            b.addAction(R.drawable.ic_stat_bell,
                    "Begin " + lesson.practiceLabel(), beginPi);
        }
        if (!rest.isEmpty()) {
            b.setContentText(rest);
            b.setStyle(new NotificationCompat.BigTextStyle().bigText(rest));
        } else {
            b.setStyle(new NotificationCompat.BigTextStyle().bigText(headline));
        }

        try {
            NotificationManagerCompat.from(ctx).notify(notifId, b.build());
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS not granted yet; nothing we can do here.
        }
    }
}
