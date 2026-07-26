package com.acimreminder.app;

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

    private static final int BACKGROUND = 0xFFF4F1EC;  // the app's cream
    private static final int INK = 0xFF1A1A1A;
    private static final int ACCENT = 0xFF7A6646;
    private static final int FAINT = 0xFFA08A63;

    @Override
    public Engine onCreateEngine() {
        return new LessonEngine();
    }

    private class LessonEngine extends Engine {

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable midnightRedraw = this::draw;
        private boolean visible;
        private int width, height;

        @Override
        public void onVisibilityChanged(boolean nowVisible) {
            visible = nowVisible;
            if (visible) {
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
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            visible = false;
            handler.removeCallbacks(midnightRedraw);
        }

        @Override
        public void onDestroy() {
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
            canvas.drawColor(BACKGROUND);

            Lesson lesson = Lessons.forDate(LessonWallpaperService.this, LocalDate.now());
            float density = getResources().getDisplayMetrics().density;
            int margin = Math.round(36 * density);
            int textWidth = Math.max(1, width - margin * 2);

            // "LESSON 104" — a quiet eyebrow above the idea itself.
            TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            label.setColor(FAINT);
            label.setTextSize(13 * density);
            label.setLetterSpacing(0.16f);
            label.setTypeface(Typeface.DEFAULT);

            // The idea, which is the point of the whole thing.
            TextPaint idea = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            idea.setColor(INK);
            idea.setTextSize(28 * density);
            idea.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));

            StaticLayout labelLayout = build(
                    lesson.title.toUpperCase(), label, textWidth);
            StaticLayout ideaLayout = build(lesson.ideaText(), idea, textWidth);

            // Sit the block slightly above centre: the lower half of a home
            // screen is where icons and the dock live.
            int gap = Math.round(18 * density);
            int block = labelLayout.getHeight() + gap + ideaLayout.getHeight();
            float top = Math.max(margin, (height - block) * 0.38f);

            canvas.save();
            canvas.translate(margin, top);
            labelLayout.draw(canvas);
            canvas.translate(0, labelLayout.getHeight() + gap);
            ideaLayout.draw(canvas);
            canvas.restore();

            // A small rule under the idea, echoing the app's dividers.
            Paint rule = new Paint(Paint.ANTI_ALIAS_FLAG);
            rule.setColor(ACCENT);
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
