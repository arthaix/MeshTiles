package ru.arthaix.meshtiles.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.creativemd.littletiles.common.tile.LittleTile;
import com.creativemd.littletiles.common.tile.math.box.LittleBox;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import ru.arthaix.meshtiles.common.BlockRef;
import ru.arthaix.meshtiles.voxel.PackedBox;

/**
 * What one import changed, enough to take it back: blocks it created, tiles it added to blocks that already held
 * LittleTiles, vanilla blocks it replaced. Tiles are stored as packed boxes (8 bytes each) against a list of block
 * names, never as tile NBT: a city-sized import has millions of them.
 */
public final class UndoLog {

    private static final int FORMAT = 2;

    /** Block keys (BlockPos.toLong) of blocks that did not contain LittleTiles before the import. */
    public long[] created = new long[256];
    public int createdCount;
    /** Created keys while the import is running: tiles added later to those blocks need no record of their own. */
    private Set<Long> createdKeys = new HashSet<>();

    /** Tiles added to one pre-existing block. */
    public static final class Appended {
        /** Grid the boxes are expressed in (0 = unknown, from old saves). */
        public int grid;
        /** PackedBox values; the id part is an index into the block name list. */
        public long[] boxes = new long[4];
        public int count;

        void add(long box) {
            if (count == boxes.length) boxes = Arrays.copyOf(boxes, count * 2);
            boxes[count++] = box;
        }

        void rescale(int factor) {
            for (int i = 0; i < count; i++) boxes[i] = scale(boxes[i], factor);
        }
    }

    public final Map<Long, Appended> appended = new HashMap<>();
    private final List<String> blockNames = new ArrayList<>();
    private final Map<String, Integer> blockIndex = new HashMap<>();
    /** Vanilla block states that were overwritten (only with replaceSolidBlocks), as state ids. */
    public final Map<Long, Integer> replaced = new HashMap<>();
    /** Real Minecraft blocks we placed (grid 1 "MC" materials), as state ids; undone only while still unchanged. */
    public final Map<Long, Integer> solid = new HashMap<>();

    // ---- recording ----

    public void recordCreated(long key) {
        if (createdCount == created.length) created = Arrays.copyOf(created, created.length * 2);
        created[createdCount++] = key;
        if (createdKeys != null) createdKeys.add(key);
    }

    public void recordAppended(long key, int grid, List<LittleTile> tiles) {
        if (createdKeys != null && createdKeys.contains(key)) return; // the undo removes this whole block anyway
        Appended a = appended.get(key);
        if (a == null) {
            a = new Appended();
            a.grid = grid;
            appended.put(key, a);
        }
        int factor = alignGrids(a, grid);
        for (LittleTile tile : tiles) {
            LittleBox b = tile.getBox();
            if (b == null || tile.getBlock() == null) continue;
            long packed = pack(indexOf(BlockRef.toId(tile.getBlock(), tile.getMeta())), b.minX * factor, b.minY * factor, b.minZ * factor,
                b.maxX * factor, b.maxY * factor, b.maxZ * factor);
            if (packed != -1) a.add(packed);
        }
    }

    public void recordReplaced(long key, IBlockState state) {
        if (!replaced.containsKey(key)) replaced.put(key, Block.getStateId(state));
    }

    public void recordSolid(long key, IBlockState state) {
        solid.put(key, Block.getStateId(state));
    }

    /** The import is over: the lookup set is no longer needed. */
    public void finishRecording() {
        createdKeys = null;
    }

    public int size() {
        return createdCount + appended.size();
    }

    // ---- matching during undo ----

    /** Grid in which the saved boxes of a block and its current tiles are compared. */
    public int compareGrid(Appended a, int blockGrid) {
        return a.grid > 0 ? Math.max(a.grid, blockGrid) : blockGrid;
    }

    /** Saved boxes of a block, expressed in {@code cmpGrid}. */
    public Set<Long> matchSet(Appended a, int cmpGrid) {
        int factor = a.grid > 0 ? Math.max(1, cmpGrid / a.grid) : 1;
        Set<Long> set = new HashSet<>(a.count * 2);
        for (int i = 0; i < a.count; i++) set.add(factor == 1 ? a.boxes[i] : scale(a.boxes[i], factor));
        return set;
    }

