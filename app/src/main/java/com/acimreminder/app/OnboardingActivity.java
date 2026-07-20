package com.acimreminder.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
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

/**
 * First-run onboarding: walks through the three permissions the app needs to be
 * reliable, then hands off to the lesson screen. Shown only until it has been
 * completed once (see {@link #hasOnboarded}); after that the main screen is clean.
 */
public class OnboardingActivity extends Activity {

    static final String PREFS = "acim";
    static final String KEY_ONBOARDED = "onboarded";
    private static final int REQ_NOTIF = 101;

    private Button btnNotif, btnExact, btnBattery, btnContinue;
    private TextView tvAllSet;

    /** True once the user has finished onboarding at least once. */
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

        btnNotif = findViewById(R.id.btnNotif);
        btnExact = findViewById(R.id.btnExact);
        btnBattery = findViewById(R.id.btnBattery);
        btnContinue = findViewById(R.id.btnContinue);
        tvAllSet = findViewById(R.id.tvAllSet);

        btnNotif.setOnClickListener(v -> requestNotifications());
        btnExact.setOnClickListener(v -> openExactAlarmSettings());
        btnBattery.setOnClickListener(v -> requestIgnoreBatteryOptimisation());
        btnContinue.setOnClickListener(v -> finishOnboarding());

        // Kick off the notification prompt straight away on first entry.
        if (!hasNotifications()) {
            requestNotifications();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean notif = hasNotifications();
        boolean exact = hasExactAlarms();
        boolean battery = isBatteryUnrestricted();

        btnNotif.setVisibility(notif ? View.GONE : View.VISIBLE);
        btnExact.setVisibility(exact ? View.GONE : View.VISIBLE);
        btnBattery.setVisibility(battery ? View.GONE : View.VISIBLE);
        tvAllSet.setVisibility((notif && exact && battery) ? View.VISIBLE : View.GONE);
    }

    private void finishOnboarding() {
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
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
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
        refresh();
    }
}
