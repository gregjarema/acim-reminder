package com.acimreminder.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.IBinder;

/**
 * Keeps Marianne's video playing while you're elsewhere, and puts it under the
 * usual media controls — headphone buttons, the lock screen, the notification
 * shade.
 *
 * Two jobs:
 *
 * 1. A foreground service of type mediaPlayback. Without one, Android is free
 *    to stop the app making sound the moment it leaves the screen.
 * 2. A MediaSession. That's what makes the headphone pause button reach this
 *    app at all, and what draws the lock-screen controls. Its callbacks are
 *    forwarded through {@link Playback} to the WebView that's actually playing.
 *
 * The video itself stays in the WebView — these videos only play on Vimeo's own
 * page, so there's no stream to hand to a real media player.
 */
public class PlaybackService extends Service {

    public static final String ACTION_START = "com.acimreminder.app.PLAYBACK_START";
    public static final String ACTION_STOP = "com.acimreminder.app.PLAYBACK_STOP";
    public static final String ACTION_PLAYING = "com.acimreminder.app.PLAYBACK_PLAYING";
    public static final String ACTION_PAUSED = "com.acimreminder.app.PLAYBACK_PAUSED";

    private static final int NOTIF_ID = 4001;

    private MediaSession session;
    private boolean playing;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stop();
            return START_NOT_STICKY;
        }

        ensureSession();
        if (ACTION_PAUSED.equals(action)) {
            playing = false;
        } else if (ACTION_PLAYING.equals(action) || ACTION_START.equals(action)) {
            playing = true;
        }
        updateState();
        startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        return START_NOT_STICKY;
    }

    private void ensureSession() {
        if (session != null) return;
        session = new MediaSession(this, "acim-playback");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                Playback.Controller c = Playback.controller();
                if (c != null) c.playbackPlay();
                playing = true;
                updateState();
            }

            @Override
            public void onPause() {
                Playback.Controller c = Playback.controller();
                if (c != null) c.playbackPause();
                playing = false;
                updateState();
            }

            @Override
            public void onStop() {
                Playback.Controller c = Playback.controller();
                if (c != null) c.playbackStop();
                stop();
            }
        });
        session.setActive(true);
    }

    private void updateState() {
        if (session == null) return;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());
    }

    private Notification buildNotification() {
        Notify.ensureChannels(this);

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(this, Notify.CH_MEDITATION)
                .setSmallIcon(R.drawable.ic_stat_bell)
                .setContentTitle(Playback.title().isEmpty() ? "Playing" : Playback.title())
                .setContentText(playing ? "Playing" : "Paused")
                .setContentIntent(open)
                .setOngoing(playing)
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()));

        b.addAction(new Notification.Action.Builder(
                Icon_(), playing ? "Pause" : "Play",
                serviceIntent(playing ? ACTION_PAUSED : ACTION_PLAYING, true)).build());
        b.addAction(new Notification.Action.Builder(
                Icon_(), "Stop", serviceIntent(ACTION_STOP, false)).build());
        return b.build();
    }

    private android.graphics.drawable.Icon Icon_() {
        return android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat_bell);
    }

    /**
     * The notification's own buttons have to move the video too, not just this
     * service's idea of the state, so they go through the controller as well.
     */
    private PendingIntent serviceIntent(String action, boolean alsoToggleVideo) {
        Intent i = new Intent(this, PlaybackControlReceiver.class)
                .setAction(action)
                .putExtra(PlaybackControlReceiver.EXTRA_TOGGLE_VIDEO, alsoToggleVideo);
        return PendingIntent.getBroadcast(this, action.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void stop() {
        playing = false;
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
