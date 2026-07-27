package com.acimreminder.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.MetricAffectingSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.SuperscriptSpan;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The app's one screen, in two tabs.
 *
 * <b>Workbook</b> — today's lesson, pinned to the calendar, with the inline
 * video and the 5-minute meditation.
 *
 * <b>Text</b> — Marianne's Text sessions, which advance only when you say so.
 * Same inline player, no meditation timer: it's a reading, not a practice.
 *
 * First-run permissions live in {@link OnboardingActivity}, so this screen
 * stays clean.
 */
public class MainActivity extends Activity implements Playback.Controller {

    /** Which tab you were last on, so reopening the app lands where you left. */
    private static final String KEY_TAB = "selected_tab";
    private static final int TAB_WORKBOOK = 0;
    private static final int TAB_TEXT = 1;
    private static final int TAB_SAVED = 2;

    private static final int SELECTED = 0xFF7A6646;   // the app's brown
    private static final int UNSELECTED = 0xFF9A9086;
    private static final int RULE = 0xFFE9E2D4;       // the faint divider line

    // Motion, kept to one quiet vocabulary so the whole app moves like it's
    // breathing rather than reacting: a single gentle ease, and two durations —
    // one for things settling in, a quicker one for things stepping aside.
    private static final long ANIM_IN = 260L;
    private static final long ANIM_OUT = 130L;
    private final Interpolator ease = new PathInterpolator(0.4f, 0f, 0.2f, 1f);

    private WebView webView;          // Workbook player
    private WebView textWebView;      // Text player
    /** Whichever player was last started, or null. What the media buttons and
     *  the playback notification drive. */
    private WebView activePlayer;
    private FrameLayout fullscreenContainer;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;

    private int boundLessonNumber = -1;
    private int boundTextDay = -1;
    private int selectedTab = TAB_WORKBOOK;

    // A video frame loads invisibly and is only faded in once it's framed, so
    // the appearance is the finished player, not the states it loads through.
    // boxLoad tags each load so a stale fallback can't reveal a newer one;
    // boxRevealed keeps the reveal to once per load.
    private final Map<Integer, Integer> boxLoad = new HashMap<>();
    private final Set<Integer> boxRevealed = new HashSet<>();

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

