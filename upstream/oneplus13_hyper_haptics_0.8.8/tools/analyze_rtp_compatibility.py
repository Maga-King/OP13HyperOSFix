#!/usr/bin/env python3
"""Classify Xiaomi RTP assets against the OnePlus 13 0916T factory library.

The report is deliberately conservative: it does not rewrite assets.  It
identifies short waveforms likely to ring, rattle the chassis, clip, or end
without a brake, then suggests the closest factory OnePlus envelope as a
starting point for a private-ID reconstruction.
"""

from __future__ import annotations

import csv
import json
import math
import re
from dataclasses import asdict, dataclass
from pathlib import Path

import numpy as np


SAMPLE_RATE = 24_000
PROJECT = Path(__file__).resolve().parents[1]
ASSETS = PROJECT / "app" / "src" / "main" / "assets" / "rtp"
OUTPUT = PROJECT / "build" / "rtp-analysis"
OPLUS_ROOT = Path(r"E:\MIO\Coloros16\odm\etc\vibrator")
K80_ROOT = Path(r"F:\MIHOOK\vendor\firmware\vib_170")
MI15_ROOT = Path(r"E:\MIO\xiaomi15hyper\odm\firmware")


@dataclass
class Features:
    samples: int
    duration_ms: float
    rms: float
    peak: float
    dc: float
    clipped_percent: float
    dominant_hz: float
    spectral_concentration: float
    centroid_hz: float
    active_percent: float
    leading_quiet_ms: float
    trailing_quiet_ms: float
    tail_ratio: float
    pulse_count: int
    envelope: list[float]


def resample(values: np.ndarray, count: int = 64) -> np.ndarray:
    if len(values) == 0:
        return np.zeros(count)
    if len(values) == 1:
        return np.full(count, values[0])
    return np.interp(
        np.linspace(0.0, len(values) - 1.0, count),
        np.arange(len(values)), values)


def quiet_edge(samples: np.ndarray, reverse: bool = False) -> int:
    source = samples[::-1] if reverse else samples
    count = 0
    for value in source:
        if abs(value) > 2:
            break
        count += 1
    return count


def extract(path: Path) -> Features:
    samples = np.fromfile(path, dtype=np.int8).astype(np.float64)
    centered = samples - np.mean(samples)
    block = 24  # 1 ms at 24 kHz
    block_rms = np.array([
        math.sqrt(float(np.mean(samples[index:index + block] ** 2)))
        for index in range(0, len(samples), block)
    ])
    maximum_block = float(np.max(block_rms)) if len(block_rms) else 0.0
    active_threshold = max(3.0, maximum_block * 0.10)
    active = block_rms >= active_threshold

    # Count separated envelope attacks, not individual carrier cycles.
    smooth = np.convolve(block_rms, np.ones(3) / 3.0, mode="same")
    pulse_threshold = max(5.0, float(np.max(smooth)) * 0.28) if len(smooth) else 5.0
    pulse_count = 0
    inside = False
    for value in smooth:
        if value >= pulse_threshold and not inside:
            pulse_count += 1
            inside = True
        elif value < pulse_threshold * 0.55:
            inside = False

    fft_size = 1
    while fft_size < max(256, len(centered) * 8):
        fft_size <<= 1
    windowed = centered * np.hanning(len(centered))
    spectrum = np.abs(np.fft.rfft(windowed, fft_size)) ** 2
    frequency = np.fft.rfftfreq(fft_size, 1.0 / SAMPLE_RATE)
    band = (frequency >= 50.0) & (frequency <= 1_000.0)
    band_energy = float(np.sum(spectrum[band]))
    if band_energy > 0.0:
        band_indices = np.where(band)[0]
        peak_index = band_indices[int(np.argmax(spectrum[band]))]
        dominant = float(frequency[peak_index])
        close = np.abs(frequency - dominant) <= 15.0
        concentration = float(np.sum(spectrum[close & band]) / band_energy)
        centroid = float(np.sum(frequency[band] * spectrum[band]) / band_energy)
    else:
        dominant = concentration = centroid = 0.0

    normalized_envelope = resample(block_rms)
    envelope_peak = float(np.max(normalized_envelope)) or 1.0
    normalized_envelope /= envelope_peak
    tail_blocks = block_rms[-4:] if len(block_rms) else np.zeros(1)
    tail_ratio = float(np.mean(tail_blocks) / (maximum_block or 1.0))

    return Features(
        samples=len(samples),
        duration_ms=len(samples) * 1000.0 / SAMPLE_RATE,
        rms=math.sqrt(float(np.mean(samples ** 2))),
        peak=float(np.max(np.abs(samples))) if len(samples) else 0.0,
        dc=float(np.mean(samples)) if len(samples) else 0.0,
        clipped_percent=float(np.mean(np.abs(samples) >= 120.0) * 100.0),
        dominant_hz=dominant,
        spectral_concentration=concentration,
        centroid_hz=centroid,
        active_percent=float(np.mean(active) * 100.0) if len(active) else 0.0,
        leading_quiet_ms=quiet_edge(samples) * 1000.0 / SAMPLE_RATE,
        trailing_quiet_ms=quiet_edge(samples, True) * 1000.0 / SAMPLE_RATE,
        tail_ratio=tail_ratio,
        pulse_count=pulse_count,
        envelope=[round(float(value), 5) for value in normalized_envelope],
    )


