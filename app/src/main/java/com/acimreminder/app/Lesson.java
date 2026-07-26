package com.acimreminder.app;

/**
 * One workbook lesson. Loaded from assets/lessons.json by {@link Lessons}.
 *
 * Besides the text, each lesson carries the practice the workbook actually
 * prescribes for that day — how long to sit, and how often — worked out by
 * tools/parse_practice.py. It varies constantly, and some days ask for no
 * sitting at all.
 */
public final class Lesson {

    /** How often to be reminded. */
    public static final String KIND_HOURLY = "hourly";     // once an hour
    public static final String KIND_INTERVAL = "interval"; // every N minutes
    public static final String KIND_COUNT = "count";       // N times across the day

    public final int number;
    public final String title;      // "Lesson 100"
    public final String phrase;     // the one-line idea
    public final String meditation; // fuller 1-2 line form of the idea, or ""
    public final String video;      // Marianne video URL (may be empty)
    public final String body;       // full lesson text, paragraphs split by blank lines

    /** Minutes to sit for. <b>0 means no timed practice</b> — just be reminded. */
    public final int practiceMinutes;
    /** One of {@link #KIND_HOURLY}, {@link #KIND_INTERVAL}, {@link #KIND_COUNT}. */
    public final String practiceKind;
    /** Minutes between reminders for INTERVAL; number of reminders for COUNT. */
    public final int practiceValue;
    /**
     * Whether the lesson <em>also</em> asks to be recalled every hour, on top of
     * its sittings. The workbook often prescribes both — "the time you give
     * morning and evening... and the hourly remembrances you make throughout
     * the day" — so these are two tracks, not one.
     */
    public final boolean hourlyRemembrance;
    /** The lines to hold during practice — the reminder's subtext. */
    public final String meditationText;

    public Lesson(int number, String title, String phrase, String meditation,
                  String video, String body,
                  int practiceMinutes, String practiceKind, int practiceValue,
                  boolean hourlyRemembrance, String meditationText) {
        this.number = number;
        this.title = title;
        this.phrase = phrase;
        this.meditation = meditation;
        this.video = video;
        this.body = body;
        this.practiceMinutes = practiceMinutes;
        this.practiceKind = practiceKind;
        this.practiceValue = practiceValue;
        this.hourlyRemembrance = hourlyRemembrance;
        this.meditationText = meditationText;
    }

    /** True when today asks you to sit for a while, rather than just remember. */
    public boolean hasTimedPractice() {
        return practiceMinutes > 0;
    }

    public long practiceMillis() {
        return practiceMinutes * 60_000L;
    }

    /** "15-minute practice" / "5-minute practice" — for the Begin button. */
    public String practiceLabel() {
        return practiceMinutes + "-minute";
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
