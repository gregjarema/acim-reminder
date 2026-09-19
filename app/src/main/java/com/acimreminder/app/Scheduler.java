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
 * What the day looks like comes from the lesson — the workbook prescribes it
 * and it changes constantly — and WHEN it may fall comes from you: the hours
 * you're willing to be reminded within, 06:00–22:00 until you set your own
 * (see {@link #startHour}, {@link #endHour} and {@link #setWindow}). The
 * lesson's sittings land at the ends of that window and its passing
 * remembrances fill the hours between. Each alarm is "exact + allowed while
 * idle" so it fires through Doze at the right minute.
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

    /**
     * The hours you're willing to be reminded within, before you set your own:
     * 06:00 to 22:00 inclusive. Change them in the app's menu — see
     * {@link #setWindow}.
     */
    public static final int DEFAULT_START_HOUR = 6;
    public static final int DEFAULT_END_HOUR = 22;

    /** Your own window, in {@link OnboardingActivity#PREFS}. */
    public static final String KEY_START_HOUR = "reminder_start_hour";
    public static final String KEY_END_HOUR = "reminder_end_hour";

    /** The first hour a reminder may land on. */
    public static int startHour(Context ctx) {
        int h = prefs(ctx).getInt(KEY_START_HOUR, DEFAULT_START_HOUR);
        return h < 0 || h > 22 ? DEFAULT_START_HOUR : h;
    }

    /**
     * The last hour a reminder may land on, inclusive. Always after the start:
     * a window has to hold at least the two ends of a day, since that's where a
     * morning-and-evening lesson puts its sittings.
     */
    public static int endHour(Context ctx) {
        int start = startHour(ctx);
        int h = prefs(ctx).getInt(KEY_END_HOUR, DEFAULT_END_HOUR);
        if (h < 0 || h > 23) h = DEFAULT_END_HOUR;
        return Math.max(start + 1, h);
    }

    /**
     * Set the window and re-arm the day against it. Anything now outside it is
     * cancelled by {@link #scheduleAll}, which sweeps the whole clock.
     */
    public static void setWindow(Context ctx, int startHour, int endHour) {
        prefs(ctx).edit()
                .putInt(KEY_START_HOUR, startHour)
                .putInt(KEY_END_HOUR, endHour)
                .apply();
        scheduleAll(ctx);
    }

    private static android.content.SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(OnboardingActivity.PREFS, Context.MODE_PRIVATE);
    }

    static final String ACTION_REMIND = "com.acimreminder.app.REMIND";
    static final String EXTRA_HOUR = "hour";
    static final String EXTRA_MINUTE = "minute";
    /** True when this slot is one of the lesson's sittings, not a passing nudge. */
    static final String EXTRA_SITTING = "sitting";

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
        List<int[]> slots = slotsFor(Lessons.today(ctx), startHour(ctx), endHour(ctx));
        Set<Integer> wanted = new HashSet<>();
        for (int[] hm : slots) {
            wanted.add(slotId(hm[0], hm[1]));
            scheduleSlot(ctx, hm[0], hm[1], hm.length > 2 && hm[2] == 1);
        }
        // Clear anything previously armed that today doesn't want. The whole
        // clock, not just today's window: narrowing the window has to take down
        // the alarms that fell outside it, and they're no longer in range to
        // find by looking only at the hours we now want.
        for (int hour = 0; hour <= 23; hour++) {
            for (int minute : ALL_MINUTES) {
                int id = slotId(hour, minute);
                if (!wanted.contains(id)) cancelSlot(ctx, hour, minute);
            }
        }
        Log.i(TAG, "Scheduled " + slots.size() + " reminders for lesson "
                + Lessons.today(ctx).number);
    }

    /**
     * Every minute-of-hour we ever schedule at, so we know what to cancel.
     * Covers the remembrance steps we support — 10, 15, 20, 30 and 60 minutes —
     * whose slots off the hour land on exactly these marks.
     */
    private static final int[] ALL_MINUTES = {0, 10, 15, 20, 30, 40, 45, 50};

    /**
     * The times of day this lesson asks to be reminded at, within the practice
     * window. COUNT spreads its reminders evenly across the window — so "twice"
     * lands near the ends of the day, which is the workbook's morning-and-evening.
     */
    static List<int[]> slotsFor(Lesson lesson, int startHour, int endHour) {
        // The sittings, plus — where the lesson asks for it — an hourly nudge in
        // between. A lesson that already sits hourly needs no second track.
        // Each slot carries whether it's a sitting (1) or a passing
        // remembrance (0), so the notification can say which one it is.
        // Sittings are added first, so a time that is both reads as a sitting.
        Set<Integer> seen = new HashSet<>();
        List<int[]> out = new ArrayList<>();
        for (int[] hm : sittingSlots(lesson, startHour, endHour)) {
            if (seen.add(slotId(hm[0], hm[1]))) out.add(new int[]{hm[0], hm[1], 1});
        }
        if (lesson.hourlyRemembrance) {
            // Usually on the hour (step 60). A lesson asking to be recalled more
            // often steps smaller — Lesson 122 every quarter hour (15), Lesson 91
            // every ten minutes. Snap to a supported step so every slot lands on
            // a mark we also know how to cancel (see ALL_MINUTES).
            int step = lesson.remembranceEveryMinutes;
            step = step >= 60 ? 60 : step >= 30 ? 30 : step >= 20 ? 20 : step >= 15 ? 15 : 10;
            for (int minutes = startHour * 60; minutes <= endHour * 60; minutes += step) {
                int hour = minutes / 60, minute = minutes % 60;
                if (seen.add(slotId(hour, minute))) out.add(new int[]{hour, minute, 0});
            }
        }
        // A review day (Review III) carries two thoughts and asks for one on the
        // hour and the other on the half hour. The :00 remembrances are added
        // above; here are the :30 ones in between. ReminderReceiver reads the
        // minute to decide which of the two thoughts to show.
        if (lesson.isReview()) {
            for (int hour = startHour; hour < endHour; hour++) {
                if (seen.add(slotId(hour, 30))) out.add(new int[]{hour, 30, 0});
            }
        }
        return out;
    }

    private static List<int[]> sittingSlots(Lesson lesson, int startHour, int endHour) {
        List<int[]> out = new ArrayList<>();
        String kind = lesson.practiceKind == null ? Lesson.KIND_HOURLY : lesson.practiceKind;

        if (Lesson.KIND_INTERVAL.equals(kind)) {
            int step = Math.max(15, Math.min(60, lesson.practiceValue));
            step = step >= 60 ? 60 : (step >= 30 ? 30 : 15);   // snap to a clean grid
            for (int minutes = startHour * 60; minutes <= endHour * 60; minutes += step) {
                out.add(new int[]{minutes / 60, minutes % 60});
            }
            return out;
        }

        if (Lesson.KIND_COUNT.equals(kind)) {
            int n = Math.max(1, Math.min(endHour - startHour + 1, lesson.practiceValue));
            if (n == 1) {
                out.add(new int[]{startHour, 0});
                return out;
            }
            // Evenly spaced, first at the start of your window and last at its end.
            for (int i = 0; i < n; i++) {
                int hour = startHour + Math.round((float) i * (endHour - startHour) / (n - 1));
                out.add(new int[]{hour, 0});
            }
            return out;
        }

        for (int hour = startHour; hour <= endHour; hour++) {
            out.add(new int[]{hour, 0});
        }
        return out;
    }

    /** A stable per-slot id, also used as the alarm request code. */
    private static int slotId(int hour, int minute) {
        return hour * 100 + minute;
    }

    /** Arm the next occurrence of a single slot. */
    public static void scheduleSlot(Context ctx, int hour, int minute, boolean sitting) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;

        PendingIntent pi = reminderPendingIntent(ctx, hour, minute, sitting);
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
        am.cancel(reminderPendingIntent(ctx, hour, minute, false));
    }

    private static PendingIntent reminderPendingIntent(Context ctx, int hour, int minute,
                                                       boolean sitting) {
        Intent i = new Intent(ctx, ReminderReceiver.class)
                .setAction(ACTION_REMIND)
                .putExtra(EXTRA_HOUR, hour)
                .putExtra(EXTRA_MINUTE, minute)
                .putExtra(EXTRA_SITTING, sitting);
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
