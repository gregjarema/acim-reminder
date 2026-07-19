#!/usr/bin/env python3
"""
Synthesize two soft, gentle singing-bowl bell tones and write them as
16-bit PCM WAV files into app/src/main/res/raw/.

No third-party libraries (pure standard library) so it runs anywhere.

The tone is built from a few inharmonic partials (like a real bowl / chime),
each with its own exponential decay, a soft raised-cosine attack, and a
slight detune for a gentle shimmer/beating. It is deliberately quiet and
warm rather than harsh.

To make your own bell instead: replace the generated .wav files in
app/src/main/res/raw/ with your own `bell_start.wav` and `bell_end.wav`
(keep the same file names). See README.
"""
import math
import os
import struct
import wave

SAMPLE_RATE = 44100


def synth(fundamental, duration, partials, peak=0.5):
    """Return a list of float samples in [-1, 1]."""
    n = int(SAMPLE_RATE * duration)
    samples = [0.0] * n
    attack = int(SAMPLE_RATE * 0.015)  # 15 ms soft attack, removes any click
    for ratio, amp, decay in partials:
        freq = fundamental * ratio
        # two very slightly detuned voices for a warm shimmer
        for detune in (-0.4, 0.4):
            f = freq + detune
            w = 2.0 * math.pi * f / SAMPLE_RATE
            for i in range(n):
                env = math.exp(-decay * (i / SAMPLE_RATE))
                samples[i] += 0.5 * amp * env * math.sin(w * i)

    # soft attack ramp
    for i in range(min(attack, n)):
        samples[i] *= 0.5 - 0.5 * math.cos(math.pi * i / attack)

    # gentle fade of the final 60 ms to guarantee no tail click
    fade = int(SAMPLE_RATE * 0.06)
    for i in range(min(fade, n)):
        idx = n - 1 - i
        samples[idx] *= i / fade

    # normalise to the requested soft peak
    hi = max(abs(s) for s in samples) or 1.0
    scale = peak / hi
    return [s * scale for s in samples]


def write_wav(path, samples):
    with wave.open(path, "w") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        frames = bytearray()
        for s in samples:
            v = int(max(-1.0, min(1.0, s)) * 32767.0)
            frames += struct.pack("<h", v)
        w.writeframes(bytes(frames))
    print("wrote", path, f"({os.path.getsize(path)} bytes)")


# Inharmonic partials typical of a singing bowl: (ratio, amplitude, decay/sec)
# Higher partials are quieter and decay faster -> warm, rounded tone.
START_PARTIALS = [
    (1.0, 1.00, 1.6),
    (2.76, 0.42, 2.6),
    (5.40, 0.18, 3.8),
    (8.93, 0.08, 5.2),
]
END_PARTIALS = [
    (1.0, 1.00, 1.1),
    (2.76, 0.40, 1.9),
    (5.40, 0.16, 3.0),
    (8.93, 0.07, 4.4),
]

RAW = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")
RAW = os.path.abspath(RAW)
os.makedirs(RAW, exist_ok=True)

# Start bell: slightly brighter, a touch shorter — "settle in".
write_wav(os.path.join(RAW, "bell_start.wav"),
          synth(fundamental=587.33, duration=2.8, partials=START_PARTIALS, peak=0.5))

# End bell: lower, warmer, longer decay — "gently come back".
write_wav(os.path.join(RAW, "bell_end.wav"),
          synth(fundamental=440.00, duration=4.0, partials=END_PARTIALS, peak=0.5))
