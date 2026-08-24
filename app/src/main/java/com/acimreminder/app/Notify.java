package com.acimreminder.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.net.Uri;

/**
 * Notification channels. Channels are how Android 8+ lets the user control the
 * sound/importance of each kind of notification. We create two:
 *   - reminders: alerts you (sound + heads-up + vibration) at each scheduled time.
 *   - meditation: silent (no sound/vibration) but still shown on the lock screen,
 *     for the live countdown.
 *
 * NOTE: a channel's sound/importance/vibration settings are fixed once it is
 * first created. To change them we must use a NEW channel id (hence the "_v2"
 * suffix) and delete the old one, otherwise the update would keep the old
 * settings.
 */
public final class Notify {

    public static final String CH_REMINDERS = "reminders_v2";
    public static final String CH_MEDITATION = "meditation_v2";
    /**
     * The end-of-sitting alert. Unlike the silent countdown channel, this one
     * carries the closing bell as its own sound and a matching buzz, so the
     * <em>system</em> sounds it when a sitting finishes — reliably, even with the
     * screen locked or the app long since backgrounded, where an in-process
     * player was dropping it. ALARM audio attributes so it plays at alarm volume
     * and carries through Do Not Disturb, exactly as the opening bell does.
     */
    public static final String CH_MEDITATION_DONE = "meditation_done_v1";

    public static void ensureChannels(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        // Remove the pre-vibration reminders channel from older installs.
        nm.deleteNotificationChannel("reminders");
        // Remove the old low-importance meditation channel; its countdown was
        // filed under "silent" and therefore hidden on the lock screen.
        nm.deleteNotificationChannel("meditation");
        // Remove the old meditation_done channel if it exists; the new v1 ensures
        // the bell sound is properly configured.
        nm.deleteNotificationChannel("meditation_done");

        NotificationChannel reminders = new NotificationChannel(
                CH_REMINDERS, "Practice reminders", NotificationManager.IMPORTANCE_HIGH);
        reminders.setDescription("Hourly nudges to pause and practice today's lesson.");
        reminders.enableVibration(true);
        reminders.setVibrationPattern(new long[]{0, 300, 150, 300});

        // DEFAULT importance (not LOW) so the live countdown is shown on the
        // lock screen rather than tucked into the hidden "silent" section. We
        // still make no sound or vibration of our own — the opening/closing
        // bells are the only audio — so this stays unobtrusive.
        NotificationChannel meditation = new NotificationChannel(
                CH_MEDITATION, "Meditation timer", NotificationManager.IMPORTANCE_DEFAULT);
        meditation.setDescription("The live 5-minute countdown shown while you meditate.");
        meditation.setSound(null, null);
        meditation.enableVibration(false);
        meditation.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        // The closing-bell alert. HIGH so it can alert with the screen locked;
        // its sound is the bundled end bell, tagged ALARM so it plays at alarm
        // volume and through Do Not Disturb. Vibration matches Haptics.END. Only
        // posted in Normal ringer mode — Vibrate/Silent use the silent channel
        // above plus a direct buzz — so this channel's alarm sound never rings
        // when the phone is meant to be quiet.
        NotificationChannel done = new NotificationChannel(
                CH_MEDITATION_DONE, "Meditation complete", NotificationManager.IMPORTANCE_HIGH);
        done.setDescription("The closing bell when a sitting finishes.");
        Uri bell = Uri.parse("android.resource://" + ctx.getPackageName() + "/" + R.raw.bell_end);
        done.setSound(bell, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        done.enableVibration(true);
        done.setVibrationPattern(Haptics.END);
        done.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        nm.createNotificationChannel(reminders);
        nm.createNotificationChannel(meditation);
        nm.createNotificationChannel(done);
    }

    private Notify() {}
}
