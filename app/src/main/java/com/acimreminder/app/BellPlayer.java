package com.acimreminder.app;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.PowerManager;
import android.util.Log;

/**
 * Plays one of the bundled bell sounds.
 *
 * The bells are tagged as ALARM audio (USAGE_ALARM). On Android 17 this is what
 * lets the closing bell sound while the app is in the background / screen off,
 * because we also hold the exact-alarm permission. Practical side effect: the
 * bell volume follows the phone's *Alarm* volume, not the Media volume.
 *
 * To use your own sound instead, drop a file named bell_start.wav / bell_end.wav
 * into app/src/main/res/raw/ (mp3/ogg/wav all work).
 */
public final class BellPlayer {

    private static final String TAG = "BellPlayer";

    public interface OnDone {
        void done();
    }

    /**
     * Play a raw-resource sound. {@code onDone} (may be null) runs after the
     * sound finishes or if it fails to start, so callers can chain cleanup.
     */
    public static void play(Context ctx, int rawResId, final OnDone onDone) {
        Log.i(TAG, "play() called, rawResId=" + rawResId);
        MediaPlayer mp = new MediaPlayer();
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        // Keep the CPU on until playback completes, even with the screen off.
        mp.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK);

        mp.setOnCompletionListener(m -> {
            Log.i(TAG, "onCompletion");
            m.release();
            if (onDone != null) onDone.done();
        });
        mp.setOnErrorListener((m, what, extra) -> {
            Log.w(TAG, "MediaPlayer error " + what + "/" + extra);
            m.release();
            if (onDone != null) onDone.done();
            return true;
        });

        try (AssetFileDescriptor afd = ctx.getResources().openRawResourceFd(rawResId)) {
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            mp.setOnPreparedListener(m -> {
                Log.i(TAG, "onPrepared; calling start()");
                m.start();
                Log.i(TAG, "start() returned; isPlaying=" + m.isPlaying());
            });
            Log.i(TAG, "calling prepareAsync()");
            mp.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "Failed to play bell", e);
            mp.release();
            if (onDone != null) onDone.done();
        }
    }

    private BellPlayer() {}
}
