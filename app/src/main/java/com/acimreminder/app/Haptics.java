package com.acimreminder.app;

import android.content.Context;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * The meditation timer's haptic cues — a buzz you feel through the desk when
 * the office is quiet and a bell would be indiscreet.
 *
 * Two patterns, echoing the bells: one soft pulse to open the sitting, three to
 * close it. Fired once, no repeat.
 *
 * minSdk is 33, so {@link VibratorManager} (API 31) and {@link VibrationEffect}
 * (API 26) are always present — no version guards needed.
 */
public final class Haptics {

    /** One soft pulse to open the sitting — the haptic of the opening bell. */
    public static final long[] START = {0, 220};

    /** Three pulses to close it — the haptic of the closing bell. */
    public static final long[] END = {0, 180, 120, 180, 120, 180};

    /**
     * Play {@code pattern} once (a {timing…} waveform in milliseconds, starting
     * with an initial off-delay). No-op if the device can't vibrate.
     */
    public static void buzz(Context ctx, long[] pattern) {
        VibratorManager vm = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
        if (vm == null) return;
        Vibrator v = vm.getDefaultVibrator();
        if (v == null || !v.hasVibrator()) return;
        // -1 = play through once and stop (no repeat index).
        v.vibrate(VibrationEffect.createWaveform(pattern, -1));
    }

    private Haptics() {}
}
