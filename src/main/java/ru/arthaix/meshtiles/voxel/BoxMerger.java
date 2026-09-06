package ru.arthaix.meshtiles.voxel;

/**
 * Greedy merging of equal voxels inside one block into as few axis-aligned boxes as possible.
 * Classic "greedy meshing" in 3D: grow along X, then extend the X-run along Y, then the XY-rectangle along Z.
 */
public final class BoxMerger {

    private BoxMerger() {}

    /**
     * @param cells   dense region buffer (tile ids, 0 = empty)
     * @param stride  region edge length in cells (index = x + stride * (y + stride * z))
     * @param ox      x offset of the block inside the region (in cells)
     * @param grid    block edge length in cells
     * @param scratch reusable boolean buffer of at least grid^3 entries, cleared by this method
     * @param out     reusable output buffer (grown if needed); returns the number of boxes written
     */
    public static int mergeBlock(char[] cells, int stride, int ox, int oy, int oz, int grid, boolean[] scratch, long[][] out) {
        java.util.Arrays.fill(scratch, 0, grid * grid * grid, false);
        long[] boxes = out[0];
        int n = 0;
        int stride2 = stride * stride;
        for (int z = 0; z < grid; z++) {
            for (int y = 0; y < grid; y++) {
                int rowBase = (ox) + stride * ((oy + y) + stride * (oz + z));
                for (int x = 0; x < grid; x++) {
                    int si = x + grid * (y + grid * z);
                    if (scratch[si]) continue;
                    int id = cells[rowBase + x];
                    if (id == 0) continue;

                    // extend along X
                    int x2 = x + 1;
                    while (x2 < grid && !scratch[si + (x2 - x)] && cells[rowBase + x2] == id) x2++;

                    // extend along Y
                    int y2 = y + 1;
                    outerY:
                    while (y2 < grid) {
                        int base = ox + stride * ((oy + y2) + stride * (oz + z));
                        int sbase = grid * (y2 + grid * z);
                        for (int xx = x; xx < x2; xx++)
                            if (scratch[sbase + xx] || cells[base + xx] != id) break outerY;
                        y2++;
                    }

                    // extend along Z
                    int z2 = z + 1;
                    outerZ:
                    while (z2 < grid) {
                        for (int yy = y; yy < y2; yy++) {
                            int base = ox + stride * ((oy + yy) + stride * (oz + z2));
                            int sbase = grid * (yy + grid * z2);
                            for (int xx = x; xx < x2; xx++)
                                if (scratch[sbase + xx] || cells[base + xx] != id) break outerZ;
                        }
                        z2++;
                    }

                    for (int zz = z; zz < z2; zz++)
                        for (int yy = y; yy < y2; yy++) {
                            int sbase = grid * (yy + grid * zz);
                            for (int xx = x; xx < x2; xx++) scratch[sbase + xx] = true;
                        }

                    if (n == boxes.length) {
                        long[] grown = new long[boxes.length * 2];
                        System.arraycopy(boxes, 0, grown, 0, n);
                        boxes = grown;
                        out[0] = boxes;
                    }
                    boxes[n++] = PackedBox.pack(id, x, y, z, x2, y2, z2);
                }
            }
        }
        return n;
    }
}
