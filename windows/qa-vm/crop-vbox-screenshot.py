#!/usr/bin/env python3
"""Fail-closed mapping from Windows desktop coordinates to a VBox PNG."""

import json
import math
import os
import sys
from pathlib import Path

from PIL import Image


def is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def mapped_crop(ready: dict, run_id: str, image_size: tuple[int, int]) -> tuple[int, int, int, int]:
    if ready.get("schema") != 1 or ready.get("run_id") != run_id or ready.get("fixture") != "DeyttConnect.Windows.QA":
        raise ValueError("window-ready metadata mismatch")
    if not is_int(ready.get("process_id")) or ready["process_id"] <= 0:
        raise ValueError("fixture process id missing")

    bounds = ready.get("bounds")
    desktop = bounds.get("desktop") if isinstance(bounds, dict) else None
    if not isinstance(desktop, dict):
        raise ValueError("guest desktop geometry missing")
    if not all(is_int(desktop.get(k)) for k in ("monitor_count", "left", "top")):
        raise ValueError("guest monitor geometry invalid")
    if desktop["monitor_count"] != 1 or desktop["left"] != 0 or desktop["top"] != 0:
        raise ValueError("only a single origin-aligned guest display can be cropped")

    guest_width, guest_height = desktop.get("width"), desktop.get("height")
    if not all(is_int(v) and 0 < v <= 16384 for v in (guest_width, guest_height)):
        raise ValueError("guest display dimensions invalid")
    png_width, png_height = image_size
    if not all(is_int(v) and 0 < v <= 16384 for v in (png_width, png_height)):
        raise ValueError("VBox screenshot dimensions invalid")

    bounds_keys = ("left", "top", "right", "bottom")
    if not all(is_int(bounds.get(k)) for k in bounds_keys):
        raise ValueError("window bounds invalid")
    left, top, right, bottom = (bounds[k] for k in bounds_keys)
    width, height = right - left, bottom - top
    if width < 320 or height < 240 or width > 4096 or height > 4096:
        raise ValueError("window bounds outside capture policy")
    if left < 0 or top < 0 or right > guest_width or bottom > guest_height:
        raise ValueError("window lies outside the single guest display")

    # DPI virtualization may make Win32 coordinates logical while the VBox PNG
    # contains framebuffer pixels. Permit only one uniform full-display scale.
    scale_x, scale_y = png_width / guest_width, png_height / guest_height
    if not 0.25 <= scale_x <= 4.0 or not 0.25 <= scale_y <= 4.0:
        raise ValueError("guest-to-PNG scale outside supported range")
    if not math.isclose(scale_x, scale_y, rel_tol=0.005, abs_tol=0.001):
        raise ValueError("guest and PNG aspect ratios do not match")

    crop_left = math.floor(left * scale_x)
    crop_top = math.floor(top * scale_y)
    crop_right = math.ceil(right * scale_x)
    crop_bottom = math.ceil(bottom * scale_y)
    if crop_left < 0 or crop_top < 0 or crop_right > png_width or crop_bottom > png_height:
        raise ValueError("mapped window exceeds VBox screenshot dimensions")
    if crop_right - crop_left < 320 or crop_bottom - crop_top < 240:
        raise ValueError("mapped crop is below minimum dimensions")
    return crop_left, crop_top, crop_right, crop_bottom


def self_test() -> None:
    ready = {
        "schema": 1,
        "run_id": "test-run",
        "fixture": "DeyttConnect.Windows.QA",
        "process_id": 123,
        "bounds": {
            "left": 100,
            "top": 80,
            "right": 600,
            "bottom": 480,
            "desktop": {"monitor_count": 1, "left": 0, "top": 0, "width": 800, "height": 600},
        },
    }
    assert mapped_crop(ready, "test-run", (800, 600)) == (100, 80, 600, 480)
    assert mapped_crop(ready, "test-run", (1600, 1200)) == (200, 160, 1200, 960)
    for mutate, png_size in (
        (lambda x: x["bounds"]["desktop"].update(monitor_count=2), (800, 600)),
        (lambda x: x["bounds"]["desktop"].update(left=-800), (800, 600)),
        (lambda x: x["bounds"]["desktop"].update(height=700), (800, 600)),
        (lambda x: x["bounds"].update(right=801), (800, 600)),
    ):
        candidate = json.loads(json.dumps(ready))
        mutate(candidate)
        try:
            mapped_crop(candidate, "test-run", png_size)
        except ValueError:
            continue
        raise AssertionError("unsafe geometry was accepted")
    screenshot = Image.new("RGB", (1600, 1200), "#243447")
    crop = screenshot.crop(mapped_crop(ready, "test-run", screenshot.size))
    assert crop.size == (1000, 800)
    print("screenshot geometry self-test passed (1x, scaled DPI, multi-monitor and mismatch gates)")


def main(argv: list[str]) -> int:
    if argv == ["--self-test"]:
        self_test()
        return 0
    if len(argv) != 4:
        print("usage: crop-vbox-screenshot.py <window-ready.json> <run-id> <full.png> <crop.png>", file=sys.stderr)
        return 64

    ready_path, run_id, full_path, crop_path = argv
    temporary = crop_path + ".tmp"
    created_temporary = False
    try:
        ready = json.loads(Path(ready_path).read_text(encoding="utf-8"))
        with Image.open(full_path) as image:
            crop_box = mapped_crop(ready, run_id, image.size)
            crop = image.crop(crop_box)
            file_descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            created_temporary = True
            with os.fdopen(file_descriptor, "wb") as stream:
                crop.save(stream, format="PNG")
        os.replace(temporary, crop_path)
        created_temporary = False
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        if created_temporary:
            Path(temporary).unlink(missing_ok=True)
        print(f"screenshot crop rejected: {exc}", file=sys.stderr)
        return 2
    print("Fixture window crop created; visual confirmation is required before guest cleanup.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
