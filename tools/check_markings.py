#!/usr/bin/env python3
"""Compares the road markings of an .obj with what actually stands in a world.

  python tools/check_markings.py <world>/region model.obj Razmetka  originX originY originZ [asphalt_block]

Assumes Y-up file, no rotation/snap (world = model + origin). For sample points on the marking triangles it looks
up the LittleTiles tile at that cell in the save and reports what is there: the marking block, another block, or nothing.
"""
import glob
import json
import math
import os
import struct
import sys
import zlib

sys.path.insert(0, os.path.dirname(__file__))
from count_lt_tiles import read_nbt  # noqa: E402


def load_tiles(region_dir, bounds):
    """Returns {(bx,by,bz): (grid, [(box6, block)])} for blocks inside bounds."""
    (x0, y0, z0, x1, y1, z1) = bounds
    tiles = {}
    for path in glob.glob(os.path.join(region_dir, "r.*.mca")):
        name = os.path.basename(path).split(".")
        rx, rz = int(name[1]), int(name[2])
        if rx * 512 > x1 or (rx + 1) * 512 < x0 or rz * 512 > z1 or (rz + 1) * 512 < z0:
            continue
        raw = open(path, "rb").read()
        for i in range(1024):
            off = struct.unpack(">I", b"\0" + raw[i * 4:i * 4 + 3])[0] * 4096
            if not off:
                continue
            length = struct.unpack(">i", raw[off:off + 4])[0]
            comp = raw[off + 4]
            data = zlib.decompress(raw[off + 5:off + 4 + length]) if comp == 2 else raw[off + 5:off + 4 + length]
            root = read_nbt(data, 0)
            for te in root.get("Level", {}).get("TileEntities", []) or []:
                if "littletiles" not in str(te.get("id", "")):
                    continue
                x, y, z = te["x"], te["y"], te["z"]
                if not (x0 <= x <= x1 and z0 <= z <= z1):
                    continue
                content = te.get("content") or {}
                grid = content.get("grid", 16) or 16
                boxes = []
                for t in content.get("tiles", []) or []:
                    blk = t.get("block", "")
                    for b in t.get("boxes", []) or ([t["bBox"]] if "bBox" in t else []):
                        if b is None:
                            continue
                        boxes.append((b, blk))
                tiles[(x, y, z)] = (grid, boxes)
    return tiles


def main():
    region, obj, mat_name = sys.argv[1], sys.argv[2], sys.argv[3]
    ox, oy, oz = int(sys.argv[4]), int(sys.argv[5]), int(sys.argv[6])
    verts = []
    tris = []
    cur = None
    with open(obj, encoding="utf-8", errors="replace") as f:
        for line in f:
            if line.startswith("v "):
                p = line.split()
                verts.append((float(p[1]), float(p[2]), float(p[3])))
            elif line.startswith("usemtl"):
                cur = line[6:].strip()
            elif line.startswith("f ") and cur == mat_name:
                idx = [int(t.split("/")[0]) for t in line.split()[1:]]
                idx = [i - 1 if i > 0 else len(verts) + i for i in idx]
                for k in range(1, len(idx) - 1):
                    tris.append((idx[0], idx[k], idx[k + 1]))
    print(f"{mat_name}: {len(tris)} triangles")
    xs = [verts[i][0] + ox for t in tris for i in t]
    zs = [verts[i][2] + oz for t in tris for i in t]
    bounds = (int(min(xs)) - 1, 0, int(min(zs)) - 1, int(max(xs)) + 1, 255, int(max(zs)) + 1)
    tiles = load_tiles(region, bounds)
    print(f"LittleTiles blocks in the marking area: {len(tiles)}")

    stats = {}
    gaps = []
    for (a, b, c) in tris:
        for s1 in range(1, 4):
            for s2 in range(1, 5 - s1):
                wa, wb = s1 / 5.0, s2 / 5.0
                wc = 1 - wa - wb
                x = wa * verts[a][0] + wb * verts[b][0] + wc * verts[c][0] + ox
                y = wa * verts[a][1] + wb * verts[b][1] + wc * verts[c][1] + oy
                z = wa * verts[a][2] + wb * verts[b][2] + wc * verts[c][2] + oz
                bx, by, bz = math.floor(x), math.floor(y), math.floor(z)
                found = None
                for dy in (0, -1):
                    entry = tiles.get((bx, by + dy, bz))
                    if entry is None:
                        continue
                    grid, boxes = entry
                    lx, ly, lz = int((x - bx) * grid), int((y - (by + dy)) * grid), int((z - bz) * grid)
                    ly = min(grid - 1, max(0, ly))
                    for (box, blk) in boxes:
                        if box[0] <= lx < box[3] and box[1] <= ly < box[4] and box[2] <= lz < box[5]:
                            found = blk
                            break
                    if found:
                        break
                key = found or "(nothing)"
                stats[key] = stats.get(key, 0) + 1
                if found is None or "concrete:7" in found:
                    if len(gaps) < 12:
                        gaps.append((round(x, 1), round(y, 2), round(z, 1), key))
    total = sum(stats.values())
    print("what stands at the marking sample points:")
    for k, v in sorted(stats.items(), key=lambda kv: -kv[1]):
        print(f"  {k}: {v} ({100.0 * v / total:.1f}%)")
    if gaps:
        print("examples of gaps (x y z -> what is there):")
        for g in gaps:
            print("  ", g)


if __name__ == "__main__":
    main()
