package ru.arthaix.meshtiles.server;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.creativemd.creativecore.common.packet.PacketHandler;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import ru.arthaix.meshtiles.MeshTiles;
import ru.arthaix.meshtiles.MeshTilesConfig;
import ru.arthaix.meshtiles.common.ChunkCodec;
import ru.arthaix.meshtiles.common.ImportPalette;
import ru.arthaix.meshtiles.network.PacketImportStatus;

/** Server-side registry of running imports; drives them from the server tick and records finished ones in the world's history. */
public final class ImportJobManager {

    public static final ImportJobManager INSTANCE = new ImportJobManager();

    private final Map<UUID, ImportJob> active = new HashMap<>();
    /** Players whose undo or redo data is being read from disk. */
    private final Set<UUID> loading = new HashSet<>();

    private ImportJobManager() {}

    public static boolean isAllowed(EntityPlayerMP player) {
        if (player.isCreative()) return true;
        return player.getServer() != null && player.getServer().getPlayerList().canSendCommands(player.getGameProfile());
    }

    public ImportJob get(EntityPlayerMP player) {
        return active.get(player.getUniqueID());
    }

    public Map<UUID, ImportJob> activeJobs() {
        return active;
    }

    public ImportJob begin(EntityPlayerMP player, int sessionId, int grid, int totalBlocks, long totalBoxes, ImportPalette palette) {
        return begin(player, (WorldServer) player.world, sessionId, grid, totalBlocks, totalBoxes, palette);
    }

    public ImportJob begin(EntityPlayerMP player, WorldServer world, int sessionId, int grid, int totalBlocks, long totalBoxes, ImportPalette palette) {
        if (busy(player, "starting another one")) return null;
        ImportJob job = new ImportJob(sessionId, player, world, grid, totalBlocks, totalBoxes, palette);
        job.historyId = ImportHistory.get(job.world).nextId();
        active.put(player.getUniqueID(), job);
        job.log("started: " + totalBlocks + " blocks, " + totalBoxes + " tiles, grid " + grid);
        chat(player, TextFormatting.GREEN + "Import #" + job.historyId + " started: " + totalBlocks + " blocks, " + totalBoxes + " tiles.");
        sendStatus(player, job, "");
        return job;
    }

    /** True (and a message) when the player already has a job running or data loading. */
    private boolean busy(EntityPlayerMP player, String what) {
        ImportJob running = active.get(player.getUniqueID());
        if (running != null && !running.isFinished()) {
            chat(player, TextFormatting.RED + "An import is still running (" + running.percent() + "%). Wait or cancel it before " + what + ".");
            return true;
        }
        if (loading.contains(player.getUniqueID())) {
            chat(player, TextFormatting.GRAY + "Still reading undo/redo data from disk, one moment...");
            return true;
        }
        return false;
    }

    public void receiveChunk(EntityPlayerMP player, int sessionId, int seq, List<ChunkCodec.Block> blocks) {
        ImportJob job = active.get(player.getUniqueID());
        if (job == null || job.id != sessionId || job.state != ImportJob.State.RECEIVING) return;
        if (seq != job.lastSeq + 1) {
            job.log("out-of-order chunk " + seq + " (expected " + (job.lastSeq + 1) + "), cancelling");
            job.cancel();
            chat(player, TextFormatting.RED + "Import cancelled: packet order broken.");
            finishJob(job, player);
            return;
        }
        job.lastSeq = seq;
        job.enqueue(blocks);
        sendStatus(player, job, "");
    }

    public void end(EntityPlayerMP player, int sessionId, int lastSeq) {
        ImportJob job = active.get(player.getUniqueID());
        if (job == null || job.id != sessionId) return;
        if (lastSeq != job.lastSeq) job.log("end received with lastSeq " + lastSeq + " but got " + job.lastSeq);
        job.endReceived = true;
    }

