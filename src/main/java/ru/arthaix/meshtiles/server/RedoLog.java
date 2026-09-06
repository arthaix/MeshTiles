package ru.arthaix.meshtiles.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;

import com.creativemd.littletiles.common.tile.LittleTile;
import com.creativemd.littletiles.common.tile.LittleTileColored;
import com.creativemd.littletiles.common.tile.math.box.LittleBox;

import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.world.WorldServer;
import ru.arthaix.meshtiles.common.BlockRef;
import ru.arthaix.meshtiles.common.ChunkCodec;
import ru.arthaix.meshtiles.common.ImportPalette;
import ru.arthaix.meshtiles.voxel.PackedBox;
import ru.arthaix.meshtiles.voxel.VoxelPalette;

/**
 * Everything an undo took out of the world, in the same form the client streams an import: a palette plus
 * per-block packed boxes. Redo feeds it back through the normal placement path. Saved next to the world
 * (data/meshtiles_redo/dim&lt;dim&gt;_&lt;id&gt;.dat) so it survives a restart.
 */
public final class RedoLog {

    private static final int BLOCKS_PER_CHUNK = 2048;

    private final List<String> ids = new ArrayList<>();
    private final List<Integer> colors = new ArrayList<>();
    private final List<Boolean> solid = new ArrayList<>();
    private final Map<String, Integer> index = new HashMap<>();

    public final List<ChunkCodec.Block> blocks = new ArrayList<>();
    public long boxCount;
    public int maxGrid = 1;

    public RedoLog() {
        ids.add("");
        colors.add(-1);
        solid.add(false);
    }

    private int id(String block, int color, boolean isSolid) {
        String key = block + '|' + color + '|' + isSolid;
        Integer id = index.get(key);
        if (id != null) return id;
        if (ids.size() > VoxelPalette.MAX_IDS) return -1;
        id = ids.size();
        ids.add(block);
        colors.add(color);
        solid.add(isSolid);
        index.put(key, id);
        return id;
    }

    /** Records the tiles of one block (boxes in the block's grid). */
    public void addTiles(long key, int grid, Iterable<LittleTile> tiles) {
        List<Long> boxes = new ArrayList<>();
        for (LittleTile tile : tiles) {
            LittleBox b = tile.getBox();
            if (b == null || tile.getBlock() == null) continue;
            if (b.minX < 0 || b.minY < 0 || b.minZ < 0 || b.maxX > 127 || b.maxY > 127 || b.maxZ > 127) continue;
            int color = tile instanceof LittleTileColored ? ((LittleTileColored) tile).color : -1;
            int id = id(BlockRef.toId(tile.getBlock(), tile.getMeta()), color, false);
            if (id < 0) continue;
            boxes.add(PackedBox.pack(id, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ));
        }
        if (boxes.isEmpty()) return;
        long[] arr = new long[boxes.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = boxes.get(i);
        blocks.add(new ChunkCodec.Block(key, grid, arr));
        boxCount += arr.length;
        maxGrid = Math.max(maxGrid, grid);
    }

    /** Records a real Minecraft block (grid-1 "MC" material). */
    public void addSolid(long key, IBlockState state) {
        int id = id(BlockRef.toId(state.getBlock(), state.getBlock().getMetaFromState(state)), -1, true);
        if (id < 0) return;
        blocks.add(new ChunkCodec.Block(key, 1, new long[] { PackedBox.pack(id, 0, 0, 0, 1, 1, 1) }));
        boxCount++;
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    public ImportPalette palette() {
        int n = ids.size();
        String[] s = ids.toArray(new String[n]);
        int[] c = new int[n];
        boolean[] f = new boolean[n];
        for (int i = 0; i < n; i++) {
            c[i] = colors.get(i);
            f[i] = solid.get(i);
        }
        return new ImportPalette(s, c, f);
    }

    // ---- files ----

    public static File file(WorldServer world, int id) {
        return file(world, world.provider.getDimension(), id);
    }

    public static File file(WorldServer anyWorld, int dim, int id) {
        File dir = new File(anyWorld.getSaveHandler().getWorldDirectory(), "data/meshtiles_redo");
        return new File(dir, "dim" + dim + "_" + id + ".dat");
    }

    public static void save(RedoLog log, File f) throws IOException {
        log.write(f);
    }

    public void write(File f) throws IOException {
        NBTTagCompound nbt = new NBTTagCompound();
        NBTTagList idList = new NBTTagList();
        for (String s : ids) idList.appendTag(new NBTTagString(s));
        nbt.setTag("ids", idList);
        int[] c = new int[colors.size()];
        byte[] s = new byte[solid.size()];
        for (int i = 0; i < c.length; i++) {
            c[i] = colors.get(i);
            s[i] = (byte) (solid.get(i) ? 1 : 0);
        }
        nbt.setIntArray("colors", c);
        nbt.setByteArray("solid", s);
        nbt.setInteger("maxGrid", maxGrid);
        nbt.setLong("boxes", boxCount);
        NBTTagList chunks = new NBTTagList();
        for (int from = 0; from < blocks.size(); from += BLOCKS_PER_CHUNK)
            chunks.appendTag(new NBTTagByteArray(ChunkCodec.encode(blocks.subList(from, Math.min(blocks.size(), from + BLOCKS_PER_CHUNK)))));
        nbt.setTag("chunks", chunks);
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            CompressedStreamTools.writeCompressed(nbt, out);
        }
    }

    public static RedoLog read(File f) throws IOException {
        NBTTagCompound nbt;
        try (FileInputStream in = new FileInputStream(f)) {
            nbt = CompressedStreamTools.readCompressed(in);
        }
        RedoLog log = new RedoLog();
        NBTTagList idList = nbt.getTagList("ids", 8);
        int[] c = nbt.getIntArray("colors");
        byte[] s = nbt.getByteArray("solid");
        log.ids.clear();
        log.colors.clear();
        log.solid.clear();
        for (int i = 0; i < idList.tagCount(); i++) {
            log.ids.add(idList.getStringTagAt(i));
            log.colors.add(i < c.length ? c[i] : -1);
            log.solid.add(i < s.length && s[i] != 0);
        }
        if (log.ids.isEmpty()) {
            log.ids.add("");
            log.colors.add(-1);
            log.solid.add(false);
        }
        log.maxGrid = Math.max(1, nbt.getInteger("maxGrid"));
        log.boxCount = nbt.getLong("boxes");
        NBTTagList chunks = nbt.getTagList("chunks", 7);
        try {
            for (int i = 0; i < chunks.tagCount(); i++) log.blocks.addAll(ChunkCodec.decode(((NBTTagByteArray) chunks.get(i)).getByteArray()));
        } catch (DataFormatException e) {
            throw new IOException("corrupt redo data: " + e.getMessage(), e);
        }
        return log;
    }
}
