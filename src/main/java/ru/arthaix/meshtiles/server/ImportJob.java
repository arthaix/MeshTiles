package ru.arthaix.meshtiles.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.creativemd.creativecore.common.utils.type.Pair;
import com.creativemd.littletiles.LittleTiles;
import com.creativemd.littletiles.common.block.BlockTile;
import com.creativemd.littletiles.common.tile.LittleTile;
import com.creativemd.littletiles.common.tile.LittleTileColored;
import com.creativemd.littletiles.common.tile.math.box.LittleBox;
import com.creativemd.littletiles.common.tile.parent.IParentTileList;
import com.creativemd.littletiles.common.tileentity.TileEntityLittleTiles;
import com.creativemd.littletiles.common.util.grid.LittleGridContext;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.BossInfo;
import net.minecraft.world.BossInfoServer;
import net.minecraft.world.WorldServer;
import ru.arthaix.meshtiles.MeshTilesConfig;
import ru.arthaix.meshtiles.MeshTiles;
import ru.arthaix.meshtiles.common.BlockRef;
import ru.arthaix.meshtiles.common.ChunkCodec;
import ru.arthaix.meshtiles.common.ImportPalette;
import ru.arthaix.meshtiles.voxel.PackedBox;

/** One streamed import for one player: receives block batches and writes them into the world under a per-tick budget. */
public final class ImportJob {

    public enum State { RECEIVING, DONE, CANCELLED, UNDOING, UNDONE }

    /** Session id chosen by the client (matches status packets). */
    public final int id;
    /** Number in the world's import history (assigned when the job starts). */
    public int historyId;
    public final UUID playerId;
    public final String playerName;
    public final WorldServer world;
    public final int grid;
    public final int totalBlocks;
    public final long totalBoxes;
    private final BlockRef[] blocks;
    private final int[] colors;
    private final boolean[] solid;

    private final ArrayDeque<ChunkCodec.Block> queue = new ArrayDeque<>();
    public State state = State.RECEIVING;
    public int received, placed, skippedProtected, skippedDense, skippedHeight;
    /** Tiles left out because the space was already occupied (e.g. an earlier material or an older import). */
    public long skippedOccupied;
    /** Tiles that were cut down to their free part because they intersected existing tiles. */
    public long trimmedOccupied;
    public long tilesPlaced;
    /** Real Minecraft blocks placed for grid-1 "MC" materials. */
    public int solidPlaced;
    public boolean endReceived;
    public int lastSeq = -1;
    public UndoLog undo = MeshTilesConfig.keepUndo ? new UndoLog() : null;
    /** While undoing: what was taken out, so it can be put back. */
    public RedoLog redo;
    /** History id this job puts back (0 = a normal import). */
    public int redoOf;
    public final long startMillis = System.currentTimeMillis();
    private int undoCursor;
    private Iterator<Map.Entry<Long, UndoLog.Appended>> undoAppended;
    private int lastReportedPercent = -1;

    public ImportJob(int id, EntityPlayerMP player, WorldServer world, int grid, int totalBlocks, long totalBoxes, ImportPalette palette) {
        this.id = id;
        this.playerId = player.getUniqueID();
        this.playerName = player.getName();
        this.world = world;
        this.grid = grid;
        this.totalBlocks = totalBlocks;
        this.totalBoxes = totalBoxes;
        this.blocks = new BlockRef[palette.size()];
        this.colors = palette.colors;
        this.solid = palette.solid;
        for (int i = 1; i < palette.size(); i++)
            blocks[i] = BlockRef.parse(palette.blockIds[i]);
    }

    /** A job that only undoes a history entry. */
    public ImportJob(ImportHistory.Entry entry, UndoLog log, WorldServer world, UUID requester, String requesterName) {
        this.id = 0;
        this.historyId = entry.id;
        this.playerId = requester;
        this.playerName = requesterName;
        this.world = world;
        this.grid = 16;
        this.totalBlocks = entry.blocks;
        this.totalBoxes = entry.tiles;
        this.blocks = new BlockRef[0];
        this.colors = new int[0];
        this.solid = new boolean[0];
        this.undo = log;
        this.state = State.UNDOING;
        this.undoAppended = undo.appended.entrySet().iterator();
        this.redo = MeshTilesConfig.keepUndo ? new RedoLog() : null;
    }

    public int queued() {
        return queue.size();
    }

    public boolean wantsMore() {
        return state == State.RECEIVING && !endReceived && queue.size() < MeshTilesConfig.maxQueuedBlocks;
    }

    public void enqueue(List<ChunkCodec.Block> batch) {
        if (state != State.RECEIVING) return;
        queue.addAll(batch);
        received += batch.size();
    }

    public void cancel() {
        queue.clear();
        if (state == State.RECEIVING) state = State.CANCELLED;
    }

