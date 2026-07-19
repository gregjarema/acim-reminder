package com.acimreminder.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

/**
 * Notification channels. Channels are how Android 8+ lets the user control the
 * sound/importance of each kind of notification. We create two:
 *   - "reminders": alerts you (sound + heads-up) at each scheduled time.
 *   - "meditation": silent, low-key channel for the live countdown.
 */
public final class Notify {

    public static final String CH_REMINDERS = "reminders";
    public static final String CH_MEDITATION = "meditation";

    public static void ensureChannels(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel reminders = new NotificationChannel(
                CH_REMINDERS, "Practice reminders", NotificationManager.IMPORTANCE_HIGH);
        reminders.setDescription("Hourly nudges to pause and practice today's lesson.");

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
