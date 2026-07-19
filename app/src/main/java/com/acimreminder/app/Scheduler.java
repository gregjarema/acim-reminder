package com.acimreminder.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.Calendar;

/**
 * Schedules the day's practice reminders with AlarmManager.
 *
 * v1 schedule: one session every hour on the hour, 07:00–22:00 inclusive
 * (16 sessions). Each alarm is "exact + allowed while idle" so it fires
 * through Doze at the right minute.
 *
 * Reliability model:
 *   - Each hour is a separate alarm keyed by its hour (request code = hour).
 *   - When an alarm fires, ReminderReceiver immediately re-arms that same hour
 *     for tomorrow, so the schedule keeps rolling forever.
 *   - BootReceiver re-arms every hour after a reboot (alarms don't survive one).
 *   - MainActivity re-arms every hour each time you open the app (belt & braces).
 */
public final class Scheduler {

    private static final String TAG = "Scheduler";

    public static final int START_HOUR = 7;   // 07:00
    public static final int END_HOUR = 22;     // 22:00 inclusive

    static final String ACTION_REMIND = "com.acimreminder.app.REMIND";
    static final String EXTRA_HOUR = "hour";

    /** Arm (or re-arm) all 16 reminders. Safe to call repeatedly. */
    public static void scheduleAll(Context ctx) {
        for (int hour = START_HOUR; hour <= END_HOUR; hour++) {
            scheduleOne(ctx, hour);
        }
        Log.i(TAG, "Scheduled reminders " + START_HOUR + ":00–" + END_HOUR + ":00");
    }

    /** Arm the next occurrence of a single hour. */
    public static void scheduleOne(Context ctx, int hour) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;

        PendingIntent pi = reminderPendingIntent(ctx, hour);
        long triggerAt = nextTimeForHour(hour);

        try {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                // Should not happen: we hold USE_EXACT_ALARM. Fall back gracefully
                // to a non-exact idle alarm rather than crashing.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                Log.w(TAG, "Exact alarms not permitted; used inexact for hour " + hour);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            Log.w(TAG, "SecurityException scheduling exact alarm; used inexact", e);
        }
    }

    private static PendingIntent reminderPendingIntent(Context ctx, int hour) {
        Intent i = new Intent(ctx, ReminderReceiver.class)
                .setAction(ACTION_REMIND)
                .putExtra(EXTRA_HOUR, hour);
        return PendingIntent.getBroadcast(
                ctx, hour, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The next date/time at HH:00 that is still in the future. */
    private static long nextTimeForHour(int hour) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return c.getTimeInMillis();
    }

    private Scheduler() {}
}
