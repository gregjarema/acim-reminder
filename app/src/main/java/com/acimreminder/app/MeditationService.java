package com.acimreminder.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.widget.RemoteViews;

import androidx.core.app.NotificationCompat;

/**
 * Runs one 5-minute meditation.
 *
 * Flow:
 *   ACTION_START  -> go foreground with a live countdown notification, sound the
 *                    opening cue, and schedule an exact alarm for the end.
 *   ACTION_END    -> (fired by that exact alarm) sound the closing cue, then stop.
 *   ACTION_STOP   -> user cancelled: drop the end alarm and stop, no cue.
 *
 * Each cue is a bell plus a buzz, and it honours the phone's ringer switch so a
 * sitting at your desk stays discreet: Normal rings the bell and buzzes, Vibrate
 * buzzes only (no bell), Silent does neither. See {@link #playCue}.
 *
 * Why a foreground service + a separate exact alarm? A plain in-app timer can be
 * frozen by Doze when the screen is off, which is exactly when you'd miss the
 * closing bell. The foreground service keeps us alive and audible; the exact
 * alarm guarantees the end fires on the minute regardless.
 */
public class MeditationService extends Service {

    private static final String TAG = "MeditationService";

    public static final String ACTION_START = "com.acimreminder.app.MEDITATE_START";
    public static final String ACTION_END = "com.acimreminder.app.MEDITATE_END";
    public static final String ACTION_STOP = "com.acimreminder.app.MEDITATE_STOP";

    /**
     * Fallback session length, used only when the day's lesson doesn't give one.
     * The real length comes from the lesson — the workbook prescribes it, and it
     * changes constantly. See {@link Lesson#practiceMillis()}.
     */
    public static final long DURATION_MS = 5 * 60 * 1000L;

    /** The length today's lesson asks for, falling back to {@link #DURATION_MS}. */
    public static long durationFor(Context ctx) {
        Lesson today = Lessons.today(ctx);
        return today.hasTimedPractice() ? today.practiceMillis() : DURATION_MS;
    }

    /**
     * True while a meditation is running — its recorded end time is still in the
     * future. Reminders use this to hold off during a sitting rather than
     * interrupting it; the value is cleared the moment the session ends.
     */
    public static boolean isRunning(Context ctx) {
        long endAt = ctx.getSharedPreferences(OnboardingActivity.PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_MEDITATION_END_AT, 0L);
        return endAt > System.currentTimeMillis();
    }

    /**
     * Wall-clock end time of the running session (0 = none), in
     * {@link OnboardingActivity#PREFS}. Lets MainActivity mirror the same
     * countdown shown in the notification, even after a cold start.
     */
    public static final String KEY_MEDITATION_END_AT = "meditation_end_at";

    /**
     * User toggle (Workbook overflow menu): turn on Do Not Disturb for the length
     * of each sitting. Off by default, and inert until "Do Not Disturb access" is
     * granted in Settings.
     */
    public static final String KEY_DND_ENABLED = "dnd_during_meditation";
    /**
     * The interruption filter in force when a sitting began, saved so it can be
     * put back afterwards — in prefs, not a field, because the closing bell can
     * fire in a freshly restarted process. 0 (UNKNOWN) means nothing to restore.
     */
    private static final String KEY_PREV_DND_FILTER = "dnd_prev_filter";

    private static final int MED_NOTIF_ID = 3001;
    private static final int END_ALARM_REQUEST = 9001;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        Log.i(TAG, "onStartCommand action=" + action);