    public boolean isFinished() {
        return state == State.DONE || state == State.CANCELLED || state == State.UNDONE;
    }

    public boolean hasUndoData() {
        return undo != null && undo.size() > 0;
    }

    /** Runs placement (or undo) until the budget is used up. Returns true if something changed this tick. */
    public boolean tick(int blockBudget, long deadlineNanos) {
        if (state == State.UNDOING) return tickUndo(blockBudget, deadlineNanos);
        if (state != State.RECEIVING) return false;
        int n = 0;
        while (n < blockBudget && !queue.isEmpty()) {
            placeBlock(queue.poll());
            n++;
            if ((n & 7) == 0 && System.nanoTime() > deadlineNanos) break;
        }
        if (queue.isEmpty() && endReceived) state = State.DONE;
        return n > 0 || state == State.DONE;
    }

    private void placeBlock(ChunkCodec.Block data) {
        BlockPos pos = BlockPos.fromLong(data.key);
        if (pos.getY() < 0 || pos.getY() >= world.getHeight()) {
            skippedHeight++;
            return;
        }
        long key = data.key;
        world.getChunk(pos); // loads the chunk if needed
        IBlockState state = world.getBlockState(pos);
        if (data.grid == 1 && data.boxes.length == 1) {
            int idx = PackedBox.id(data.boxes[0]);
            if (idx > 0 && idx < blocks.length && solid[idx]) {
                placeSolid(pos, key, state, blocks[idx]);
                return;
            }
        }
        TileEntityLittleTiles te = null;
        boolean created = false;
        if (state.getBlock() instanceof BlockTile) {
            TileEntity tile = world.getTileEntity(pos);
            if (tile instanceof TileEntityLittleTiles) te = (TileEntityLittleTiles) tile;
        }
        if (te == null) {
            boolean replaceable = state.getBlock().isReplaceable(world, pos);
            if (!replaceable && !(MeshTilesConfig.replaceSolidBlocks && !state.getBlock().hasTileEntity(state))) {
                skippedProtected++;
                return;
            }
            if (!replaceable && undo != null) undo.recordReplaced(key, state);
            world.setBlockState(pos, BlockTile.getStateByAttribute(0), 2);
            TileEntity tile = world.getTileEntity(pos);
            if (!(tile instanceof TileEntityLittleTiles)) {
                skippedProtected++;
                return;
            }
            te = (TileEntityLittleTiles) tile;
            created = true;
        }

        int entryGrid = data.grid > 0 ? data.grid : grid;
        LittleGridContext ctx = LittleGridContext.get(entryGrid);
        te.convertToAtMinimum(ctx);
        int scale = te.getContext().size / entryGrid;
        int n = data.boxes.length;
        if (te.tilesCount() + n > LittleTiles.CONFIG.general.maxAllowedDensity) {
            skippedDense++;
            if (created) world.setBlockToAir(pos);
            return;
        }
        List<LittleTile> tiles = new ArrayList<>(n);
        List<int[]> occupied = null;
        for (long box : data.boxes) {
            int idx = PackedBox.id(box);
            if (idx <= 0 || idx >= blocks.length) continue;
            BlockRef ref = blocks[idx];
            int color = colors[idx];
            LittleBox lb = new LittleBox(PackedBox.minX(box) * scale, PackedBox.minY(box) * scale, PackedBox.minZ(box) * scale,
                PackedBox.maxX(box) * scale, PackedBox.maxY(box) * scale, PackedBox.maxZ(box) * scale);
            if (created || te.isSpaceForLittleTile(lb)) {
                tiles.add(newTile(ref, color, lb));
                continue;
            }
            // Space already taken (an earlier material or an older import): keep only the free part of the tile,
            // e.g. a lamp post keeps going through the pavement surface instead of losing a whole block.
            if (occupied == null) occupied = occupiedBoxes(te);
            List<int[]> free = BoxCutter.subtract(new int[] { lb.minX, lb.minY, lb.minZ, lb.maxX, lb.maxY, lb.maxZ }, occupied);
            if (free == null || free.isEmpty()) {
                skippedOccupied++;
                continue;
            }
            trimmedOccupied++;
            for (int[] f : free)
                tiles.add(newTile(ref, color, new LittleBox(f[0], f[1], f[2], f[3], f[4], f[5])));
        }
        if (te.tilesCount() + tiles.size() > LittleTiles.CONFIG.general.maxAllowedDensity) {
            skippedDense++;
            if (created) world.setBlockToAir(pos);
            return;
        }
        if (tiles.isEmpty()) {
            if (created) world.setBlockToAir(pos);
            return;
        }
        final List<LittleTile> toAdd = tiles;
        te.updateTiles(x -> x.noneStructureTiles().addAll(toAdd));
        if (undo != null) {
            if (created) undo.recordCreated(key);
            else undo.recordAppended(key, te.getContext().size, tiles);
        }
        placed++;
        tilesPlaced += tiles.size();
    }

