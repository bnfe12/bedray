#!/usr/bin/env python3
"""Scales the screenshots of the `settings_preview` autotest to 640x360 PNG files for the settings panel.

Usage: python tools/make_preview_assets.py <autotest/settings_preview dir> [output dir]
Output default: common/src/main/resources/assets/bedray/textures/gui/preview
Each file stays <= 400 KB (palette quantisation when needed); the total must stay <= 12 MB. Prints a size table.
"""
import os
import shutil
import sys

from PIL import Image

LIMIT = 400 * 1024
TOTAL_LIMIT = 12 * 1024 * 1024


def convert(src, dst):
    im = Image.open(src).convert("RGB")
    im = im.resize((640, 360), Image.LANCZOS)
    im.save(dst, "PNG", optimize=True)
    if os.path.getsize(dst) > LIMIT:
        q = im.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.FLOYDSTEINBERG)
        q.save(dst, "PNG", optimize=True)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    src_dir = sys.argv[1]
    here = os.path.dirname(os.path.abspath(__file__))
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(here, "..", "common", "src", "main", "resources", "assets", "bedray",
                                                           "textures", "gui", "preview")
    os.makedirs(out, exist_ok=True)
    total = 0
    n = 0
    for name in sorted(os.listdir(src_dir)):
        if name.endswith(".png"):
            dst = os.path.join(out, name)
            convert(os.path.join(src_dir, name), dst)
            size = os.path.getsize(dst)
            total += size
            n += 1
            print("%-44s %7.1f KB%s" % (name, size / 1024, "  OVER 400 KB" if size > LIMIT else ""))
        elif name == "manifest.properties":
            shutil.copy(os.path.join(src_dir, name), os.path.join(out, name))
    print("files: %d, total %.2f MB" % (n, total / 1024 / 1024))
    return 0 if total <= TOTAL_LIMIT else 1


if __name__ == "__main__":
    sys.exit(main())
