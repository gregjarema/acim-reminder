package com.acimreminder.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plays today's lesson video inside the app instead of handing off to a
 * browser or the Vimeo app. A clean "vimeo.com/{id}/{hash}" link (the usual
 * case, decoded offline from the email's tracking button) is turned into the
 * official chrome-less player embed. Anything else — an occasional email
 * whose tracking link we couldn't decode offline — is loaded as-is; the
 * WebView simply follows the same live redirect a browser would, landing on
 * the normal Vimeo page, still inside the app.
 */
public class VideoActivity extends Activity {

    public static final String EXTRA_VIDEO_URL = "video_url";

    private static final Pattern VIMEO_ID_HASH =
            Pattern.compile("vimeo\\.com/(\\d+)(?:/([a-zA-Z0-9]+))?");

    private WebView webView;
    private FrameLayout fullscreenContainer;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video);

        String url = getIntent().getStringExtra(EXTRA_VIDEO_URL);
        if (url == null || url.isEmpty()) {
            finish();
            return;
        }

        webView = findViewById(R.id.webView);
        fullscreenContainer = findViewById(R.id.fullscreenContainer);
        ProgressBar progress = findViewById(R.id.progress);

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
                webView.setVisibility(View.GONE);
            }

            @Override
            public void onHideCustomView() {
                hideFullscreen();
            }
        });

        webView.loadUrl(playerUrl(url));
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

    private void hideFullscreen() {
        if (fullscreenView == null) return;
        fullscreenContainer.removeView(fullscreenView);
        fullscreenContainer.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        fullscreenView = null;
        if (fullscreenCallback != null) fullscreenCallback.onCustomViewHidden();
        fullscreenCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (fullscreenView != null) {
            hideFullscreen();
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        webView.destroy();
        super.onDestroy();
    }
}
