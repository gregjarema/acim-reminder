package com.acimreminder.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.List;

/**
 * First-run onboarding, one required step at a time — no skip:
 *   1. Notifications (so you see reminders and the countdown).
 *   2. Battery-optimisation exemption (so reminders and the closing bell aren't
 *      delayed or killed). This prompt fires automatically the moment
 *      notifications are granted, and you only reach the lesson once it's done.
 * Shown only until completed once (onboarded flag in SharedPreferences).
 */
public class OnboardingActivity extends Activity {

    static final String PREFS = "acim";
    static final String KEY_ONBOARDED = "onboarded";
    private static final int REQ_NOTIF = 101;

    private TextView tvHeading, tvBody;
    private Button btnAction;
    private TextView btnSecondary;
    private boolean batteryPrompted = false;
    private boolean finished = false;

    static boolean hasOnboarded(Activity a) {
        return a.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ONBOARDED, false);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);
        Notify.ensureChannels(this);

        final View content = findViewById(R.id.content);
        final int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(pad, pad + bars.top, pad, pad + bars.bottom);
            return insets;
        });

        tvHeading = findViewById(R.id.tvHeading);
        tvBody = findViewById(R.id.tvBody);
        btnAction = findViewById(R.id.btnAction);
        btnAction.setOnClickListener(v -> onAction());
        btnSecondary = findViewById(R.id.btnSecondary);
        btnSecondary.setOnClickListener(v -> chooseStartingLesson());

        render();
        // Kick off the notification request straight away on first entry.
        if (!hasNotifications()) {
            requestNotifications();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    /** Show the current required step, or finish if everything's granted. */
    private void render() {
        if (finished) return;

        if (!hasNotifications()) {
            btnSecondary.setVisibility(View.GONE);
            tvHeading.setText("Notifications");
            tvBody.setText("First, allow notifications so you can see each reminder and the live meditation countdown.");
            btnAction.setText("Allow notifications");
            return;
        }

        if (!isBatteryUnrestricted()) {
            btnSecondary.setVisibility(View.GONE);
            tvHeading.setText("One more step");
            tvBody.setText("Let the app ignore battery optimisation, so your reminders fire on time and the closing bell always rings — even overnight. Choose “Allow” / “Don't optimise” on the next screen.");
            btnAction.setText("Turn off battery optimisation");
            // Pop the system prompt automatically the first time we land here.
            if (!batteryPrompted) {
                batteryPrompted = true;
                requestIgnoreBatteryOptimisation();
            }
            return;
        }

        // Last: where in the workbook to begin. Everything before this was a
        // system permission prompt, so this is the first real choice you make.
        if (!Lessons.hasChosenStart(this)) {
            tvHeading.setText("Where to start");
            tvBody.setText("The workbook runs one lesson a day, starting today and advancing at midnight.\n\nBegin at Lesson 1, or pick up wherever you already are.");
            btnAction.setText("Start at Lesson 1");
            btnSecondary.setVisibility(View.VISIBLE);
            btnSecondary.setText("I'm further along — choose a lesson");
            return;
        }

        complete();
    }

    private void onAction() {
        if (!hasNotifications()) {
            requestNotifications();
        } else if (!isBatteryUnrestricted()) {
            requestIgnoreBatteryOptimisation();
        } else if (!Lessons.hasChosenStart(this)) {
            Lessons.startFrom(this, 1);
            complete();
        } else {
            complete();
        }
    }

    /**
     * The full lesson list, so someone mid-workbook can start where they
     * actually are instead of clicking forward from Lesson 1.
     */
    private void chooseStartingLesson() {
        final List<Lesson> all = Lessons.all(this);
        if (all.isEmpty()) {          // no content shipped; don't strand the user
            Lessons.startFrom(this, 1);
            complete();
            return;
        }

        final String[] labels = new String[all.size()];
        for (int i = 0; i < all.size(); i++) {
            Lesson l = all.get(i);
            labels[i] = l.title + " — " + l.idea().replace("\n", " · ");
        }

        new AlertDialog.Builder(this)
                .setTitle("Start from which lesson?")
                .setSingleChoiceItems(labels, 0, (dialog, which) -> {
                    dialog.dismiss();
                    Lessons.startFrom(this, all.get(which).number);
                    complete();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void complete() {
        if (finished) return;
        finished = true;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ONBOARDED, true).apply();
        Scheduler.scheduleAll(this);
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    // ---- permission helpers ----

    private boolean hasNotifications() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isBatteryUnrestricted() {
        PowerManager pm = getSystemService(PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestNotifications() {
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
    }

    @SuppressWarnings("BatteryLife")
    private void requestIgnoreBatteryOptimisation() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        // When notifications are granted, render() advances to the battery step
        // and auto-fires its prompt.
        render();
    }
}
