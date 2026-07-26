package com.acimreminder.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The passages you've marked, with any note you added.
 *
 * Stored as JSON in the app's own preferences rather than a database: this is a
 * personal collection of a few hundred lines at most, it must survive a
 * reinstall of the content, and keeping it in one string means the whole
 * collection can be exported by copying it.
 *
 * Crucially, a saved passage keeps its own copy of the text. Anchoring into the
 * lesson by offset would break the moment the readings are rebuilt from source
 * — which happens every time more Text days are transcribed.
 */
public final class SavedPassages {

    private static final String KEY = "saved_passages";

    /** One marked passage. */
    public static final class Passage {
        public final long id;           // creation time, also the sort tiebreak
        public final String citation;   // "T-13.II.5" / "W-104.3"
        public final String source;     // "Day 158" / "Lesson 104"
        public final String text;       // the passage itself
        public final String note;       // your note, or ""

        Passage(long id, String citation, String source, String text, String note) {
            this.id = id;
            this.citation = citation;
            this.source = source;
            this.text = text;
            this.note = note;
        }
    }

    public static List<Passage> all(Context ctx) {
        List<Passage> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Passage(
                        o.optLong("id"),
                        o.optString("citation", ""),
                        o.optString("source", ""),
                        o.optString("text", ""),
                        o.optString("note", "")));
            }
        } catch (Exception ignored) {
            // A corrupt store shouldn't cost you the app; you just see none.
        }
        // Course order, not the order you happened to save them in.
        Collections.sort(out, (a, b) -> {
            int c = Citation.sortKey(a.citation).compareTo(Citation.sortKey(b.citation));
            return c != 0 ? c : Long.compare(a.id, b.id);
        });
        return out;
    }

    public static void add(Context ctx, String citation, String source,
                           String text, String note) {
        List<Passage> list = all(ctx);
        list.add(new Passage(System.currentTimeMillis(), citation, source,
                text == null ? "" : text.trim(), note == null ? "" : note.trim()));
        write(ctx, list);
    }

    public static void remove(Context ctx, long id) {
        List<Passage> list = all(ctx);
        list.removeIf(p -> p.id == id);
        write(ctx, list);
    }

    public static void setNote(Context ctx, long id, String note) {
        List<Passage> list = all(ctx);
        for (int i = 0; i < list.size(); i++) {
            Passage p = list.get(i);
            if (p.id == id) {
                list.set(i, new Passage(p.id, p.citation, p.source, p.text,
                        note == null ? "" : note.trim()));
                break;
            }
        }
        write(ctx, list);
    }

    public static int count(Context ctx) {
        return all(ctx).size();
    }

    /** Everything as plain text, for copying out of the app. */
    public static String asPlainText(Context ctx) {
        StringBuilder sb = new StringBuilder();
        for (Passage p : all(ctx)) {
            sb.append(p.citation).append("  (").append(p.source).append(")\n");
            sb.append(p.text).append('\n');
            if (!p.note.isEmpty()) sb.append("— ").append(p.note).append('\n');
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static void write(Context ctx, List<Passage> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Passage p : list) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("citation", p.citation);
                o.put("source", p.source);
                o.put("text", p.text);
                o.put("note", p.note);
                arr.put(o);
            }
            prefs(ctx).edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(OnboardingActivity.PREFS, Context.MODE_PRIVATE);
    }

    private SavedPassages() {}
}
