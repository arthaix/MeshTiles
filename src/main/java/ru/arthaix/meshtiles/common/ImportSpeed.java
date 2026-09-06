package ru.arthaix.meshtiles.common;

/** Steps of the placement speed slider, shared by the GUI and the server. */
public final class ImportSpeed {

    /** Blocks per second for each slider step; 0 = as fast as the server budget allows. */
    private static final int[] LEVELS = { 1, 2, 5, 10, 20, 50, 100, 200, 500, 0 };
    public static final int MAX_INDEX = LEVELS.length - 1;

    private ImportSpeed() {}

    public static int clamp(int index) {
        return Math.max(0, Math.min(MAX_INDEX, index));
    }

    public static int blocksPerSecond(int index) {
        return LEVELS[clamp(index)];
    }

    public static String label(int index) {
        int bps = blocksPerSecond(index);
        return bps == 0 ? "Max" : bps + " bl/s";
    }
}
