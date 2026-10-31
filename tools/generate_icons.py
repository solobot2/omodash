#!/usr/bin/env python3
"""Render the canonical neon SVG, preserving its glow in Android launcher icons."""
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "branding/omodash-icon.svg"
RES = ROOT / "app/src/main/res"


def adaptive_foreground():
    namespace = "http://www.w3.org/2000/svg"
    ET.register_namespace("", namespace)
    root = ET.parse(SOURCE).getroot()
    group = ET.Element("{" + namespace + "}g", {
        # Include the neon halo in the circular adaptive icon's safe area.
        "transform": "translate(512 512) scale(0.72) translate(-512 -512)"
    })
    for element in list(root):
        if element.attrib.get("id") == "background":
            root.remove(element)
        elif element.tag not in {"{" + namespace + "}" + name for name in ("defs", "title", "desc")}:
            root.remove(element)
            group.append(element)
    root.append(group)
    return ET.tostring(root, encoding="utf-8")


def main():
    renderer = shutil.which("rsvg-convert")
    if not renderer:
        raise SystemExit("rsvg-convert is required (macOS: brew install librsvg).")
    # VectorDrawable cannot render SVG blur filters. Rasterize the same SVG for
    # every Android density so adaptive and legacy icons keep the actual glow.
    for obsolete in ("drawable/ic_launcher_foreground.xml", "mipmap-anydpi/ic_launcher.xml",
                     "mipmap-anydpi/ic_launcher_round.xml"):
        (RES / obsolete).unlink(missing_ok=True)
    (RES / "mipmap-anydpi-v26").mkdir(parents=True, exist_ok=True)
    for name in ("ic_launcher", "ic_launcher_round"):
        (RES / f"mipmap-anydpi-v26/{name}.xml").write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@color/launcher_background" />\n'
            '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
            '</adaptive-icon>\n')
    foreground = adaptive_foreground()
    for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        destination = RES / f"mipmap-{density}"
        destination.mkdir(parents=True, exist_ok=True)
        target = destination / "ic_launcher.png"
        subprocess.run([renderer, "--width", str(size), "--height", str(size), "--output", str(target), str(SOURCE)], check=True)
        shutil.copyfile(target, destination / "ic_launcher_round.png")
        destination = RES / f"drawable-{density}"
        destination.mkdir(parents=True, exist_ok=True)
        pixels = str(size * 108 // 48)
        subprocess.run([renderer, "--width", pixels, "--height", pixels, "--output",
                        str(destination / "ic_launcher_foreground.png")], input=foreground, check=True)
    for size in (1024, 2048):
        subprocess.run([renderer, "--width", str(size), "--height", str(size), "--output",
                        str(ROOT / f"branding/omodash-icon-{size}.png"), str(SOURCE)], check=True)
    print("Generated neon adaptive and legacy launcher icons, plus 1024px and 2048px master PNGs.")


if __name__ == "__main__":
    main()
