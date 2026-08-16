#!/usr/bin/env python3
"""Create a reviewable Minecraft-vanilla sound palette from local client assets.

The script reads metadata and OGG files already installed by the official launcher.
It writes measurements only; no Minecraft audio is copied or redistributed.
"""

from __future__ import annotations

import argparse
import json
import math
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

SAMPLE_RATE = 22_050
FFT_SIZE = 8_192
TONAL_KEYWORDS = (
    "note_block", "bell", "chime", "amethyst", "allay", "beacon", "conduit",
    "portal", "respawn_anchor", "sculk", "enchant", "experience", "firework",
    "enderman", "ghast", "warden", "goat_horn", "music_disc",
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft-dir", type=Path, required=True, help="Official launcher game directory")
    parser.add_argument("--version", required=True, help="Installed client version, e.g. 1.21.4")
    parser.add_argument("--output", type=Path, required=True, help="Generated YAML candidate catalog")
    parser.add_argument("--all-events", action="store_true", help="Analyze every event instead of the tonal candidate set")
    parser.add_argument("--limit", type=int, default=0, help="Maximum events to analyze (0 means no limit)")
    args = parser.parse_args()

    version_json = args.minecraft_dir / "versions" / args.version / f"{args.version}.json"
    if not version_json.is_file():
        fail(f"Could not find installed client files for {args.version} under {args.minecraft_dir}")
    metadata = json.loads(version_json.read_text())
    asset_index_id = metadata["assetIndex"]["id"]
    index_path = args.minecraft_dir / "assets" / "indexes" / f"{asset_index_id}.json"
    if not index_path.is_file():
        fail(f"Missing local asset index {asset_index_id}: {index_path}")
    asset_index = json.loads(index_path.read_text())["objects"]

    sounds_definition = asset_path(args.minecraft_dir, asset_index, "minecraft/sounds.json")
    if sounds_definition is None:
        fail("Local asset index does not contain minecraft/sounds.json")
    sounds = json.loads(sounds_definition.read_text())
    event_paths = resolve_events(sounds)
    events = [(name, paths) for name, paths in sorted(event_paths.items()) if args.all_events or is_tonal_candidate(name)]
    if args.limit:
        events = events[:args.limit]
    if not events:
        fail("No matching sound events found")

    print(f"Analyzing {len(events)} sound events from Minecraft {args.version} (asset index {asset_index_id})…")
    sample_cache: dict[str, Measurement | None] = {}
    candidates: list[tuple[str, list[str], list[Measurement]]] = []
    for number, (event, resources) in enumerate(events, 1):
        measurements = []
        for resource in resources:
            asset = asset_index.get(resource)
            if not asset:
                continue
            digest = asset["hash"]
            if digest not in sample_cache:
                path = args.minecraft_dir / "assets" / "objects" / digest[:2] / digest
                sample_cache[digest] = measure(path) if path.is_file() else None
            if sample_cache[digest] is not None:
                measurements.append(sample_cache[digest])
        if measurements:
            candidates.append((event, resources, measurements))
        if number % 25 == 0 or number == len(events):
            print(f"  {number}/{len(events)} events", file=sys.stderr)

    write_catalog(args.output, args.version, asset_index_id, candidates)
    print(f"Wrote {len(candidates)} analyzed events to {args.output}")
    return 0


def resolve_events(sounds: dict) -> dict[str, list[str]]:
    resolved: dict[str, list[str]] = {}
    for event, definition in sounds.items():
        entries = definition.get("sounds", []) if isinstance(definition, dict) else definition
        paths = []
        for entry in entries:
            name = entry if isinstance(entry, str) else entry.get("name")
            if not name or entry is not None and isinstance(entry, dict) and entry.get("type") == "event":
                continue
            namespace, _, sound = name.partition(":")
            if not sound:
                namespace, sound = "minecraft", namespace
            if namespace == "minecraft":
                paths.append(f"minecraft/sounds/{sound}.ogg")
        if paths:
            resolved[f"minecraft:{event}"] = paths
    return resolved


def asset_path(minecraft_dir: Path, asset_index: dict, resource: str) -> Path | None:
    asset = asset_index.get(resource)
    if asset is None:
        return None
    digest = asset["hash"]
    return minecraft_dir / "assets" / "objects" / digest[:2] / digest


def is_tonal_candidate(event: str) -> bool:
    return any(keyword in event for keyword in TONAL_KEYWORDS)


def measure(path: Path) -> "Measurement | None":
    command = ["ffmpeg", "-v", "error", "-i", str(path), "-ac", "1", "-ar", str(SAMPLE_RATE), "-t", "2", "-f", "s16le", "-"]
    result = subprocess.run(command, capture_output=True)
    if result.returncode != 0 or len(result.stdout) < FFT_SIZE * 2:
        return None
    samples = [int.from_bytes(result.stdout[index:index + 2], "little", signed=True) / 32768.0 for index in range(0, len(result.stdout), 2)]
    rms = math.sqrt(sum(sample * sample for sample in samples) / len(samples))
    start = min(int(SAMPLE_RATE * 0.06), max(0, len(samples) - FFT_SIZE))
    window = samples[start:start + FFT_SIZE]
    magnitudes = fft_magnitudes(window)
    low_bin, high_bin = max(1, int(40 * FFT_SIZE / SAMPLE_RATE)), min(len(magnitudes) - 1, int(2_500 * FFT_SIZE / SAMPLE_RATE))
    band = magnitudes[low_bin:high_bin]
    peak_offset = max(range(len(band)), key=band.__getitem__)
    peak_bin = low_bin + peak_offset
    peak = magnitudes[peak_bin]
    average = sum(band) / len(band)
    frequency = peak_bin * SAMPLE_RATE / FFT_SIZE
    midi = 69.0 + 12.0 * math.log2(frequency / 440.0)
    confidence = max(0.0, min(1.0, (peak / max(average, 1e-9) - 2.0) / 20.0))
    duration_ms = round(len(samples) * 1000 / SAMPLE_RATE)
    return Measurement(midi, confidence, duration_ms, rms)


def fft_magnitudes(samples: list[float]) -> list[float]:
    values = [complex(sample * (0.5 - 0.5 * math.cos(2 * math.pi * index / (len(samples) - 1))), 0) for index, sample in enumerate(samples)]
    size = len(values)
    index = 1
    for position in range(1, size):
        bit = size >> 1
        while index & bit:
            index ^= bit
            bit >>= 1
        index ^= bit
        if position < index:
            values[position], values[index] = values[index], values[position]
    width = 2
    while width <= size:
        rotation = complex(math.cos(-2 * math.pi / width), math.sin(-2 * math.pi / width))
        for offset in range(0, size, width):
            factor = 1 + 0j
            half = width // 2
            for index in range(half):
                even, odd = values[offset + index], factor * values[offset + index + half]
                values[offset + index], values[offset + index + half] = even + odd, even - odd
                factor *= rotation
        width *= 2
    return [abs(value) for value in values[:size // 2]]


def write_catalog(output: Path, version: str, asset_index: str, candidates: list[tuple[str, list[str], list["Measurement"]]]) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        f"minecraft-version: '{version}'",
        f"asset-index: '{asset_index}'",
        "generated-by: vanilla_sound_catalog.py",
        "profiles:",
    ]
    for event, resources, measurements in candidates:
        pitch = sum(measurement.midi for measurement in measurements) / len(measurements)
        confidence = min(measurement.confidence for measurement in measurements)
        duration = round(sum(measurement.duration_ms for measurement in measurements) / len(measurements))
        rms = sum(measurement.rms for measurement in measurements) / len(measurements)
        lines.extend([
            f"  '{event}':",
            f"    sample-variants: {len(resources)}",
            f"    base-midi-estimate: {pitch:.2f}",
            f"    tonal-confidence: {confidence:.3f}",
            f"    duration-ms: {duration}",
            f"    rms: {rms:.4f}",
            "    review: pending",
            f"    suggested-role: {suggest_role(event, pitch, confidence)}",
        ])
    output.write_text("\n".join(lines) + "\n")


def suggest_role(event: str, pitch: float, confidence: float) -> str:
    if "note_block" in event or "bell" in event or "chime" in event: return "tonal-core"
    if "amethyst" in event: return "crystal"
    if "allay" in event: return "airy-lead"
    if "sculk" in event: return "texture"
    if confidence < 0.20: return "review-for-texture"
    if pitch < 54: return "low-tone"
    if pitch > 72: return "high-tone"
    return "mid-tone"


class Measurement:
    def __init__(self, midi: float, confidence: float, duration_ms: int, rms: float):
        self.midi = midi
        self.confidence = confidence
        self.duration_ms = duration_ms
        self.rms = rms


def fail(message: str) -> None:
    raise SystemExit(f"error: {message}")


if __name__ == "__main__":
    raise SystemExit(main())
