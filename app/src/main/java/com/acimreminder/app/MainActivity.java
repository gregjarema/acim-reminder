package com.acimreminder.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * The app's one screen: today's lesson, a "Watch video" link, and a Begin
 * button. First-run permissions live in {@link OnboardingActivity}, so this
 * screen stays clean.
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // On first launch, do the permissions walkthrough instead of the lesson.
        if (!OnboardingActivity.hasOnboarded(this)) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }

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

        // Arm today's reminders now, and again every time the app is opened.
        Scheduler.scheduleAll(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (OnboardingActivity.hasOnboarded(this)) {
            bindToday();      // keep the lesson current across a midnight rollover
        }
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
}