    /** Key of a tile that currently sits in a block of grid {@code blockGrid}, comparable with {@link #matchSet}. */
    public long tileKey(LittleTile tile, int blockGrid, int cmpGrid) {
        LittleBox b = tile.getBox();
        if (b == null || tile.getBlock() == null) return -1;
        Integer idx = blockIndex.get(BlockRef.toId(tile.getBlock(), tile.getMeta()));
        if (idx == null) return -1;
        int f = Math.max(1, cmpGrid / blockGrid);
        return pack(idx, b.minX * f, b.minY * f, b.minZ * f, b.maxX * f, b.maxY * f, b.maxZ * f);
    }

    // ---- helpers ----

    private int indexOf(String name) {
        Integer idx = blockIndex.get(name);
        if (idx != null) return idx;
        idx = blockNames.size();
        blockNames.add(name);
        blockIndex.put(name, idx);
        return idx;
    }

    /** Brings a block record and new boxes to a common grid; returns the factor for the new boxes. */
    private static int alignGrids(Appended a, int grid) {
        if (a.grid == grid || a.grid <= 0 || grid <= 0) return 1;
        if (grid > a.grid && grid % a.grid == 0) {
            a.rescale(grid / a.grid);
            a.grid = grid;
            return 1;
        }
        if (a.grid > grid && a.grid % grid == 0) return a.grid / grid;
        return 1;
    }

