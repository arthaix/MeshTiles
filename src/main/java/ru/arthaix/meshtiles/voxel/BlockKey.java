package ru.arthaix.meshtiles.voxel;

/**
 * Packs signed block coordinates into a long exactly like Minecraft's BlockPos.toLong
 * (x: 26 bits, y: 12 bits, z: 26 bits), so keys can be handed to BlockPos.fromLong on the MC side.
 */
public final class BlockKey {

    private BlockKey() {}

    public static long pack(int x, int y, int z) {
        return ((long) x & 0x3ffffffL) << 38 | ((long) y & 0xfffL) << 26 | ((long) z & 0x3ffffffL);
    }

    public static int x(long key) { return (int) (key << 0 >> 38); }
    public static int y(long key) { return (int) (key << 26 >> 52); }
    public static int z(long key) { return (int) (key << 38 >> 38); }
}
