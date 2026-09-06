package ru.arthaix.meshtiles.voxel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of voxelization: for every touched world block, an array of {@link PackedBox} longs
 * (already greedily merged), plus the palette that resolves tile ids to (material, colour).
 * Block coordinates are absolute world block positions (origin already applied).
 */
public final class VoxelModel {

    public final int grid;
    public final VoxelPalette palette;
    private final Map<Long, long[]> blocks = new HashMap<>();
    private long boxCount;
    private long voxelCount;
    public int minBlockX = Integer.MAX_VALUE, minBlockY = Integer.MAX_VALUE, minBlockZ = Integer.MAX_VALUE;
    public int maxBlockX = Integer.MIN_VALUE, maxBlockY = Integer.MIN_VALUE, maxBlockZ = Integer.MIN_VALUE;

    public VoxelModel(int grid, VoxelPalette palette) {
        this.grid = grid;
        this.palette = palette;
    }

    public synchronized void putBlock(int bx, int by, int bz, long[] boxes) {
        if (boxes.length == 0) return;
        long key = BlockKey.pack(bx, by, bz);
        long[] old = blocks.put(key, boxes);
        if (old != null) {
            boxCount -= old.length;
            for (long b : old) voxelCount -= PackedBox.volume(b);
        }
        boxCount += boxes.length;
        for (long b : boxes) voxelCount += PackedBox.volume(b);
        if (bx < minBlockX) minBlockX = bx;
        if (by < minBlockY) minBlockY = by;
        if (bz < minBlockZ) minBlockZ = bz;
        if (bx > maxBlockX) maxBlockX = bx;
        if (by > maxBlockY) maxBlockY = by;
        if (bz > maxBlockZ) maxBlockZ = bz;
    }

    public long[] getBlock(long key) {
        return blocks.get(key);
    }

    public Map<Long, long[]> blocks() {
        return blocks;
    }

    public int blockCount() {
        return blocks.size();
    }

    public long boxCount() {
        return boxCount;
    }

    public long voxelCount() {
        return voxelCount;
    }

    /** Block keys sorted by (y, x, z) so placement proceeds bottom-up and chunk-coherently. */
    public List<Long> sortedKeys() {
        List<Long> keys = new ArrayList<>(blocks.keySet());
        Collections.sort(keys, (a, b) -> {
            int ya = BlockKey.y(a), yb = BlockKey.y(b);
            if (ya != yb) return Integer.compare(ya, yb);
            int cxa = BlockKey.x(a) >> 4, cxb = BlockKey.x(b) >> 4;
            if (cxa != cxb) return Integer.compare(cxa, cxb);
            int cza = BlockKey.z(a) >> 4, czb = BlockKey.z(b) >> 4;
            if (cza != czb) return Integer.compare(cza, czb);
            return Long.compare(a, b);
        });
        return keys;
    }

    /** Largest number of boxes in a single block (relevant for LittleTiles' maxAllowedDensity). */
    public int maxBoxesPerBlock() {
        int max = 0;
        for (long[] b : blocks.values()) if (b.length > max) max = b.length;
        return max;
    }
}
