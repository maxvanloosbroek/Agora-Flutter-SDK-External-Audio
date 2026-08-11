#!/usr/bin/env python3
"""Compute ERLE (echo return loss enhancement) between two raw PCM16 mono files.

Usage: erle.py nearend_before.pcm nearend_after.pcm
Both files must be 48 kHz, mono, signed 16-bit little endian.
Run this over a segment where ONLY the remote party was talking.
"""
import sys
import array
import math


def read_pcm(path):
    data = array.array("h")
    with open(path, "rb") as handle:
        data.frombytes(handle.read())
    return data


def energy(samples):
    if not samples:
        return 0.0
    return sum(float(s) * float(s) for s in samples) / len(samples)


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 1
    before = read_pcm(sys.argv[1])
    after = read_pcm(sys.argv[2])
    count = min(len(before), len(after))
    if count == 0:
        print("empty input")
        return 1
    energy_before = energy(before[:count])
    energy_after = energy(after[:count])
    if energy_after <= 0.0 or energy_before <= 0.0:
        print("silent input; cannot compute ERLE")
        return 1
    erle_db = 10.0 * math.log10(energy_before / energy_after)
    print("samples=%d" % count)
    print("ERLE=%.2f dB" % erle_db)
    return 0


if __name__ == "__main__":
    sys.exit(main())
