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
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
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

import java.util.List;

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

    private WebView webView;          // Workbook player
    private WebView textWebView;      // Text player
    /** Whichever player was last started, or null. Tracked explicitly because
     *  "listen only" hides the box, so visibility can't answer this. */
    private WebView activePlayer;
    private FrameLayout fullscreenContainer;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;

    private int boundLessonNumber = -1;
    private int boundTextDay = -1;
    private int selectedTab = TAB_WORKBOOK;

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
        findViewById(R.id.btnCopyAll).setOnClickListener(v -> copyAllSaved());

        enableSaving(R.id.tvBody, false);
        enableSaving(R.id.tvTextBody, true);

        chronoMeditation = findViewById(R.id.chronoMeditation);
        chronoMeditation.setOnClickListener(v -> stopMeditation());
        findViewById(R.id.btnBegin).setOnClickListener(v -> beginMeditation());

        findViewById(R.id.btnLessonJump).setOnClickListener(v -> showJumpToLessonDialog());
        findViewById(R.id.btnWallpaper).setOnClickListener(v -> chooseWallpaper());
        findViewById(R.id.btnListenOnly).setOnClickListener(
                v -> toggleListenOnly(R.id.videoBox, R.id.btnListenOnly));
        findViewById(R.id.btnTextListenOnly).setOnClickListener(
                v -> toggleListenOnly(R.id.textVideoBox, R.id.btnTextListenOnly));
        Playback.setController(this);

        findViewById(R.id.btnTextNext).setOnClickListener(v -> markTextRead());
        findViewById(R.id.btnTextPrev).setOnClickListener(v -> goToPreviousTextDay());
        findViewById(R.id.btnTextJump).setOnClickListener(v -> showJumpToDayDialog());

        bindToday();
        bindTextDay();
        refreshMeditationState();
        selectTab(prefs().getInt(KEY_TAB, TAB_WORKBOOK));

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
        }
    }

    // ---------------------------------------------------------------- tabs

    private void selectTab(int tab) {
        // If a build ships without Text content the tab is hidden, so never
        // restore onto it.
        if (tab == TAB_TEXT && findViewById(R.id.tabText).getVisibility() != View.VISIBLE) {
            tab = TAB_WORKBOOK;
        }
        selectedTab = tab;
        prefs().edit().putInt(KEY_TAB, tab).apply();

        findViewById(R.id.workbookScroll).setVisibility(vis(tab == TAB_WORKBOOK));
        findViewById(R.id.textScroll).setVisibility(vis(tab == TAB_TEXT));
        findViewById(R.id.savedScroll).setVisibility(vis(tab == TAB_SAVED));

        paintTab(R.id.tabWorkbookLabel, R.id.tabWorkbookUnderline, tab == TAB_WORKBOOK);
        paintTab(R.id.tabTextLabel, R.id.tabTextUnderline, tab == TAB_TEXT);
        paintTab(R.id.tabSavedLabel, R.id.tabSavedUnderline, tab == TAB_SAVED);

        // Leaving a tab stops whatever was playing in it, so switching away
        // doesn't leave Marianne talking from a hidden pane.
        if (tab != TAB_WORKBOOK) pausePlayer(webView);
        if (tab != TAB_TEXT) pausePlayer(textWebView);

        if (tab == TAB_SAVED) bindSaved();
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

    /** Put both players away and show their "Watch" links again. */
    private void collapsePlayers() {
        activePlayer = null;
        resetPlayer(webView, R.id.btnVideo, R.id.videoBox);
        resetPlayer(textWebView, R.id.btnTextVideo, R.id.textVideoBox);
        findViewById(R.id.btnVideo).setVisibility(
                Lessons.today(this).video.isEmpty() ? View.GONE : View.VISIBLE);
        TextDay d = TextDays.current(this);
        findViewById(R.id.btnTextVideo).setVisibility(
                d == null || d.video.isEmpty() ? View.GONE : View.VISIBLE);
        findViewById(R.id.btnListenOnly).setVisibility(View.GONE);
        findViewById(R.id.btnTextListenOnly).setVisibility(View.GONE);
    }

    /**
     * Hide the picture but keep the sound. There's no audio-only stream to
     * switch to — the video plays inside Vimeo's page — so "listen only" means
     * shrinking the player away while it carries on. The box is left INVISIBLE
     * at 1px rather than GONE: a WebView with no size stops playing.
     */
    private void toggleListenOnly(int boxId, int toggleId) {
        View box = findViewById(boxId);
        TextView toggle = findViewById(toggleId);
        boolean hiding = box.getVisibility() == View.VISIBLE;
        box.setVisibility(hiding ? View.INVISIBLE : View.VISIBLE);
        ViewGroup.LayoutParams lp = box.getLayoutParams();
        lp.height = hiding ? 1 : Math.round(320 * getResources().getDisplayMetrics().density);
        box.setLayoutParams(lp);
        toggle.setText(hiding ? "Show video" : "Listen only (hide video)");
    }

    // ------------------------------------------------------------- players

    private WebView setUpVideoPlayer(int webViewId, int progressId) {
        WebView view = findViewById(webViewId);
        ProgressBar progress = findViewById(progressId);

        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        view.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                // The watch page arrives with Vimeo's header, sign-in bar and
                // title around the player. Strip them so the box frames the
                // video. Re-run a couple of times: the page finishes loading
                // before its own scripts finish laying the player out.
                frameThePlayer(v);
                mainHandler.postDelayed(() -> frameThePlayer(v), 900);
                mainHandler.postDelayed(() -> frameThePlayer(v), 2200);
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
    private void playInline(WebView player, int linkId, int boxId, String url) {
        try {
            findViewById(linkId).setVisibility(View.GONE);
            findViewById(boxId).setVisibility(View.VISIBLE);
            player.onResume();
            player.loadUrl(url);

            // Media buttons, lock-screen controls and background audio all hang
            // off the foreground service; without it Android may silence us the
            // moment the app leaves the screen.
            activePlayer = player;
            boolean isText = player == textWebView;
            findViewById(isText ? R.id.btnTextListenOnly : R.id.btnListenOnly)
                    .setVisibility(View.VISIBLE);
            TextDay day = TextDays.current(this);
            startPlaybackService(isText
                    ? (day == null ? "" : day.shortTitle())
                    : Lessons.today(this).title);
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't play the video.", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Trim the watch page down to just the player.
     *
     * We can't use Vimeo's embed URL — these videos are whitelisted to specific
     * domains, and the embed refuses to play anywhere else — so the plain watch
     * page it is, chrome and all. This walks up from the player element and
     * hides every sibling on the way to <body>, which leaves the player's own
     * ancestor chain and nothing else. That's deliberately structural rather
     * than a list of Vimeo class names, which would rot the moment they ship a
     * redesign. If the player can't be found, nothing is hidden and you get the
     * ordinary page — no worse than before.
     */
    private void frameThePlayer(WebView v) {
        v.evaluateJavascript(
                "(function(){try{"
                + "var p=document.querySelector('video')"
                + "||document.querySelector('iframe[src*=\"player.vimeo.com\"]')"
                + "||document.querySelector('[class*=\"player\"]');"
                + "if(!p)return;"
                + "var n=p.tagName==='VIDEO'?(p.closest('div')||p):p;"
                + "while(n&&n!==document.body){var par=n.parentNode;if(par){"
                + "Array.prototype.forEach.call(par.children,function(s){"
                + "if(s!==n)s.style.display='none';});}n=par;}"
                + "document.body.style.margin='0';"
                + "document.documentElement.style.background='#000';"
                + "window.scrollTo(0,0);"
                + "}catch(e){}})();", null);
    }

    /** Collapse a player back to its "Watch" link and stop it loading. */
    private void resetPlayer(WebView player, int linkId, int boxId) {
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
            resetPlayer(webView, R.id.btnVideo, R.id.videoBox);

            View videoLink = findViewById(R.id.btnVideo);
            if (today.video == null || today.video.isEmpty()) {
                videoLink.setVisibility(View.GONE);
            } else {
                videoLink.setVisibility(View.VISIBLE);
                videoLink.setOnClickListener(v ->
                        playInline(webView, R.id.btnVideo, R.id.videoBox, today.video));
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
        findViewById(R.id.btnCopyAll).setVisibility(vis(!all.isEmpty()));
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
        t.setTextSize(14);
        t.setTextColor(SELECTED);
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

        // "Mark read" is the primary action; at the end of what's transcribed
        // so far, say so instead of offering a day that doesn't exist yet.
        TextView next = findViewById(R.id.btnTextNext);
        boolean hasNext = TextDays.hasNext(this);
        next.setEnabled(hasNext);
        next.setText(hasNext
                ? "Mark read — on to Day " + (day.number + 1)
                : "Day " + day.number + " — the latest session so far");

        View prev = findViewById(R.id.btnTextPrev);
        prev.setEnabled(TextDays.hasPrevious(this));
        prev.setAlpha(TextDays.hasPrevious(this) ? 1f : 0.4f);

        if (day.number != boundTextDay) {
            boundTextDay = day.number;
            resetPlayer(textWebView, R.id.btnTextVideo, R.id.textVideoBox);

            View videoLink = findViewById(R.id.btnTextVideo);
            if (day.video == null || day.video.isEmpty()) {
                videoLink.setVisibility(View.GONE);
            } else {
                videoLink.setVisibility(View.VISIBLE);
                videoLink.setOnClickListener(v ->
                        playInline(textWebView, R.id.btnTextVideo, R.id.textVideoBox, day.video));
            }
        }
    }

    private void markTextRead() {
        if (TextDays.advance(this)) {
            bindTextDay();
            findViewById(R.id.textScroll).scrollTo(0, 0);
        }
    }

    private void goToPreviousTextDay() {
        if (TextDays.goBack(this)) {
            bindTextDay();
            findViewById(R.id.textScroll).scrollTo(0, 0);
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
                    TextDays.setCurrent(this, all.get(which).number);
                    bindTextDay();
                    findViewById(R.id.textScroll).scrollTo(0, 0);
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
        return Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT);
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
