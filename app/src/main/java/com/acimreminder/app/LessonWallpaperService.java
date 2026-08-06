package com.acimreminder.app;

import android.app.WallpaperColors;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.SurfaceHolder;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/**
 * A live wallpaper showing today's lesson, so the idea is in front of you
 * whenever you unlock the phone — no notification required.
 *
 * It draws once and then sits idle: there's no animation and nothing to tick,
 * so it costs nothing between redraws. It repaints when the wallpaper becomes
 * visible (which catches a day rollover the moment you look at the phone) and
 * schedules one redraw at midnight for the case where you're staring at the
 * home screen as the day turns.
 */
public class LessonWallpaperService extends WallpaperService {

    // Two palettes. Light is the app's cream and ink; dark keeps the warm cream
    // text and accents but sits them on pure black, which reads cleanest on an
    // OLED lock screen and gives the phone's white system text the deepest
    // ground to stand on.
    private static final int BACKGROUND = 0xFFF4F1EC;
    private static final int INK = 0xFF1A1A1A;
    private static final int ACCENT = 0xFF7A6646;
    private static final int FAINT = 0xFFA08A63;

    private static final int BACKGROUND_DARK = 0xFF000000;
    private static final int INK_DARK = 0xFFEDE6D9;
    private static final int ACCENT_DARK = 0xFFC2A878;
    private static final int FAINT_DARK = 0xFF9A8A6C;

    // Some phones only ever paint the lock screen's date, status bar and
    // notifications white, whatever a wallpaper tells them — the clock follows
    // the wallpaper but nothing around it does. There, the cream page leaves
    // that white text invisible by day. This preference forces the dark paper
    // regardless of the phone's own light/dark setting, so the white system
    // text has a dark ground under it the way it already does after sunset.
    // Set from the "Dark lock-screen wallpaper" item in the app's ⋯ menu.
    static final String PREFS = "acim";
    static final String KEY_FORCE_DARK = "wallpaper_force_dark";

