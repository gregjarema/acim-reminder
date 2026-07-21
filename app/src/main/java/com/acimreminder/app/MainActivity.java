package com.acimreminder.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Html;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Chronometer;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * The app's one screen: today's lesson, an inline video player you can read
 * alongside, and a Begin button. First-run permissions live in
 * {@link OnboardingActivity}, so this screen stays clean.
 */
public class MainActivity extends Activity {

    private WebView webView;
    private FrameLayout fullscreenContainer;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private int boundLessonNumber = -1;

    private Chronometer chronoMeditation;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable meditationEndRunnable;

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

        setUpVideoPlayer();
        chronoMeditation = findViewById(R.id.chronoMeditation);
        chronoMeditation.setOnClickListener(v -> stopMeditation());
        findViewById(R.id.btnBegin).setOnClickListener(v -> beginMeditation());
        bindToday();
        refreshMeditationState();

        // Arm today's reminders now, and again every time the app is opened.
        Scheduler.scheduleAll(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (OnboardingActivity.hasOnboarded(this)) {
            bindToday();      // keep the lesson current across a midnight rollover
            refreshMeditationState();
        }
    }

    private void setUpVideoPlayer() {
        webView = findViewById(R.id.webView);
        fullscreenContainer = findViewById(R.id.fullscreenContainer);
        ProgressBar progress = findViewById(R.id.videoProgress);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            // Vimeo's player asks for HTML5 fullscreen via JS; without handling
            // this the fullscreen button in the embed silently does nothing.
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullscreenView = view;
                fullscreenCallback = callback;
                fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                fullscreenContainer.setVisibility(View.VISIBLE);
            }

            @Override
            public void onHideCustomView() {
                hideFullscreen();
            }
        });
    }

    private void hideFullscreen() {
        if (fullscreenView == null) return;
        fullscreenContainer.removeView(fullscreenView);
        fullscreenContainer.setVisibility(View.GONE);
        fullscreenView = null;
        if (fullscreenCallback != null) fullscreenCallback.onCustomViewHidden();
        fullscreenCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (fullscreenView != null) {
            hideFullscreen();
        } else {
            super.onBackPressed();
        }
    }

    /** Show today's scheduled lesson. */
    private void bindToday() {
        Lesson today = Lessons.today(this);
        ((TextView) findViewById(R.id.tvTitle)).setText(today.title);
        ((TextView) findViewById(R.id.tvSubtitle)).setText(today.phrase);
        // The body carries italic emphasis (quoted Course passages, prayers) as
        // <i> tags; paragraph breaks are blank lines. Render both as HTML.
        String bodyHtml = today.body == null ? "" :
                today.body.replace("\n\n", "<br><br>").replace("\n", "<br>");
        ((TextView) findViewById(R.id.tvBody))
                .setText(Html.fromHtml(bodyHtml, Html.FROM_HTML_MODE_COMPACT));

        // Only reset the video on an actual lesson change (a midnight rollover
        // while the app is open) — not on every resume, so simply switching
        // away and back doesn't interrupt something you're watching.
        if (today.number != boundLessonNumber) {
            boundLessonNumber = today.number;
            hideFullscreen();
            findViewById(R.id.videoBox).setVisibility(View.GONE);
            webView.loadUrl("about:blank");

            View videoLink = findViewById(R.id.btnVideo);
            if (today.video == null || today.video.isEmpty()) {
                videoLink.setVisibility(View.GONE);
            } else {
                videoLink.setVisibility(View.VISIBLE);
                videoLink.setOnClickListener(v -> playInline(today.video));
            }
        }
    }

    /**
     * Load the video right above the lesson text so you can watch and read
     * together. This loads the plain vimeo.com watch page as-is rather than
     * rewriting it to the player.vimeo.com iframe-embed URL: these videos are
     * locked to specific whitelisted domains for third-party embedding, so the
     * embed URL hits Vimeo's "this video cannot be played here" privacy error.
     * The plain vimeo.com page is Vimeo's own site, not a third-party embed,
     * so only the link's own access hash matters there — it just plays.
     */
    private void playInline(String url) {
        try {
            findViewById(R.id.btnVideo).setVisibility(View.GONE);
            findViewById(R.id.videoBox).setVisibility(View.VISIBLE);
            webView.loadUrl(url);
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't play the video.", Toast.LENGTH_SHORT).show();
        }
    }

    private void beginMeditation() {
        // The app is visible, so this foreground service starts with full
        // "while-in-use" capability and its audio is allowed to play.
        Intent i = new Intent(this, MeditationService.class)
                .setAction(MeditationService.ACTION_START);
        ContextCompat.startForegroundService(this, i);
        // The service persists this same end time; computing it here too (rather
        // than waiting on it) shows the countdown immediately with no lag.
        showMeditationActive(System.currentTimeMillis() + MeditationService.DURATION_MS);
    }

    private void stopMeditation() {
        startService(new Intent(this, MeditationService.class)
                .setAction(MeditationService.ACTION_STOP));
        hideMeditationActive();
    }

    /** Mirror whatever the notification is currently showing — active or not. */
    private void refreshMeditationState() {
        SharedPreferences prefs = getSharedPreferences(OnboardingActivity.PREFS, MODE_PRIVATE);
        long endAt = prefs.getLong(MeditationService.KEY_MEDITATION_END_AT, 0);
        if (endAt > System.currentTimeMillis()) {
            showMeditationActive(endAt);
        } else {
            hideMeditationActive();
        }
    }

    /** Show the same live countdown the notification has, in the app too. */
    private void showMeditationActive(long endTime) {
        findViewById(R.id.btnBegin).setVisibility(View.GONE);
        chronoMeditation.setVisibility(View.VISIBLE);
        chronoMeditation.setCountDown(true);
        chronoMeditation.setFormat("Meditating — %s remaining · tap to stop");
        chronoMeditation.setBase(SystemClock.elapsedRealtime() + (endTime - System.currentTimeMillis()));
        chronoMeditation.start();

        if (meditationEndRunnable != null) mainHandler.removeCallbacks(meditationEndRunnable);
        meditationEndRunnable = this::hideMeditationActive;
        mainHandler.postDelayed(meditationEndRunnable, Math.max(0, endTime - System.currentTimeMillis()));
    }

    private void hideMeditationActive() {
        if (meditationEndRunnable != null) {
            mainHandler.removeCallbacks(meditationEndRunnable);
            meditationEndRunnable = null;
        }
        chronoMeditation.stop();
        chronoMeditation.setVisibility(View.GONE);
        findViewById(R.id.btnBegin).setVisibility(View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
