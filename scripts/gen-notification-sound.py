#!/usr/bin/env python3
"""Generate Knit's notification sounds: the message sound `knit.ogg` and the call ring `knit_ring.ogg`.

    python3 scripts/gen-notification-sound.py

Both land in `app/src/main/res/raw/`. Needs numpy, scipy and an ffmpeg built with libvorbis. The committed `.ogg`
files are what ships; this script is their source, so change a sound here and regenerate, never edit a file by
hand (ADR 2026-10.xukf).

**The message sound.** Morse K, dah-dit-dah, the radio operator's "over, go ahead": three felt-mallet notes on a
55 ms unit (A5 held 165 ms, E5 held 55 ms, then C#6 left to ring), about 1.2 s with the tail. The rhythm is the
sound's identity, the part that still reads through a phone speaker or across a room. The felt timbre (a
marimba-like bar's partials, the overtones dying fast) and the 7 kHz ceiling are what keep it soft: its energy
sits around 1.1 kHz, where a phone speaker is at its best, with next to none above 5 kHz.

**The ring.** A music-box line on the same felt with a little more octave in it, built on the K: the motif, a
turn around it, then the same shape a step up resolving home on A5, 4.6 s. The file is one loop with its pause
inside, because an insistent call notification plays it over and over; whatever rings past the loop point is
folded back onto its start, so the seam is silent.

**Loudness.** Integrated loudness (ITU-R BS.1770, measured here): -16 LUFS for the message, its peak near -5
dBFS, on purpose quieter than most notification sounds; -13 LUFS for the ring over three loops, since a call has
to reach the next room. Raise a target a few dB if it gets missed.

**Reproducible.** The noise in the mallet's contact and the room is drawn from a fixed seed per sound (the one
its auditioned take was rendered with), and ffmpeg runs bit-exact, so the same numpy, scipy and ffmpeg give the
same bytes. Other versions may differ in the last bits, which is why the output is committed.
"""

import argparse
import os
import subprocess
import sys
import tempfile

import numpy as np
from scipy.io import wavfile
from scipy.signal import butter, fftconvolve, lfilter, sosfilt

SR = 48_000
OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")

# A soft felt mallet on a marimba-like bar: (partial ratio, amplitude, decay as a fraction of the note's tau).
FELT = [(1.0, 1.0, 1.0), (2.0, 0.07, 0.5), (3.93, 0.16, 0.22), (9.6, 0.025, 0.08)]
# The same felt with more octave and a third partial: the ring's, for presence in a pocket.
BRIGHT_FELT = [(1.0, 1.0, 1.0), (2.0, 0.16, 0.55), (3.0, 0.05, 0.35), (3.93, 0.18, 0.25), (9.6, 0.03, 0.08)]

E5, FS5, A5, B5, CS6, D6, E6, FS6 = 659.26, 739.99, 880.0, 987.77, 1108.73, 1174.66, 1318.51, 1479.98


def note(rng, freq, tau, gain, gate=None, timbre=FELT, length=1.4):
    """One struck note. `gate` damps it after that many seconds, which is how a dah outlasts a dit."""
    t = np.arange(int(length * SR)) / SR
    phase = 2 * np.pi * np.cumsum(np.full_like(t, freq)) / SR
    out = np.zeros_like(t)
    for ratio, amp, decay in timbre:
        out += amp * np.sin(ratio * phase) * np.exp(-t / (tau * decay))
    env = 0.5 - 0.5 * np.cos(np.pi * np.clip(t / 0.004, 0, 1))
    if gate is not None:
        env *= np.where(t < gate, 1.0, np.exp(-(t - gate) / 0.022))
    # The felt's contact: a few ms of band-limited noise, about 32 dB under the tone.
    n = int(0.006 * SR)
    tick = rng.standard_normal(n) * np.hanning(n) * 0.025
    out[:n] += sosfilt(butter(2, [1800, 4200], "bandpass", fs=SR, output="sos"), tick)
    return gain * out * env


def place(events, length):
    buf = np.zeros(length)
    for onset, sig in events:
        i = int(onset * SR)
        seg = sig[: len(buf) - i]
        buf[i : i + len(seg)] += seg
    return buf


def room(rng, buf):
    """A small soft room, 10 % wet, then the 320 Hz to 7 kHz band a phone speaker plays."""
    ir_t = np.arange(int(0.45 * SR)) / SR
    ir = rng.standard_normal(len(ir_t)) * np.exp(-ir_t / 0.09)
    ir = sosfilt(butter(2, 4500, fs=SR, output="sos"), ir)
    ir /= np.sqrt(np.sum(ir**2))
    wet = fftconvolve(buf, ir)[: len(buf)]
    buf = buf + 0.10 * wet / (np.max(np.abs(wet)) + 1e-9) * np.max(np.abs(buf))
    buf = sosfilt(butter(2, 320, "highpass", fs=SR, output="sos"), buf)
    return sosfilt(butter(2, 7000, fs=SR, output="sos"), buf)


