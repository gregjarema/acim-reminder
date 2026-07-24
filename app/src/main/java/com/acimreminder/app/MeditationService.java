package com.acimreminder.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.widget.RemoteViews;

import androidx.core.app.NotificationCompat;

/**
 * Runs one 5-minute meditation.
 *
 * Flow:
 *   ACTION_START  -> go foreground with a live countdown notification, play the
 *                    opening bell, and schedule an exact alarm for the end.
 *   ACTION_END    -> (fired by that exact alarm) play the closing bell, then stop.
 *   ACTION_STOP   -> user cancelled: drop the end alarm and stop, no bell.
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

    /** Length of one session. Change this single number to make it shorter/longer. */
    public static final long DURATION_MS = 5 * 60 * 1000L;

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
        long endTime = System.currentTimeMillis() + DURATION_MS;
        getSharedPreferences(OnboardingActivity.PREFS, MODE_PRIVATE)
                .edit().putLong(KEY_MEDITATION_END_AT, endTime).apply();

        startForeground(MED_NOTIF_ID, buildCountdown(endTime),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);

        scheduleEndAlarm(endTime);
        BellPlayer.play(this, R.raw.bell_start, null);
    }

    private void handleEnd() {
        Notify.ensureChannels(this);
        // Re-assert foreground in case the process was restarted just to run this.
        startForeground(MED_NOTIF_ID, buildCompleting(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        cancelEndAlarm();
        // Stop only once the closing bell has finished ringing.
        BellPlayer.play(this, R.raw.bell_end, this::finish);
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
        String phrase = Lessons.today(this).phrase;

        RemoteViews small = new RemoteViews(getPackageName(), R.layout.notif_meditation_collapsed);
        small.setChronometer(R.id.notif_chrono, base, null, true);
        small.setChronometerCountDown(R.id.notif_chrono, true);

        RemoteViews big = new RemoteViews(getPackageName(), R.layout.notif_meditation_big);
        big.setChronometer(R.id.notif_chrono, base, null, true);
        big.setChronometerCountDown(R.id.notif_chrono, true);
        big.setTextViewText(R.id.notif_phrase, phrase);

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
                .setContentText(Lessons.today(this).phrase)
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
