package ru.arthaix.meshtiles.voxel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A voxelized model made of several {@link VoxelModel} parts, one per LittleTiles grid.
 * Materials with different grids are voxelized separately; the parts share the world coordinate system.
 */
public final class MultiModel {

    public final List<VoxelModel> parts;
    public final int minBlockX, minBlockY, minBlockZ, maxBlockX, maxBlockY, maxBlockZ;
    public final int maxGrid;

    public MultiModel(List<VoxelModel> parts) {
        this.parts = Collections.unmodifiableList(new ArrayList<>(parts));
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int grid = 1;
        for (VoxelModel p : parts) {
            if (p.blockCount() == 0) continue;
            minX = Math.min(minX, p.minBlockX);
            minY = Math.min(minY, p.minBlockY);
            minZ = Math.min(minZ, p.minBlockZ);
            maxX = Math.max(maxX, p.maxBlockX);
            maxY = Math.max(maxY, p.maxBlockY);
            maxZ = Math.max(maxZ, p.maxBlockZ);
            grid = Math.max(grid, p.grid);
        }
        if (minX == Integer.MAX_VALUE) minX = minY = minZ = maxX = maxY = maxZ = 0;
        this.minBlockX = minX;
        this.minBlockY = minY;
        this.minBlockZ = minZ;
        this.maxBlockX = maxX;
        this.maxBlockY = maxY;
        this.maxBlockZ = maxZ;
        this.maxGrid = grid;
    }

    /** Block visits (a block holding two grids counts twice, like the server sees it). */
    public int blockCount() {
        int n = 0;
        for (VoxelModel p : parts) n += p.blockCount();
        return n;
    }

    public long boxCount() {
        long n = 0;
        for (VoxelModel p : parts) n += p.boxCount();
        return n;
    }

    public long voxelCount() {
        long n = 0;
        for (VoxelModel p : parts) n += p.voxelCount();
        return n;
    }

    public int maxBoxesPerBlock() {
        int n = 0;
        for (VoxelModel p : parts) n = Math.max(n, p.maxBoxesPerBlock());
        return n;
    }

    public boolean isEmpty() {
        return boxCount() == 0;
    }
}
