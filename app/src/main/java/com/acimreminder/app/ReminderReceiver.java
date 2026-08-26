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

    /**
     * Every reminder posts under this one id, so a newer reminder simply
     * replaces the one already on screen — you only ever have a single practice
     * nudge, never a pile of stale ones stacking up through the day. This holds
     * across both of a day's tracks (a timed sitting and a passing remembrance)
     * and across a review day's frequent nudges alike.
     *
     * The meditation countdown is deliberately NOT this: it is a separate
     * notification with its own id and channel, so it sits alongside a reminder
     * rather than replacing it.
     */
    static final int NOTIF_REMINDER = 2000;

    /** Carries the posted notification's id to "Begin", so it can clear it. */
    static final String EXTRA_NOTIF_ID = "notif_id";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        int hour = intent.getIntExtra(Scheduler.EXTRA_HOUR, -1);
        int minute = intent.getIntExtra(Scheduler.EXTRA_MINUTE, 0);
        boolean sitting = intent.getBooleanExtra(Scheduler.EXTRA_SITTING, true);

        Notify.ensureChannels(ctx);
        // Hold off while a meditation is running: a remembrance nudge (or the
        // next sitting's) firing mid-sitting would interrupt the very quiet it
        // is calling you to. The slot is simply skipped, not rescheduled for
        // later — the next one comes round on its own.
        if (!MeditationService.isRunning(ctx)) {
            postReminder(ctx, hour, minute, sitting);
        }

        // Keep the cycle going. Re-arm the whole day rather than just this slot:
        // the lesson changes at midnight and the next one may want entirely
        // different times, so this is where the schedule follows it.
        Scheduler.scheduleAll(ctx);
    }

    private void postReminder(Context ctx, int hour, int minute, boolean sitting) {
        Lesson lesson = Lessons.today(ctx);
        // All reminders share one id, so each new nudge replaces the one already
        // showing — a single reminder on screen, never a stack.
        int notifId = NOTIF_REMINDER;
        String headline;
        String rest;
        if (lesson.isReview()) {
            // A review day alternates two thoughts: one on the hour, the other on
            // the half hour. The slot's minute says which this is. The single
            // thought is the whole message, so there's no subtext beneath it.
            headline = minute == 30 ? lesson.halfThought() : lesson.hourThought();
            rest = "";
        } else {
            // The heading is the lesson's own idea; the verse to hold beneath it
            // depends on which track this is. The workbook often gives two
            // separate verses — a fuller one to open the timed sitting with, a
            // shorter one to carry through the day's passing remembrances — so a
            // sitting reminder shows meditationText and an hourly one shows
            // remembranceText, rather than always conflating the two.
            headline = lesson.idea();
            rest = sitting ? lesson.meditationText() : lesson.remembranceText();
            // A lesson with no separate verse falls back to its idea — don't print
            // the same line twice. Compare ignoring the line breaks a verse adds.
            if (rest.replace("\n", " ").equals(headline)) rest = "";
        }

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
                .setTimeoutAfter(60_000L)
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
        } else if (headline.contains("\n")) {
            // A multi-line idea: collapsed shows the first line, expanding reveals
            // the rest — so the body adds something the title doesn't already show.
            b.setStyle(new NotificationCompat.BigTextStyle().bigText(headline));
        } else {
            // A single-line idea with no separate verse (a review day's one
            // thought). Show it once — as the title, wrapping to its full length
            // via the big-text template — with no body echoing the same sentence
            // beneath it, which is what printed it twice before.
            b.setStyle(new NotificationCompat.BigTextStyle().setBigContentTitle(headline));
        }

        try {
            NotificationManagerCompat.from(ctx).notify(notifId, b.build());
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS not granted yet; nothing we can do here.
        }
    }
}