def spring_score(feature: Features) -> tuple[float, list[str]]:
    score = 0.0
    reasons: list[str] = []
    if not 12.0 <= feature.duration_ms <= 180.0:
        return score, reasons
    if feature.spectral_concentration >= 0.42:
        score += 2.0
        reasons.append("single-tone")
    elif feature.spectral_concentration >= 0.28:
        score += 1.0
    if 65.0 <= feature.dominant_hz <= 175.0:
        score += 1.5
        reasons.append("low-resonant")
    if feature.active_percent >= 68.0:
        score += 1.0
        reasons.append("continuous")
    if feature.tail_ratio >= 0.24 and feature.trailing_quiet_ms < 0.5:
        score += 1.5
        reasons.append("unbraked-tail")
    if feature.rms >= 52.0:
        score += 1.0
        reasons.append("high-energy")
    if feature.clipped_percent >= 2.0:
        score += 1.0
        reasons.append("near-clipping")
    if abs(feature.dc) >= 4.0:
        score += 0.75
        reasons.append("dc-offset")
    return score, reasons


def distance(source: Features, target: Features) -> float:
    duration = abs(math.log((source.duration_ms + 1.0) / (target.duration_ms + 1.0)))
    rms = abs(math.log((source.rms + 3.0) / (target.rms + 3.0)))
    frequency = abs(math.log((source.dominant_hz + 40.0) / (target.dominant_hz + 40.0)))
    centroid = abs(math.log((source.centroid_hz + 80.0) / (target.centroid_hz + 80.0)))
    source_envelope = np.asarray(source.envelope)
    target_envelope = np.asarray(target.envelope)
    envelope = float(np.mean(np.abs(source_envelope - target_envelope)))
    pulses = min(3.0, abs(source.pulse_count - target.pulse_count))
    return 2.3 * duration + 0.35 * rms + 0.8 * frequency + 0.35 * centroid + 2.2 * envelope + 0.25 * pulses


def effect_id(path: Path) -> int:
    match = re.search(r"effect_(\d+)\.bin$", path.name)
    if not match:
        raise ValueError(path)
    return int(match.group(1))


def xiaomi_names() -> dict[int, str]:
    names: dict[int, str] = {}
    for root in (K80_ROOT, MI15_ROOT):
        if not root.is_dir():
            continue
        for path in root.glob("*.bin"):
            match = re.match(r"(\d+)_([^.]*)", path.name)
            if match:
                names.setdefault(int(match.group(1)), match.group(2))
    return names