        if (ACTION_START.equals(action)) {
            handleStart();
        } else if (ACTION_END.equals(action)) {
            // The end is now handled directly by EndBellReceiver (see endNow),
            // which doesn't need this service to be running. Tolerate a stray
            // ACTION_END by ending cleanly anyway.
            endNow(this);
        } else if (ACTION_STOP.equals(action)) {
            handleStop();
        } else {
            // Unexpected (e.g. a bare restart): don't linger silently.
            finish();
        }
        return START_NOT_STICKY;
    }

    private void handleStart() {
        Notify.ensureChannels(this);
        clearReminders();
        long endTime = System.currentTimeMillis() + durationFor(this);
        getSharedPreferences(OnboardingActivity.PREFS, MODE_PRIVATE)
                .edit().putLong(KEY_MEDITATION_END_AT, endTime).apply();

        startForeground(MED_NOTIF_ID, buildCountdown(endTime),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);

        applyDnd();
        scheduleEndAlarm(endTime);
        playCue(R.raw.bell_start, Haptics.START, null);
    }

    /**
     * If the user has opted in and granted Do Not Disturb access, silence the
     * phone for the sitting — but at the "alarms only" level, so the closing bell
     * (which is ALARM audio) still rings. The filter in force now is saved so
     * {@link #restoreDnd(Context)} can put it back when the sitting ends.
     */
    private void applyDnd() {
        if (!getSharedPreferences(OnboardingActivity.PREFS, MODE_PRIVATE)
                .getBoolean(KEY_DND_ENABLED, false)) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || !nm.isNotificationPolicyAccessGranted()) return;
        try {
            getSharedPreferences(OnboardingActivity.PREFS, MODE_PRIVATE).edit()
                    .putInt(KEY_PREV_DND_FILTER, nm.getCurrentInterruptionFilter()).apply();
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS);
        } catch (Exception e) {
            Log.w(TAG, "Could not turn on Do Not Disturb", e);
        }
    }

    /** Put back whatever interruption filter was in force before the sitting. */
    static void restoreDnd(Context ctx) {
        int prev = ctx.getSharedPreferences(OnboardingActivity.PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_PREV_DND_FILTER, 0);
        if (prev == 0) return;   // nothing was saved
        ctx.getSharedPreferences(OnboardingActivity.PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_PREV_DND_FILTER).apply();
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || !nm.isNotificationPolicyAccessGranted()) return;
        try {
            nm.setInterruptionFilter(prev);
        } catch (Exception e) {
            Log.w(TAG, "Could not restore Do Not Disturb", e);
        }
    }

    private static final int MED_DONE_NOTIF_ID = 3002;

    /**
     * End the sitting straight from the alarm receiver, WITHOUT depending on this
     * service still being alive. Post the closing cue (a notification the system
     * sounds), put Do Not Disturb back, clear the running-session marker, take
     * down the live countdown, and release the service if it's still around.
     *
     * The old path started a foreground service here to play the bell — which is
     * exactly what some phones refuse or kill once the screen is locked, so the
     * end bell silently never rang. A broadcast receiver posting a notification
     * needs none of that, and fires even if the process was already reclaimed.
     */
    static void endNow(Context ctx) {
        Notify.ensureChannels(ctx);
        postCompletion(ctx);
        restoreDnd(ctx);
        ctx.getSharedPreferences(OnboardingActivity.PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_MEDITATION_END_AT).apply();
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(MED_NOTIF_ID);   // take down the live countdown
        ctx.stopService(new Intent(ctx, MeditationService.class));
    }

    /**
     * Post the "Practice complete" alert. Its sound and vibration are the
     * notification channel's, so the system plays them — reliable with the screen
     * locked. Ringer-aware, like the opening cue: Normal uses the sounding
     * channel (bell + buzz); Vibrate uses the silent channel plus a direct buzz;
     * Silent shows a silent notification only. It clears itself after ten seconds.
     */
    private static void postCompletion(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        AudioManager am = ctx.getSystemService(AudioManager.class);
        int mode = am != null ? am.getRingerMode() : AudioManager.RINGER_MODE_NORMAL;

        boolean ring = mode == AudioManager.RINGER_MODE_NORMAL;
        Log.i(TAG, "postCompletion: ringerMode=" + mode + " ring=" + ring);
        android.app.NotificationChannel ch = nm.getNotificationChannel(
                ring ? Notify.CH_MEDITATION_DONE : Notify.CH_MEDITATION);
        if (ch != null) {
            Log.i(TAG, "channel " + ch.getId() + ": importance=" + ch.getImportance()
                    + " sound=" + ch.getSound() + " vibration=" + ch.shouldVibrate()
                    + " canBypassDnd=" + ch.canBypassDnd());
        } else {
            Log.w(TAG, "channel is null!");
        }
        Log.i(TAG, "areNotificationsEnabled=" + nm.areNotificationsEnabled()
                + " currentInterruptionFilter=" + nm.getCurrentInterruptionFilter());
        if (!ring && mode != AudioManager.RINGER_MODE_SILENT) {
            Haptics.buzz(ctx, Haptics.END);
        }

        Intent open = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(
                ctx, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // The sound comes from the notification CHANNEL (set in Notify.ensureChannels),
        // not the builder — on Android O+ (our minSdk 33 always qualifies) the channel's
        // sound is authoritative and NotificationCompat.Builder has no Uri+AudioAttributes
        // overload to set one here anyway.
        Notification n = new NotificationCompat.Builder(ctx,
                ring ? Notify.CH_MEDITATION_DONE : Notify.CH_MEDITATION)
                .setSmallIcon(R.drawable.ic_stat_bell)
                .setContentTitle("Practice complete")
                .setContentText(Lessons.today(ctx).idea())
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setTimeoutAfter(10_000L)   // clears itself after ten seconds
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openPi)
                .build();
        nm.notify(MED_DONE_NOTIF_ID, n);
    }

    /**
     * Sound one meditation cue, honouring the phone's ringer switch so a sitting
     * in a quiet office doesn't ring out loud:
     *
     *   Normal   -> bell + buzz
     *   Vibrate  -> buzz only (no bell)
     *   Silent   -> neither
     *
     * The bells are ALARM audio and so would otherwise play through Vibrate and
     * Silent; reading the ringer mode here is what makes them defer to it.
     *
     * {@code onDone} (may be null) runs once the cue is delivered — after the
     * bell finishes when there is one, or right after the buzz otherwise — so the
     * closing cue can chain {@link #finish()}. The buzz is fire-and-forget: the
     * system vibrator keeps going even if the service stops immediately after.
     */
    private void playCue(int rawResId, long[] haptic, BellPlayer.OnDone onDone) {
        AudioManager am = getSystemService(AudioManager.class);
        int mode = am != null ? am.getRingerMode() : AudioManager.RINGER_MODE_NORMAL;

        if (mode != AudioManager.RINGER_MODE_SILENT) {
            Haptics.buzz(this, haptic);
        }

        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            BellPlayer.play(this, rawResId, onDone);
        } else if (onDone != null) {
            onDone.done();
        }
    }

    /**
     * Take down any practice reminders still on screen, so the countdown reads
     * as replacing the nudge that prompted it rather than piling on top.
     *
     * Selected by channel, not by id: the reminder ids are derived from the
     * slot's hour and minute and so span a wide range that other notifications
     * sit inside. The channel is the one thing that says "this is a reminder".
     */
    private void clearReminders() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        for (android.service.notification.StatusBarNotification sbn : nm.getActiveNotifications()) {
            if (Notify.CH_REMINDERS.equals(sbn.getNotification().getChannelId())) {
                nm.cancel(sbn.getId());
            }
        }
    }

    private void handleStop() {
        cancelEndAlarm();
        finish();
    }

    private void finish() {
        restoreDnd(this);
        getSharedPreferences(OnboardingActivity.PREFS, MODE_PRIVATE)
                .edit().remove(KEY_MEDITATION_END_AT).apply();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    // ---- the end-of-meditation exact alarm ----

    private PendingIntent endAlarmPendingIntent() {
        Intent i = new Intent(this, EndBellReceiver.class);
        return PendingIntent.getBroadcast(
                this, END_ALARM_REQUEST, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void scheduleEndAlarm(long endTime) {
        AlarmManager am = getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent operation = endAlarmPendingIntent();

        // setAlarmClock is the strongest "this must fire" alarm Android offers:
        // exact even in Doze, and no exact-alarm permission needed. That is what
        // guarantees EndBellReceiver actually runs at the end minute with the
        // screen locked, so it can sound the closing bell. It surfaces a small
        // alarm indicator while the sitting is pending — a fair trade for the
        // bell reliably ringing.
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent showPi = PendingIntent.getActivity(
                this, END_ALARM_REQUEST, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(endTime, showPi), operation);
        } catch (Exception e) {
            // Fall back to an exact idle alarm if setAlarmClock is ever refused.
            Log.w(TAG, "setAlarmClock failed; falling back to exact idle alarm", e);
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTime, operation);
            } catch (SecurityException se) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTime, operation);
            }
        }
    }

    private void cancelEndAlarm() {
        AlarmManager am = getSystemService(AlarmManager.class);
        if (am != null) am.cancel(endAlarmPendingIntent());
    }

    // ---- notifications ----

    private Notification buildCountdown(long endTime) {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, MeditationService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 0, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Custom layouts with a self-ticking countdown clock. The Chronometer
        // counts down to `base`, which is on the elapsed-realtime clock the
        // widget uses (not wall-clock), so convert from endTime. Collapsed uses
        // a compact clock that fits; expanded uses a big one.
        long base = SystemClock.elapsedRealtime() + (endTime - System.currentTimeMillis());
        // The fuller two-line verse when the lesson has one, so the couplet you're
        // holding stays in front of you for the whole sitting.
        String idea = Lessons.today(this).meditationText();

        RemoteViews small = new RemoteViews(getPackageName(), R.layout.notif_meditation_collapsed);
        small.setChronometer(R.id.notif_chrono, base, null, true);
        small.setChronometerCountDown(R.id.notif_chrono, true);

        RemoteViews big = new RemoteViews(getPackageName(), R.layout.notif_meditation_big);
        big.setChronometer(R.id.notif_chrono, base, null, true);
        big.setChronometerCountDown(R.id.notif_chrono, true);
        big.setTextViewText(R.id.notif_phrase, idea);

        // Not setSilent(true): a silent notification is filed under the hidden
        // "silent" section and disappears from the lock screen. The channel
        // already has no sound/vibration, so this stays quiet while remaining
        // visible on the lock screen for the whole session. setOnlyAlertOnce
        // keeps the per-second countdown ticks from re-alerting.
        return new NotificationCompat.Builder(this, Notify.CH_MEDITATION)
                .setSmallIcon(R.drawable.ic_stat_bell)
                .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(small)
                .setCustomBigContentView(big)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openPi)
                .addAction(R.drawable.ic_stat_bell, "Stop", stopPi)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
