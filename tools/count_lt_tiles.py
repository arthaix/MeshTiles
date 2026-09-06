#!/usr/bin/env python3
"""Count LittleTiles tile entities in an Anvil world: python tools/count_lt_tiles.py <world>/region"""
import glob
import os
import struct
import sys
import zlib


def read_nbt(data, pos):
    def tag(t, p):
        if t == 0:
            return None, p
        if t == 1:
            return data[p], p + 1
        if t == 2:
            return struct.unpack(">h", data[p:p + 2])[0], p + 2
        if t == 3:
            return struct.unpack(">i", data[p:p + 4])[0], p + 4
        if t == 4:
            return struct.unpack(">q", data[p:p + 8])[0], p + 8
        if t == 5:
            return struct.unpack(">f", data[p:p + 4])[0], p + 4
        if t == 6:
            return struct.unpack(">d", data[p:p + 8])[0], p + 8
        if t == 7:
            n = struct.unpack(">i", data[p:p + 4])[0]
            return None, p + 4 + n
        if t == 8:
            n = struct.unpack(">H", data[p:p + 2])[0]
            return data[p + 2:p + 2 + n].decode("utf-8", "replace"), p + 2 + n
        if t == 9:
            st = data[p]
            n = struct.unpack(">i", data[p + 1:p + 5])[0]
            p += 5
            out = []
            for _ in range(n):
                v, p = tag(st, p)
                out.append(v)
            return out, p
        if t == 10:
            out = {}
            while True:
                st = data[p]
                p += 1
                if st == 0:
                    return out, p
                n = struct.unpack(">H", data[p:p + 2])[0]
                name = data[p + 2:p + 2 + n].decode("utf-8", "replace")
                p += 2 + n
                v, p = tag(st, p)
                out[name] = v
        if t == 11:
            n = struct.unpack(">i", data[p:p + 4])[0]
            return None, p + 4 + 4 * n
        if t == 12:
            n = struct.unpack(">i", data[p:p + 4])[0]
            return None, p + 4 + 8 * n
        raise ValueError("bad tag %d" % t)

    t = data[pos]
    n = struct.unpack(">H", data[pos + 1:pos + 3])[0]
    return tag(t, pos + 3 + n)[0]


def main(region_dir):
    total = 0
    minp = [10 ** 9] * 3
    maxp = [-10 ** 9] * 3
    per_y = {}
    for path in glob.glob(os.path.join(region_dir, "r.*.mca")):
        with open(path, "rb") as f:
            raw = f.read()
        for i in range(1024):
            off = struct.unpack(">I", b"\0" + raw[i * 4:i * 4 + 3])[0] * 4096
            if off == 0:
                continue
            length = struct.unpack(">i", raw[off:off + 4])[0]
            comp = raw[off + 4]
            payload = raw[off + 5:off + 4 + length]
            data = zlib.decompress(payload) if comp == 2 else payload
            root = read_nbt(data, 0)
            for te in root.get("Level", {}).get("TileEntities", []) or []:
                if "littletiles" in str(te.get("id", "")):
                    total += 1
                    p = (te["x"], te["y"], te["z"])
                    for k in range(3):
                        minp[k] = min(minp[k], p[k])
                        maxp[k] = max(maxp[k], p[k])
                    per_y[p[1]] = per_y.get(p[1], 0) + 1
    print("LittleTiles tile entities:", total)
    if total:
        print("bounds:", minp, "-", maxp)
        print("per y:", sorted(per_y.items()))


if __name__ == "__main__":
    main(sys.argv[1])
