package com.acimreminder.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Alarms are wiped when the phone reboots, so we re-arm the whole day's
 * reminders as soon as the device finishes booting.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Notify.ensureChannels(ctx);
            Scheduler.scheduleAll(ctx);
        UpdateReceiver.schedule(ctx);
        }
    }
}
