#!/usr/bin/env python3
"""Draw Bedray's vector mark and animated brand illustration. Requires Pillow.

Run from any directory: python tools/make_brand_assets.py
The animation is a schematic light path, not a capture of the renderer.
"""

from functools import lru_cache
from html import escape
from math import hypot
import os
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "images"
BG = "#0d1a1b"
INK = "#edf3e7"
MINT = "#b6d0b8"
GOLD = "#f2c66f"


@lru_cache(maxsize=64)
def font(size, bold=False):
    candidates = [
        Path(os.environ.get("WINDIR", "C:/Windows")) / "Fonts" / ("segoeuib.ttf" if bold else "segoeui.ttf"),
        Path("/usr/share/fonts/truetype/dejavu") / ("DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf"),
        Path("/System/Library/Fonts/Supplemental") / ("Arial Bold.ttf" if bold else "Arial.ttf"),
    ]
    for path in candidates:
        if path.is_file():
            return ImageFont.truetype(str(path), size)
    raise RuntimeError("Install Segoe UI, DejaVu Sans or Arial to render brand typography.")


class Canvas:
    def __init__(self, width, height, scale=2):
        self.width, self.height, self.scale = width, height, scale
        self.image = Image.new("RGBA", (width * scale, height * scale))
        self.draw = ImageDraw.Draw(self.image)
        self.svg = []

    def coords(self, points):
        return [(round(x * self.scale), round(y * self.scale)) for x, y in points]

    def polygon(self, points, fill):
        self.draw.polygon(self.coords(points), fill=fill)
        self.svg.append('<polygon points="' + " ".join(f"{x:g},{y:g}" for x, y in points) + f'" fill="{fill}"/>')

    def line(self, points, fill, width=1):
        coords = self.coords(points)
        self.draw.line(coords, fill=fill, width=max(1, round(width * self.scale)), joint="curve")
        # Explicit circles make joints consistent between Pillow and SVG.
        radius = width * self.scale / 2
        for x, y in coords[1:-1]:
            self.draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=fill)
        self.svg.append('<polyline points="' + " ".join(f"{x:g},{y:g}" for x, y in points)
                        + f'" fill="none" stroke="{fill}" stroke-width="{width:g}" stroke-linejoin="round"/>')

    def rect(self, box, fill, radius=0):
        x0, y0, x1, y1 = box
        self.draw.rounded_rectangle(tuple(v * self.scale for v in box), radius=radius * self.scale, fill=fill)
        self.svg.append(f'<rect x="{x0:g}" y="{y0:g}" width="{x1-x0:g}" height="{y1-y0:g}" rx="{radius:g}" fill="{fill}"/>')

    def text(self, x, y, value, size=16, fill=INK, bold=False):
        self.draw.text((x * self.scale, y * self.scale), value, font=font(size * self.scale, bold), fill=fill, anchor="lt")
        self.svg.append(f'<text x="{x:g}" y="{y+size*.8:g}" fill="{fill}" font-size="{size}" '
                        f'font-family="Segoe UI, DejaVu Sans, Arial, sans-serif" font-weight="{700 if bold else 400}">{escape(value)}</text>')

    def raster(self):
        return self.image.resize((self.width, self.height), Image.Resampling.LANCZOS)

    def save_svg(self, path, title, description):
        path.write_text(
            f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {self.width} {self.height}" role="img" aria-labelledby="title desc">\n'
            f'<title id="title">{escape(title)}</title>\n<desc id="desc">{escape(description)}</desc>\n'
            + "\n".join(self.svg) + "\n</svg>\n", encoding="utf-8", newline="\n")


RAY = [(128, 104), (344, 230), (174, 329), (282, 391)]


def partial_path(points, distance):
    out = [points[0]]
    for a, b in zip(points, points[1:]):
        length = hypot(b[0]-a[0], b[1]-a[1])
        if distance < length:
            out.append((a[0]+(b[0]-a[0])*distance/length, a[1]+(b[1]-a[1])*distance/length))
            return out
        out.append(b)
        distance -= length
    return out


