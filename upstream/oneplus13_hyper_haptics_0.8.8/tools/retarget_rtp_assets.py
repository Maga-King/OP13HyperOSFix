#!/usr/bin/env python3
"""Stage or apply 0916T-safe replacements for incompatible Xiaomi RTPs."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path


PROJECT = Path(__file__).resolve().parents[1]
ASSETS = PROJECT / "app" / "src" / "main" / "assets" / "rtp"
OPLUS = Path(r"E:\MIO\Coloros16\odm\etc\vibrator\9999")
GENERATED = PROJECT / "build" / "rtp-back-tests"
STAGING = PROJECT / "build" / "rtp-retarget-preview"
BACKUP = PROJECT / "rtp-original-backup-before-0916t-retarget"


# Explicit mappings preserve scene families and weak/strong pairs.  These are
# private Xiaomi IDs only; Android/OnePlus standard IDs 0-12 are never listed.
FACTORY_MAP: dict[int, tuple[str, int, str]] = {
    88: ("def", 2, "launcher selection: short factory click"),
    104: ("def", 100, "door open: factory two-stage motion"),
    106: ("def", 47, "scene step: factory four-pulse cadence"),
    114: ("def", 365, "punch: factory long three-pulse impact"),
    115: ("def", 61, "pan: same-duration factory three-pulse motion"),
    118: ("def", 5, "scene jump: full fingerprint-success envelope"),
    132: ("def", 47, "QBZ: factory multi-pulse cadence"),
    137: ("soft", 309, "P18C: soft factory impulse"),
    166: ("def", 2, "neutral feedback: short factory click"),
    168: ("soft", 309, "fingerprint record: soft factory confirmation"),
    169: ("def", 5, "lockdown: full fingerprint-success envelope"),
    174: ("def", 318, "negative button: preserve two-pulse structure"),
    175: ("soft", 363, "button: soft factory single motion"),
    178: ("def", 303, "clock unit: short factory tick"),
    180: ("soft", 364, "high key: soft factory single motion"),
    181: ("def", 310, "key unit: stronger short factory tick"),
    185: ("def", 365, "popup: factory long three-pulse motion"),
    187: ("def", 54, "switch: stronger factory sweep"),
    188: ("def", 11, "tab: factory release impulse"),
    202: ("soft", 1, "bottom boundary: weaker factory boundary"),
    203: ("soft", 309, "top boundary: stronger factory boundary"),
    301: ("soft", 109, "WeChat pat: factory soft confirmation"),
    405: ("soft", 2, "answer 1: weak factory answer"),
    406: ("soft", 309, "answer 2: strong factory answer"),
    408: ("def", 107, "dislike: preserve two-pulse negative feedback"),
    410: ("soft", 109, "generic long press: factory soft confirmation"),
}

GENERATED_MAP: dict[int, tuple[str, str]] = {
    162: ("d_162_135hz_deep.bin", "approved deep pull pulse"),
    163: ("e_163_fingerprint_release.bin", "private zero-slider/failure fallback"),
    10001: ("e_10001_fingerprint_full.bin", "approved full effect-5 notification"),
    # Earlier private aliases are retained for any old caller still using them.
    10002: ("d_162_135hz_deep.bin", "gesture pull compatibility alias"),
    10003: ("e_163_fingerprint_release.bin", "gesture release compatibility alias"),
}


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def factory_source(variant: str, identifier: int) -> Path:
    return OPLUS / variant / f"effect_{identifier}.bin"


def mapping() -> list[tuple[int, Path, str, str]]:
    result: list[tuple[int, Path, str, str]] = []
    for identifier, (variant, source_id, reason) in FACTORY_MAP.items():
        result.append((
            identifier,
            factory_source(variant, source_id),
            f"OnePlus {variant}:{source_id}",
            reason,
        ))
    for identifier, (filename, reason) in GENERATED_MAP.items():
        result.append((identifier, GENERATED / filename, filename, reason))
    return sorted(result)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true",
                        help="replace module assets after staging and backup")
    args = parser.parse_args()

    STAGING.mkdir(parents=True, exist_ok=True)
    manifest: list[dict] = []
    for identifier, source, source_label, reason in mapping():
        if not source.is_file():
            raise FileNotFoundError(source)
        destination = ASSETS / f"effect_{identifier}.bin"
        if not destination.is_file():
            raise FileNotFoundError(destination)
        staged = STAGING / destination.name
        shutil.copyfile(source, staged)
        manifest.append({
            "effect_id": identifier,
            "source": source_label,
            "reason": reason,
            "old_bytes": destination.stat().st_size,
            "old_sha256": digest(destination),
            "new_bytes": staged.stat().st_size,
            "new_sha256": digest(staged),
        })

    (STAGING / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = [
        "# 0916T RTP retarget preview", "",
        "| ID | Source | Old bytes | New bytes | Reason |",
        "|---:|---|---:|---:|---|",
    ]
    for item in manifest:
        lines.append(
            f"| {item['effect_id']} | {item['source']} | {item['old_bytes']} | "
            f"{item['new_bytes']} | {item['reason']} |")
    (STAGING / "manifest.md").write_text(
        "\n".join(lines) + "\n", encoding="utf-8")

    if args.apply:
        BACKUP.mkdir(parents=True, exist_ok=True)
        for item in manifest:
            identifier = item["effect_id"]
            destination = ASSETS / f"effect_{identifier}.bin"
            backup = BACKUP / destination.name
            if not backup.exists():
                shutil.copyfile(destination, backup)
            shutil.copyfile(STAGING / destination.name, destination)
        shutil.copyfile(STAGING / "manifest.json", BACKUP / "retarget_manifest.json")


if __name__ == "__main__":
    main()
