package com.acimreminder.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

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

    public static void ensureChannels(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        // Remove the pre-vibration reminders channel from older installs.
        nm.deleteNotificationChannel("reminders");
        // Remove the old low-importance meditation channel; its countdown was
        // filed under "silent" and therefore hidden on the lock screen.
        nm.deleteNotificationChannel("meditation");

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

        nm.createNotificationChannel(reminders);
        nm.createNotificationChannel(meditation);
    }

    private Notify() {}
}
