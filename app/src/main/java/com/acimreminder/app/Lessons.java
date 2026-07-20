package com.acimreminder.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loads all lessons from assets/lessons.json and maps calendar dates to lessons.
 *
 * The per-day schedule: Lesson {@link #DAY_ONE_NUMBER} falls on {@link #DAY_ONE},
 * and each following calendar day advances by one lesson. Before the first day
 * or after the last available lesson, we clamp to the nearest end (so the app
 * always shows something sensible instead of crashing or going blank).
 *
 * To shift the whole schedule by a day, change DAY_ONE. To add lessons, drop
 * more HTML into lessons_source/ and rerun tools/build_lessons.py.
 */
public final class Lessons {

    /** The calendar day on which the schedule starts... */
    public static final LocalDate DAY_ONE = LocalDate.of(2026, 7, 20);
    /** ...and the lesson number shown on that day. */
    public static final int DAY_ONE_NUMBER = 99;

    private static List<Lesson> cache;

    public static synchronized List<Lesson> all(Context ctx) {
        if (cache != null) return cache;

        List<Lesson> list = new ArrayList<>();
        try (InputStream in = ctx.getAssets().open("lessons.json")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            JSONArray arr = new JSONArray(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new Lesson(
                        o.getInt("number"),
                        o.getString("title"),
                        o.getString("phrase"),
                        o.optString("video", ""),
                        o.getString("body")));
            }
        } catch (Exception e) {
            // Never crash over content; an empty list is handled by callers.
        }
        Collections.sort(list, (a, b) -> Integer.compare(a.number, b.number));
        cache = list;
        return cache;
    }

    /** The lesson for today's date. */
    public static Lesson today(Context ctx) {
        return forDate(ctx, LocalDate.now());
    }

    public static Lesson forDate(Context ctx, LocalDate date) {
        List<Lesson> all = all(ctx);
        if (all.isEmpty()) {
            return new Lesson(DAY_ONE_NUMBER, "Lesson " + DAY_ONE_NUMBER, "", "", "");
        }
        long offset = ChronoUnit.DAYS.between(DAY_ONE, date);
        int target = (int) (DAY_ONE_NUMBER + offset);

        int first = all.get(0).number;
        int last = all.get(all.size() - 1).number;
        if (target < first) target = first;
        if (target > last) target = last;

        for (Lesson l : all) {
            if (l.number == target) return l;
        }
        return all.get(0);
    }

    private Lessons() {}
}
