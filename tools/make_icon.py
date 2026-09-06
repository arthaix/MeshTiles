#!/usr/bin/env python3
"""Renders the MeshTiles icon (400x400 PNG) for CurseForge / GitHub: an isometric block turning into tiles,
with a wireframe cube on top, in the colours of the importer block. Pure Pillow, no external assets."""
import math
import sys

from PIL import Image, ImageDraw

SIZE = 400
BG = (24, 26, 30)
DARK = (32, 32, 32)
LIGHT = (116, 116, 116)
CYAN = (0, 229, 255)
ORANGE = (255, 136, 0)
ASPHALT = (58, 58, 62)
WHITE = (235, 235, 235)


def iso(x, y, z, ox, oy, s):
    """Isometric projection of block coords (x right, y up, z towards the viewer)."""
    px = ox + (x - z) * s * math.cos(math.radians(30))
    py = oy + (x + z) * s * math.sin(math.radians(30)) - y * s
    return px, py


def poly(draw, pts, fill, outline=None, width=1):
    draw.polygon(pts, fill=fill, outline=outline)
    if outline and width > 1:
        for i in range(len(pts)):
            draw.line([pts[i], pts[(i + 1) % len(pts)]], fill=outline, width=width)


def cube(draw, x, y, z, w, h, d, ox, oy, s, top, left, right, outline=None):
    a = iso(x, y + h, z, ox, oy, s); b = iso(x + w, y + h, z, ox, oy, s)
    c = iso(x + w, y + h, z + d, ox, oy, s); dd = iso(x, y + h, z + d, ox, oy, s)
    e = iso(x, y, z + d, ox, oy, s); f = iso(x + w, y, z + d, ox, oy, s); g = iso(x + w, y, z, ox, oy, s)
    poly(draw, [a, b, c, dd], top, outline)
    poly(draw, [dd, c, f, e], left, outline)
    poly(draw, [c, b, g, f], right, outline)


def shade(c, k):
    return tuple(max(0, min(255, int(v * k))) for v in c)


def main(out):
    img = Image.new("RGBA", (SIZE * 2, SIZE * 2), BG + (255,))
    d = ImageDraw.Draw(img)
    ox, oy, s = SIZE, SIZE * 1.05, 70

    # big base block: the importer plate (dark) with a light rim
    cube(d, 0, 0, 0, 4, 0.5, 4, ox, oy, s, DARK, shade(DARK, 0.7), shade(DARK, 0.85))
    cube(d, 0, 0.5, 0, 4, 0.25, 0.25, ox, oy, s, LIGHT, shade(LIGHT, 0.7), shade(LIGHT, 0.85))
    cube(d, 0, 0.5, 3.75, 4, 0.25, 0.25, ox, oy, s, LIGHT, shade(LIGHT, 0.7), shade(LIGHT, 0.85))
    cube(d, 0, 0.5, 0.25, 0.25, 0.25, 3.5, ox, oy, s, LIGHT, shade(LIGHT, 0.7), shade(LIGHT, 0.85))
    cube(d, 3.75, 0.5, 0.25, 0.25, 0.25, 3.5, ox, oy, s, LIGHT, shade(LIGHT, 0.7), shade(LIGHT, 0.85))
    # pivot marker
    cube(d, 0, 0.75, 3.25, 0.75, 0.5, 0.75, ox, oy, s, ORANGE, shade(ORANGE, 0.7), shade(ORANGE, 0.85))

    # tiles growing out of the plate: a small staircase of merged boxes (asphalt) with white markings
    for i, (x, z, w, dd, h) in enumerate([(1, 1, 2, 2, 0.5), (1.25, 1.25, 1.5, 1.5, 0.5), (1.5, 1.5, 1, 1, 0.5)]):
        cube(d, x, 0.75 + i * 0.5, z, w, h, dd, ox, oy, s, ASPHALT, shade(ASPHALT, 0.7), shade(ASPHALT, 0.85))
    cube(d, 1.9, 2.25, 1.2, 0.2, 0.05, 0.6, ox, oy, s, WHITE, shade(WHITE, 0.7), shade(WHITE, 0.85))

    # wireframe cube floating above (the "mesh")
    W = 3
    pts = {}
    for cx in (0, 1):
        for cy in (0, 1):
            for cz in (0, 1):
                pts[(cx, cy, cz)] = iso(0.5 + cx * 3, 2.9 + cy * 2.2, 0.5 + cz * 3, ox, oy, s)
    edges = [((0, 0, 0), (1, 0, 0)), ((0, 0, 0), (0, 1, 0)), ((0, 0, 0), (0, 0, 1)), ((1, 1, 1), (0, 1, 1)), ((1, 1, 1), (1, 0, 1)),
             ((1, 1, 1), (1, 1, 0)), ((1, 0, 0), (1, 1, 0)), ((1, 0, 0), (1, 0, 1)), ((0, 1, 0), (1, 1, 0)), ((0, 1, 0), (0, 1, 1)),
             ((0, 0, 1), ((1, 0, 1))), ((0, 0, 1), (0, 1, 1))]
    for a, b in edges:
        d.line([pts[a], pts[b]], fill=CYAN + (255,), width=W * 2)
    # a couple of diagonals to read as triangles
    for a, b in [((0, 1, 0), (1, 1, 1)), ((0, 0, 1), (1, 1, 1)), ((0, 0, 0), (1, 1, 0))]:
        d.line([pts[a], pts[b]], fill=CYAN + (150,), width=W)

    img = img.resize((SIZE, SIZE), Image.LANCZOS)
    img.save(out)
    print("written", out)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "icon.png")
