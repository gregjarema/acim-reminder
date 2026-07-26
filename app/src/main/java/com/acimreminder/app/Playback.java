package com.acimreminder.app;

import java.lang.ref.WeakReference;

/**
 * The bridge between the media controls and the video actually playing.
 *
 * The video lives in a WebView inside the activity, but the things that want to
 * control it — headphone buttons, the lock screen, the notification — arrive at
 * a service. Rather than move playback out of the WebView (which isn't possible;
 * these videos only play on Vimeo's own page), the service reaches back to the
 * activity through this.
 *
 * The reference is weak deliberately: the activity can be destroyed while the
 * service is still finishing up, and holding it strongly would leak the whole
 * view hierarchy.
 */
public final class Playback {

    /** Implemented by whoever is actually showing the video. */
    public interface Controller {
        void playbackPlay();
        void playbackPause();
        void playbackStop();
    }

    private static WeakReference<Controller> controller = new WeakReference<>(null);

    /** What's playing, for the notification: "Lesson 104" / "Day 158". */
    private static String title = "";

    public static void setController(Controller c) {
        controller = new WeakReference<>(c);
    }

    public static void clearController(Controller c) {
        if (controller.get() == c) controller = new WeakReference<>(null);
    }

    public static Controller controller() {
        return controller.get();
    }

    public static void setTitle(String t) {
        title = t == null ? "" : t;
    }

    public static String title() {
        return title;
    }

    private Playback() {}
}
