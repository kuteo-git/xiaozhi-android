#!/usr/bin/env python3
"""Compare two recordings of the R1's own speaker, band by band.

Long-term average spectrum of the loud frames of each file, then the difference in dB per band. The
mic's own response cancels because it is the same mic in both, which is what makes an A/B of the
equalizer meaningful through a mic that is nothing like flat.

What this CANNOT tell you, measured 2026-09-25 by recording silence and comparing:
  60 Hz +0.7 dB S/N, 230 Hz +19.2, 910 Hz +4.9, 1800 Hz +15.0, 3600 Hz +1.4, 6000 Hz +6.4.
At 60 Hz and 3600 Hz the mic hears only its own noise floor, so a number there is not a measurement.
Trust 120-2000 Hz; below 200 Hz cabinet vibration lifts the reading (120 Hz reads +35 dB over what
the source has), and 2.5-5 kHz has to be judged by ear or with an external mic.
"""
import sys
import wave

import numpy as np

BANDS = [(160, 130, 200), (320, 260, 400), (640, 520, 800),
         (1280, 1050, 1560), (2560, 2100, 3100)]


def ltas(path, n=2048):
    w = wave.open(path)
    sr = w.getframerate()
    x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float64) / 32768
    win = np.hanning(n)
    acc = np.zeros(n // 2 + 1)
    used = 0
    # Only the loud frames: the speaker, not the room between words.
    threshold = np.sqrt((x ** 2).mean()) * 1.5
    for i in range(0, len(x) - n, n // 2):
        seg = x[i:i + n]
        if np.sqrt((seg ** 2).mean()) < threshold:
            continue
        acc += np.abs(np.fft.rfft(seg * win)) ** 2
        used += 1
    return np.fft.rfftfreq(n, 1 / sr), acc / max(used, 1), used, np.abs(x).max()


def main(before, after):
    f, a, na, pa = ltas(before)
    _, b, nb, pb = ltas(after)
    print(f"A: {na} frames, peak {pa:.3f}    B: {nb} frames, peak {pb:.3f}")
    for centre, lo, hi in BANDS:
        m = (f >= lo) & (f < hi)
        print(f"{centre:>5} Hz  {10 * np.log10(b[m].mean() / a[m].mean()):>+7.1f} dB")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
