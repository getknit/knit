#!/usr/bin/env python3
"""Generate Knit's message notification sound, `app/src/main/res/raw/knit.ogg`.

    python3 scripts/gen-notification-sound.py

Needs numpy, scipy and an ffmpeg built with libvorbis. The committed `.ogg` is what ships; this script is its
source, so change the sound here and regenerate, never edit the file by hand (ADR 2026-10.xukf).

**What it makes.** Morse K, dah-dit-dah, the radio operator's "over, go ahead": three felt-mallet notes on a
55 ms unit (A5 held 165 ms, E5 held 55 ms, then C#6 left to ring), about 1.2 s with the tail. The rhythm is
the sound's identity, the part that still reads through a phone speaker or across a room. The felt timbre
(a marimba-like bar's partials, the overtones dying fast) and the 7 kHz ceiling are what keep it soft: its
energy sits around 1.1 kHz, where a phone speaker is at its best, with next to none above 5 kHz.

**Loudness.** Integrated -16 LUFS (ITU-R BS.1770, measured here), which leaves the peak near -5 dBFS. That is
quieter than most notification sounds by design; raise TARGET_LUFS a few dB if it gets missed.

**Reproducible.** The noise in the mallet's contact and the room is drawn from a fixed seed (the one the
auditioned take was rendered with), and ffmpeg runs bit-exact, so the same numpy, scipy and ffmpeg give the
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
SEED = 3353598296
TARGET_LUFS = -16.0
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw", "knit.ogg")

# A soft felt mallet on a marimba-like bar: (partial ratio, amplitude, decay as a fraction of the note's tau).
FELT = [(1.0, 1.0, 1.0), (2.0, 0.07, 0.5), (3.93, 0.16, 0.22), (9.6, 0.025, 0.08)]

A5, E5, CS6 = 880.0, 659.26, 1108.73


def note(rng, freq, tau, gain, gate=None, length=1.4):
    """One struck note. `gate` damps it after that many seconds, which is how a dah outlasts a dit."""
    t = np.arange(int(length * SR)) / SR
    phase = 2 * np.pi * np.cumsum(np.full_like(t, freq)) / SR
    out = np.zeros_like(t)
    for ratio, amp, decay in FELT:
        out += amp * np.sin(ratio * phase) * np.exp(-t / (tau * decay))
    env = 0.5 - 0.5 * np.cos(np.pi * np.clip(t / 0.004, 0, 1))
    if gate is not None:
        env *= np.where(t < gate, 1.0, np.exp(-(t - gate) / 0.022))
    # The felt's contact: a few ms of band-limited noise, about 32 dB under the tone.
    n = int(0.006 * SR)
    tick = rng.standard_normal(n) * np.hanning(n) * 0.025
    out[:n] += sosfilt(butter(2, [1800, 4200], "bandpass", fs=SR, output="sos"), tick)
    return gain * out * env


def render():
    rng = np.random.default_rng(SEED)
    events = [
        (0.000, note(rng, A5, 0.30, 0.85, gate=0.165)),  # dah
        (0.220, note(rng, E5, 0.30, 0.60, gate=0.055)),  # dit
        (0.330, note(rng, CS6, 0.34, 1.00)),  # dah, left to ring
    ]
    end = 0.330 + 0.9
    buf = np.zeros(int(end * SR) + SR)
    for onset, sig in events:
        i = int(onset * SR)
        seg = sig[: len(buf) - i]
        buf[i : i + len(seg)] += seg
    buf = buf[: int(end * SR)]
    # A small soft room, 10 % wet.
    ir_t = np.arange(int(0.45 * SR)) / SR
    ir = rng.standard_normal(len(ir_t)) * np.exp(-ir_t / 0.09)
    ir = sosfilt(butter(2, 4500, fs=SR, output="sos"), ir)
    ir /= np.sqrt(np.sum(ir**2))
    wet = fftconvolve(buf, ir)[: len(buf)]
    buf = buf + 0.10 * wet / (np.max(np.abs(wet)) + 1e-9) * np.max(np.abs(buf))
    buf = sosfilt(butter(2, 320, "highpass", fs=SR, output="sos"), buf)
    buf = sosfilt(butter(2, 7000, fs=SR, output="sos"), buf)
    fade = int(0.08 * SR)
    buf[-fade:] *= np.linspace(1, 0, fade) ** 2
    return buf / np.max(np.abs(buf))


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
    parser.add_argument("--out", default=OUT, help="where to write the .ogg (default: the app's res/raw)")
    args = parser.parse_args()

    sig = render()
    sig *= 10 ** ((TARGET_LUFS - loudness(sig)) / 20)
    peak = 20 * np.log10(np.max(np.abs(sig)))
    if peak > -1.0:
        sys.exit(f"peak {peak:.1f} dBFS would clip after encoding; lower TARGET_LUFS")
    with tempfile.TemporaryDirectory() as tmp:
        wav = os.path.join(tmp, "knit.wav")
        wavfile.write(wav, SR, sig.astype(np.float32))
        subprocess.run(
            ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", wav, "-map_metadata", "-1",
             "-metadata", "title=Knit", "-c:a", "libvorbis", "-q:a", "5",
             "-fflags", "+bitexact", "-flags:a", "+bitexact", os.path.abspath(args.out)],
            check=True,
        )
    print(f"{os.path.relpath(args.out)}: {len(sig) / SR:.2f} s, {loudness(sig):.1f} LUFS, peak {peak:.1f} dBFS")


if __name__ == "__main__":
    main()
