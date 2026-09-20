#!/usr/bin/env python3
"""Build louder 0916T versions of private OnePlus-template RTP assets.

The driver voltage is already saturated at the module slider maximum.  This
therefore expands the signed 8-bit waveform itself.  A normalized tanh curve
raises low/mid-level samples by roughly 1.5x without introducing the flat
clipped plateaus produced by a plain integer multiply.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import shutil
from pathlib import Path

from retarget_rtp_assets import (
    ASSETS,
    FACTORY_MAP,
    GENERATED,
    factory_source,
)


PROJECT = Path(__file__).resolve().parents[1]
OUTPUT = PROJECT / "build" / "rtp-loud-preview"
BACKUP = PROJECT / "rtp-original-backup-before-private-loudness-boost"
DRIVE = 1.5


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def signed(value: int) -> int:
    return value - 256 if value >= 128 else value


def soft_boost(data: bytes) -> bytes:
    denominator = math.tanh(DRIVE)
    result = bytearray()
    for raw in data:
        sample = signed(raw)
        if sample == 0:
            boosted = 0
        else:
            boosted = round(
                127.0 * math.tanh(DRIVE * sample / 127.0) / denominator)
            boosted = max(-127, min(127, boosted))
        result.append(boosted & 0xFF)
    return bytes(result)


def rms(data: bytes) -> float:
    values = [signed(value) for value in data]
    return math.sqrt(sum(value * value for value in values) / len(values))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    OUTPUT.mkdir(parents=True, exist_ok=True)
    report: list[dict] = []
    for effect_id, (variant, source_id, reason) in sorted(FACTORY_MAP.items()):
        source = factory_source(variant, source_id)
        original = source.read_bytes()
        boosted = soft_boost(original)
        destination = OUTPUT / f"effect_{effect_id}.bin"
        destination.write_bytes(boosted)
        report.append({
            "effect_id": effect_id,
            "source": f"OnePlus {variant}:{source_id}",
            "reason": reason,
            "bytes": len(boosted),
            "rms_before": round(rms(original), 2),
            "rms_after": round(rms(boosted), 2),
            "rms_ratio": round(rms(boosted) / max(rms(original), 0.01), 3),
            "sha256": digest(boosted),
        })

    # Long-press notification now reuses the approved deep return-pull pulse.
    # This removes the quiet 16 ms gap present in the full fingerprint effect.
    notification = (GENERATED / "d_162_135hz_deep.bin").read_bytes()
    (OUTPUT / "effect_10001.bin").write_bytes(notification)
    report.append({
        "effect_id": 10001,
        "source": "approved return 162 deep pulse",
        "reason": "audible notification long-press without a silent middle gap",
        "bytes": len(notification),
        "rms_before": None,
        "rms_after": round(rms(notification), 2),
        "rms_ratio": None,
        "sha256": digest(notification),
    })

    (OUTPUT / "manifest.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    if args.apply:
        BACKUP.mkdir(parents=True, exist_ok=True)
        for item in report:
            destination = ASSETS / f"effect_{item['effect_id']}.bin"
            backup = BACKUP / destination.name
            if not backup.exists():
                shutil.copyfile(destination, backup)
            shutil.copyfile(OUTPUT / destination.name, destination)
        shutil.copyfile(OUTPUT / "manifest.json", BACKUP / "loudness_manifest.json")


if __name__ == "__main__":
    main()
