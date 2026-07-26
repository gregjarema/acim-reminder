package com.acimreminder.app;

/**
 * One day of the ACIM Text reading. Loaded from assets/text_days.json by
 * {@link TextDays}.
 *
 * The Text side has no meditation timer — it's a reading, not a practice, so
 * a {@code TextDay} is deliberately thinner than a {@link Lesson}.
 */
public final class TextDay {

    public final int number;    // 1..N, the session number
    public final String label;  // "Day 158: Ch. 13, Section II (5-9)"
    public final String video;  // Marianne's session video (may be empty)
    public final String body;   // the reading, paragraphs split by blank lines

    public TextDay(int number, String label, String video, String body) {
        this.number = number;
        this.label = label;
        this.video = video;
        this.body = body;
    }

    /**
     * Marianne's labels are inconsistent about the separator — "Day 1: Ch. 1,
     * Introduction" but "Day 440 Ch. 22, Section III (5-6)" — so both forms are
     * split the same way: the leading "Day N" is the heading, the remainder is
     * the citation. Splitting on the colon alone left the no-colon labels whole,
     * which showed the citation twice.
     */
    private static final java.util.regex.Pattern LABEL_RE =
            java.util.regex.Pattern.compile("^\\s*(Day\\s+\\d+)\\s*[:\\-–]?\\s*(.*)$",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /** "Day 158" — the short form, for the screen's headline. */
    public String shortTitle() {
        java.util.regex.Matcher m = LABEL_RE.matcher(label);
        return m.matches() ? m.group(1).trim() : "Day " + number;
    }

    /** "Ch. 13, Section II (5-9)" — the citation, without repeating the day. */
    public String citation() {
        java.util.regex.Matcher m = LABEL_RE.matcher(label);
        return m.matches() ? m.group(2).trim() : "";
    }
}
