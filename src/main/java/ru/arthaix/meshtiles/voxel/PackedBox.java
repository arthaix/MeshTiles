package ru.arthaix.meshtiles.voxel;

/**
 * A tile box inside one block, packed into a long:
 * bits 0..15 tile id, then minX,minY,minZ,maxX,maxY,maxZ as 7-bit fields (grid up to 64, max is exclusive).
 */
public final class PackedBox {

    private PackedBox() {}

    public static long pack(int id, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return (id & 0xffffL)
            | ((long) minX << 16) | ((long) minY << 23) | ((long) minZ << 30)
            | ((long) maxX << 37) | ((long) maxY << 44) | ((long) maxZ << 51);
    }

    public static int id(long b) { return (int) (b & 0xffff); }
    public static int minX(long b) { return (int) ((b >>> 16) & 0x7f); }
    public static int minY(long b) { return (int) ((b >>> 23) & 0x7f); }
    public static int minZ(long b) { return (int) ((b >>> 30) & 0x7f); }
    public static int maxX(long b) { return (int) ((b >>> 37) & 0x7f); }
    public static int maxY(long b) { return (int) ((b >>> 44) & 0x7f); }
    public static int maxZ(long b) { return (int) ((b >>> 51) & 0x7f); }

    public static int volume(long b) {
        return (maxX(b) - minX(b)) * (maxY(b) - minY(b)) * (maxZ(b) - minZ(b));
    }
}