    private static long pack(int idx, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (idx < 0 || idx > 0xffff) return -1;
        if (minX < 0 || minY < 0 || minZ < 0 || maxX > 127 || maxY > 127 || maxZ > 127) return -1;
        return PackedBox.pack(idx, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static long scale(long box, int f) {
        long p = pack(PackedBox.id(box), PackedBox.minX(box) * f, PackedBox.minY(box) * f, PackedBox.minZ(box) * f,
            PackedBox.maxX(box) * f, PackedBox.maxY(box) * f, PackedBox.maxZ(box) * f);
        return p == -1 ? box : p;
    }

    // ---- storage ----

    public static void save(UndoLog log, File file) throws IOException {
        HistoryIO.writeNbt(log.write(), file);
    }

    public static UndoLog load(File file) throws IOException {
        return read(HistoryIO.readNbt(file));
    }

    public NBTTagCompound write() {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setInteger("format", FORMAT);
        writeKeys(nbt, "created", created, createdCount);
        NBTTagList names = new NBTTagList();
        for (String s : blockNames) names.appendTag(new NBTTagString(s));
        nbt.setTag("blockNames", names);
        try {
            long bytes = 4;
            for (Appended a : appended.values()) bytes += 16 + 8L * a.count;
            ByteArrayOutputStream raw = new ByteArrayOutputStream((int) Math.min(Integer.MAX_VALUE - 8, bytes));
            DataOutputStream out = new DataOutputStream(raw);
            out.writeInt(appended.size());
            for (Map.Entry<Long, Appended> e : appended.entrySet()) {
                Appended a = e.getValue();
                out.writeLong(e.getKey());
                out.writeInt(a.grid);
                out.writeInt(a.count);
                for (int i = 0; i < a.count; i++) out.writeLong(a.boxes[i]);
            }
            out.flush();
            nbt.setByteArray("appendedData", raw.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e); // in-memory stream
        }
        writeStates(nbt, "replaced", replaced);
        writeStates(nbt, "solid", solid);
        return nbt;
    }

    public static UndoLog read(NBTTagCompound nbt) {
        UndoLog log = new UndoLog();
        log.createdKeys = null;
        long[] c = readKeys(nbt, "created");
        log.created = Arrays.copyOf(c, Math.max(16, c.length));
        log.createdCount = c.length;
        if (nbt.getInteger("format") >= 2) {
            NBTTagList names = nbt.getTagList("blockNames", 8);
            for (int i = 0; i < names.tagCount(); i++) log.indexOf(names.getStringTagAt(i));
            try {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(nbt.getByteArray("appendedData")));
                int blocks = in.readInt();
                for (int i = 0; i < blocks; i++) {
                    long key = in.readLong();
                    Appended a = new Appended();
                    a.grid = in.readInt();
                    int n = in.readInt();
                    a.boxes = new long[Math.max(1, n)];
                    for (int k = 0; k < n; k++) a.boxes[k] = in.readLong();
                    a.count = n;
                    log.appended.put(key, a);
                }
            } catch (IOException e) {
                throw new IllegalStateException("corrupt undo data: " + e.getMessage(), e);
            }
            readStates(nbt, "replaced", log.replaced);
            readStates(nbt, "solid", log.solid);
        } else {
            readLegacy(nbt, log, c);
        }
        return log;
    }

    /** First format: created blocks as int pairs, appended tiles as full tile NBT, states as compound lists. */
    private static void readLegacy(NBTTagCompound nbt, UndoLog log, long[] created) {
        Set<Long> createdSet = new HashSet<>(created.length * 2);
        for (long k : created) createdSet.add(k);
        NBTTagList app = nbt.getTagList("appended", 10);
        for (int i = 0; i < app.tagCount(); i++) {
            NBTTagCompound t = app.getCompoundTagAt(i);
            long key = t.getLong("pos");
            if (createdSet.contains(key)) continue; // same import created the block: the undo removes it whole
            NBTTagList tiles = t.getTagList("tiles", 10);
            Appended a = null;
            for (int k = 0; k < tiles.tagCount(); k++) {
                NBTTagCompound tile = tiles.getCompoundTagAt(k);
                int[] b = tile.getIntArray("box");
                if (b.length != 6) continue;
                int grid = tile.getInteger("grid");
                if (a == null) {
                    a = new Appended();
                    a.grid = grid;
                }
                int f = alignGrids(a, grid);
                long packed = pack(log.indexOf(tile.getString("block")), b[0] * f, b[1] * f, b[2] * f, b[3] * f, b[4] * f, b[5] * f);
                if (packed != -1) a.add(packed);
            }
            if (a != null && a.count > 0) log.appended.put(key, a);
        }
        NBTTagList rep = nbt.getTagList("replaced", 10);
        for (int i = 0; i < rep.tagCount(); i++) log.replaced.put(rep.getCompoundTagAt(i).getLong("pos"), rep.getCompoundTagAt(i).getInteger("state"));
        NBTTagList sol = nbt.getTagList("solid", 10);
        for (int i = 0; i < sol.tagCount(); i++) log.solid.put(sol.getCompoundTagAt(i).getLong("pos"), sol.getCompoundTagAt(i).getInteger("state"));
    }

    private static void writeKeys(NBTTagCompound nbt, String name, long[] keys, int count) {
        // 1.12 NBT has no long array: two int arrays
        int[] hi = new int[count], lo = new int[count];
        for (int i = 0; i < count; i++) {
            hi[i] = (int) (keys[i] >>> 32);
            lo[i] = (int) keys[i];
        }
        nbt.setIntArray(name + "Hi", hi);
        nbt.setIntArray(name + "Lo", lo);
    }

    private static long[] readKeys(NBTTagCompound nbt, String name) {
        int[] hi = nbt.getIntArray(name + "Hi"), lo = nbt.getIntArray(name + "Lo");
        int n = Math.min(hi.length, lo.length);
        long[] keys = new long[n];
        for (int i = 0; i < n; i++) keys[i] = ((long) hi[i] << 32) | (lo[i] & 0xffffffffL);
        return keys;
    }

    private static void writeStates(NBTTagCompound nbt, String name, Map<Long, Integer> states) {
        long[] keys = new long[states.size()];
        int[] ids = new int[states.size()];
        int i = 0;
        for (Map.Entry<Long, Integer> e : states.entrySet()) {
            keys[i] = e.getKey();
            ids[i++] = e.getValue();
        }
        writeKeys(nbt, name, keys, keys.length);
        nbt.setIntArray(name + "State", ids);
    }

    private static void readStates(NBTTagCompound nbt, String name, Map<Long, Integer> into) {
        long[] keys = readKeys(nbt, name);
        int[] ids = nbt.getIntArray(name + "State");
        for (int i = 0; i < keys.length && i < ids.length; i++) into.put(keys[i], ids[i]);
    }
}