    /** Cancels the running import of the given player; the requester gets the messages. */
    public boolean cancel(UUID target, ICommandSender requester) {
        ImportJob job = active.get(target);
        if (job == null || job.isFinished()) {
            msg(requester, TextFormatting.GRAY + "No import is running.");
            return false;
        }
        job.cancel();
        job.log("cancelled: " + job.summary());
        msg(requester, TextFormatting.YELLOW + "Import #" + job.historyId + " cancelled: " + job.summary() + (job.hasUndoData() ? " (undo #" + job.historyId + " is available)" : ""));
        EntityPlayerMP owner = player(target);
        finishJob(job, owner);
        return true;
    }

    /** Undoes a history entry (id, or the requester's last import when id is 0). Undo data is read off the server thread. */
    public boolean undo(EntityPlayerMP requester, int id) {
        if (busy(requester, "undoing")) return false;
        UUID uid = requester.getUniqueID();
        String name = requester.getName();
        ImportHistory history = ImportHistory.get((WorldServer) requester.world);
        ImportHistory.Entry entry = id > 0 ? history.find(id) : history.lastOf(uid);
        if (entry == null || entry.undoBlocks == 0) {
            chat(requester, TextFormatting.GRAY + (id > 0 ? "Import #" + id + " is not in the history." : "Nothing to undo."));
            return false;
        }
        if (!entry.player.equals(uid) && !requester.canUseCommand(2, "meshtiles")) {
            chat(requester, TextFormatting.RED + "Import #" + entry.id + " belongs to " + entry.playerName + "; op level 2 is needed to undo it.");
            return false;
        }
        WorldServer world = requester.getServer().getWorld(entry.dim);
        File file = ImportHistory.undoFile(world, entry.id);
        history.remove(entry);
        loading.add(uid);
        chat(requester, TextFormatting.YELLOW + "Undoing import #" + entry.id + " (" + entry.undoBlocks + " blocks)...");
        HistoryIO.read(file, UndoLog::load, log -> {
            loading.remove(uid);
            ImportJob job = new ImportJob(entry, log, world, uid, name);
            active.put(uid, job);
            HistoryIO.delete(file);
            job.log("undo started (" + log.size() + " blocks)");
            EntityPlayerMP p = player(uid);
            if (p != null) sendStatus(p, job, "");
        }, error -> {
            loading.remove(uid);
            MeshTiles.logger.warn("Undo data of import #" + entry.id + " could not be read: " + error);
            EntityPlayerMP p = player(uid);
            if (p != null) chat(p, TextFormatting.RED + "Undo data of #" + entry.id + " could not be read: " + error);
        });
        return true;
    }

    /** Puts an undone import back (id, or the requester's last undone import when id is 0). */
    public boolean redo(EntityPlayerMP requester, int id) {
        if (busy(requester, "redoing")) return false;
        UUID uid = requester.getUniqueID();
        ImportHistory history = ImportHistory.get((WorldServer) requester.world);
        ImportHistory.Undone entry = id > 0 ? history.findUndone(id) : history.lastUndoneOf(uid);
        if (entry == null) {
            chat(requester, TextFormatting.GRAY + (id > 0 ? "Import #" + id + " has no redo data." : "Nothing to redo."));
            return false;
        }
        if (!entry.player.equals(uid) && !requester.canUseCommand(2, "meshtiles")) {
            chat(requester, TextFormatting.RED + "Import #" + entry.id + " belongs to " + entry.playerName + "; op level 2 is needed to redo it.");
            return false;
        }
        WorldServer world = requester.getServer().getWorld(entry.dim);
        File file = RedoLog.file(world, entry.dim, entry.id);
        history.removeUndone(entry);
        loading.add(uid);
        HistoryIO.read(file, RedoLog::read, redo -> {
            loading.remove(uid);
            EntityPlayerMP p = player(uid);
            ImportJob job = p == null ? null : begin(p, world, 0, redo.maxGrid, redo.blocks.size(), redo.boxCount, redo.palette());
            if (job == null) {
                history.addUndone(entry); // keep it for later
                return;
            }
            job.redoOf = entry.id;
            job.enqueue(redo.blocks);
            job.endReceived = true;
            HistoryIO.delete(file);
            job.log("redo of #" + entry.id);
        }, error -> {
            loading.remove(uid);
            MeshTiles.logger.warn("Redo data of import #" + entry.id + " could not be read: " + error);
            EntityPlayerMP p = player(uid);
            if (p != null) chat(p, TextFormatting.RED + "Redo data of #" + entry.id + " could not be read: " + error);
        });
        return true;
    }

