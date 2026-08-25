package com.acimreminder.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;

/**
 * Fired by an exact alarm at the precise end-of-meditation minute. We schedule
 * this alarm explicitly (rather than trusting an in-app timer, which the OS can
 * pause during Doze) so the closing bell always rings on time — even with the
 * screen off.
 *
 * It posts the "Practice complete" notification (whose channel sound the system
 * plays where the phone allows it) and, in Normal ringer mode, ALSO plays the
 * bell directly here via MediaPlayer — the same reliable method the opening bell
 * uses. Some phones' battery managers show a channel's notification but silently
 * drop its sound in the background; playing the file ourselves doesn't depend on
 * the OS choosing to honour that. It deliberately does NOT start a foreground
 * service: starting one from the background with the screen locked is exactly
 * what some phones refuse or kill, which was silently dropping the end bell. A
 * receiver runs from the alarm even if the app process was reclaimed.
 *
 * goAsync() holds the receiver (and the process) alive long enough for the bell
 * to finish playing — normally onReceive() returning lets the OS reclaim the
 * process immediately, which would cut the sound off.
 */
public class EndBellReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        MeditationService.endNow(ctx);

        AudioManager am = ctx.getSystemService(AudioManager.class);
        int mode = am != null ? am.getRingerMode() : AudioManager.RINGER_MODE_NORMAL;
        if (mode != AudioManager.RINGER_MODE_NORMAL) return;

        final PendingResult pending = goAsync();
        BellPlayer.play(ctx, R.raw.bell_end, pending::finish);
    }
}
