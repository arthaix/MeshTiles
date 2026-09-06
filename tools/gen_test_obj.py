#!/usr/bin/env python3
"""Generate test .obj/.mtl files for MeshTiles.

  python tools/gen_test_obj.py small  out/small.obj   # 3 materials, ~1k tris, 6x6x6 blocks
  python tools/gen_test_obj.py big    out/big.obj     # ~1M tris, 120 blocks across
  python tools/gen_test_obj.py ngon   out/ngon.obj    # quads + a 7-gon, tests fan triangulation

Units: 1 obj unit = 1 Minecraft block. Y is up.
"""
import math
import os
import sys


def write_mtl(path, mats):
    with open(path, "w") as f:
        for name, (r, g, b) in mats.items():
            f.write(f"newmtl {name}\nKa 0 0 0\nKd {r} {g} {b}\nd 1.0\n\n")


class ObjWriter:
    def __init__(self, path):
        self.f = open(path, "w")
        self.nv = 0
        self.nt = 0
        self.tris = 0

    def mtllib(self, name):
        self.f.write(f"mtllib {name}\n")

    def usemtl(self, name):
        self.f.write(f"usemtl {name}\n")

    def v(self, x, y, z):
        self.f.write(f"v {x:.5f} {y:.5f} {z:.5f}\n")
        self.nv += 1
        return self.nv

    def vt(self, u, w):
        self.f.write(f"vt {u:.5f} {w:.5f}\n")
        self.nt += 1
        return self.nt

    def face(self, *idx):
        self.f.write("f " + " ".join(str(i) for i in idx) + "\n")
        self.tris += max(0, len(idx) - 2)

    def close(self):
        self.f.close()


def box(w, x0, y0, z0, x1, y1, z1):
    a = w.v(x0, y0, z0); b = w.v(x1, y0, z0); c = w.v(x1, y1, z0); d = w.v(x0, y1, z0)
    e = w.v(x0, y0, z1); f = w.v(x1, y0, z1); g = w.v(x1, y1, z1); h = w.v(x0, y1, z1)
    # outward-facing (counter-clockwise seen from outside), like Blender exports
    for quad in ((a, d, c, b), (e, f, g, h), (a, b, f, e), (d, h, g, c), (a, e, h, d), (b, c, g, f)):
        w.face(*quad)


def sphere(w, cx, cy, cz, r, seg, rings):
    base = w.nv
    for i in range(rings + 1):
        phi = math.pi * i / rings
        for j in range(seg):
            th = 2 * math.pi * j / seg
            w.v(cx + r * math.sin(phi) * math.cos(th), cy + r * math.cos(phi), cz + r * math.sin(phi) * math.sin(th))
    for i in range(rings):
        for j in range(seg):
            a = base + i * seg + j + 1
            b = base + i * seg + (j + 1) % seg + 1
            c = a + seg
            d = b + seg
            w.face(a, b, d)
            w.face(a, d, c)


def gen_small(path):
    mtl = os.path.splitext(path)[0] + ".mtl"
    write_mtl(mtl, {"material001": (0.6, 0.6, 0.6), "material002": (0.95, 0.95, 0.9), "material003": (0.4, 0.25, 0.1)})
    w = ObjWriter(path)
    w.mtllib(os.path.basename(mtl))
    w.usemtl("material001")
    box(w, 0, 0, 0, 6, 1, 6)            # stone slab floor 6x1x6
    w.usemtl("material002")
    sphere(w, 3, 3.5, 3, 2.2, 32, 16)   # quartz sphere
    w.usemtl("material003")
    box(w, 5.5, 1, 5.5, 6, 6, 6)        # dirt pillar (half block thick)
    w.close()
    return w


def gen_big(path):
    mtl = os.path.splitext(path)[0] + ".mtl"
    write_mtl(mtl, {"material001": (0.5, 0.5, 0.5), "material002": (0.9, 0.9, 0.9), "material003": (0.3, 0.6, 0.2)})
    w = ObjWriter(path)
    w.mtllib(os.path.basename(mtl))
    w.usemtl("material001")
    # ground plate 120x1x120 subdivided into 300x300 quads => 180k tris
    n = 300
    base = w.nv
    for i in range(n + 1):
        for j in range(n + 1):
            w.v(i * 120.0 / n, 0.3 * math.sin(i * 0.2) * math.cos(j * 0.2), j * 120.0 / n)
    for i in range(n):
        for j in range(n):
            a = base + i * (n + 1) + j + 1
            w.face(a, a + 1, a + n + 2, a + n + 1)
    w.usemtl("material002")
    # dense spheres => ~800k tris
    for k in range(20):
        sphere(w, 10 + (k % 5) * 25, 12, 10 + (k // 5) * 25, 8, 200, 100)
    w.usemtl("material003")
    for k in range(6):
        box(w, k * 20, 1, 100, k * 20 + 3, 30, 103)
    w.close()
    return w


def gen_ngon(path):
    mtl = os.path.splitext(path)[0] + ".mtl"
    write_mtl(mtl, {"material001": (1, 0, 0)})
    w = ObjWriter(path)
    w.mtllib(os.path.basename(mtl))
    w.usemtl("material001")
    box(w, 0, 0, 0, 4, 4, 4)
    ids = [w.v(6 + 2 * math.cos(2 * math.pi * i / 7), 0, 6 + 2 * math.sin(2 * math.pi * i / 7)) for i in range(7)]
    w.face(*reversed(ids))  # normal +Y
    w.close()
    return w


if __name__ == "__main__":
    kind, out = sys.argv[1], sys.argv[2]
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    w = {"small": gen_small, "big": gen_big, "ngon": gen_ngon}[kind](out)
    print(f"{out}: {w.nv} vertices, {w.tris} triangles")
