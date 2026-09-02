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
    /**
     * Minutes between those passing remembrances, when {@link #hourlyRemembrance}
     * is set. 60 — the default — is the usual "on the hour" cadence. A lesson
     * that asks to be recalled more often sets this smaller: Lesson 122's "each
     * quarter of an hour" is 15; Lesson 91's "five or six times an hour" is 10.
     * The scheduler snaps it to a supported step (10, 15, 20, 30 or 60).
     */
    public final int remembranceEveryMinutes;
    /** The lines to hold during practice — the reminder's subtext. */
    public final String meditationText;
    /**
     * The shorter verse meant for the <em>hourly</em> remembrance, when the
     * lesson gives one separately from {@link #meditationText}. The workbook
     * often gives two: a fuller form to open the timed sitting with, and a
     * second, usually briefer, restatement to carry through the day — see
     * {@link #remembranceText()}. Empty on most lessons, which give only the
     * one verse for both.
     */
    public final String remembranceText;

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
    /**
     * The constant theme held during the timed sitting on a Review IV day
     * (Lessons 141-150), shared by all ten days of that review — unlike
     * Review III, where {@link #hourIdea}/{@link #halfIdea} are themselves
     * the whole practice. Empty everywhere else, including Review III, where
     * {@link #idea()} shows the two alternating thoughts as the heading
     * instead of a separate theme.
     */
    public final String reviewTheme;

    public Lesson(int number, String title, String phrase, String meditation,
                  String video, String body,
                  int practiceMinutes, String practiceKind, int practiceValue,
                  boolean hourlyRemembrance, int remembranceEveryMinutes,
                  String meditationText, String remembranceText,
                  String hourIdea, String halfIdea, String reviewTheme) {
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
        // Default and normalise: a missing or nonsensical value is the ordinary
        // hourly cadence.
        this.remembranceEveryMinutes = remembranceEveryMinutes <= 0 ? 60 : remembranceEveryMinutes;
        this.meditationText = meditationText;
        this.remembranceText = remembranceText == null ? "" : remembranceText;
        this.hourIdea = hourIdea == null ? "" : hourIdea;
        this.halfIdea = halfIdea == null ? "" : halfIdea;
        this.reviewTheme = reviewTheme == null ? "" : reviewTheme;
    }

    /** True on a Review III day, which carries two alternating thoughts. */
    public boolean isReview() {
        return !hourIdea.isEmpty() && !halfIdea.isEmpty();
    }

    /** "(121)" style references to the lessons a review day revisits. */
    private static final Pattern NUMBERED_IDEA = Pattern.compile("\\(\\d+\\)");

    /**
     * A review day proper — one that revisits a set of already-given lessons,
     * listed as numbered ideas in its body. True for Reviews I–V (Lessons
     * 51–60, 81–90, 111–120, 141–150, 171–180); false for the single-idea days
     * of Review VI and for ordinary lessons. These days remind often, so their
     * reminders post under one id and replace each other rather than stack.
     */
    public boolean isReviewDay() {
        if (body == null || body.isEmpty()) return false;
        Matcher m = NUMBERED_IDEA.matcher(body);
        int n = 0;
        while (m.find()) {
            if (++n >= 2) return true;
        }
        return false;
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
        // A Review IV day (141-150) holds a single constant theme through the
        // sitting, shared by all ten days — that's the heading, not the two
        // ideas that alternate hour to hour beneath it.
        if (isReview() && !reviewTheme.isEmpty()) {
            return tidy(reviewTheme);
        }
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
     * lesson gives no separate verse. May contain '\n' between verse lines —
     * but only where a line actually ends a sentence; see {@link #flowThought}.
     */
    public String meditationText() {
        String s = meditationText != null && !meditationText.isEmpty()
                ? meditationText
                : (meditation != null && !meditation.isEmpty() ? meditation : phrase);
        return flowThought(tidy(s));
    }

    /**
     * The (usually shorter) verse for the <em>hourly</em> remembrance, falling
     * back to {@link #meditationText()} when the lesson gives no separate
     * one. The workbook commonly opens the timed sitting with a fuller form
     * of the idea and later gives a briefer restatement to carry through the
     * day between sittings — this is that second one, when {@link
     * #remembranceText} confidently identifies it.
     */
    public String remembranceText() {
        if (remembranceText == null || remembranceText.isEmpty()) return meditationText();
        return flowThought(tidy(remembranceText));
    }

    /**
     * The stored thought carries the original email's line wrapping as '\n', so
     * a flowing sentence gets chopped at arbitrary points ("...a different kind
     * of\nthought..."). Keep a break only where the line ends a sentence — a
     * real verse line, like "Forgiveness offers everything I want." — and join
     * the rest back into flowing text with a space.
     */
    private static String flowThought(String s) {
        if (s == null || s.indexOf('\n') < 0) return s;
        String[] lines = s.split("\n", -1);
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < lines.length; i++) {
            out.append(lines[i]);
            if (i == lines.length - 1) break;
            String t = lines[i].trim();
            char last = t.isEmpty() ? ' ' : t.charAt(t.length() - 1);
            out.append(last == '.' || last == '?' || last == '!' ? '\n' : ' ');
        }
        return out.toString();
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