def oneplus_templates() -> list[tuple[str, int, Path, Features]]:
    config = json.loads((OPLUS_ROOT / "vibrator_effect.json").read_text())
    entries: list[dict] = []

    def walk(value: object) -> None:
        if isinstance(value, dict):
            if "effect_id" in value and "effect_file" in value:
                entries.append(value)
            for child in value.values():
                walk(child)
        elif isinstance(value, list):
            for child in value:
                walk(child)

    walk(config)
    result: list[tuple[str, int, Path, Features]] = []
    for entry in entries:
        if entry.get("play_rate_hz") != SAMPLE_RATE:
            continue
        relative = str(entry["effect_file"]).removeprefix("/odm/etc/vibrator/")
        path = OPLUS_ROOT / relative
        if not path.is_file() or path.stat().st_size > SAMPLE_RATE:
            continue
        variant = "soft" if "/soft/" in str(entry["effect_file"]) else "def"
        result.append((variant, int(entry["effect_id"]), path, extract(path)))
    return result


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    templates = oneplus_templates()
    names = xiaomi_names()
    rows: list[dict] = []
    for path in sorted(ASSETS.glob("effect_*.bin"), key=effect_id):
        identifier = effect_id(path)
        feature = extract(path)
        score, reasons = spring_score(feature)
        short = path.stat().st_size <= SAMPLE_RATE
        candidates = sorted(
            ((distance(feature, candidate[3]), candidate) for candidate in templates),
            key=lambda item: item[0]) if short else []
        closest = candidates[0] if candidates else None
        rows.append({
            "effect_id": identifier,
            "name": names.get(identifier, ""),
            "kind": "short" if short else "long",
            "risk_score": round(score, 2),
            "risk_reasons": ",".join(reasons),
            "recommended_action": (
                "factory-retarget" if short and score >= 4.0
                else "review" if short and score >= 2.75
                else "long-policy" if not short
                else "keep"),
            "closest_oplus": (
                f"{closest[1][0]}:{closest[1][1]}" if closest else ""),
            "match_distance": round(closest[0], 4) if closest else "",
            **{key: value for key, value in asdict(feature).items() if key != "envelope"},
        })

    fields = list(rows[0])
    with (OUTPUT / "rtp_compatibility.csv").open("w", newline="", encoding="utf-8-sig") as output:
        writer = csv.DictWriter(output, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
    (OUTPUT / "rtp_compatibility.json").write_text(
        json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")

    retarget = [row for row in rows if row["recommended_action"] == "factory-retarget"]
    review = [row for row in rows if row["recommended_action"] == "review"]
    lines = [
        "# Xiaomi RTP compatibility report",
        "",
        f"- Total: {len(rows)}",
        f"- Short: {sum(row['kind'] == 'short' for row in rows)}",
        f"- Long/separate policy: {sum(row['kind'] == 'long' for row in rows)}",
        f"- Factory retarget: {len(retarget)}",
        f"- Manual review: {len(review)}",
        "",
        "## Factory-retarget candidates",
        "",
        "| ID | Name | Score | Reasons | Closest OnePlus | Duration | Dominant |",
        "|---:|---|---:|---|---|---:|---:|",
    ]
    for row in retarget:
        lines.append(
            f"| {row['effect_id']} | {row['name']} | {row['risk_score']} | "
            f"{row['risk_reasons']} | {row['closest_oplus']} | "
            f"{row['duration_ms']:.1f} ms | {row['dominant_hz']:.1f} Hz |")
    lines.extend(["", "## Manual-review candidates", "",
                  "| ID | Name | Score | Reasons | Closest OnePlus |",
                  "|---:|---|---:|---|---|"])
    for row in review:
        lines.append(
            f"| {row['effect_id']} | {row['name']} | {row['risk_score']} | "
            f"{row['risk_reasons']} | {row['closest_oplus']} |")
    (OUTPUT / "rtp_compatibility.md").write_text(
        "\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
