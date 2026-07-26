package com.acimreminder.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loads the ACIM Text days from assets/text_days.json and tracks which one
 * you're up to.
 *
 * Unlike the Workbook — which is pinned to the calendar, one lesson per day —
 * the Text advances only when you say so. Fall a week behind and it waits for
 * you; read three in one sitting and it moves three. The whole schedule is
 * therefore a single stored number: the day you're currently on.
 *
 * The list is built from Marianne's authoritative per-day pages by
 * tools/build_text_days.py.
 */
public final class TextDays {

    /** The day you're currently on. Absent until you first move off day 1. */
    private static final String KEY_CURRENT = "text_current_day";

    private static List<TextDay> cache;

    public static synchronized List<TextDay> all(Context ctx) {
        if (cache != null) return cache;

        List<TextDay> list = new ArrayList<>();
        try (InputStream in = ctx.getAssets().open("text_days.json")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            JSONArray arr = new JSONArray(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new TextDay(
                        o.getInt("n"),
                        o.optString("label", ""),
                        o.optString("video", ""),
                        o.optString("body", "")));
            }
        } catch (Exception e) {
            // Never crash over content; an empty list is handled by callers.
        }
        Collections.sort(list, (a, b) -> Integer.compare(a.number, b.number));
        cache = list;
        return cache;
    }

    /** How many days we shipped. Grows as more sessions are transcribed. */
    public static int count(Context ctx) {
        return all(ctx).size();
    }

    /** The day you're up to, clamped to what's actually in the app. */
    public static int currentNumber(Context ctx) {
        int stored = prefs(ctx).getInt(KEY_CURRENT, 1);
        return clamp(ctx, stored);
    }

    /** The day you're up to, or null if no Text content shipped at all. */
    public static TextDay current(Context ctx) {
        return byNumber(ctx, currentNumber(ctx));
    }

    public static TextDay byNumber(Context ctx, int number) {
        for (TextDay d : all(ctx)) {
            if (d.number == number) return d;
        }
        return null;
    }

    /** Move to a specific day, clamped to the available range. */
    public static void setCurrent(Context ctx, int number) {
        prefs(ctx).edit().putInt(KEY_CURRENT, clamp(ctx, number)).apply();
    }

    public static boolean hasNext(Context ctx) {
        return currentNumber(ctx) < maxNumber(ctx);
    }

    public static boolean hasPrevious(Context ctx) {
        return currentNumber(ctx) > minNumber(ctx);
    }

    /**
     * Mark the current day read and move on. Returns false at the end of what
     * we have — the last transcribed session — so the caller can say so rather
     * than silently doing nothing.
     */
    public static boolean advance(Context ctx) {
        if (!hasNext(ctx)) return false;
        setCurrent(ctx, currentNumber(ctx) + 1);
        return true;
    }

    public static boolean goBack(Context ctx) {
        if (!hasPrevious(ctx)) return false;
        setCurrent(ctx, currentNumber(ctx) - 1);
        return true;
    }

    private static int minNumber(Context ctx) {
        List<TextDay> all = all(ctx);
        return all.isEmpty() ? 1 : all.get(0).number;
    }

    private static int maxNumber(Context ctx) {
        List<TextDay> all = all(ctx);
        return all.isEmpty() ? 1 : all.get(all.size() - 1).number;
    }

    /**
     * Keep the stored day inside the range we actually have. This matters as
     * new sessions land: the stored number is never lost, only bounded, so a
     * day that's out of range today becomes reachable once it ships.
     */
    private static int clamp(Context ctx, int number) {
        if (all(ctx).isEmpty()) return 1;
        return Math.max(minNumber(ctx), Math.min(maxNumber(ctx), number));
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(OnboardingActivity.PREFS, Context.MODE_PRIVATE);
    }

    private TextDays() {}
}
