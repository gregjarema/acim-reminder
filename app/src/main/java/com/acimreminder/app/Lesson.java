package com.acimreminder.app;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * A review day (Review III, Lessons 111-120) reviews two lessons at once and
     * carries <b>two</b> one-line thoughts: one to use on the hour, the other on
     * the half hour. Empty on every ordinary lesson. When set, this lesson is a
     * review day — see {@link #isReview()} — and its practice regime differs:
     * a five-minute sitting morning and evening, with these two thoughts
     * alternating in the hourly and half-hourly remembrances between.
     */
    public final String hourIdea;
    public final String halfIdea;

    public Lesson(int number, String title, String phrase, String meditation,
                  String video, String body,
                  int practiceMinutes, String practiceKind, int practiceValue,
                  boolean hourlyRemembrance, String meditationText,
                  String hourIdea, String halfIdea) {
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
        this.hourIdea = hourIdea == null ? "" : hourIdea;
        this.halfIdea = halfIdea == null ? "" : halfIdea;
    }

    /** True on a Review III day, which carries two alternating thoughts. */
    public boolean isReview() {
        return !hourIdea.isEmpty() && !halfIdea.isEmpty();
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
     * <b>The lesson's own idea</b> — what the lesson is called, and the line
     * that belongs in the heading, on the wallpaper and at the top of a
     * reminder: "I seek but what belongs to me in truth."
     *
     * This is deliberately NOT {@link #meditation}. The two are different
     * things: Lesson 104's idea is that single line, while its meditation is
     * the fuller verse that continues "And joy and peace are my inheritance."
     * Showing the verse as the heading bled the practice text into the lesson's
     * own title.
     */
    public String idea() {
        // A Review III day has no single idea of its own — its "phrase" is only
        // the instruction "For morning and evening review:". It reviews two
        // lessons, so BOTH thoughts are its heading: the one used on the hour and
        // the one used on the half hour, a line each.
        if (isReview()) {
            return tidy(hourIdea) + "\n" + tidy(halfIdea);
        }
        // Review I and II days head the day the same way — a bare instruction
        // ("Our ideas for review today are:") with the ideas themselves listed
        // below. Show those ideas as the heading, a line each, rather than the
        // instruction, so the day's actual content isn't hidden in the body.
        // (Callers that need a single line — the jump-to-lesson list — flatten
        // the newlines themselves.)
        if (phrase != null && phrase.trim().endsWith(":")) {
            String ideas = reviewedIdeas();
            if (!ideas.isEmpty()) return ideas;
        }
        return tidy(phrase);
    }

    /** "(1) <i>...</i>" numbered idea lines on a Review I/II day, one per line. */
    private static final Pattern REVIEWED_IDEA =
            Pattern.compile("\\(\\d+\\)\\s*<i>(.*?)</i>", Pattern.DOTALL);

    private String reviewedIdeas() {
        if (body == null || body.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        Matcher m = REVIEWED_IDEA.matcher(body);
        while (m.find()) {
            String s = tidy(unescape(m.group(1).replaceAll("<[^>]+>", "")));
            if (!s.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(s);
            }
        }
        return sb.toString();
    }

    /** The few HTML entities the stored body uses, back to plain text. */
    private static String unescape(String s) {
        return s.replace("&#39;", "'").replace("&quot;", "\"")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    /** The thought to use on the hour — the on-the-hour half of a review day. */
    public String hourThought() {
        return tidy(hourIdea);
    }

    /** The thought to use on the half hour — a review day's second thought. */
    public String halfThought() {
        return tidy(halfIdea);
    }

    /**
     * The fuller form to hold during practice, falling back to the idea when a
     * lesson gives no separate verse. May contain '\n' between verse lines.
     */
    public String meditationText() {
        String s = meditationText != null && !meditationText.isEmpty()
                ? meditationText
                : (meditation != null && !meditation.isEmpty() ? meditation : phrase);
        return tidy(s);
    }

    /**
     * Trim, and tidy a stray double period ("truth.." -> "truth.") while
     * leaving a genuine ellipsis alone.
     */
    private static String tidy(String s) {
        if (s == null) return "";
        return s.trim().replaceAll("(?<!\\.)\\.\\.$", ".");
    }

    /**
     * The idea to show — kept for callers that want the fuller verse.
     * @deprecated prefer {@link #idea()} for headings and {@link #meditationText()}
     *             for practice text; this conflated the two.
     */
    @Deprecated
    public String ideaText() {
        String s = (meditation != null && !meditation.isEmpty()) ? meditation : phrase;
        return tidy(s);
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
