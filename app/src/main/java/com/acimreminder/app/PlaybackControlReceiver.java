package com.acimreminder.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

/**
 * The Play/Pause/Stop buttons on the playback notification.
 *
 * They have to do two things: move the video in the WebView, and tell
 * {@link PlaybackService} to redraw itself in the new state. A receiver keeps
 * that off the notification's PendingIntents, which can only target one thing.
 */
public class PlaybackControlReceiver extends BroadcastReceiver {

    static final String EXTRA_TOGGLE_VIDEO = "toggle_video";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        if (intent.getBooleanExtra(EXTRA_TOGGLE_VIDEO, false)) {
            Playback.Controller c = Playback.controller();
            if (c != null) {
                if (PlaybackService.ACTION_PAUSED.equals(action)) {
                    c.playbackPause();
                } else if (PlaybackService.ACTION_PLAYING.equals(action)) {
                    c.playbackPlay();
                }
            }
        } else if (PlaybackService.ACTION_STOP.equals(action)) {
            Playback.Controller c = Playback.controller();
            if (c != null) c.playbackStop();
        }

        ContextCompat.startForegroundService(ctx,
                new Intent(ctx, PlaybackService.class).setAction(action));
    }
}