    /** Called when an undo has finished: keeps what was removed so it can be redone (written off the server thread). */
    private void finishUndo(ImportJob job, EntityPlayerMP player) {
        String hint = "";
        if (job.redo != null && !job.redo.isEmpty()) {
            ImportHistory history = ImportHistory.get(job.world);
            ImportHistory.Undone u = new ImportHistory.Undone();
            u.id = job.historyId;
            u.dim = job.world.provider.getDimension();
            u.player = job.playerId;
            u.playerName = job.playerName;
            u.time = System.currentTimeMillis();
            u.blocks = job.redo.blocks.size();
            u.tiles = job.redo.boxCount;
            u.summary = job.redo.blocks.size() + " blocks, " + job.redo.boxCount + " tiles";
            HistoryIO.write(RedoLog.file(job.world, u.dim, u.id), job.redo, RedoLog::save);
            for (ImportHistory.Undone dropped : history.addUndone(u)) HistoryIO.delete(RedoLog.file(job.world, dropped.dim, dropped.id));
            hint = " Redo with the GUI or /meshtiles redo " + job.historyId;
        }
        job.log("undone");
        if (player != null) chat(player, TextFormatting.GREEN + "Import #" + job.historyId + " undone." + hint);
    }

    public void list(ICommandSender sender, WorldServer world) {
        List<ImportHistory.Entry> entries = ImportHistory.get(world).entries();
        List<ImportHistory.Undone> undone = ImportHistory.get(world).undone();
        if (entries.isEmpty() && undone.isEmpty()) {
            msg(sender, TextFormatting.GRAY + "No imports recorded in this world.");
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM HH:mm");
        msg(sender, TextFormatting.AQUA + "Imports (newest last), /meshtiles undo <id> to revert:");
        int from = Math.max(0, entries.size() - 15);
        for (int i = from; i < entries.size(); i++) {
            ImportHistory.Entry e = entries.get(i);
            msg(sender, "#" + e.id + " " + e.playerName + " " + fmt.format(new Date(e.time)) + ": " + e.summary);
        }
        if (!undone.isEmpty()) {
            msg(sender, TextFormatting.AQUA + "Undone, /meshtiles redo <id> to put back:");
            int ufrom = Math.max(0, undone.size() - 10);
            for (int i = ufrom; i < undone.size(); i++) {
                ImportHistory.Undone u = undone.get(i);
                msg(sender, TextFormatting.GRAY + "#" + u.id + " " + u.playerName + " " + fmt.format(new Date(u.time)) + ": " + u.summary);
            }
        }
        for (ImportJob job : active.values())
            msg(sender, TextFormatting.YELLOW + "running: #" + job.historyId + " " + job.playerName + " " + job.percent() + "%");
    }

    private void finishJob(ImportJob job, EntityPlayerMP owner) {
        job.removeBar();
        active.remove(job.playerId);
        if (job.hasUndoData() && (job.state == ImportJob.State.DONE || job.state == ImportJob.State.CANCELLED)) {
            ImportHistory history = ImportHistory.get(job.world);
            ImportHistory.Entry e = new ImportHistory.Entry();
            e.id = job.historyId;
            e.dim = job.world.provider.getDimension();
            e.player = job.playerId;
            e.playerName = job.playerName;
            e.time = System.currentTimeMillis();
            e.blocks = job.placed;
            e.tiles = job.tilesPlaced;
            e.summary = job.summary() + (job.state == ImportJob.State.CANCELLED ? " (cancelled)" : "");
            e.undoBlocks = job.undo.size();
            job.undo.finishRecording();
            HistoryIO.write(ImportHistory.undoFile(job.world, e.id), job.undo, UndoLog::save);
            for (ImportHistory.Entry dropped : history.add(e)) HistoryIO.delete(ImportHistory.undoFile(job.world, dropped.id));
        }
        if (owner != null) sendStatus(owner, job, "");
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || active.isEmpty()) return;
        long deadline = System.nanoTime() + MeshTilesConfig.maxMillisPerTick * 1_000_000L;
        int placeBudget = Math.max(1, MeshTilesConfig.blocksPerTick / active.size());
        int undoBudget = Math.max(1, MeshTilesConfig.undoBlocksPerTick / active.size());
        Iterator<ImportJob> it = active.values().iterator();
        while (it.hasNext()) {
            ImportJob job = it.next();
            EntityPlayerMP player = player(job.playerId);
            boolean wantedMore = job.wantsMore();
            try {
                job.tick(job.state == ImportJob.State.UNDOING ? undoBudget : placeBudget, deadline);
            } catch (Exception e) {
                job.log("error while placing: " + e);
                e.printStackTrace();
                job.cancel();
                if (player != null) chat(player, TextFormatting.RED + "Import failed: " + e);
            }
            if (job.state != ImportJob.State.RECEIVING && job.state != ImportJob.State.UNDOING) job.removeBar();
            if (job.state == ImportJob.State.DONE || job.state == ImportJob.State.CANCELLED) {
                job.log((job.state == ImportJob.State.DONE ? "done: " : "cancelled: ") + job.summary());
                if (player != null && job.state == ImportJob.State.DONE)
                    chat(player, TextFormatting.GREEN + "Import #" + job.historyId + " done: " + job.summary() + ". Undo with the GUI or /meshtiles undo " + job.historyId);
                it.remove();
                finishJob(job, player);
            } else if (job.state == ImportJob.State.UNDONE) {
                it.remove();
                finishUndo(job, player);
                if (player != null) sendStatus(player, job, "");
            } else if (player != null) {
                job.updateBar(player);
                if (job.crossedPercentStep()) {
                    sendStatus(player, job, "");
                } else if (!wantedMore && job.wantsMore()) {
                    sendStatus(player, job, ""); // queue drained below the threshold: ask the client for more
                }
            }
        }
    }