        // The system draws us edge-to-edge. The tab bar is pinned at the top, so
        // it takes the status-bar inset; each scrolling pane takes the navigation
        // -bar inset at the bottom so the last line of text clears it.
        final View tabBar = findViewById(R.id.tabBar);
        final int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        ViewCompat.setOnApplyWindowInsetsListener(tabBar, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, bars.top, 0, 0);
            findViewById(R.id.content).setPadding(pad, pad, pad, pad + bars.bottom);
            findViewById(R.id.textContent).setPadding(pad, pad, pad, pad + bars.bottom);
            findViewById(R.id.savedContent).setPadding(pad, pad, pad, pad + bars.bottom);
            return insets;
        });

        webView = setUpVideoPlayer(R.id.webView, R.id.videoProgress);
        textWebView = setUpVideoPlayer(R.id.textWebView, R.id.textVideoProgress);
        fullscreenContainer = findViewById(R.id.fullscreenContainer);

        findViewById(R.id.tabWorkbook).setOnClickListener(v -> selectTab(TAB_WORKBOOK));
        findViewById(R.id.tabText).setOnClickListener(v -> selectTab(TAB_TEXT));
        findViewById(R.id.tabSaved).setOnClickListener(v -> selectTab(TAB_SAVED));
        findViewById(R.id.btnOverflow).setOnClickListener(this::showOverflow);

        enableSaving(R.id.tvBody, false);
        enableSaving(R.id.tvTextBody, true);

        chronoMeditation = findViewById(R.id.chronoMeditation);
        chronoMeditation.setOnClickListener(v -> stopMeditation());
        findViewById(R.id.btnBegin).setOnClickListener(v -> beginMeditation());

        Playback.setController(this);

        findViewById(R.id.btnTextNext).setOnClickListener(v -> markTextRead());
        findViewById(R.id.btnTextPrev).setOnClickListener(v -> goToPreviousTextDay());

        bindToday();
        bindTextDay();
        refreshMeditationState();
        selectTab(prefs().getInt(KEY_TAB, TAB_WORKBOOK));
        fadeIn(findViewById(R.id.tabContainer));   // the reading settles in on open

        // Arm today's reminders now, and again every time the app is opened.
        Scheduler.scheduleAll(this);
        UpdateReceiver.schedule(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (OnboardingActivity.hasOnboarded(this)) {
            bindToday();      // keep the lesson current across a midnight rollover
            refreshMeditationState();
            offerUpdate();
        }
    }

    // -------------------------------------------------------------- updates

    /** The last time we asked GitHub, so opening the app repeatedly doesn't. */
    private static final String KEY_UPDATE_CHECKED_AT = "update_checked_at";
    private static final long CHECK_EVERY_MS = 15 * 60 * 1000L;

    /**
     * Offer a waiting build in a strip under the tabs, each time you open the app.
     *
     * The daily notification still runs — it's what does the downloading while
     * you're not here — but a notification only appears once, and it's easy to
     * swipe away and forget. This asks while you're already looking at the app,
     * and keeps asking until you install it or dismiss it.
     */
    private void offerUpdate() {
        // Anything already downloaded can be offered straight away, with no
        // network call at all.
        showUpdateBanner(Updater.readyBuild(this));

        long last = prefs().getLong(KEY_UPDATE_CHECKED_AT, 0L);
        if (System.currentTimeMillis() - last < CHECK_EVERY_MS) return;
        prefs().edit().putLong(KEY_UPDATE_CHECKED_AT, System.currentTimeMillis()).apply();

        new Thread(() -> {
            final int ready = Updater.checkAndFetch(getApplicationContext(), false);
            mainHandler.post(() -> {
                if (!isFinishing() && !isDestroyed()) showUpdateBanner(ready);
            });
        }).start();
    }

    private void showUpdateBanner(int build) {
        View banner = findViewById(R.id.updateBanner);
        if (build <= 0 || Updater.dismissed(this, build)) {
            banner.setVisibility(View.GONE);
            return;
        }
        ((TextView) findViewById(R.id.updateBannerText))
                .setText("Build " + build + " is ready to install");
        findViewById(R.id.updateBannerInstall).setOnClickListener(v -> {
            try {
                startActivity(Updater.installIntent(this, Updater.apkFile(this)));
            } catch (Exception e) {
                Toast.makeText(this, "Couldn't open the installer.", Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.updateBannerDismiss).setOnClickListener(v -> {
            Updater.dismiss(this, build);
            banner.setVisibility(View.GONE);
        });
        if (banner.getVisibility() != View.VISIBLE) fadeIn(banner);
    }

    // ---------------------------------------------------------------- tabs

    private void selectTab(int tab) {
        // If a build ships without Text content the tab is hidden, so never
        // restore onto it.
        if (tab == TAB_TEXT && findViewById(R.id.tabText).getVisibility() != View.VISIBLE) {
            tab = TAB_WORKBOOK;
        }
        boolean changed = tab != selectedTab;
        selectedTab = tab;
        prefs().edit().putInt(KEY_TAB, tab).apply();

        // One pane fades out as the next fades in, rather than snapping over.
        if (changed) softFade(findViewById(R.id.tabContainer));
        findViewById(R.id.workbookScroll).setVisibility(vis(tab == TAB_WORKBOOK));
        findViewById(R.id.textScroll).setVisibility(vis(tab == TAB_TEXT));
        findViewById(R.id.savedScroll).setVisibility(vis(tab == TAB_SAVED));

        paintTab(R.id.tabWorkbookLabel, R.id.tabWorkbookUnderline, tab == TAB_WORKBOOK);
        paintTab(R.id.tabTextLabel, R.id.tabTextUnderline, tab == TAB_TEXT);
        paintTab(R.id.tabSavedLabel, R.id.tabSavedUnderline, tab == TAB_SAVED);
        if (changed) revealUnderline(tab == TAB_WORKBOOK ? R.id.tabWorkbookUnderline
                : tab == TAB_TEXT ? R.id.tabTextUnderline : R.id.tabSavedUnderline);

        // Leaving a tab stops whatever was playing in it, so switching away
        // doesn't leave Marianne talking from a hidden pane.
        if (tab != TAB_WORKBOOK) pausePlayer(webView);
        if (tab != TAB_TEXT) pausePlayer(textWebView);

        if (tab == TAB_SAVED) bindSaved();
    }

    // -------------------------------------------------------------- motion

    /** Crossfade whatever visibility changes next inside this container. */
    private void softFade(ViewGroup container) {
        Fade fade = new Fade();
        fade.setDuration(ANIM_IN);
        fade.setInterpolator(ease);
        TransitionManager.beginDelayedTransition(container, fade);
    }

    /** Bring a hidden view in on a gentle fade. */
    private void fadeIn(View v) {
        v.animate().cancel();
        v.setAlpha(0f);
        v.setVisibility(View.VISIBLE);
        v.animate().alpha(1f).setDuration(ANIM_IN).setInterpolator(ease).start();
    }

    /**
     * Swap the content of a view in place: dim it out, let the caller rebind,
     * then ease it back. Used when the reading or lesson changes under you, so
     * the new words arrive rather than jump-cut.
     */
    private void refreshWithFade(View content, Runnable rebind) {
        content.animate().cancel();
        content.animate().alpha(0f).setDuration(ANIM_OUT).setInterpolator(ease)
                .withEndAction(() -> {
                    rebind.run();
                    content.setAlpha(0f);
                    content.animate().alpha(1f).setDuration(ANIM_IN)
                            .setInterpolator(ease).start();
                }).start();
    }

    /** The newly selected tab's underline grows out from its centre. */
    private void revealUnderline(int underlineId) {
        View u = findViewById(underlineId);
        u.animate().cancel();
        u.setScaleX(0.55f);
        u.animate().scaleX(1f).setDuration(ANIM_IN).setInterpolator(ease).start();
    }

    /**
     * The occasional actions. These used to sit stacked under the lesson —
     * three permanent buttons for things you do a few times a year, competing
     * with the two you use daily. They're the same actions, just no longer in
     * the way; the menu is built per tab so it only offers what applies here.
     */
    /**
     * A context for menus and dialogs, with the app's selectable-text default
     * turned back off.
     *
     * The theme makes every TextView selectable so any passage can be copied,
     * and that reaches into popups too — where each row *is* a TextView. A
     * selectable one takes the touch for itself instead of letting the list
     * handle it, so the menu opened and nothing in it could be tapped. Same
     * trap that once made Delete on a saved passage do nothing.
     */
    private android.content.Context popupContext() {
        return new android.view.ContextThemeWrapper(
                this, R.style.ThemeOverlay_AcimReminder_Popup);
    }

    private void showOverflow(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(popupContext(), anchor);
        final int JUMP = 1, WALLPAPER = 2, COPY = 3;

        if (selectedTab == TAB_WORKBOOK) {
            menu.getMenu().add(Menu.NONE, JUMP, 0, "Jump to lesson…");
            menu.getMenu().add(Menu.NONE, WALLPAPER, 1, "Use the live wallpaper…");
        } else if (selectedTab == TAB_TEXT) {
            menu.getMenu().add(Menu.NONE, JUMP, 0, "Jump to day…");
            menu.getMenu().add(Menu.NONE, WALLPAPER, 1, "Use the live wallpaper…");
        } else {
            menu.getMenu().add(Menu.NONE, COPY, 0, "Copy all passages");
        }

        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case JUMP:
                    if (selectedTab == TAB_TEXT) showJumpToDayDialog();
                    else showJumpToLessonDialog();
                    return true;
                case WALLPAPER: chooseWallpaper(); return true;
                case COPY: copyAllSaved(); return true;
                default: return false;
            }
        });
        menu.show();
    }

    private static int vis(boolean shown) {
        return shown ? View.VISIBLE : View.GONE;
    }

    private void paintTab(int labelId, int underlineId, boolean selected) {
        ((TextView) findViewById(labelId)).setTextColor(selected ? SELECTED : UNSELECTED);
        findViewById(underlineId).setBackgroundColor(selected ? SELECTED : RULE);
    }

    /** Silence a hidden player without tearing down the page it has loaded. */
    private void pausePlayer(WebView player) {
        if (player != null) player.onPause();
    }

    // ---------------------------------------------------------- playback

    /**
     * The player currently in use, or null if nothing has been started. The two
     * WebViews never play at once — switching tabs pauses the other.
     */
    private WebView activePlayer() {
        return activePlayer;
    }

    /**
     * Drive the &lt;video&gt; element directly. We load Vimeo's own page as the
     * top-level document, so injected script can reach the element — which is
     * the only handle we have on playback, since the video can't be played
     * anywhere but there.
     */
    private void videoScript(String js) {
        WebView p = activePlayer();
        if (p == null) return;
        p.evaluateJavascript(
                "(function(){try{var v=document.querySelector('video');if(v){" + js
                        + "}}catch(e){}})();", null);
    }

    @Override
    public void playbackPlay() {
        runOnUiThread(() -> {
            WebView p = activePlayer();
            if (p != null) p.onResume();
            videoScript("v.play();");
        });
    }

    @Override
    public void playbackPause() {
        runOnUiThread(() -> videoScript("v.pause();"));
    }

    @Override
    public void playbackStop() {
        runOnUiThread(() -> {
            videoScript("v.pause();");
            stopPlaybackService();
            collapsePlayers();
        });
    }

    private void startPlaybackService(String title) {
        Playback.setTitle(title);
        Playback.setController(this);
        ContextCompat.startForegroundService(this,
                new Intent(this, PlaybackService.class)
                        .setAction(PlaybackService.ACTION_START));
    }

    private void stopPlaybackService() {
        startService(new Intent(this, PlaybackService.class)
                .setAction(PlaybackService.ACTION_STOP));
    }

    /**
     * Put the players away after a Stop, then re-show each tab's preloaded
     * frame so the player is always sitting there ready.
     */
    private void collapsePlayers() {
        activePlayer = null;

        resetPlayer(webView, R.id.videoBox);
        Lesson today = Lessons.today(this);
        if (today.video != null && !today.video.isEmpty()) {
            showVideoFrame(webView, 0, R.id.videoBox, today.video);
        }

        resetPlayer(textWebView, R.id.textVideoBox);
        TextDay d = TextDays.current(this);
        if (d != null && !d.video.isEmpty()) {
            showVideoFrame(textWebView, 0, R.id.textVideoBox, d.video);
        }
    }

    // ------------------------------------------------------------- players

    private WebView setUpVideoPlayer(int webViewId, int progressId) {
        WebView view = findViewById(webViewId);
        ProgressBar progress = findViewById(progressId);

        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        // Let the page tell us when its <video> actually starts and stops. The
        // foreground media service — the thing that keeps audio alive with the
        // screen off — then comes up exactly while something is playing, rather
        // than the moment the frame is merely on screen (it's preloaded now).
        final WebView self = view;
        view.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void onPlay()  { mainHandler.post(() -> onVideoPlay(self)); }
            @android.webkit.JavascriptInterface
            public void onPause() { mainHandler.post(() -> onVideoPaused()); }
            @android.webkit.JavascriptInterface
            public void onEnded() { mainHandler.post(() -> onVideoEnded()); }
            @android.webkit.JavascriptInterface
            public void onFramed() { mainHandler.post(() -> onVideoFramed(self)); }
        }, "AndroidPlayback");

        view.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                // Injected before Vimeo's own scripts run, so the page never
                // hears the screen go off and pause the video. See keepPlaying.
                keepPlaying(v);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                // The watch page arrives with Vimeo's header, sign-in bar and
                // title around the player. Strip them so the box frames the
                // video. Re-run a couple of times: the page finishes loading
                // before its own scripts finish laying the player out.
                keepPlaying(v);
                hookMediaEvents(v);
                frameThePlayer(v);
                mainHandler.postDelayed(() -> { hookMediaEvents(v); frameThePlayer(v); }, 900);
                mainHandler.postDelayed(() -> { hookMediaEvents(v); frameThePlayer(v); }, 2200);
            }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int newProgress) {
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            // Vimeo's player asks for HTML5 fullscreen via JS; without handling
            // this the fullscreen button in the embed silently does nothing.
            @Override
            public void onShowCustomView(View v, CustomViewCallback callback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullscreenView = v;
                fullscreenCallback = callback;
                fullscreenContainer.addView(v, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                fullscreenContainer.setVisibility(View.VISIBLE);
            }

            @Override
            public void onHideCustomView() {
                hideFullscreen();
            }
        });
        return view;
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
        } else if (selectedTab == TAB_TEXT) {
            selectTab(TAB_WORKBOOK);   // Back out of the Text tab before leaving
        } else {
            super.onBackPressed();
        }
    }

    /**
     * Load a video right above the reading so you can watch and read together.
     * This loads the plain vimeo.com watch page as-is rather than rewriting it
     * to the player.vimeo.com iframe-embed URL: these videos are locked to
     * specific whitelisted domains for third-party embedding, so the embed URL
     * hits Vimeo's "this video cannot be played here" privacy error. The plain
     * vimeo.com page is Vimeo's own site, not a third-party embed, so only the
     * link's own access hash matters there — it just plays. Both the Workbook
     * lessons and the Text sessions carry that hash in their URL.
     */
    private void showVideoFrame(WebView player, int linkId, int boxId, String url) {
        try {
            if (linkId != 0) findViewById(linkId).setVisibility(View.GONE);
            View box = findViewById(boxId);

            // Lay the frame out but keep it invisible while it loads. The page
            // passes through a black box, a spinner and Vimeo's raw chrome
            // before it's stripped to just the player; hidden, none of that
            // shows. onVideoFramed fades it in once the player is framed.
            final int gen = boxLoad.merge(boxId, 1, Integer::sum);
            boxRevealed.remove(boxId);
            box.animate().cancel();
            box.setAlpha(0f);
            box.setVisibility(View.VISIBLE);
            // Frame the box to the video's 16:9 shape instead of a fixed
            // height, so there's no black band left under the picture. The
            // width isn't known until the box is laid out, so wait for that.
            box.post(() -> {
                int w = box.getWidth();
                if (w > 0) {
                    ViewGroup.LayoutParams lp = box.getLayoutParams();
                    lp.height = Math.round(w * 9f / 16f);
                    box.setLayoutParams(lp);
                }
            });

            // Don't start the media service here — it comes up in onVideoPlay
            // when the video actually plays, so a preloaded frame you never
            // watch doesn't post a media notification.
            player.onResume();
            player.loadUrl(url);

            // If the framing signal never lands (a slow or unusual page), reveal
            // it anyway so the frame is never left invisible — but only if this
            // is still the load it was armed for.
            mainHandler.postDelayed(() -> {
                Integer cur = boxLoad.get(boxId);
                if (cur != null && cur == gen) revealBox(boxId);
            }, 3500);
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't load the video.", Toast.LENGTH_SHORT).show();
        }
    }

    /** The page finished framing its player — fade the ready frame in. */
    private void onVideoFramed(WebView player) {
        revealBox(player == textWebView ? R.id.textVideoBox : R.id.videoBox);
    }

    /** Fade a loaded, framed video box into view, once per load. */
    private void revealBox(int boxId) {
        if (!boxRevealed.add(boxId)) return;   // already revealed this load
        View box = findViewById(boxId);
        if (box.getVisibility() != View.VISIBLE) return;
        box.animate().cancel();
        box.animate().alpha(1f).setDuration(ANIM_IN).setInterpolator(ease).start();
    }

    /** The &lt;video&gt; actually started — bring the media service up around it. */
    private void onVideoPlay(WebView player) {
        activePlayer = player;
        boolean isText = player == textWebView;
        TextDay day = TextDays.current(this);
        startPlaybackService(isText
                ? (day == null ? "" : day.shortTitle())
                : Lessons.today(this).title);
    }

    /** Paused from the page itself — show that in the notification. */
    private void onVideoPaused() {
        if (activePlayer == null) return;
        startService(new Intent(this, PlaybackService.class)
                .setAction(PlaybackService.ACTION_PAUSED));
    }

    /** Played to the end — let the service go. */
    private void onVideoEnded() {
        stopPlaybackService();
    }

    /** Report the &lt;video&gt;'s own play/pause/ended back to the service. */
    private void hookMediaEvents(WebView v) {
        v.evaluateJavascript(
                "(function(){try{var e=document.querySelector('video');"
                + "if(e&&!e.__acimHooked){e.__acimHooked=true;"
                + "e.addEventListener('play',function(){try{AndroidPlayback.onPlay();}catch(x){}});"
                + "e.addEventListener('pause',function(){try{AndroidPlayback.onPause();}catch(x){}});"
                + "e.addEventListener('ended',function(){try{AndroidPlayback.onEnded();}catch(x){}});"
                + "}}catch(e){}})();", null);
    }

    /**
     * Keep the video playing when the phone is locked.
     *
     * A screen-off makes the browser mark the page hidden — document.hidden
     * flips true and a visibilitychange fires — and Vimeo's player, like most,
     * pauses on that. Brave and Chrome keep audio going in the background
     * because they don't surface it to the page; we do the same by hand: pin
     * document.hidden to false and visibilityState to "visible", and swallow the
     * visibilitychange before the player's own listener can see it. Paired with
     * BackgroundWebView (which stops the native side pausing), the audio simply
     * carries on with the screen off.
     */
    private void keepPlaying(WebView v) {
        v.evaluateJavascript(
                "(function(){try{"
                + "function pin(p,val){try{Object.defineProperty(document,p,"
                + "{configurable:true,get:function(){return val;}});}catch(e){}}"
                + "pin('hidden',false);pin('visibilityState','visible');"
                + "pin('webkitHidden',false);pin('webkitVisibilityState','visible');"
                + "if(!window.__acimVis){window.__acimVis=true;"
                + "var eat=function(e){e.stopImmediatePropagation();};"
                + "['visibilitychange','webkitvisibilitychange'].forEach(function(t){"
                + "document.addEventListener(t,eat,true);"
                + "window.addEventListener(t,eat,true);});}"
                + "}catch(e){}})();", null);
    }

    /**
     * Trim the watch page down to just the player.
     *
     * We can't use Vimeo's embed URL — these videos are whitelisted to specific
     * domains, and the embed refuses to play anywhere else — so the plain watch
     * page it is, chrome and all. This walks up from the video and hides the
     * siblings on the way to <body>.
     *
     * It hides a sibling only if it doesn't overlap the video. Vimeo's own
     * controls are a sibling that sits ON the picture, so hiding siblings
     * outright took the play button with them — which is what left the video
     * playable only from the phone's media controls. The header, sign-in bar
     * and title all sit outside the picture and still go.
     *
     * The test is geometric first, so it doesn't depend on Vimeo's class names
     * surviving their next redesign. If the video can't be found or hasn't been
     * laid out yet, nothing is hidden and a later pass tries again.
     */
    private void frameThePlayer(WebView v) {
        v.evaluateJavascript(
                "(function(){try{"
                + "var p=document.querySelector('video')"
                + "||document.querySelector('iframe[src*=\"player.vimeo.com\"]');"
                + "if(!p)return;"
                + "var r=p.getBoundingClientRect();"
                + "if(r.width<10||r.height<10)return;"
                + "function over(el){var b=el.getBoundingClientRect();"
                + "return b.width>0&&b.height>0&&b.left<r.right&&b.right>r.left"
                + "&&b.top<r.bottom&&b.bottom>r.top;}"
                // The class-name check is a second net under the geometry: a
                // control bar faded right out could measure as nothing.
                + "function keep(el){return over(el)"
                + "||/control|ui|button|play|volume/i.test(String(el.className||''));}"
                + "var n=p;"
                + "while(n&&n!==document.body){var par=n.parentNode;if(par&&par.children){"
                + "Array.prototype.forEach.call(par.children,function(s){"
                + "if(s!==n&&!keep(s))s.style.display='none';});}n=par;}"
                + "document.body.style.margin='0';"
                + "document.documentElement.style.background='#000';"
                + "window.scrollTo(0,0);"
                // Framed and stripped: tell the app it's ready to be shown.
                + "try{AndroidPlayback.onFramed();}catch(e){}"
                + "}catch(e){}})();", null);
    }

    /** Hide a player and stop it loading. */
    private void resetPlayer(WebView player, int boxId) {
        hideFullscreen();
        findViewById(boxId).setVisibility(View.GONE);
        player.loadUrl("about:blank");
    }

    // -------------------------------------------------------- workbook tab

    /** Show today's scheduled lesson. */
    private void bindToday() {
        Lesson today = Lessons.today(this);
        ((TextView) findViewById(R.id.tvTitle)).setText(today.title);
        ((TextView) findViewById(R.id.tvSubtitle)).setText(today.idea());
        ((TextView) findViewById(R.id.tvBody)).setText(asHtml(today.body));

        // The workbook sets the practice, and it changes constantly. Some days
        // ask only that you remember the idea — on those, there's nothing to
        // time, so the Begin button goes away rather than inventing a sitting.
        Button begin = findViewById(R.id.btnBegin);
        if (today.hasTimedPractice()) {
            begin.setText("Begin " + today.practiceLabel() + " practice");
            begin.setVisibility(chronoMeditation.getVisibility() == View.VISIBLE
                    ? View.GONE : View.VISIBLE);
        } else {
            begin.setVisibility(View.GONE);
        }
        TextView practice = findViewById(R.id.tvPractice);
        practice.setText(practiceSummary(today));

        // Only reset the video on an actual lesson change (a midnight rollover
        // while the app is open) — not on every resume, so simply switching
        // away and back doesn't interrupt something you're watching.
        if (today.number != boundLessonNumber) {
            boundLessonNumber = today.number;
            resetPlayer(webView, R.id.videoBox);
            // No "Watch" link — the frame is preloaded in place, so the player
            // is simply there above the lesson, ready for you to press play.
            if (today.video != null && !today.video.isEmpty()) {
                showVideoFrame(webView, 0, R.id.videoBox, today.video);
            }
        }
    }

    /**
     * Open Android's wallpaper preview on ours, so setting it is one tap rather
     * than a hunt through Settings. Falls back to the general live-wallpaper
     * picker on devices that don't honour the direct intent.
     */
    private void chooseWallpaper() {
        Intent direct = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        new ComponentName(this, LessonWallpaperService.class));
        try {
            startActivity(direct);
        } catch (Exception e) {
            try {
                startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
            } catch (Exception e2) {
                Toast.makeText(this, "Couldn't open the wallpaper picker.",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** A plain-English line describing what today actually asks of you. */
    private String practiceSummary(Lesson l) {
        String when;
        if (Lesson.KIND_INTERVAL.equals(l.practiceKind)) {
            when = l.practiceValue == 30 ? "every half hour"
                    : "every " + l.practiceValue + " minutes";
        } else if (Lesson.KIND_COUNT.equals(l.practiceKind)) {
            when = l.practiceValue == 1 ? "once today"
                    : l.practiceValue == 2 ? "morning and evening"
                    : l.practiceValue + " times today";
        } else {
            when = "every hour";
        }
        String s = l.hasTimedPractice()
                ? l.practiceMinutes + " minutes, " + when
                : "Just remember the idea — " + when;
        // The second track, where the lesson asks for both.
        if (l.hourlyRemembrance) s += " · plus hourly reminders";
        return s;
    }

    /**
     * Re-anchor the workbook so today is the lesson you pick, advancing daily
     * from there. Unlike the Text's bookmark this moves the whole schedule —
     * which is what you want whether you're correcting a first-run mistake or
     * genuinely picking up at a different point.
     */
    private void showJumpToLessonDialog() {
        final List<Lesson> all = Lessons.all(this);
        if (all.isEmpty()) return;

        final String[] labels = new String[all.size()];
        int checked = 0;
        int current = Lessons.today(this).number;
        for (int i = 0; i < all.size(); i++) {
            Lesson l = all.get(i);
            labels[i] = l.title + " — " + l.idea();
            if (l.number == current) checked = i;
        }

        new AlertDialog.Builder(this)
                .setTitle("Start today at…")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    Lessons.startFrom(this, all.get(which).number);
                    bindToday();
                    findViewById(R.id.workbookScroll).scrollTo(0, 0);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // -------------------------------------------------------- saved tab

    /**
     * Add "Save passage" to the selection menu that appears when you highlight
     * text. Hooking the existing menu means highlighting works the way it does
     * everywhere else on the phone — no custom gesture to learn, and Copy and
     * Share stay where they are.
     */
    private void enableSaving(int textViewId, boolean isTextTab) {
        TextView tv = findViewById(textViewId);
        tv.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                menu.add(Menu.NONE, R.id.action_save_passage, 0, "Save passage");
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (item.getItemId() != R.id.action_save_passage) return false;
                int start = Math.min(tv.getSelectionStart(), tv.getSelectionEnd());
                int end = Math.max(tv.getSelectionStart(), tv.getSelectionEnd());
                if (start >= 0 && end > start) {
                    promptToSave(tv.getText().subSequence(start, end).toString(),
                            tv.getText(), start, isTextTab);
                }
                mode.finish();
                return true;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) { }
        });
    }

    /** Offer a note alongside the passage — the note is usually the point. */
    private void promptToSave(String passage, CharSequence full, int start, boolean isTextTab) {
        final EditText input = new EditText(this);
        input.setHint("Add a note (optional)");
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);

        String citation, source;
        if (isTextTab) {
            TextDay day = TextDays.current(this);
            citation = day == null ? "T-?" : Citation.forTextDay(day.label, full, start);
            source = day == null ? "" : day.shortTitle();
        } else {
            Lesson l = Lessons.today(this);
            citation = Citation.forLesson(l.number, full, start);
            source = l.title;
        }
        final String fCitation = citation, fSource = source;

        new AlertDialog.Builder(this)
                .setTitle("Save " + citation)
                .setView(wrap)
                .setPositiveButton("Save", (d, w) -> {
                    SavedPassages.add(this, fCitation, fSource, passage,
                            input.getText().toString());
                    Toast.makeText(this, "Saved " + fCitation, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Rebuild the saved list, in Course order. */
    private void bindSaved() {
        List<SavedPassages.Passage> all = SavedPassages.all(this);
        LinearLayout list = findViewById(R.id.savedList);
        list.removeAllViews();

        TextView hint = findViewById(R.id.tvSavedHint);
        if (all.isEmpty()) {
            hint.setText("Highlight any passage in the Workbook or Text and choose "
                    + "“Save passage”. They'll collect here, in Course order.");
            return;
        }
        hint.setText(all.size() == 1 ? "1 passage" : all.size() + " passages");

        float density = getResources().getDisplayMetrics().density;
        for (SavedPassages.Passage p : all) {
            list.addView(savedRow(p, density));
        }
    }

    private View savedRow(SavedPassages.Passage p, float density) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(14 * density);
        row.setPadding(0, pad, 0, pad);

        TextView cite = new TextView(this);
        cite.setText(p.citation + "   ·   " + p.source);
        cite.setTextSize(12);
        cite.setLetterSpacing(0.12f);
        cite.setTextColor(0xFFA08A63);
        cite.setTextIsSelectable(false);
        row.addView(cite);

        TextView body = new TextView(this);
        // Selectable text swallows long-press, which is why long-pressing a
        // saved passage did nothing. These rows are read, not selected.
        body.setTextIsSelectable(false);
        body.setText(p.text);
        body.setTextSize(16);
        body.setLineSpacing(0, 1.4f);
        body.setTextColor(0xFF1E1E1E);
        body.setTypeface(android.graphics.Typeface.SERIF);
        body.setPadding(0, Math.round(6 * density), 0, 0);
        row.addView(body);

        if (!p.note.isEmpty()) {
            TextView note = new TextView(this);
            note.setTextIsSelectable(false);
            note.setText("— " + p.note);
            note.setTextSize(14);
            note.setTextColor(0xFF7A6646);
            note.setPadding(0, Math.round(6 * density), 0, 0);
            row.addView(note);
        }

        // Visible controls rather than a hidden long-press: there's no way to
        // discover a gesture, and nothing else on this screen hints at one.
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, Math.round(8 * density), 0, 0);
        actions.addView(rowAction(p.note.isEmpty() ? "Add note" : "Edit note",
                v -> editSaved(p), density));
        actions.addView(rowAction("Delete", v -> confirmDelete(p), density));
        row.addView(actions);

        row.setOnLongClickListener(v -> {
            editSaved(p);
            return true;
        });

        View rule = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(density)));
        lp.topMargin = Math.round(12 * density);
        rule.setLayoutParams(lp);
        rule.setBackgroundColor(RULE);
        row.addView(rule);
        return row;
    }

    /** A small text button for the saved-passage rows. */
    private TextView rowAction(String label, View.OnClickListener onClick, float density) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        t.setTextColor(0xFFA08A63);
        t.setTextIsSelectable(false);
        t.setPadding(0, Math.round(6 * density), Math.round(22 * density),
                Math.round(6 * density));
        t.setBackgroundResource(android.R.drawable.list_selector_background);
        t.setOnClickListener(onClick);
        return t;
    }

    /** Deleting a passage can't be undone, so ask first. */
    private void confirmDelete(SavedPassages.Passage p) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + p.citation + "?")
                .setMessage(p.text.length() > 160 ? p.text.substring(0, 160) + "…" : p.text)
                .setPositiveButton("Delete", (d, w) -> {
                    SavedPassages.remove(this, p.id);
                    bindSaved();
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Keep", null)
                .show();
    }

    private void editSaved(SavedPassages.Passage p) {
        final EditText input = new EditText(this);
        input.setText(p.note);
        input.setHint("Note");
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);

        new AlertDialog.Builder(this)
                .setTitle(p.citation)
                .setView(wrap)
                .setPositiveButton("Save note", (d, w) -> {
                    SavedPassages.setNote(this, p.id, input.getText().toString());
                    bindSaved();
                })
                .setNeutralButton("Remove", (d, w) -> {
                    SavedPassages.remove(this, p.id);
                    bindSaved();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void copyAllSaved() {
        ClipboardManager cb = getSystemService(ClipboardManager.class);
        if (cb == null) return;
        cb.setPrimaryClip(ClipData.newPlainText("Saved passages",
                SavedPassages.asPlainText(this)));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ text tab

    /** Show the Text day you're up to — wherever you left off, whenever that was. */
    private void bindTextDay() {
        TextDay day = TextDays.current(this);
        if (day == null) {
            // No Text content shipped in this build; hide the tab entirely
            // rather than showing an empty screen.
            findViewById(R.id.tabText).setVisibility(View.GONE);
            return;
        }

        ((TextView) findViewById(R.id.tvTextTitle)).setText(day.shortTitle());

        TextView citation = findViewById(R.id.tvTextCitation);
        citation.setText(day.citation());
        citation.setVisibility(day.citation().isEmpty() ? View.GONE : View.VISIBLE);

        ((TextView) findViewById(R.id.tvTextBody)).setText(asHtml(day.body));

        // "Next" moves you on and marks this session read. When there's no
        // further session yet, dim it the same way Previous dims at Day 1.
        View next = findViewById(R.id.btnTextNext);
        boolean hasNext = TextDays.hasNext(this);
        next.setEnabled(hasNext);
        next.setAlpha(hasNext ? 1f : 0.4f);

        View prev = findViewById(R.id.btnTextPrev);
        prev.setEnabled(TextDays.hasPrevious(this));
        prev.setAlpha(TextDays.hasPrevious(this) ? 1f : 0.4f);

        if (day.number != boundTextDay) {
            boundTextDay = day.number;
            resetPlayer(textWebView, R.id.textVideoBox);
            // No "Watch this session" link — the frame is preloaded in place, so
            // the player is simply there, ready for you to press play.
            if (day.video != null && !day.video.isEmpty()) {
                showVideoFrame(textWebView, 0, R.id.textVideoBox, day.video);
            }
        }
    }

    private void markTextRead() {
        if (TextDays.advance(this)) {
            refreshWithFade(findViewById(R.id.textContent), () -> {
                bindTextDay();
                findViewById(R.id.textScroll).scrollTo(0, 0);
            });
        }
    }

    private void goToPreviousTextDay() {
        if (TextDays.goBack(this)) {
            refreshWithFade(findViewById(R.id.textContent), () -> {
                bindTextDay();
                findViewById(R.id.textScroll).scrollTo(0, 0);
            });
        }
    }

    /**
     * Jump straight to any session. With 400+ days, clicking through from
     * wherever you left off isn't an option — this opens the full list at your
     * current place, so you can go back to re-read or skip ahead freely.
     */
    private void showJumpToDayDialog() {
        final List<TextDay> all = TextDays.all(this);
        if (all.isEmpty()) return;

        final String[] labels = new String[all.size()];
        int checked = 0;
        int current = TextDays.currentNumber(this);
        for (int i = 0; i < all.size(); i++) {
            labels[i] = all.get(i).label;
            if (all.get(i).number == current) checked = i;
        }

        // setSingleChoiceItems opens the list scrolled to the checked row, which
        // is what makes this usable at this length.
        new AlertDialog.Builder(this)
                .setTitle("Jump to day")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    refreshWithFade(findViewById(R.id.textContent), () -> {
                        TextDays.setCurrent(this, all.get(which).number);
                        bindTextDay();
                        findViewById(R.id.textScroll).scrollTo(0, 0);
                    });
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // --------------------------------------------------------- meditation

    private void beginMeditation() {
        // The app is visible, so this foreground service starts with full
        // "while-in-use" capability and its audio is allowed to play.
        Intent i = new Intent(this, MeditationService.class)
                .setAction(MeditationService.ACTION_START);
        ContextCompat.startForegroundService(this, i);
        // The service persists this same end time; computing it here too (rather
        // than waiting on it) shows the countdown immediately with no lag.
        showMeditationActive(System.currentTimeMillis() + MeditationService.durationFor(this));
    }

    private void stopMeditation() {
        startService(new Intent(this, MeditationService.class)
                .setAction(MeditationService.ACTION_STOP));
        hideMeditationActive();
    }

    /** Mirror whatever the notification is currently showing — active or not. */
    private void refreshMeditationState() {
        long endAt = prefs().getLong(MeditationService.KEY_MEDITATION_END_AT, 0);
        if (endAt > System.currentTimeMillis()) {
            showMeditationActive(endAt);
        } else {
            hideMeditationActive();
        }
    }

    /** Show the same live countdown the notification has, in the app too. */
    private void showMeditationActive(long endTime) {
        // Begin dissolves into the running countdown rather than blinking over.
        if (chronoMeditation.getVisibility() != View.VISIBLE) {
            softFade(findViewById(R.id.content));
        }
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
        if (chronoMeditation.getVisibility() == View.VISIBLE) {
            softFade(findViewById(R.id.content));
        }
        chronoMeditation.stop();
        chronoMeditation.setVisibility(View.GONE);
        // Only bring Begin back if today actually has something to time.
        findViewById(R.id.btnBegin).setVisibility(
                Lessons.today(this).hasTimedPractice() ? View.VISIBLE : View.GONE);
    }

    // -------------------------------------------------------------- shared

    /**
     * Both bodies carry emphasis as HTML — the Workbook's italic Course
     * quotations, the Text's bold headings and sentence-number superscripts —
     * with paragraphs separated by blank lines.
     */
    private CharSequence asHtml(String body) {
        if (body == null) return "";
        String html = body.replace("\n\n", "<br><br>").replace("\n", "<br>");
        Spanned spanned = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT);

        // The sentence-number superscripts otherwise sit so high they stretch
        // the lines that carry one, leaving the body unevenly spaced. Swap the
        // stock SuperscriptSpan — which raises the digit half a full-size
        // ascent — for a gentler one that shrinks it and lifts it only a
        // little, so it stays within the line's own height and nothing spreads.
        SpannableStringBuilder out = new SpannableStringBuilder(spanned);
        for (SuperscriptSpan sup : out.getSpans(0, out.length(), SuperscriptSpan.class)) {
            int start = out.getSpanStart(sup);
            int end = out.getSpanEnd(sup);
            int flags = out.getSpanFlags(sup);
            out.removeSpan(sup);
            // Drop any size span the parser paired with it, so our own shrink is
            // the only one that applies rather than compounding to a speck.
            for (RelativeSizeSpan sz : out.getSpans(start, end, RelativeSizeSpan.class)) {
                out.removeSpan(sz);
            }
            out.setSpan(new GentleSuperscript(), start, end, flags);
        }
        return out;
    }

    /**
     * A superscript that doesn't stretch its line: it shrinks the digit and
     * lifts it a little, keeping its top within the surrounding text's ascent,
     * so every line stays the same height whether or not it carries a number.
     */
    private static class GentleSuperscript extends MetricAffectingSpan {
        @Override public void updateDrawState(TextPaint p) { apply(p); }
        @Override public void updateMeasureState(TextPaint p) { apply(p); }

        private void apply(TextPaint p) {
            // Lift by less than a third of the full-size ascent (ascent is
            // negative, so this shifts up), then shrink — small enough that the
            // raised digit still fits under the line's normal top.
            p.baselineShift += (int) (p.ascent() * 0.30f);
            p.setTextSize(p.getTextSize() * 0.6f);
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(OnboardingActivity.PREFS, MODE_PRIVATE);
    }

    @Override
    protected void onDestroy() {
        Playback.clearController(this);
        stopPlaybackService();
        mainHandler.removeCallbacksAndMessages(null);
        if (webView != null) webView.destroy();
        if (textWebView != null) textWebView.destroy();
        super.onDestroy();
    }
}