    /** A grid-1 material with "MC" on: the real block goes into the world, no LittleTiles involved. */
    private void placeSolid(BlockPos pos, long key, IBlockState state, BlockRef ref) {
        if (state.getBlock() instanceof BlockTile) {
            skippedOccupied++;
            return;
        }
        boolean replaceable = state.getBlock().isReplaceable(world, pos);
        if (!replaceable && !(MeshTilesConfig.replaceSolidBlocks && !state.getBlock().hasTileEntity(state))) {
            skippedProtected++;
            return;
        }
        IBlockState target = ref.block.getStateFromMeta(ref.meta);
        if (undo != null) {
            if (!replaceable) undo.recordReplaced(key, state);
            undo.recordCreated(key);
            undo.recordSolid(key, target);
        }
        world.setBlockState(pos, target, 2);
        placed++;
        solidPlaced++;
    }

    private static LittleTile newTile(BlockRef ref, int color, LittleBox box) {
        LittleTile tile = color == -1 ? new LittleTile(ref.block, ref.meta) : new LittleTileColored(ref.block, ref.meta, color);
        tile.setBox(box);
        return tile;
    }

    /** Bounding boxes of every tile in the block (structure tiles included), in the block's current grid. */
    private static List<int[]> occupiedBoxes(TileEntityLittleTiles te) {
        List<int[]> list = new ArrayList<>(te.tilesCount());
        for (Pair<IParentTileList, LittleTile> p : te.allTiles()) {
            LittleBox b = p.value.getBox();
            if (b != null) list.add(new int[] { b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ });
        }
        return list;
    }

    private boolean tickUndo(int blockBudget, long deadlineNanos) {
        int n = 0;
        if (undoCursor < undo.createdCount) {
            // Vanilla removes a tile entity with ArrayList.remove on the list of ALL loaded tile entities (linear scan,
            // milliseconds each in a world full of LittleTiles). Detach this tick's batch in one pass instead.
            int end = Math.min(undo.createdCount, undoCursor + blockBudget);
            List<BlockPos> batch = new ArrayList<>(end - undoCursor);
            java.util.Set<TileEntity> detached = new java.util.HashSet<>();
            for (int i = undoCursor; i < end; i++) {
                BlockPos pos = BlockPos.fromLong(undo.created[i]);
                net.minecraft.world.chunk.Chunk chunk = world.getChunk(pos);
                IBlockState cur = world.getBlockState(pos);
                if (!(cur.getBlock() instanceof BlockTile)) {
                    // a real block we placed: take it back only while it is still ours
                    Integer ours = undo.solid.get(pos.toLong());
                    if (ours == null || Block.getStateId(cur) != ours) continue;
                    if (redo != null) redo.addSolid(pos.toLong(), cur);
                }
                TileEntity te = chunk.getTileEntity(pos, net.minecraft.world.chunk.Chunk.EnumCreateEntityType.CHECK);
                if (te != null) {
                    if (redo != null && te instanceof TileEntityLittleTiles) {
                        TileEntityLittleTiles lt = (TileEntityLittleTiles) te;
                        redo.addTiles(pos.toLong(), lt.getContext().size, lt.noneStructureTiles());
                    }
                    detached.add(te);
                    chunk.getTileEntityMap().remove(pos);
                    te.invalidate();
                }
                batch.add(pos);
            }
            if (!detached.isEmpty()) {
                world.loadedTileEntityList.removeAll(detached);
                world.tickableTileEntities.removeAll(detached);
            }
            for (BlockPos pos : batch) {
                Integer prev = undo.replaced.get(pos.toLong());
                world.setBlockState(pos, prev != null ? Block.getStateById(prev) : net.minecraft.init.Blocks.AIR.getDefaultState(), 2);
            }
            undoCursor = end;
            n = batch.size();
            if (System.nanoTime() > deadlineNanos) return true;
        }
        while (n < blockBudget && undoAppended != null && undoAppended.hasNext()) {
            Map.Entry<Long, UndoLog.Appended> e = undoAppended.next();
            BlockPos pos = BlockPos.fromLong(e.getKey());
            world.getChunk(pos);
            TileEntity tile = world.getTileEntity(pos);
            if (tile instanceof TileEntityLittleTiles) {
                TileEntityLittleTiles te = (TileEntityLittleTiles) tile;
                final int blockGrid = te.getContext().size;
                final int cmpGrid = undo.compareGrid(e.getValue(), blockGrid);
                final java.util.Set<Long> wanted = undo.matchSet(e.getValue(), cmpGrid);
                final List<LittleTile> removed = new ArrayList<>();
                te.updateTiles(x -> x.noneStructureTiles().removeIf(t -> {
                    long k = undo.tileKey(t, blockGrid, cmpGrid);
                    if (k == -1 || !wanted.remove(k)) return false;
                    removed.add(t);
                    return true;
                }));
                if (redo != null && !removed.isEmpty()) redo.addTiles(e.getKey(), blockGrid, removed);
            }
            n++;
            if ((n & 7) == 0 && System.nanoTime() > deadlineNanos) return true;
        }
        if (undoCursor >= undo.createdCount && (undoAppended == null || !undoAppended.hasNext())) {
            // the log stays untouched: it may still be on its way to disk on the IO thread
            state = State.UNDONE;
        }
        return true;
    }

