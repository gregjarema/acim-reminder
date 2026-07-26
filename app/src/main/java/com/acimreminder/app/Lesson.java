package com.acimreminder.app;

/**
 * One workbook lesson. Loaded from assets/lessons.json by {@link Lessons}.
 */
public final class Lesson {

    public final int number;
    public final String title;      // "Lesson 100"
    public final String phrase;     // the one-line idea
    public final String meditation; // fuller 1-2 line form of the idea, or "" — see tools/meditation.py
    public final String video;      // Marianne video URL (may be empty)
    public final String body;       // full lesson text, paragraphs split by blank lines

    public Lesson(int number, String title, String phrase, String meditation,
                  String video, String body) {
        this.number = number;
        this.title = title;
        this.phrase = phrase;
        this.meditation = meditation;
        this.video = video;
        this.body = body;
    }

    /**
     * The idea to show — the fuller {@link #meditation} form when we have one,
     * otherwise the one-line {@link #phrase}. Trailing whitespace is trimmed and
     * a stray double period ("truth.." -> "truth.") is tidied, while a genuine
     * ellipsis is left alone. May contain a single '\n' between two verse lines.
     */
    public String ideaText() {
        String s = (meditation != null && !meditation.isEmpty()) ? meditation : phrase;
        if (s == null) return "";
        s = s.trim().replaceAll("(?<!\\.)\\.\\.$", ".");
        return s;
    }

    /** The first line of {@link #ideaText()} — the notification headline. */
    public String ideaHeadline() {
        String s = ideaText();
        int nl = s.indexOf('\n');
        return nl >= 0 ? s.substring(0, nl) : s;
    }

    /** Any lines after the first (empty when the idea is a single line). */
    public String ideaRest() {
        String s = ideaText();
        int nl = s.indexOf('\n');
        return nl >= 0 ? s.substring(nl + 1) : "";
    }
}
