package com.acimreminder.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.health.connect.HealthConnectException;
import android.health.connect.HealthConnectManager;
import android.health.connect.InsertRecordsResponse;
import android.health.connect.datatypes.Metadata;
import android.health.connect.datatypes.MindfulnessSessionRecord;
import android.os.Build;
import android.os.OutcomeReceiver;
import android.os.ext.SdkExtensions;
import android.util.Log;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.function.Consumer;

/**
 * Saves each finished sitting to Health Connect as a meditation session, so it
 * counts towards the phone's mindful minutes.
 *
 * Opt-in, from "Save sittings to Health Connect" in the Workbook ⋯ menu, and
 * inert until Health Connect access is allowed. The mindfulness record only
 * exists from Android 16 (or Android 14/15 with a new enough Health Connect
 * module), so on anything older this quietly does nothing.
 *
 * What gets saved is the time you actually sat: a sitting that runs to the
 * closing bell is saved in full, one you stop early is saved up to the moment
 * you stopped, and anything under a minute isn't saved at all.
 */
final class HealthLog {

    private static final String TAG = "HealthLog";

    /** The user's toggle, in {@link OnboardingActivity#PREFS}. */
    static final String KEY_ENABLED = "health_connect_enabled";
    /** Wall-clock start of the running sitting; set by MeditationService. */
    static final String KEY_SITTING_START = "meditation_start_at";
    /** One line saying what happened to the last sitting, for the status dialog. */
    static final String KEY_LAST = "health_connect_last";

    static final String PERMISSION = "android.permission.health.WRITE_MINDFULNESS";

    /** Health Connect's own "app permissions" screen, for when the prompt won't show. */
    static final String ACTION_MANAGE = "android.health.connect.action.MANAGE_HEALTH_PERMISSIONS";

    private static final long MIN_SITTING_MS = 60_000L;

    private HealthLog() { }

    /** True when this phone's Health Connect knows what a mindfulness session is. */
    static boolean supported() {
        if (Build.VERSION.SDK_INT >= 36) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) >= 15;
        }
        return false;
    }

    static boolean granted(Context ctx) {
        return ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean enabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, false);
    }

    /** Called as a sitting begins. */
    static void sittingStarted(Context ctx, long startMillis) {
        prefs(ctx).edit().putLong(KEY_SITTING_START, startMillis).apply();
    }

    /**
     * Called as a sitting ends, by the closing bell or by Stop. Always clears the
     * stored start, so a stray second call can't save the same sitting twice.
     */
    static void sittingEnded(Context ctx, long endMillis) {
        SharedPreferences p = prefs(ctx);
        long start = p.getLong(KEY_SITTING_START, 0L);
        p.edit().remove(KEY_SITTING_START).apply();
        // Every way out records why, so "it didn't show up" can be answered
        // from the status dialog instead of guessed at.
        if (start <= 0L) {
            note(ctx, "Not saved: no start time (the sitting began before this "
                    + "version was installed?)");
            return;
        }
        if (endMillis - start < MIN_SITTING_MS) {
            note(ctx, "Not saved: under a minute");
            return;
        }
        if (!enabled(ctx)) { note(ctx, "Not saved: switched off in the menu"); return; }
        if (!supported()) { note(ctx, "Not saved: this Android has no mindfulness record"); return; }
        if (!granted(ctx)) { note(ctx, "Not saved: no Health Connect access"); return; }
        Lesson lesson = Lessons.forDate(ctx,
                LocalDate.ofInstant(Instant.ofEpochMilli(start), ZoneId.systemDefault()));
        write(ctx, start, endMillis, lesson.title, lesson.idea(), null);
    }

    /**
     * Save a one-minute session ending now, from the status dialog, so the whole
     * path can be checked without sitting for fifteen minutes. {@code done} gets
     * a line saying how it went, on the main thread.
     */
    static void writeTest(Context ctx, Consumer<String> done) {
        if (!supported()) { done.accept("This Android has no mindfulness record."); return; }
        if (!granted(ctx)) { done.accept("No Health Connect access yet."); return; }
        long end = System.currentTimeMillis();
        write(ctx, end - MIN_SITTING_MS, end, "Test sitting",
                "A test from A Course in Miracles. Safe to delete.", done);
    }

    /** The last recorded outcome, or a line saying there isn't one yet. */
    static String lastStatus(Context ctx) {
        return prefs(ctx).getString(KEY_LAST, "No sitting has ended since this version was installed.");
    }

    private static void write(Context ctx, long start, long end, String title, String notes,
                              Consumer<String> done) {
        try {
            Writer.write(ctx.getApplicationContext(), start, end, title, notes, done);
        } catch (Throwable t) {
            // Never let Health Connect take the closing bell down with it.
            Log.w(TAG, "Could not save the sitting to Health Connect", t);
            report(ctx, "Not saved: " + t, done);
        }
    }

    private static void report(Context ctx, String line, Consumer<String> done) {
        if (done != null) done.accept(line);
        else note(ctx, line);
    }

    private static void note(Context ctx, String line) {
        String when = java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM HH:mm"));
        Log.i(TAG, line);
        prefs(ctx).edit().putString(KEY_LAST, when + "  " + line).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(OnboardingActivity.PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Kept apart so the Health Connect classes are only touched on a phone that
     * has them (see {@link #supported()}).
     */
    private static final class Writer {
        static void write(Context ctx, long startMillis, long endMillis,
                          String title, String notes, Consumer<String> done) {
            HealthConnectManager hc = ctx.getSystemService(HealthConnectManager.class);
            if (hc == null) {
                report(ctx, "Not saved: Health Connect isn't available on this phone", done);
                return;
            }

            Instant start = Instant.ofEpochMilli(startMillis);
            Instant end = Instant.ofEpochMilli(endMillis);
            ZoneId zone = ZoneId.systemDefault();

            Metadata metadata = new Metadata.Builder()
                    // One id per sitting, so a repeat write replaces rather than doubles it.
                    .setClientRecordId("sitting-" + startMillis)
                    .setRecordingMethod(Metadata.RECORDING_METHOD_ACTIVELY_RECORDED)
                    .build();

            MindfulnessSessionRecord record = new MindfulnessSessionRecord.Builder(
                    metadata, start, end,
                    MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_MEDITATION)
                    .setStartZoneOffset(zone.getRules().getOffset(start))
                    .setEndZoneOffset(zone.getRules().getOffset(end))
                    .setTitle(title)
                    .setNotes(notes)
                    .build();

            hc.insertRecords(Collections.singletonList(record), ctx.getMainExecutor(),
                    new OutcomeReceiver<InsertRecordsResponse, HealthConnectException>() {
                        @Override
                        public void onResult(InsertRecordsResponse response) {
                            report(ctx, "Saved " + ((endMillis - startMillis) / 60_000L)
                                    + " min to Health Connect (" + title + ")", done);
                        }

                        @Override
                        public void onError(HealthConnectException e) {
                            Log.w(TAG, "Health Connect refused the sitting", e);
                            report(ctx, "Health Connect refused it (error "
                                    + e.getErrorCode() + "): " + e.getMessage(), done);
                        }
                    });
        }
    }
}