    /** Whichever engine is on screen, so a theme change can repaint it. */
    private LessonEngine active;

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Catches the switch happening while you're looking at the home screen;
        // otherwise the repaint on visibility would only land next time you
        // returned to it.
        if (active != null) active.draw();
    }

    private boolean night() {
        int mode = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** The paper to draw: dark when the phone is dark, or when forced to. */
    private boolean dark() {
        return night()
                || getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getBoolean(KEY_FORCE_DARK, false);
    }

    @Override
    public Engine onCreateEngine() {
        return new LessonEngine();
    }

    private class LessonEngine extends Engine {

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable midnightRedraw = this::draw;
        private boolean visible;
        private int width, height;
        /** The palette the system was last told about; null until it's asked. */
        private Boolean toldDark;

        /**
         * What colour the lock screen and home screen should draw *their* text in.
         *
         * The system has no way to look at a live wallpaper and work this out —
         * it asks. Without an answer it assumes a dark wallpaper and keeps the
         * clock, date and notifications white, which on the daytime cream is
         * near invisible. This is the wallpaper's side of the same fix that
         * windowLightStatusBar makes inside the app.
         */
        @Override
        public WallpaperColors onComputeColors() {
            boolean dark = dark();
            toldDark = dark;
            int background = dark ? BACKGROUND_DARK : BACKGROUND;
            int accent = dark ? ACCENT_DARK : ACCENT;
            return new WallpaperColors(
                    // Primary and tertiary both anchor to the paper, the colour
                    // that fills all but a few lines of the screen; the accent
                    // is the one grace note. The old palette reported the serif
                    // INK — near-black on the light page, near-white on the dark
                    // one — as a third of itself, which reads as the opposite of
                    // the wallpaper it describes. The clock trusted the explicit
                    // hint below and went dark, but the status bar, date and
                    // notifications don't: a good few OEM skins judge those from
                    // the palette's own lightness, and a near-black third pulled
                    // that judgement dark, leaving their text white on the cream.
                    // Reporting a palette that matches the paper's own tone keeps
                    // the two ways a skin can decide from contradicting.
                    Color.valueOf(background),
                    Color.valueOf(accent),
                    Color.valueOf(background),
                    // Stated outright rather than left to be guessed from a
                    // bitmap: the sampling heuristic wants a nearly unbroken
                    // bright field, and a page of dark serif text over cream can
                    // fall the wrong side of it.
                    dark ? WallpaperColors.HINT_SUPPORTS_DARK_THEME
                         : WallpaperColors.HINT_SUPPORTS_DARK_TEXT);
        }

        @Override
        public void onVisibilityChanged(boolean nowVisible) {
            visible = nowVisible;
            if (visible) {
                active = this;
                draw();
            } else {
                handler.removeCallbacks(midnightRedraw);
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            width = w;
            height = h;
            draw();
        }

        @Override
        public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            // The system asks for a fresh frame when it's about to read the
            // wallpaper — including the snapshot some launchers and lock screens
            // sample to pick their text colour. Painting on demand means that
            // snapshot catches the actual cream page, not a surface we haven't
            // drawn into yet (which would sample black and turn their text white).
            draw();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            visible = false;
            if (active == this) active = null;
            handler.removeCallbacks(midnightRedraw);
        }

        @Override
        public void onDestroy() {
            if (active == this) active = null;
            handler.removeCallbacks(midnightRedraw);
            super.onDestroy();
        }

        private void draw() {
            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas();
                if (canvas != null) render(canvas);
            } catch (Exception ignored) {
                // A wallpaper that throws takes the launcher down with it.
            } finally {
                if (canvas != null) {
                    try { holder.unlockCanvasAndPost(canvas); } catch (Exception ignored) { }
                }
            }
            // The system caches the answer to onComputeColors, so a flip between
            // the light and dark palettes has to be announced or the lock screen
            // keeps the text colour it chose for the other one. This also carries
            // a change to the force-dark preference through on the next paint.
            if (toldDark == null || toldDark != dark()) notifyColorsChanged();
            scheduleMidnight();
        }

        /** One redraw as the date turns, but only while we're on screen. */
        private void scheduleMidnight() {
            handler.removeCallbacks(midnightRedraw);
            if (!visible) return;
            long untilMidnight = ChronoUnit.MILLIS.between(
                    LocalTime.now(), LocalTime.MAX) + 1000L;
            handler.postDelayed(midnightRedraw, Math.max(1000L, untilMidnight));
        }

        private void render(Canvas canvas) {
            boolean dark = dark();
            int background = dark ? BACKGROUND_DARK : BACKGROUND;
            int ink = dark ? INK_DARK : INK;
            int accent = dark ? ACCENT_DARK : ACCENT;
            int faint = dark ? FAINT_DARK : FAINT;
            canvas.drawColor(background);

            Lesson lesson = Lessons.forDate(LessonWallpaperService.this, LocalDate.now());
            float density = getResources().getDisplayMetrics().density;
            int margin = Math.round(36 * density);
            int textWidth = Math.max(1, width - margin * 2);

            // "LESSON 104" — a quiet eyebrow above the idea itself.
            TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            label.setColor(faint);
            label.setTextSize(13 * density);
            label.setLetterSpacing(0.16f);
            label.setTypeface(Typeface.DEFAULT);

            // The idea, which is the point of the whole thing.
            TextPaint idea = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            idea.setColor(ink);
            idea.setTextSize(28 * density);
            idea.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));

            StaticLayout labelLayout = build(
                    lesson.title.toUpperCase(), label, textWidth);
            StaticLayout ideaLayout = build(lesson.idea(), idea, textWidth);

            // The lock screen leaves exactly one clear band. Above it: the
            // clock, and the media player's card when something is playing.
            // Below it: the fingerprint reader, then the shortcuts. Too high
            // and the lesson is behind the media card; too low and it's behind
            // the fingerprint. Both have happened.
            //
            // So the BOTTOM of the block is anchored just above the reader,
            // and a longer idea grows upward into the gap rather than downward
            // into it. The ceiling keeps it out of the card overhead.
            int gap = Math.round(18 * density);
            int block = labelLayout.getHeight() + gap + ideaLayout.getHeight();
            float ruleGap = gap * 1.5f;
            float top = height * 0.64f - block - ruleGap;
            float highest = Math.max(margin, height * 0.42f);
            float lowest = height - margin - block - ruleGap;   // keep the rule on screen
            top = Math.max(highest, Math.min(top, lowest));

            canvas.save();
            canvas.translate(margin, top);
            labelLayout.draw(canvas);
            canvas.translate(0, labelLayout.getHeight() + gap);
            ideaLayout.draw(canvas);
            canvas.restore();

            // A small rule under the idea, echoing the app's dividers.
            Paint rule = new Paint(Paint.ANTI_ALIAS_FLAG);
            rule.setColor(accent);
            rule.setStrokeWidth(Math.max(1f, density));
            float ruleY = top + block + gap * 1.5f;
            canvas.drawLine(margin, ruleY, margin + 44 * density, ruleY, rule);
        }

        private StaticLayout build(String text, TextPaint paint, int width) {
            return StaticLayout.Builder
                    .obtain(text == null ? "" : text, 0, text == null ? 0 : text.length(),
                            paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.25f)
                    .setIncludePad(false)
                    .build();
        }
    }
}
