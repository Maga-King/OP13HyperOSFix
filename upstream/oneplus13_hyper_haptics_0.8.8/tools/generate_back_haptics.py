#!/usr/bin/env python3
"""Generate conservative OnePlus 13 0916T gesture-back RTP candidates.

The qcom-hv-haptics FIFO consumes signed 8-bit samples at 24 kHz.  The
device reports t_lra_us=7692 (about 130 Hz).  These candidates use a smooth,
DC-free carrier around that measured resonance and deliberately leave ample
headroom so final strength remains controlled by the HAL gain, not clipping.
"""

from __future__ import annotations

import math
from pathlib import Path


SAMPLE_RATE = 24_000
OUTPUT = Path(__file__).resolve().parents[1] / "build" / "rtp-back-tests"
ONEPLUS_EFFECT_5 = Path(
    r"E:\MIO\Coloros16\odm\etc\vibrator\9999\def\effect_5.bin")
ONEPLUS_EFFECT_9 = Path(
    r"E:\MIO\Coloros16\odm\etc\vibrator\9999\def\effect_9.bin")


def smoothstep(value: float) -> float:
    value = max(0.0, min(1.0, value))
    return value * value * (3.0 - 2.0 * value)


def envelope(time_ms: float, duration_ms: float, attack_ms: float,
             release_ms: float) -> float:
    attack = smoothstep(time_ms / attack_ms)
    release = smoothstep((duration_ms - time_ms) / release_ms)
    return attack * release


def make_pulse(duration_ms: float, frequency_hz: float, peak: float,
               attack_ms: float, release_ms: float,
               second_lobe: float = 0.0) -> bytes:
    count = int(round(duration_ms * SAMPLE_RATE / 1000.0))
    # qcom-hv-haptics programs its FIFO in four-byte bursts.
    count = (count + 3) & ~3
    values: list[float] = []
    for index in range(count):
        time_ms = index * 1000.0 / SAMPLE_RATE
        env = envelope(time_ms, duration_ms, attack_ms, release_ms)
        if second_lobe:
            center = duration_ms * 0.66
            width = duration_ms * 0.13
            env *= 1.0 + second_lobe * math.exp(
                -0.5 * ((time_ms - center) / width) ** 2)
        phase = 2.0 * math.pi * frequency_hz * index / SAMPLE_RATE
        values.append(peak * env * math.sin(phase))

    # Remove the tiny finite-window DC component before quantization.  This
    # avoids a mechanical position step at either edge of the pulse.
    mean = sum(values) / len(values)
    centered = [value - mean for value in values]
    measured_peak = max(abs(value) for value in centered) or 1.0
    # Treat peak as a hard output target even after DC removal.  This makes
    # iterations comparable and guarantees that a secondary lobe cannot push
    # the final signed sample into clipping.
    normalization = peak / measured_peak
    quantized = [
        max(-126, min(126, round(value * normalization)))
        for value in centered
    ]
    quantized[0] = 0
    quantized[-1] = 0
    return bytes(value & 0xFF for value in quantized)


def write(name: str, data: bytes) -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / name).write_bytes(data)


def scale_signed(data: bytes, gain: float) -> bytes:
    result = bytearray()
    for raw in data:
        sample = raw - 256 if raw >= 128 else raw
        scaled = max(-126, min(126, round(sample * gain)))
        result.append(scaled & 0xFF)
    return bytes(result)


def compress_to_peak(data: bytes, drive: float, peak_target: int = 126) -> bytes:
    samples = [raw - 256 if raw >= 128 else raw for raw in data]
    source_peak = max(abs(sample) for sample in samples) or 1
    denominator = math.tanh(drive)
    result = bytearray()
    for sample in samples:
        value = round(
            peak_target
            * math.tanh(drive * sample / source_peak)
            / denominator)
        result.append(max(-peak_target, min(peak_target, value)) & 0xFF)
    return bytes(result)


def main() -> None:
    # A: measured-resonance, deeper and fuller.
    write("a_162_130hz.bin", make_pulse(
        17.5, 130.0, 68.0, 1.8, 5.0))
    write("a_163_130hz.bin", make_pulse(
        22.5, 130.0, 78.0, 1.4, 5.5, second_lobe=0.18))

    # B: slightly above resonance, shorter/crisper and less spring-like.
    write("b_162_150hz.bin", make_pulse(
        15.5, 150.0, 72.0, 1.4, 4.0))
    write("b_163_150hz.bin", make_pulse(
        19.5, 150.0, 82.0, 1.2, 4.5, second_lobe=0.12))

    # C: the selected B character at full-slider listening level.  Samples
    # remain below signed 8-bit full scale; the Settings slider controls the
    # final RichTap voltage without altering this waveform.
    write("c_162_150hz_loud.bin", make_pulse(
        16.5, 150.0, 96.0, 1.2, 4.2))
    write("c_163_150hz_loud.bin", make_pulse(
        21.0, 150.0, 112.0, 1.0, 4.8, second_lobe=0.12))

    # Notification long press: preserve the current roughly 30 ms gesture,
    # but move its dominant energy from ~220 Hz to the chosen crisp 150 Hz
    # family.  A longer release gives it weight without an abrupt click.
    write("c_10001_notification_loud.bin", make_pulse(
        28.0, 150.0, 112.0, 1.0, 7.0, second_lobe=0.10))

    # D: deeper hybrid.  135 Hz sits close to the measured 130 Hz mechanical
    # resonance, while its short release avoids the springy tail of the first
    # 130 Hz experiment.
    write("d_162_135hz_deep.bin", make_pulse(
        18.0, 135.0, 100.0, 1.0, 4.2))
    write("d_163_135hz_deep.bin", make_pulse(
        22.5, 135.0, 116.0, 0.9, 4.8, second_lobe=0.08))
    write("d_10001_notification_deep.bin", make_pulse(
        30.0, 135.0, 116.0, 0.9, 6.2, second_lobe=0.08))

    # E: use the exact OnePlus fingerprint-success source.  Effect 5 contains
    # a 28 ms soft pulse, about 16 ms of rest, then a strong 20 ms confirmation
    # pulse.  The release candidate uses only that final factory pulse, while
    # notification long press keeps the complete factory effect.
    if ONEPLUS_EFFECT_5.is_file():
        fingerprint = ONEPLUS_EFFECT_5.read_bytes()
        release_start = round(44.0 * SAMPLE_RATE / 1000.0)
        release = fingerprint[release_start:]
        release += bytes((-len(release)) % 4)
        notification = fingerprint + bytes((-len(fingerprint)) % 4)
        write("e_163_fingerprint_release.bin", release)
        write("e_10001_fingerprint_full.bin", notification)

    # F: exact stock OnePlus effect 9 timing/envelope with its unusually low
    # sample headroom used for a stronger, still crisp quick-back response.
    if ONEPLUS_EFFECT_9.is_file():
        effect_9 = ONEPLUS_EFFECT_9.read_bytes()
        write("f_163_oplus9_2x.bin", scale_signed(effect_9, 2.0))
        write("f_163_oplus9_2_5x.bin", scale_signed(effect_9, 2.5))
        write("f_163_oplus9_3x.bin", scale_signed(effect_9, 3.0))
        write("f_163_oplus9_compressed_max.bin", compress_to_peak(
            effect_9, drive=1.5, peak_target=126))
        write("f_163_oplus9_compressed_116.bin", compress_to_peak(
            effect_9, drive=1.5, peak_target=116))


if __name__ == "__main__":
    main()
