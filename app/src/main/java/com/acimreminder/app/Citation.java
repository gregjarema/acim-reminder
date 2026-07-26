package com.acimreminder.app;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out the Course citation for a passage you've saved — "T-13.II.5" for
 * the Text, "W-104.3" for a workbook lesson — so saved passages can be sorted
 * and found the way the Course itself is referenced.
 *
 * The chapter and section come from the day's label ("Ch. 13, Section II
 * (5-9)"). The paragraph is recovered from the reading itself: each paragraph
 * is rendered with its own number in front of it, so scanning back from where
 * your selection starts to the nearest paragraph marker gives the paragraph
 * you were actually in.
 */
public final class Citation {

    /** "Ch. 13, Section II (5-9)" and the looser "Ch 15, Section I (1-3)". */
    private static final Pattern CHAPTER_RE =
            Pattern.compile("Ch\\.?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECTION_RE =
            Pattern.compile("Section\\s+([IVXLC]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INTRO_RE =
            Pattern.compile("Introduction", Pattern.CASE_INSENSITIVE);

    /** A paragraph marker at the start of a line: "5. In the calm light..." */
    private static final Pattern PARAGRAPH_RE =
            Pattern.compile("(?:^|\\n)\\s*(\\d{1,3})\\.\\s");

    /**
     * The citation for a selection inside a Text day.
     *
     * @param label      the day's label, e.g. "Day 158: Ch. 13, Section II (5-9)"
     * @param rendered   the reading as displayed
     * @param selStart   where the selection begins in {@code rendered}
     */
    public static String forTextDay(String label, CharSequence rendered, int selStart) {
        String chapter = firstGroup(CHAPTER_RE, label);
        String section = firstGroup(SECTION_RE, label);
        if (section == null && INTRO_RE.matcher(label == null ? "" : label).find()) {
            section = "in";                       // the Course's own "T-1.in"
        }

        StringBuilder sb = new StringBuilder("T-");
        sb.append(chapter == null ? "?" : chapter);
        if (section != null) sb.append('.').append(section);

        String para = paragraphAt(rendered, selStart);
        if (para != null) sb.append('.').append(para);
        return sb.toString();
    }

    /** The citation for a selection inside a workbook lesson: "W-104.3". */
    public static String forLesson(int lessonNumber, CharSequence rendered, int selStart) {
        String para = paragraphAt(rendered, selStart);
        return "W-" + lessonNumber + (para == null ? "" : "." + para);
    }

    /**
     * The number of the paragraph the offset falls inside — the last paragraph
     * marker at or before it. Null when the text carries no markers, which is
     * the case for workbook lessons.
     */
    static String paragraphAt(CharSequence rendered, int offset) {
        if (rendered == null) return null;
        String s = rendered.toString();
        if (offset > s.length()) offset = s.length();

        String found = null;
        Matcher m = PARAGRAPH_RE.matcher(s);
        while (m.find()) {
            if (m.start() > offset) break;
            found = m.group(1);
        }
        return found;
    }

    private static String firstGroup(Pattern p, String s) {
        if (s == null) return null;
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /**
     * A sort key that orders citations the way the Course runs, rather than
     * alphabetically — otherwise T-10 sorts before T-2, and the workbook
     * interleaves with the Text. Text first, then chapter, section, paragraph.
     */
    public static String sortKey(String citation) {
        if (citation == null) return "";
        boolean workbook = citation.startsWith("W");
        StringBuilder out = new StringBuilder(workbook ? "1" : "0");
        Matcher m = Pattern.compile("(\\d+|[IVXLC]+|in)").matcher(citation);
        while (m.find()) {
            String part = m.group(1);
            int value = part.matches("\\d+") ? Integer.parseInt(part)
                    : "in".equals(part) ? 0 : roman(part);
            out.append(String.format("%05d", value));
        }
        return out.toString();
    }

    private static int roman(String s) {
        int total = 0, prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int v = switch (s.charAt(i)) {
                case 'I' -> 1; case 'V' -> 5; case 'X' -> 10;
                case 'L' -> 50; case 'C' -> 100; default -> 0;
            };
            total += v < prev ? -v : v;
            prev = Math.max(prev, v);
        }
        return total;
    }

    private Citation() {}
}
