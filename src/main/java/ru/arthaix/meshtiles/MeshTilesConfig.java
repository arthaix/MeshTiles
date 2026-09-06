package ru.arthaix.meshtiles;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

/** config/meshtiles.cfg */
public final class MeshTilesConfig {

    // server
    public static int blocksPerTick = 48;
    public static int maxMillisPerTick = 25;
    public static boolean replaceSolidBlocks = false;
    public static boolean keepUndo = true;
    public static int maxQueuedBlocks = 4096;
    public static int historySize = 25;
    public static int undoBlocksPerTick = 1500;

    // client
    public static int colorLevels = 32;
    public static int voxelThreads = 0;
    public static int recipeMaxBoxes = 200000;
    public static int blocksPerPacket = 192;

    private MeshTilesConfig() {}

    public static void load(File file) {
        Configuration cfg = new Configuration(file);
        cfg.load();
        blocksPerTick = cfg.getInt("blocksPerTick", "server", blocksPerTick, 1, 4096, "How many world blocks the server fills per tick while importing");
        maxMillisPerTick = cfg.getInt("maxMillisPerTick", "server", maxMillisPerTick, 1, 500, "Stop placing for this tick once this many milliseconds were spent");
        replaceSolidBlocks = cfg.getBoolean("replaceSolidBlocks", "server", replaceSolidBlocks, "Overwrite non-replaceable vanilla blocks (stone, dirt...) with the imported tiles. Off = only air/replaceable blocks and existing LittleTiles blocks are used");
        keepUndo = cfg.getBoolean("keepUndo", "server", keepUndo, "Remember the last import per player so it can be undone from the GUI");
        maxQueuedBlocks = cfg.getInt("maxQueuedBlocks", "server", maxQueuedBlocks, 256, 1 << 20, "Flow control: the client is asked for more data only while fewer blocks than this are waiting");
        historySize = cfg.getInt("historySize", "server", historySize, 1, 500, "How many finished imports (with undo data) are kept per world; see /meshtiles list");
        undoBlocksPerTick = cfg.getInt("undoBlocksPerTick", "server", undoBlocksPerTick, 1, 100000, "Blocks removed per tick while undoing (removal is much cheaper than placing)");

        colorLevels = cfg.getInt("colorLevels", "client", colorLevels, 2, 256, "Colour quantisation steps per channel. Fewer steps = far fewer tiles (better merging), more steps = truer colours");
        voxelThreads = cfg.getInt("voxelThreads", "client", voxelThreads, 0, 64, "Worker threads for voxelisation, 0 = all cores");
        recipeMaxBoxes = cfg.getInt("recipeMaxBoxes", "client", recipeMaxBoxes, 100, 50000000, "Refuse to build a recipe item with more tiles than this (the item NBT would become unusable)");
        blocksPerPacket = cfg.getInt("blocksPerPacket", "client", blocksPerPacket, 8, 4096, "World blocks per streaming packet");
        if (cfg.hasChanged()) cfg.save();
    }
}
