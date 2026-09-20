package ru.arthaix.meshtiles.voxel;

import java.util.Arrays;
import java.util.Map;

/**
 * Turns the shell of an "air" material into a solid volume: everything a closed surface encloses is marked as
 * air too, so a modelled box clears the whole pit and not only its walls.
 *
 * <p>The voxelizer writes the surface of the mesh. Here the model's bounding box is flooded from the outside at
 * grid 1, and every block cell the flood cannot reach is inside the shell. A model with a hole in it simply lets
 * the flood in, so nothing is filled and only the surface is cleared: no guessing, no runaway fills.</p>
 */
public final class AirFill {

    /** Bounding boxes above this many block cells are left unfilled (a 320x320x320 pit is still fine). */
    public static final long MAX_CELLS = 32_000_000L;

    private static final byte UNKNOWN = 0, SHELL = 1, OUTSIDE = 2;

    /** Result of one fill: how many block cells were added, or why none were. */
    public static final class Result {
        public int shellBlocks;
        public int filledBlocks;
        public boolean tooBig;
    }

    private AirFill() {}

    /** Fills the inside of every air shell of a grid-1 model. Other materials in the model are left untouched. */
    public static Result fill(VoxelModel model, boolean[] airMaterial) {
        Result r = new Result();
        if (model.grid != 1) return r;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int airId = 0;
        for (Map.Entry<Long, long[]> e : model.blocks().entrySet()) {
            int id = airIdOf(model, e.getValue(), airMaterial);
            if (id == 0) continue;
            if (airId == 0) airId = id;
            long key = e.getKey();
            int x = BlockKey.x(key), y = BlockKey.y(key), z = BlockKey.z(key);
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
            r.shellBlocks++;
        }
        if (r.shellBlocks == 0) return r;

        // one cell of margin all around, so the flood always starts outside the model
        minX--;
        minY--;
        minZ--;
        maxX++;
        maxY++;
        maxZ++;
        long nx = maxX - minX + 1L, ny = maxY - minY + 1L, nz = maxZ - minZ + 1L;
        if (nx * ny * nz > MAX_CELLS) {
            r.tooBig = true;
            return r;
        }
        final int sx = (int) nx, sy = (int) ny, sz = (int) nz;
        byte[] cells = new byte[sx * sy * sz];
        for (Map.Entry<Long, long[]> e : model.blocks().entrySet()) {
            if (airIdOf(model, e.getValue(), airMaterial) == 0) continue;
            long key = e.getKey();
            cells[index(BlockKey.x(key) - minX, BlockKey.y(key) - minY, BlockKey.z(key) - minZ, sx, sz)] = SHELL;
        }

        flood(cells, sx, sy, sz);

        for (int i = 0; i < cells.length; i++) {
            if (cells[i] != UNKNOWN) continue;
            int x = i % sx, z = (i / sx) % sz, y = i / (sx * sz);
            add(model, minX + x, minY + y, minZ + z, airId);
            r.filledBlocks++;
        }
        return r;
    }

    /** The palette id of the first air box of a block, or 0 when the block holds no air. */
    private static int airIdOf(VoxelModel model, long[] boxes, boolean[] airMaterial) {
        for (long box : boxes) {
            int id = PackedBox.id(box);
            if (id <= 0 || id >= model.palette.size()) continue;
            int m = model.palette.material(id);
            if (m >= 0 && m < airMaterial.length && airMaterial[m]) return id;
        }
        return 0;
    }

    /** Marks every cell reachable from the border without crossing the shell. */
    private static void flood(byte[] cells, int sx, int sy, int sz) {
        final int layer = sx * sz;
        int[] stack = new int[4096];
        int top = 0;
        // the margin makes the whole border free unless a shell block sits exactly on it
        for (int i = 0; i < cells.length; i++) {
            int x = i % sx, z = (i / sx) % sz, y = i / layer;
            if (x != 0 && x != sx - 1 && y != 0 && y != sy - 1 && z != 0 && z != sz - 1) continue;
            if (cells[i] != UNKNOWN) continue;
            cells[i] = OUTSIDE;
            if (top == stack.length) stack = Arrays.copyOf(stack, top * 2);
            stack[top++] = i;
        }
        while (top > 0) {
            if (top + 6 > stack.length) stack = Arrays.copyOf(stack, stack.length * 2);
            int i = stack[--top];
            int x = i % sx, z = (i / sx) % sz, y = i / layer;
            if (x > 0 && cells[i - 1] == UNKNOWN) stack[top++] = mark(cells, i - 1);
            if (x < sx - 1 && cells[i + 1] == UNKNOWN) stack[top++] = mark(cells, i + 1);
            if (z > 0 && cells[i - sx] == UNKNOWN) stack[top++] = mark(cells, i - sx);
            if (z < sz - 1 && cells[i + sx] == UNKNOWN) stack[top++] = mark(cells, i + sx);
            if (y > 0 && cells[i - layer] == UNKNOWN) stack[top++] = mark(cells, i - layer);
            if (y < sy - 1 && cells[i + layer] == UNKNOWN) stack[top++] = mark(cells, i + layer);
        }
    }

    private static int mark(byte[] cells, int i) {
        cells[i] = OUTSIDE;
        return i;
    }

    private static int index(int x, int y, int z, int sx, int sz) {
        return y * sx * sz + z * sx + x;
    }

    /** Adds a whole-block air box to a cell, keeping whatever the cell already holds. */
    private static void add(VoxelModel model, int bx, int by, int bz, int airId) {
        long box = PackedBox.pack(airId, 0, 0, 0, 1, 1, 1);
        long[] old = model.getBlock(BlockKey.pack(bx, by, bz));
        if (old == null) {
            model.putBlock(bx, by, bz, new long[] { box });
            return;
        }
        long[] merged = Arrays.copyOf(old, old.length + 1);
        merged[old.length] = box;
        model.putBlock(bx, by, bz, merged);
    }
}
