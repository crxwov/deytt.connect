#!/usr/bin/env python3
"""Reorder and align APK entries so AndroidManifest.xml and resources are at the start of the archive.
This guarantees streaming parsers (such as SAI and Android's PackageInstaller) never need to
decompress hundreds of megabytes of native libraries across IPC pipes to read the manifest.
"""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def tool(name):
    sdk = Path(os.environ.get("ANDROID_HOME", ROOT / ".toolchain/android-sdk"))
    path = sdk / "build-tools/35.0.0" / name
    if os.name == "nt":
        path = path.with_suffix(".bat" if name == "apksigner" else ".exe")
    if not path.is_file():
        raise ValueError(f"install android build-tools 35.0.0: missing {name}")
    return str(path)


def optimize_apk(input_path: Path, output_path: Path):
    with zipfile.ZipFile(input_path, "r") as zin:
        names = zin.namelist()
        if "AndroidManifest.xml" not in names:
            raise ValueError(f"{input_path} has no AndroidManifest.xml")

        # Optimal order:
        # 1. AndroidManifest.xml (must be entry 0 for instant streaming extraction)
        # 2. resources.arsc
        # 3. classes*.dex
        # 4. assets / res / other resources
        # 5. lib/ native binaries (heavy, placed at the end)
        # 6. META-INF/
        first = ["AndroidManifest.xml", "resources.arsc"]
        dex = sorted([n for n in names if n.endswith(".dex")])
        meta = [n for n in names if n.startswith("META-INF/")]
        libs = [n for n in names if n.startswith("lib/")]
        others = [n for n in names if n not in first and n not in dex and n not in meta and n not in libs]

        ordered = [f for f in first if f in names] + dex + others + libs + meta
        assert len(ordered) == len(names), f"Entry count mismatch: {len(ordered)} vs {len(names)}"

        with tempfile.TemporaryDirectory(prefix=".opt-", dir=output_path.parent) as tmp_dir:
            temp_reordered = Path(tmp_dir) / "reordered.apk"
            with zipfile.ZipFile(temp_reordered, "w") as zout:
                for name in ordered:
                    info = zin.getinfo(name)
                    data = zin.read(name)
                    new_info = zipfile.ZipInfo(name, date_time=info.date_time)
                    new_info.compress_type = info.compress_type
                    new_info.external_attr = info.external_attr
                    zout.writestr(new_info, data)

            zipalign_bin = tool("zipalign")
            subprocess.run([zipalign_bin, "-f", "-P", "16", "4", str(temp_reordered), str(output_path)], check=True)
            subprocess.run([zipalign_bin, "-c", "-P", "16", "4", str(output_path)], check=True)

    print(f"Optimized entry order for {input_path.name} -> {output_path.name}")
    with zipfile.ZipFile(output_path, "r") as z:
        print(f"First entries: {z.namelist()[:3]}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Usage: optimize_apk_order.py <input.apk> <output.apk>")
        sys.exit(1)
    optimize_apk(Path(sys.argv[1]), Path(sys.argv[2]))
