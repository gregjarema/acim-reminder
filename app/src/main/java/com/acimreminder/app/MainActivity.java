package com.acimreminder.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * The app's one screen: shows the full lesson, a Begin button, and a small
 * one-time setup card that walks you through the three permissions the app
 * needs to be reliable.
 */
public class MainActivity extends Activity {

    private static final int REQ_NOTIF = 101;

    private Button btnNotif, btnExact, btnBattery;
    private TextView tvAllSet;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Notify.ensureChannels(this);

        // The system draws us edge-to-edge, so pad the content clear of the
        // status bar (top) and navigation bar (bottom).
        final View content = findViewById(R.id.content);
        final int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(pad, pad + bars.top, pad, pad + bars.bottom);
            return insets;
        });

        findViewById(R.id.btnBegin).setOnClickListener(v -> beginMeditation());
        bindToday();

        btnNotif = findViewById(R.id.btnNotif);
        btnExact = findViewById(R.id.btnExact);
        btnBattery = findViewById(R.id.btnBattery);
        tvAllSet = findViewById(R.id.tvAllSet);

        btnNotif.setOnClickListener(v -> requestNotifications());
        btnExact.setOnClickListener(v -> openExactAlarmSettings());
        btnBattery.setOnClickListener(v -> requestIgnoreBatteryOptimisation());

        // Arm today's reminders now, and again every time the app is opened.
        Scheduler.scheduleAll(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        bindToday();      // keep the lesson current across a midnight rollover
        refreshSetupUi();
    }

    /** Show today's scheduled lesson. */
    private void bindToday() {
        Lesson today = Lessons.today(this);
        ((TextView) findViewById(R.id.tvTitle)).setText(today.title);
        ((TextView) findViewById(R.id.tvSubtitle)).setText(today.phrase);
        ((TextView) findViewById(R.id.tvBody)).setText(today.body);

        View videoLink = findViewById(R.id.btnVideo);
        if (today.video == null || today.video.isEmpty()) {
            videoLink.setVisibility(View.GONE);
        } else {
            videoLink.setVisibility(View.VISIBLE);
            videoLink.setOnClickListener(v -> openVideo(today.video));
        }
    }

    private void beginMeditation() {
        // The app is visible, so this foreground service starts with full
        // "while-in-use" capability and its audio is allowed to play.
        Intent i = new Intent(this, MeditationService.class)
                .setAction(MeditationService.ACTION_START);
        ContextCompat.startForegroundService(this, i);
        Toast.makeText(this, "Five minutes begins now.", Toast.LENGTH_SHORT).show();
    }

    private void openVideo(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't open the video.", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- setup card ----

    private void refreshSetupUi() {
        boolean notif = hasNotifications();
        boolean exact = hasExactAlarms();
        boolean battery = isBatteryUnrestricted();

        btnNotif.setVisibility(notif ? View.GONE : View.VISIBLE);
        btnExact.setVisibility(exact ? View.GONE : View.VISIBLE);
        btnBattery.setVisibility(battery ? View.GONE : View.VISIBLE);
        tvAllSet.setVisibility((notif && exact && battery) ? View.VISIBLE : View.GONE);
    }

    private boolean hasNotifications() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasExactAlarms() {
        AlarmManager am = getSystemService(AlarmManager.class);
        return am != null && am.canScheduleExactAlarms();
    }

    private boolean isBatteryUnrestricted() {
        PowerManager pm = getSystemService(PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestNotifications() {
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
    }

    private void openExactAlarmSettings() {
        // With USE_EXACT_ALARM this is normally already granted, but on some
        // builds we still send you to the per-app toggle.
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    @SuppressWarnings("BatteryLife")
    private void requestIgnoreBatteryOptimisation() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_NOTIF) {
            refreshSetupUi();
            if (results.length > 0 && results[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this,
                        "Without notifications you won't see the reminders.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // Kept for clarity: this app targets API 37, so Build.VERSION checks aren't
    // needed for the permissions above (all exist on Android 13+ = our minSdk).
    @SuppressWarnings("unused")
    private boolean isAtLeast(int sdk) {
        return Build.VERSION.SDK_INT >= sdk;
    }
}