def mark(c, x, y, size, tile=True, phase=None):
    k = size / 512

    def points(values):
        return [(x+px*k, y+py*k) for px, py in values]

    def poly(values, color):
        c.polygon(points(values), color)

    def line(values, color, width):
        c.line(points(values), color, width*k)

    if tile:
        c.rect((x+12*k, y+12*k, x+500*k, y+500*k), "#203735", 96*k)
        c.rect((x+16*k, y+16*k, x+496*k, y+496*k), BG, 92*k)

    # A cutaway voxel: the two interior walls and the floor form one room.
    poly([(88, 168), (256, 72), (256, 272), (88, 368)], "#305348")
    poly([(256, 72), (424, 168), (424, 368), (256, 272)], "#1c3b35")
    poly([(88, 368), (256, 272), (424, 368), (256, 464)], "#446354")

    # Broad, quiet surface divisions echo blocks without grass/dirt decoration.
    line([(172, 120), (172, 320)], "#3a5d50", 3)
    line([(340, 120), (340, 320)], "#284b40", 3)
    line([(88, 268), (256, 172), (424, 268)], "#426451", 3)
    line([(172, 320), (340, 416)], "#56745e", 3)
    line([(340, 320), (172, 416)], "#56745e", 3)
    line([(88, 168), (256, 72), (424, 168)], "#93b39a", 6)
    line([(88, 168), (88, 368), (256, 464), (424, 368), (424, 168)], "#749780", 5)
    line([(256, 72), (256, 272)], "#63856d", 4)

    # The ray has two surface bounces. Motion only advances the light packet.
    if phase is None:
        active = RAY
    else:
        total = sum(hypot(b[0]-a[0], b[1]-a[1]) for a, b in zip(RAY, RAY[1:]))
        progress = min(1.0, phase / .74)
        active = partial_path(RAY, total * (progress*progress*(3-2*progress)))
        line(RAY, "#6e6a46", 6)

    line(active, "#b58b46", 27)
    line(active, GOLD, 17)
    line(active, "#fff0bc", 5)
    for px, py in RAY[1:-1]:
        poly([(px, py-8), (px+8, py), (px, py+8), (px-8, py)], GOLD)
    sx, sy = RAY[0]
    poly([(sx, sy-15), (sx+15, sy), (sx, sy+15), (sx-15, sy)], "#fff2c4")
    if phase is not None:
        px, py = active[-1]
        poly([(px, py-9), (px+9, py), (px, py+9), (px-9, py)], "#fff9de")


def banner(phase=None, social=False):
    h = 640 if social else 400
    c = Canvas(1280, h)
    c.rect((0, 0, 1280, h), BG)
    c.rect((0, 0, 9, h), GOLD)
    y = 105 if social else 0

    # Sparse isometric guides on the scene side, drawn from the same voxel axes.
    for i in range(-3, 7):
        c.line([(735+i*70, 400+y), (1250+i*70, 103+y)], "#172c2b", 1)
        c.line([(740+i*70, 105+y), (1255+i*70, 402+y)], "#172c2b", 1)
    c.rect((35, y+20, 680, y+385), BG)
    c.text(72, y+55, "MINECRAFT JAVA  /  VULKAN", 14, MINT, True)
    mark(c, 58, y+109, 134, tile=False)
    c.text(214, y+130, "Bedray", 87, INK, True)
    c.text(76, y+249, "Light, block by block.", 29, INK)
    c.line([(78, y+315), (127, y+315)], GOLD, 3)
    c.text(144, y+307, "PATH TRACING  /  SODIUM ADD-ON", 13, MINT, True)
    mark(c, 766, y-2, 432, tile=False, phase=phase)

    if social:
        c.text(77, 549, "Early alpha", 18, GOLD, True)
        c.text(77, 580, "Minecraft Java Edition  ·  NeoForge + Fabric", 17, MINT)
        c.rect((1119, 555, 1201, 583), "#243e35", 5)
        c.text(1135, 562, "ALPHA", 12, MINT, True)
    return c


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    logo = Canvas(512, 512, scale=3)
    mark(logo, 0, 0, 512)
    logo.save_svg(OUT / "bedray-logo.svg", "Bedray logo", "A warm ray reflects twice inside an open voxel, connecting light transport with Minecraft's block-built world.")
    logo.raster().save(OUT / "bedray-logo.png", optimize=True)

    still = banner()
    still.raster().convert("RGB").save(OUT / "bedray-banner.png", optimize=True)
    still.save_svg(OUT / "bedray-banner.svg", "Bedray — light, block by block", "Brand illustration of a reflected light path. This is not gameplay footage.")
    banner(social=True).raster().convert("RGB").save(OUT / "bedray-social.png", optimize=True)

    frames = [banner(i/63).raster().convert("RGB") for i in range(64)]
    # A shared palette avoids temporal dithering and keeps the loop small.
    palette = Image.new("RGB", (1280, 1200))
    for j, i in enumerate((8, 30, 55)):
        palette.paste(frames[i], (0, j*400))
    palette = palette.quantize(colors=128, method=Image.Quantize.MEDIANCUT)
    quantized = [f.quantize(palette=palette, dither=Image.Dither.NONE) for f in frames]
    # Hold the completed path, then retrace it to make a continuous loop.
    # Most frames are static outside the light path and compress to small deltas.
    reverse = list(reversed(quantized[1:-1]))
    sequence = quantized + reverse
    sequence[0].save(OUT / "bedray-banner.gif", save_all=True, append_images=sequence[1:],
                     duration=70, loop=0, optimize=True, disposal=1)

    icon_dir = ROOT / "common" / "src" / "main" / "resources" / "assets" / "bedray"
    icon_dir.mkdir(parents=True, exist_ok=True)
    (icon_dir / "icon.png").write_bytes((OUT / "bedray-logo.png").read_bytes())
    for path in sorted(OUT.glob("bedray-*")):
        print(f"{path.relative_to(ROOT)}: {path.stat().st_size/1024:.1f} KiB")


if __name__ == "__main__":
    main()
