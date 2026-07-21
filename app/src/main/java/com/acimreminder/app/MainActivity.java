package com.acimreminder.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Html;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The app's one screen: today's lesson, an inline video player you can read
 * alongside, and a Begin button. First-run permissions live in
 * {@link OnboardingActivity}, so this screen stays clean.
 */
public class MainActivity extends Activity {

    private static final Pattern VIMEO_ID_HASH =
            Pattern.compile("vimeo\\.com/(\\d+)(?:/([a-zA-Z0-9]+))?");

    private WebView webView;
    private FrameLayout fullscreenContainer;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private int boundLessonNumber = -1;

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

    /** Rewrite a clean vimeo.com link to the official embeddable player URL. */
    private static String playerUrl(String url) {
        Matcher m = VIMEO_ID_HASH.matcher(url);
        if (!m.find()) return url;   // not a clean vimeo.com link — load as-is
        String id = m.group(1);
        String hash = m.group(2);
        String embed = "https://player.vimeo.com/video/" + id
                + "?autoplay=1&title=0&byline=0&portrait=0";
        if (hash != null) embed += "&h=" + hash;
        return embed;
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

    /** Load the video right above the lesson text so you can watch and read together. */
    private void playInline(String url) {
        try {
            findViewById(R.id.btnVideo).setVisibility(View.GONE);
            findViewById(R.id.videoBox).setVisibility(View.VISIBLE);
            webView.loadUrl(playerUrl(url));
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
        Toast.makeText(this, "Five minutes begins now.", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
