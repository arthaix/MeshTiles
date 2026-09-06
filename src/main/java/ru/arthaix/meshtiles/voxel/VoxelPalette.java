package ru.arthaix.meshtiles.voxel;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps (material index, ARGB colour) pairs to compact 16-bit tile ids and back.
 * Id 0 is reserved for "empty". Ids are 1..65534.
 */
public final class VoxelPalette {

    public static final int EMPTY = 0;
    public static final int MAX_IDS = 65535;

    private final Map<Long, Integer> lookup = new HashMap<>();
    private int[] materials = new int[256];
    private int[] colors = new int[256];
    private int size = 1; // 0 = empty

    /** Returns the id for the pair, allocating a new one if needed. Returns -1 when the palette is full. */
    public int idFor(int material, int argb) {
        long key = ((long) material << 32) | (argb & 0xffffffffL);
        Integer id = lookup.get(key);
        if (id != null) return id;
        if (size >= MAX_IDS) return -1;
        if (size == materials.length) {
            int[] m = new int[size * 2];
            int[] c = new int[size * 2];
            System.arraycopy(materials, 0, m, 0, size);
            System.arraycopy(colors, 0, c, 0, size);
            materials = m;
            colors = c;
        }
        materials[size] = material;
        colors[size] = argb;
        lookup.put(key, size);
        return size++;
    }

    public int material(int id) {
        return materials[id];
    }

    public int color(int id) {
        return colors[id];
    }

    /** Number of allocated ids including the reserved empty id. */
    public int size() {
        return size;
    }

    public int[] materialsArray() {
        int[] r = new int[size];
        System.arraycopy(materials, 0, r, 0, size);
        return r;
    }

    public int[] colorsArray() {
        int[] r = new int[size];
        System.arraycopy(colors, 0, r, 0, size);
        return r;
    }

    /** Rebuilds a palette from arrays (used when receiving a model over the network). */
    public static VoxelPalette fromArrays(int[] materials, int[] colors) {
        VoxelPalette p = new VoxelPalette();
        for (int i = 1; i < materials.length; i++)
            p.idFor(materials[i], colors[i]);
        return p;
    }
}
