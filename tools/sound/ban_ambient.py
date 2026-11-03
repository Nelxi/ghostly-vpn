"""Renders the dark ambient loop of a banned subscription (original, synthesised from scratch, no samples).

    python tools/sound/ban_ambient.py shared/src/commonMain/composeResources/files/ban_ambient.wav

A seamless 48 s loop at 16 kHz mono: a beating sub drone, a tritone pad, breathing noise,
a slow heartbeat and a far-off metallic bell with a long echo. Filters and reverb are circular,
so the tail of the loop rings into its start and the loop has no seam.
"""
import sys
import wave

import numpy as np
from scipy.signal import butter, sosfilt, fftconvolve

SR = 16_000
LEN = 48.0
N = int(SR * LEN)
t = np.arange(N) / SR
rng = np.random.default_rng(666)


def loop_sin(freq, phase=0.0):
    # a whole number of cycles per loop, so every layer wraps without a click
    f = round(freq * LEN) / LEN
    return np.sin(2 * np.pi * f * t + phase)


def lfo(period_s, lo, hi, phase=0.0):
    return lo + (hi - lo) * (0.5 + 0.5 * loop_sin(1 / period_s, phase))


def _circular(sos, x):
    # run the filter over the loop's last second first, so its start has no warm-up click
    if len(x) != N:
        return sosfilt(sos, x)
    return sosfilt(sos, np.concatenate([x[-SR:], x]))[SR:]


def lowpass(x, hz, order=4):
    return _circular(butter(order, hz, "low", fs=SR, output="sos"), x)


def bandpass(x, lo, hi, order=2):
    return _circular(butter(order, [lo, hi], "band", fs=SR, output="sos"), x)


mix = np.zeros(N)

# 1. Sub drone: E1 against a slightly sharp E1 — a slow, uneasy beating.
sub = loop_sin(41.2) + 0.8 * loop_sin(41.9, 1.0) + 0.35 * loop_sin(82.4, 0.3)
mix += 0.32 * sub * lfo(16, 0.55, 1.0)


# 2. Tritone pad (E2 / A#2), a few soft harmonics each, swelling against each other.
def pad(f0, period, ph):
    p = sum((0.6 ** k) * loop_sin(f0 * (k + 1), ph * k) for k in range(5))
    return p * lfo(period, 0.15, 1.0, ph)


mix += 0.11 * lowpass(pad(82.41, 24, 0.0) + pad(116.54, 16, 2.1) + 0.6 * pad(123.47, 12, 4.0), 900)

# 3. Breath: low noise that swells and fades like something exhaling in the dark.
noise = rng.standard_normal(N)
mix += 0.16 * bandpass(noise, 70, 420) * lfo(8, 0.05, 1.0, 1.3) ** 2

# 4. Heartbeat, slow (≈ 48 bpm): two soft thumps per beat.
beat = np.zeros(N)
period = LEN / 38  # 38 beats per loop
for i in range(38):
    for off, amp in ((0.0, 1.0), (0.22, 0.6)):
        s = int(((i * period + off) % LEN) * SR)
        k = np.arange(int(0.35 * SR))
        thump = amp * np.sin(2 * np.pi * 52 * k / SR) * np.exp(-k / (0.07 * SR))
        idx = (s + k) % N
        beat[idx] += thump
mix += 0.30 * lowpass(beat, 160)

# 5. A far bell from below: inharmonic partials, long decay, three hits per loop.
bell = np.zeros(N)
for start, f0 in ((5.0, 146.8), (21.0, 138.6), (37.0, 155.6)):
    k = np.arange(int(9 * SR))
    tone = np.zeros(len(k))
    for ratio, amp, decay in ((1.0, 1.0, 3.2), (2.76, 0.55, 1.8), (5.40, 0.35, 0.9), (8.93, 0.2, 0.5), (0.5, 0.4, 4.0)):
        tone += amp * np.sin(2 * np.pi * f0 * ratio * k / SR) * np.exp(-k / (decay * SR))
    tone *= np.minimum(1, k / (0.004 * SR))
    idx = (int(start * SR) + k) % N
    bell[idx] += tone
mix += 0.13 * bell

# 6. A rising swell before each bell, like something coming closer.
swell = np.zeros(N)
for start in (5.0, 21.0, 37.0):
    k = np.arange(int(3 * SR))
    env = (k / len(k)) ** 3
    idx = (int((start - 3.0) * SR) + k) % N
    swell[idx] += bandpass(rng.standard_normal(len(k)), 300, 1800) * env
mix += 0.05 * swell

# Circular reverb: a dark, long room; the tail wraps into the start, so the loop is seamless.
ir_n = int(3.2 * SR)
ir = rng.standard_normal(ir_n) * np.exp(-np.arange(ir_n) / (0.9 * SR))
ir = sosfilt(butter(4, 2500, "low", fs=SR, output="sos"), ir)
ir /= np.sqrt(np.sum(ir ** 2))
wet = fftconvolve(mix, ir)  # N + ir_n - 1 samples
wet_loop = wet[:N].copy()
wet_loop[: len(wet) - N] += wet[N:]  # the tail past the loop's end rings into its start
out = 0.62 * mix + 0.55 * wet_loop
out = lowpass(out, 3800, order=2)
out -= out.mean()
out *= 10 ** (-5 / 20) / np.max(np.abs(out))  # peak at -5 dBFS; the app plays it at medium volume

with wave.open(sys.argv[1], "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes((out * 32767).astype("<i2").tobytes())
print("wrote", sys.argv[1], f"{N / SR:.0f}s")
