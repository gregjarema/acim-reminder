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
     * Wall-clock end time of the running session (0 = none), in
     * {@link OnboardingActivity#PREFS}. Lets MainActivity mirror the same
     * countdown shown in the notification, even after a cold start.
     */
    public static final String KEY_MEDITATION_END_AT = "meditation_end_at";

    private static final int MED_NOTIF_ID = 3001;
    private static final int END_ALARM_REQUEST = 9001;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        Log.i(TAG, "onStartCommand action=" + action);

        if (ACTION_START.equals(action)) {
            handleStart();
        } else if (ACTION_END.equals(action)) {
            handleEnd();
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

        scheduleEndAlarm(endTime);
        playCue(R.raw.bell_start, Haptics.START, null);
    }

    private void handleEnd() {
        Notify.ensureChannels(this);
        // Re-assert foreground in case the process was restarted just to run this.
        startForeground(MED_NOTIF_ID, buildCompleting(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        cancelEndAlarm();
        // Stop only once the closing cue has been delivered.
        playCue(R.raw.bell_end, Haptics.END, this::finish);
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
        PendingIntent pi = endAlarmPendingIntent();
        try {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTime, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTime, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTime, pi);
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

    private Notification buildCompleting() {
        return new NotificationCompat.Builder(this, Notify.CH_MEDITATION)
                .setSmallIcon(R.drawable.ic_stat_bell)
                .setContentTitle("Practice complete")
                .setContentText(Lessons.today(this).idea())
                .setOngoing(false)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