    // ---- progress bar (vanilla boss bar, no client code needed) ----

    private BossInfoServer bar;
    private EntityPlayerMP barPlayer;
    private int barTicks;
    private long phaseStartMillis;
    private int phaseStartValue;

    /** Shows or refreshes the progress bar for the owner; cheap enough to call every tick. */
    public void updateBar(EntityPlayerMP player) {
        if (player == null) return;
        boolean undoing = state == State.UNDOING;
        if (bar == null) {
            bar = new BossInfoServer(new TextComponentString(""), undoing ? BossInfo.Color.RED : BossInfo.Color.BLUE, BossInfo.Overlay.NOTCHED_10);
            bar.setDarkenSky(false);
            bar.setPlayEndBossMusic(false);
            bar.setCreateFog(false);
        }
        if (barPlayer != player) {
            if (barPlayer != null) bar.removePlayer(barPlayer);
            bar.addPlayer(player);
            barPlayer = player;
            barTicks = 0;
        }
        int done = undoing ? undoCursor : placed;
        int total = undoing ? undo.createdCount + undo.appended.size() : totalBlocks;
        if (phaseStartMillis == 0 && done > 0) {
            phaseStartMillis = System.currentTimeMillis();
            phaseStartValue = done;
        }
        if (barTicks++ % 10 != 0) return; // twice a second is plenty for a bar
        bar.setPercent(total == 0 ? 1f : Math.min(1f, (float) done / total));
        StringBuilder sb = new StringBuilder();
        sb.append(undoing ? "Undo #" : "Import #").append(historyId).append(": ").append(percent()).append("%   ");
        sb.append(String.format("%,d / %,d blocks", done, total));
        if (!undoing && !endReceived) sb.append("   (uploading)");
        long elapsed = System.currentTimeMillis() - phaseStartMillis;
        if (phaseStartMillis != 0 && elapsed > 2000 && done > phaseStartValue && total > done) {
            double perMs = (done - phaseStartValue) / (double) elapsed;
            long left = (long) ((total - done) / perMs / 1000);
            sb.append("   ").append(left >= 60 ? (left / 60) + " min " + (left % 60) + " s left" : left + " s left");
        }
        bar.setName(new TextComponentString(sb.toString()));
    }

    /** Hides the bar (job finished, cancelled, undone or the owner left). */
    public void removeBar() {
        if (bar == null) return;
        if (barPlayer != null) bar.removePlayer(barPlayer);
        bar = null;
        barPlayer = null;
    }

    public int percent() {
        if (state == State.UNDOING) {
            int total = undo.createdCount + undo.appended.size();
            return total == 0 ? 100 : (int) (100L * undoCursor / Math.max(1, total));
        }
        return totalBlocks == 0 ? 100 : (int) Math.min(100, 100L * placed / totalBlocks);
    }

    /** True once per new 5 % step, used to throttle chat output. */
    public boolean crossedPercentStep() {
        int p = percent() / 5;
        if (p != lastReportedPercent) {
            lastReportedPercent = p;
            return true;
        }
        return false;
    }

    public String summary() {
        long secs = (System.currentTimeMillis() - startMillis) / 1000;
        StringBuilder sb = new StringBuilder();
        if (redoOf > 0) sb.append("redo of #").append(redoOf).append(": ");
        sb.append(placed).append('/').append(totalBlocks).append(" blocks, ").append(tilesPlaced).append(" tiles, ").append(secs).append(" s");
        if (solidPlaced > 0) sb.append(", ").append(solidPlaced).append(" solid blocks");
        if (skippedProtected > 0) sb.append(", ").append(skippedProtected).append(" skipped (solid)");
        if (skippedDense > 0) sb.append(", ").append(skippedDense).append(" skipped (too dense)");
        if (skippedHeight > 0) sb.append(", ").append(skippedHeight).append(" skipped (out of world)");
        if (trimmedOccupied > 0) sb.append(", ").append(trimmedOccupied).append(" tiles trimmed (space taken)");
        if (skippedOccupied > 0) sb.append(", ").append(skippedOccupied).append(" tiles skipped (space taken)");
        return sb.toString();
    }

    void log(String msg) {
        MeshTiles.logger.info("[import #" + historyId + " " + playerName + "] " + msg);
    }
}