    /** Server is stopping: imports still being placed keep their undo data instead of losing it. */
    public void onServerStopping() {
        for (ImportJob job : new ArrayList<>(active.values())) {
            if (job.state != ImportJob.State.RECEIVING) continue;
            job.cancel();
            job.log("server stopping, stopped at: " + job.summary());
            finishJob(job, null);
        }
        active.clear();
        loading.clear();
    }

    public void onPlayerLoggedOut(EntityPlayerMP player) {
        ImportJob job = active.get(player.getUniqueID());
        if (job != null) job.removeBar();
        if (job != null && job.state == ImportJob.State.RECEIVING && !job.endReceived) {
            job.cancel();
            job.log("player left before the upload finished, cancelled: " + job.summary());
            finishJob(job, null);
        }
    }

    private static EntityPlayerMP player(UUID id) {
        return FMLCommonHandler.instance().getMinecraftServerInstance().getPlayerList().getPlayerByUUID(id);
    }

    public static void sendStatus(EntityPlayerMP player, ImportJob job, String message) {
        PacketHandler.sendPacketToPlayer(new PacketImportStatus(job, message), player);
    }

    public static void chat(EntityPlayerMP player, String text) {
        player.sendMessage(new TextComponentString(text));
    }

    public static void msg(ICommandSender sender, String text) {
        sender.sendMessage(new TextComponentString(text));
    }
}
