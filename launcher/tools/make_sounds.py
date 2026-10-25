#!/usr/bin/env python3
"""Synthesizes Zen Home's UI sounds into app/res/raw (16-bit mono WAV, 48 kHz).

Original sounds in the spirit of Apple TV's focus feedback (no Apple audio is used):
  tick   - focus moved: a short, soft, slightly glassy tick
  select - something opened: a rounder, lower "tock"
  bump   - can't move further: a muted low thud
Run:  python3 -I tools/make_sounds.py   (from the launcher directory)
"""
import math
import os
import random
import struct
import sys
import wave

RATE = 48000
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "res", "raw")


def render(ms, partials, decay_ms, noise=0.0, noise_ms=1.5, attack_ms=0.6, gain=0.3):
    """partials: list of (frequency Hz, amplitude). Exponential decay, tiny attack ramp."""
    n = int(RATE * ms / 1000)
    rnd = random.Random(7)
    lp = 0.0
    out = []
    for i in range(n):
        t = i / RATE
        env = math.exp(-t / (decay_ms / 1000.0))
        env *= min(1.0, t / (attack_ms / 1000.0)) if attack_ms > 0 else 1.0
        s = sum(a * math.sin(2 * math.pi * f * t) for f, a in partials)
        if noise and t < noise_ms / 1000.0:
            lp += 0.35 * (rnd.uniform(-1, 1) - lp)  # soften the click transient
            s += noise * lp * (1 - t / (noise_ms / 1000.0))
        out.append(s * env * gain)
    # 3 ms fade-out so nothing pops at the end
    fade = int(RATE * 0.003)
    for k in range(fade):
        out[n - 1 - k] *= k / fade
    peak = max(abs(v) for v in out) or 1.0
    scale = gain / peak  # normalize every sound to exactly its target peak
    return [v * scale for v in out]


def write(name, samples):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name + ".wav")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, v)) * 32767)) for v in samples))
    print(path, len(samples) * 1000 // RATE, "ms")


if __name__ == "__main__":
    write("tick", render(32, [(2350, 0.55), (4700, 0.18), (1180, 0.30)], decay_ms=5.5,
                         noise=0.35, gain=0.45))
    write("select", render(70, [(880, 0.6), (1760, 0.22), (2640, 0.08)], decay_ms=14,
                           noise=0.25, noise_ms=2.0, attack_ms=1.0, gain=0.5))
    write("bump", render(80, [(196, 0.7), (392, 0.25)], decay_ms=18,
                         noise=0.15, noise_ms=3.0, attack_ms=1.5, gain=0.45))
    sys.exit(0)
