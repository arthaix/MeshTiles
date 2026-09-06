package ru.arthaix.meshtiles.server;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import ru.arthaix.meshtiles.MeshTiles;
import ru.arthaix.meshtiles.MeshTilesConfig;

/**
 * The world's import history (data/meshtiles_imports.dat). Only a small index lives here, because Minecraft rewrites
 * this file on the server thread at every autosave after a change. The undo data of each import is a separate file in
 * data/meshtiles_undo, written and read by {@link HistoryIO} off the server thread.
 */
public final class ImportHistory extends WorldSavedData {

    public static final String NAME = "meshtiles_imports";

    public static final class Entry {
        public int id;
        public int dim;
        public UUID player;
        public String playerName = "";
        public long time;
        public int blocks;
        public long tiles;
        public String summary = "";
        /** Blocks the undo will visit (0 = nothing to undo). */
        public int undoBlocks;
        /** Undo data found inline in an old-format history file; moved to its own file right after loading. */
        UndoLog legacyUndo;

        NBTTagCompound write() {
            NBTTagCompound nbt = new NBTTagCompound();
            nbt.setInteger("id", id);
            nbt.setInteger("dim", dim);
            nbt.setUniqueId("player", player);
            nbt.setString("name", playerName);
            nbt.setLong("time", time);
            nbt.setInteger("blocks", blocks);
            nbt.setLong("tiles", tiles);
            nbt.setString("summary", summary);
            nbt.setInteger("undoBlocks", undoBlocks);
            return nbt;
        }

        static Entry read(NBTTagCompound nbt) {
            Entry e = new Entry();
            e.id = nbt.getInteger("id");
            e.dim = nbt.getInteger("dim");
            e.player = nbt.getUniqueId("player");
            e.playerName = nbt.getString("name");
            e.time = nbt.getLong("time");
            e.blocks = nbt.getInteger("blocks");
            e.tiles = nbt.getLong("tiles");
            e.summary = nbt.getString("summary");
            e.undoBlocks = nbt.getInteger("undoBlocks");
            if (nbt.hasKey("undo", 10)) {
                try {
                    e.legacyUndo = UndoLog.read(nbt.getCompoundTag("undo"));
                    e.undoBlocks = e.legacyUndo.size();
                } catch (RuntimeException ex) {
                    MeshTiles.logger.warn("Undo data of import #" + e.id + " is unreadable and was dropped: " + ex);
                    e.undoBlocks = 0;
                }
            }
            return e;
        }
    }

    /** An import that was undone and can be put back (its redo data sits in data/meshtiles_redo). */
    public static final class Undone {
        public int id;
        public int dim;
        public UUID player;
        public String playerName = "";
        public long time;
        public int blocks;
        public long tiles;
        public String summary = "";

        NBTTagCompound write() {
            NBTTagCompound nbt = new NBTTagCompound();
            nbt.setInteger("id", id);
            nbt.setInteger("dim", dim);
            nbt.setUniqueId("player", player);
            nbt.setString("name", playerName);
            nbt.setLong("time", time);
            nbt.setInteger("blocks", blocks);
            nbt.setLong("tiles", tiles);
            nbt.setString("summary", summary);
            return nbt;
        }

        static Undone read(NBTTagCompound nbt) {
            Undone u = new Undone();
            u.id = nbt.getInteger("id");
            u.dim = nbt.getInteger("dim");
            u.player = nbt.getUniqueId("player");
            u.playerName = nbt.getString("name");
            u.time = nbt.getLong("time");
            u.blocks = nbt.getInteger("blocks");
            u.tiles = nbt.getLong("tiles");
            u.summary = nbt.getString("summary");
            return u;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<Undone> undone = new ArrayList<>();
    private int nextId = 1;

    public ImportHistory() {
        super(NAME);
    }

    public ImportHistory(String name) {
        super(name);
    }

    public static ImportHistory get(WorldServer world) {
        MapStorage storage = world.getMapStorage();
        ImportHistory h = (ImportHistory) storage.getOrLoadData(ImportHistory.class, NAME);
        if (h == null) {
            h = new ImportHistory();
            storage.setData(NAME, h);
        }
        h.moveLegacyUndo(world);
        return h;
    }

    /** Undo data file of one import. Ids are unique per save, so the dimension is not part of the name. */
    public static File undoFile(WorldServer world, int id) {
        return new File(world.getSaveHandler().getWorldDirectory(), "data/meshtiles_undo/" + id + ".dat");
    }

    /** Old saves kept every undo log inside this file: write them out once and shrink the index. */
    private void moveLegacyUndo(WorldServer world) {
        int moved = 0;
        for (Entry e : entries) {
            if (e.legacyUndo == null) continue;
            HistoryIO.write(undoFile(world, e.id), e.legacyUndo, UndoLog::save);
            e.legacyUndo = null;
            moved++;
        }
        if (moved > 0) {
            markDirty();
            MeshTiles.logger.info("Moved the undo data of " + moved + " imports out of " + NAME + ".dat into data/meshtiles_undo");
        }
    }

    public int nextId() {
        int id = nextId++;
        markDirty();
        return id;
    }

    /** Adds a finished import; returns the entries that fell out of the history (their undo files can go). */
    public List<Entry> add(Entry e) {
        entries.add(e);
        List<Entry> dropped = new ArrayList<>();
        while (entries.size() > Math.max(1, MeshTilesConfig.historySize)) dropped.add(entries.remove(0));
        markDirty();
        return dropped;
    }

    public List<Entry> entries() {
        return entries;
    }

    public Entry find(int id) {
        for (Entry e : entries) if (e.id == id) return e;
        return null;
    }

    public Entry lastOf(UUID player) {
        for (int i = entries.size() - 1; i >= 0; i--)
            if (entries.get(i).player.equals(player)) return entries.get(i);
        return null;
    }

    public void remove(Entry e) {
        entries.remove(e);
        markDirty();
    }

    /** Records an undone import; returns the entries that fell out of the list (their redo files can go). */
    public List<Undone> addUndone(Undone u) {
        undone.add(u);
        List<Undone> dropped = new ArrayList<>();
        while (undone.size() > Math.max(1, MeshTilesConfig.historySize)) dropped.add(undone.remove(0));
        markDirty();
        return dropped;
    }

    public List<Undone> undone() {
        return undone;
    }

    public Undone findUndone(int id) {
        for (Undone u : undone) if (u.id == id) return u;
        return null;
    }

    public Undone lastUndoneOf(UUID player) {
        for (int i = undone.size() - 1; i >= 0; i--)
            if (undone.get(i).player.equals(player)) return undone.get(i);
        return null;
    }

    public void removeUndone(Undone u) {
        undone.remove(u);
        markDirty();
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        entries.clear();
        nextId = Math.max(1, nbt.getInteger("nextId"));
        NBTTagList list = nbt.getTagList("entries", 10);
        for (int i = 0; i < list.tagCount(); i++) entries.add(Entry.read(list.getCompoundTagAt(i)));
        undone.clear();
        NBTTagList un = nbt.getTagList("undone", 10);
        for (int i = 0; i < un.tagCount(); i++) undone.add(Undone.read(un.getCompoundTagAt(i)));
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        nbt.setInteger("nextId", nextId);
        NBTTagList list = new NBTTagList();
        for (Entry e : entries) list.appendTag(e.write());
        nbt.setTag("entries", list);
        NBTTagList un = new NBTTagList();
        for (Undone u : undone) un.appendTag(u.write());
        nbt.setTag("undone", un);
        return nbt;
    }
}
