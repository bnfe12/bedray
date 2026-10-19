"""Writes a tiny LabPBR 1.3 test resource pack (for the rt_modes autotest, step c of docs/experiments/rt-modes.md).

Usage: python -I scripts/make-labpbr-testpack.py <resourcepacks dir>
Creates <dir>/vkrtx_labpbr_test/ with:
  smooth_stone_s.png   smoothness 230, F0 10/255, no emission       -> glossy floor (reflections)
  iron_block_s.png     smoothness 255, green 230 (iron)              -> mirror metal
  diamond_block_s.png  smoothness 200, F0 40, emission alpha 200     -> LabPBR emission
  white_concrete_n.png sine bumps (DirectX normal, AO 255, height)   -> normal mapping on the walls
Spec: https://shaderlabs.org/wiki/LabPBR_Material_Standard (1.3). Pack format 97 = resource_major of MC 26.3 (version.json).
"""
import json
import math
import os
import sys

from PIL import Image


def solid(path, rgba, size=16):
    img = Image.new("RGBA", (size, size), rgba)
    img.save(path)


def bumps(path, size=16):
    img = Image.new("RGBA", (size, size))
    for y in range(size):
        for x in range(size):
            # height h = sin(2 pi x / 8) * sin(2 pi y / 8); normal = normalize(-dh/dx, -dh/dy, 1)
            k = 2 * math.pi / 8
            dhdx = 0.6 * k * math.cos(k * x) * math.sin(k * y)
            dhdy = 0.6 * k * math.sin(k * x) * math.cos(k * y)
            nx, ny, nz = -dhdx, -dhdy, 1.0
            ln = math.sqrt(nx * nx + ny * ny + nz * nz)
            nx, ny = nx / ln, ny / ln
            # DirectX: green points down (+y of the image) -> the image y axis is already "down"
            r = int(round((nx * 0.5 + 0.5) * 255))
            g = int(round((ny * 0.5 + 0.5) * 255))
            img.putpixel((x, y), (r, g, 255, 255))
    img.save(path)


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    root = os.path.join(sys.argv[1], "vkrtx_labpbr_test")
    tex = os.path.join(root, "assets", "minecraft", "textures", "block")
    os.makedirs(tex, exist_ok=True)
    meta = {"pack": {"description": "VKB RT LabPBR test pack", "pack_format": 97, "min_format": 97, "max_format": 97}}
    with open(os.path.join(root, "pack.mcmeta"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(meta, f, indent=2)
    solid(os.path.join(tex, "smooth_stone_s.png"), (230, 10, 0, 255))
    solid(os.path.join(tex, "iron_block_s.png"), (255, 230, 0, 255))
    solid(os.path.join(tex, "diamond_block_s.png"), (200, 40, 0, 200))
    bumps(os.path.join(tex, "white_concrete_n.png"))
    print("written", root)


if __name__ == "__main__":
    main()
