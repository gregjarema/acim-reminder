package com.acimreminder.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    public static final int START_HOUR = 6;   // 06:00
    public static final int END_HOUR = 22;     // 22:00 inclusive

    static final String ACTION_REMIND = "com.acimreminder.app.REMIND";
    static final String EXTRA_HOUR = "hour";
    static final String EXTRA_MINUTE = "minute";

    /**
     * Arm (or re-arm) today's reminders. Safe to call repeatedly.
     *
     * The times come from the lesson itself, because the workbook prescribes
     * them and they change constantly: "every hour on the hour" is one regime,
     * "twice an hour" another, and much of Part II asks only for morning and
     * evening. Slots the lesson doesn't ask for are cancelled, so a two-a-day
     * lesson doesn't leave yesterday's 17 alarms firing.
     */
    public static void scheduleAll(Context ctx) {
        List<int[]> slots = slotsFor(Lessons.today(ctx));
        Set<Integer> wanted = new HashSet<>();
        for (int[] hm : slots) {
            wanted.add(slotId(hm[0], hm[1]));
            scheduleSlot(ctx, hm[0], hm[1]);
        }
        // Clear anything previously armed that today doesn't want.
        for (int hour = START_HOUR; hour <= END_HOUR; hour++) {
            for (int minute : ALL_MINUTES) {
                int id = slotId(hour, minute);
                if (!wanted.contains(id)) cancelSlot(ctx, hour, minute);
            }
        }
        Log.i(TAG, "Scheduled " + slots.size() + " reminders for lesson "
                + Lessons.today(ctx).number);
    }

    /** Every minute-of-hour we ever schedule at, so we know what to cancel. */
    private static final int[] ALL_MINUTES = {0, 15, 30, 45};

    /**
     * The times of day this lesson asks to be reminded at, within the practice
     * window. COUNT spreads its reminders evenly across the window — so "twice"
     * lands near the ends of the day, which is the workbook's morning-and-evening.
     */
    static List<int[]> slotsFor(Lesson lesson) {
        List<int[]> out = new ArrayList<>();
        String kind = lesson.practiceKind == null ? Lesson.KIND_HOURLY : lesson.practiceKind;

        if (Lesson.KIND_INTERVAL.equals(kind)) {
            int step = Math.max(15, Math.min(60, lesson.practiceValue));
            step = step >= 60 ? 60 : (step >= 30 ? 30 : 15);   // snap to a clean grid
            for (int minutes = START_HOUR * 60; minutes <= END_HOUR * 60; minutes += step) {
                out.add(new int[]{minutes / 60, minutes % 60});
            }
            return out;
        }

        if (Lesson.KIND_COUNT.equals(kind)) {
            int n = Math.max(1, Math.min(END_HOUR - START_HOUR + 1, lesson.practiceValue));
            if (n == 1) {
                out.add(new int[]{START_HOUR, 0});
                return out;
            }
            // Evenly spaced, first at START_HOUR and last at END_HOUR.
            for (int i = 0; i < n; i++) {
                int hour = START_HOUR + Math.round((float) i * (END_HOUR - START_HOUR) / (n - 1));
                out.add(new int[]{hour, 0});
            }
            return out;
        }

        for (int hour = START_HOUR; hour <= END_HOUR; hour++) {
            out.add(new int[]{hour, 0});
        }
        return out;
    }

    /** A stable per-slot id, also used as the alarm request code. */
    private static int slotId(int hour, int minute) {
        return hour * 100 + minute;
    }

    /** Arm the next occurrence of a single slot. */
    public static void scheduleSlot(Context ctx, int hour, int minute) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;

        PendingIntent pi = reminderPendingIntent(ctx, hour, minute);
        long triggerAt = nextTimeFor(hour, minute);

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

    /** Drop a slot we no longer want (yesterday's regime may have used it). */
    static void cancelSlot(Context ctx, int hour, int minute) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        am.cancel(reminderPendingIntent(ctx, hour, minute));
    }

    private static PendingIntent reminderPendingIntent(Context ctx, int hour, int minute) {
        Intent i = new Intent(ctx, ReminderReceiver.class)
                .setAction(ACTION_REMIND)
                .putExtra(EXTRA_HOUR, hour)
                .putExtra(EXTRA_MINUTE, minute);
        return PendingIntent.getBroadcast(
                ctx, slotId(hour, minute), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The next date/time at HH:MM that is still in the future. */
    private static long nextTimeFor(int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return c.getTimeInMillis();
    }

    private Scheduler() {}
}
