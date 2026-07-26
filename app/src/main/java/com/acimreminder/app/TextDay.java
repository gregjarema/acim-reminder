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

    /** "Day 158" — the short form, for the screen's headline. */
    public String shortTitle() {
        int colon = label.indexOf(':');
        String head = colon > 0 ? label.substring(0, colon) : label;
        return head.trim().isEmpty() ? "Day " + number : head.trim();
    }

    /**
     * "Ch. 13, Section II (5-9)" — the citation that follows the day number.
     * Marianne's labels are inconsistent about the colon ("Day 440 Ch. 22..."),
     * so fall back to splitting on the first space after the number.
     */
    public String citation() {
        int colon = label.indexOf(':');
        if (colon > 0) return label.substring(colon + 1).trim();
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("^Day\\s+\\d+\\s+(.*)$").matcher(label);
        return m.matches() ? m.group(1).trim() : "";
    }
}
