#!/usr/bin/env python3
"""Create static font instances for Avalonia from the Android client's OFL fonts."""

from pathlib import Path

from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/assets/fonts"
DESTINATION = ROOT / "windows/DeyttConnect.Windows/Assets/Fonts"

FONTS = {
    "inter-tight-variable.ttf": ("Inter Tight", ((400, "Regular"), (600, "SemiBold"), (700, "Bold"))),
    "unbounded-variable.ttf": ("Unbounded", ((400, "Regular"), (600, "SemiBold"), (700, "Bold"))),
    "jetbrains-mono-variable.ttf": ("JetBrains Mono", ((400, "Regular"), (600, "SemiBold"))),
}

FAMILY_NAME_IDS = {1, 16, 21}
SUBFAMILY_NAME_IDS = {2, 17, 22}
FULL_NAME_IDS = {4, 18}


def replace_name(font: TTFont, name_id: int, value: str) -> None:
    for record in font["name"].names:
        if record.nameID == name_id:
            record.string = value.encode(record.getEncoding(), errors="replace")


def main() -> None:
    DESTINATION.mkdir(parents=True, exist_ok=True)
    for filename, (family, instances) in FONTS.items():
        for weight, style in instances:
            font = TTFont(SOURCE / filename)
            static = instantiateVariableFont(font, {"wght": weight}, inplace=True)
            full_name = f"{family} {style}"
            postscript_name = f"{family.replace(' ', '')}-{style}"
            unique_name = f"DEYTT {family} {style} {weight}"

            for name_id in FAMILY_NAME_IDS:
                replace_name(static, name_id, family)
            for name_id in SUBFAMILY_NAME_IDS:
                replace_name(static, name_id, style)
            for name_id in FULL_NAME_IDS:
                replace_name(static, name_id, full_name)
            replace_name(static, 3, unique_name)
            replace_name(static, 6, postscript_name)
            static["OS/2"].usWeightClass = weight
            static.save(DESTINATION / f"{family.replace(' ', '')}-{style}.ttf")
            font.close()
            static.close()


if __name__ == "__main__":
    main()