def message():
    rng = np.random.default_rng(3353598296)
    events = [
        (0.000, note(rng, A5, 0.30, 0.85, gate=0.165)),  # dah
        (0.220, note(rng, E5, 0.30, 0.60, gate=0.055)),  # dit
        (0.330, note(rng, CS6, 0.34, 1.00)),  # dah, left to ring
    ]
    buf = room(rng, place(events, int((0.330 + 0.9) * SR)))
    fade = int(0.08 * SR)
    buf[-fade:] *= np.linspace(1, 0, fade) ** 2
    return buf / np.max(np.abs(buf))


def ring():
    rng = np.random.default_rng(2028935855)
    step = 0.15
    # (step, pitch, length in steps, gain): the K and a turn round it, then the same shape a step up, home on A5.
    line = [
        (0, A5, 2, 0.90), (2, E5, 1, 0.60), (3, CS6, 2, 0.85), (5, E6, 1, 0.80), (6, CS6, 1, 0.65), (7, B5, 1, 0.60),
        (8, A5, 3, 0.85), (12, B5, 2, 0.80), (14, FS5, 1, 0.60), (15, D6, 2, 0.85), (17, FS6, 1, 0.80),
        (18, E6, 1, 0.65), (19, CS6, 1, 0.60), (20, A5, 4, 1.00),
    ]
    events = [
        (s * step, note(rng, f, 0.18 * d + 0.12, g, gate=d * step * 0.9, timbre=BRIGHT_FELT, length=1.6))
        for s, f, d, g in line
    ]
    n = int(4.6 * SR)
    buf = room(rng, place(events, n + 2 * SR))
    out = buf[:n].copy()
    out[: len(buf) - n] += buf[n:]  # fold the tail onto the start: a seamless loop
    return out / np.max(np.abs(out))


# file name, title tag, render, target LUFS, how many copies the loudness is measured over
SOUNDS = [
    ("knit.ogg", "Knit", message, -16.0, 1),
    ("knit_ring.ogg", "Knit ring", ring, -13.0, 3),
]


def loudness(x):
    """Integrated loudness of a mono 48 kHz signal, ITU-R BS.1770-4 (K-weighting, 400 ms blocks, both gates)."""
    shelf = ([1.53512485958697, -2.69169618940638, 1.19839281085285], [1.0, -1.69065929318241, 0.73248077421585])
    high_pass = ([1.0, -2.0, 1.0], [1.0, -1.99004745483398, 0.99007225036621])
    y = lfilter(*high_pass, lfilter(*shelf, x))
    size, step = int(0.4 * SR), int(0.1 * SR)
    power = np.array([np.mean(y[i : i + size] ** 2) for i in range(0, len(y) - size + 1, step)])
    block = -0.691 + 10 * np.log10(power + 1e-20)
    power = power[block > -70]
    relative = -0.691 + 10 * np.log10(np.mean(power)) - 10
    power = power[-0.691 + 10 * np.log10(power) > relative]
    return -0.691 + 10 * np.log10(np.mean(power))


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out-dir", default=OUT_DIR, help="where to write the .ogg files (default: the app's res/raw)")
    args = parser.parse_args()

    for name, title, render, target, copies in SOUNDS:
        sig = render()
        sig *= 10 ** ((target - loudness(np.tile(sig, copies))) / 20)
        peak = 20 * np.log10(np.max(np.abs(sig)))
        if peak > -1.0:
            sys.exit(f"{name}: peak {peak:.1f} dBFS would clip after encoding; lower its target")
        out = os.path.abspath(os.path.join(args.out_dir, name))
        with tempfile.TemporaryDirectory() as tmp:
            wav = os.path.join(tmp, "sound.wav")
            wavfile.write(wav, SR, sig.astype(np.float32))
            subprocess.run(
                ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", wav, "-map_metadata", "-1",
                 "-metadata", f"title={title}", "-c:a", "libvorbis", "-q:a", "5",
                 "-fflags", "+bitexact", "-flags:a", "+bitexact", out],
                check=True,
            )
        print(f"{os.path.relpath(out)}: {len(sig) / SR:.2f} s, {loudness(np.tile(sig, copies)):.1f} LUFS, "
              f"peak {peak:.1f} dBFS")


if __name__ == "__main__":
    main()
