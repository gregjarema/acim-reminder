package com.acimreminder.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

/**
 * Notification channels. Channels are how Android 8+ lets the user control the
 * sound/importance of each kind of notification. We create two:
 *   - reminders: alerts you (sound + heads-up + vibration) at each scheduled time.
 *   - meditation: silent, low-key channel for the live countdown.
 *
 * NOTE: a channel's sound/vibration settings are fixed once it is first created.
 * To change them we must use a NEW channel id (hence the "_v2" suffix) and
 * delete the old one, otherwise the update would keep the old settings.
 */
public final class Notify {

    public static final String CH_REMINDERS = "reminders_v2";
    public static final String CH_MEDITATION = "meditation";

    public static void ensureChannels(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        // Remove the pre-vibration reminders channel from older installs.
        nm.deleteNotificationChannel("reminders");

        NotificationChannel reminders = new NotificationChannel(
                CH_REMINDERS, "Practice reminders", NotificationManager.IMPORTANCE_HIGH);
        reminders.setDescription("Hourly nudges to pause and practice today's lesson.");
        reminders.enableVibration(true);
        reminders.setVibrationPattern(new long[]{0, 300, 150, 300});

        NotificationChannel meditation = new NotificationChannel(
                CH_MEDITATION, "Meditation timer", NotificationManager.IMPORTANCE_LOW);
        meditation.setDescription("The live 5-minute countdown shown while you meditate.");
        meditation.setSound(null, null);
        meditation.enableVibration(false);

        nm.createNotificationChannel(reminders);
        nm.createNotificationChannel(meditation);
    }

    private Notify() {}
}
