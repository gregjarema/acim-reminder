package com.acimreminder.app;

/**
 * One workbook lesson. Loaded from assets/lessons.json by {@link Lessons}.
 */
public final class Lesson {

    public final int number;
    public final String title;   // "Lesson 100"
    public final String phrase;  // the one-line idea
    public final String video;   // Marianne video URL (may be empty)
    public final String body;    // full lesson text, paragraphs split by blank lines

    public Lesson(int number, String title, String phrase, String video, String body) {
        this.number = number;
        this.title = title;
        this.phrase = phrase;
        this.video = video;
        this.body = body;
    }
}
