package ru.arthaix.meshtiles.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

import com.creativemd.creativecore.common.packet.PacketHandler;

import ru.arthaix.meshtiles.MeshTilesConfig;
import ru.arthaix.meshtiles.common.ChunkCodec;
import ru.arthaix.meshtiles.common.ImportPalette;
import ru.arthaix.meshtiles.network.PacketImportBegin;
import ru.arthaix.meshtiles.network.PacketImportChunk;
import ru.arthaix.meshtiles.network.PacketImportEnd;
import ru.arthaix.meshtiles.network.PacketImportStatus;
import ru.arthaix.meshtiles.server.ImportJob;
import ru.arthaix.meshtiles.voxel.MaterialSetup;
import ru.arthaix.meshtiles.voxel.MultiModel;
import ru.arthaix.meshtiles.voxel.PackedBox;
import ru.arthaix.meshtiles.voxel.VoxelModel;
import ru.arthaix.meshtiles.voxel.VoxelPalette;

/**
 * Streams a {@link MultiModel} to the server: Begin, then Chunk packets whenever the server asks for more
 * (its queue is below the threshold), then End. Everything runs on the client thread (PacketHandler is not thread-safe).
 *
 * <p>Blocks are sent material by material in the order of the GUI list (top first): every pass contains only the
 * boxes of one material, so the server places the first material everywhere before starting the next one.
 * A block that holds several materials is therefore visited once per material (later visits append tiles and skip
 * whatever is already occupied). Parts with different grids keep their own grid per entry.</p>
 */
public final class ImportUploader {

    public final int sessionId = new Random().nextInt(Integer.MAX_VALUE - 1) + 1;
    /** Largest chunk payload sent in one packet: below CreativeCore's 31767-byte split threshold with headers to spare. */
    public static final int MAX_PAYLOAD = 30_000;
    private final MultiModel model;
    private final List<MaterialSetup> materials;
    /** Combined palette over all parts (ids of part k are shifted by its offset). */
    private final ImportPalette palette;
    /** Entries in send order: parallel arrays of block key, grid and boxes (one material per entry). */
    private final long[] entryKeys;
    private final int[] entryGrids;
    private final long[][] entryBoxes;
    private int cursor;
    private int seq;
    private boolean endSent;
    private boolean finished;
    public int sentBlocks;

    /**
     * @param materialRank rank per mesh material index (lower = sent first); null = everything in one pass
     */
    public ImportUploader(MultiModel model, List<MaterialSetup> materials, int[] materialRank) {
        this.model = model;
        this.materials = materials;

        // combined palette: part k uses ids [offset_k, offset_k + size_k - 1]
        List<String> ids = new ArrayList<>();
        List<Integer> cols = new ArrayList<>();
        List<Boolean> solid = new ArrayList<>();
        ids.add("");
        cols.add(-1);
        solid.add(false);
        int[] offsets = new int[model.parts.size()];
        for (int k = 0; k < model.parts.size(); k++) {
            VoxelPalette p = model.parts.get(k).palette;
            ImportPalette ip = ImportPalette.fromVoxelPalette(p, materials);
            offsets[k] = ids.size() - 1; // id i of this part becomes i + offset
            for (int i = 1; i < ip.size(); i++) {
                ids.add(ip.blockIds[i]);
                cols.add(ip.colors[i]);
                solid.add(ip.solid[i]);
            }
        }
        int[] colArr = new int[cols.size()];
        boolean[] solidArr = new boolean[cols.size()];
        for (int i = 0; i < colArr.length; i++) {
            colArr[i] = cols.get(i);
            solidArr[i] = solid.get(i);
        }
        this.palette = new ImportPalette(ids.toArray(new String[0]), colArr, solidArr);

        // entries grouped by rank (material order), then by block; each entry holds one part's boxes of one rank
        TreeMap<Integer, List<Object[]>> byRank = new TreeMap<>();
        long[] scratch = new long[1024];
        for (int k = 0; k < model.parts.size(); k++) {
            VoxelModel part = model.parts.get(k);
            VoxelPalette pal = part.palette;
            int paletteSize = pal.size();
            int[] idRank = new int[paletteSize];
            for (int id = 1; id < paletteSize; id++) {
                int m = pal.material(id);
                idRank[id] = materialRank == null || m >= materialRank.length ? 0 : materialRank[m];
            }
            java.util.TreeSet<Integer> ranks = new java.util.TreeSet<>();
            for (int id = 1; id < paletteSize; id++) ranks.add(idRank[id]);
            List<Long> keys = part.sortedKeys();
            for (int rank : ranks) {
                List<Object[]> list = byRank.computeIfAbsent(rank, r -> new ArrayList<>());
                for (long key : keys) {
                    long[] boxes = part.getBlock(key);
                    int n = 0;
                    for (long box : boxes) {
                        int id = PackedBox.id(box);
                        if (id > 0 && id < paletteSize && idRank[id] == rank) {
                            if (n == scratch.length) scratch = java.util.Arrays.copyOf(scratch, n * 2);
                            scratch[n++] = (box & ~0xffffL) | (id + offsets[k]);
                        }
                    }
                    if (n == 0) continue;
                    list.add(new Object[] { key, part.grid, java.util.Arrays.copyOf(scratch, n) });
                }
            }
        }
        int total = 0;
        for (List<Object[]> l : byRank.values()) total += l.size();
        entryKeys = new long[total];
        entryGrids = new int[total];
        entryBoxes = new long[total][];
        int i = 0;
        for (List<Object[]> l : byRank.values())
            for (Object[] e : l) {
                entryKeys[i] = (Long) e[0];
                entryGrids[i] = (Integer) e[1];
                entryBoxes[i] = (long[]) e[2];
                i++;
            }
    }

    public void start() {
        PacketHandler.sendPacketToServer(new PacketImportBegin(sessionId, model.maxGrid, entryKeys.length, model.boxCount(), palette));
        // prime the server queue with a couple of chunks; the rest is pulled by status packets
        sendNext();
        sendNext();
    }

    public boolean isFinished() {
        return finished;
    }

    public void onStatus(PacketImportStatus st) {
        if (st.sessionId != sessionId) return;
        ImportJob.State state = st.state();
        if (state != ImportJob.State.RECEIVING) {
            finished = true;
            return;
        }
        if (st.requestMore) {
            // send a few chunks per request to keep the pipe full without flooding
            int n = 0;
            while (n++ < 3 && sendNext()) {}
        }
        if (cursor >= entryKeys.length && !endSent) {
            PacketHandler.sendPacketToServer(new PacketImportEnd(sessionId, seq - 1));
            endSent = true;
        }
    }

    /** @return false when everything has been sent */
    private boolean sendNext() {
        if (cursor >= entryKeys.length) return false;
        int end = Math.min(entryKeys.length, cursor + MeshTilesConfig.blocksPerPacket);
        List<ChunkCodec.Block> batch = new ArrayList<>(end - cursor);
        for (int i = cursor; i < end; i++)
            batch.add(new ChunkCodec.Block(entryKeys[i], entryGrids[i], entryBoxes[i]));
        cursor = end;
        sentBlocks = cursor;
        sendBatch(batch);
        if (cursor >= entryKeys.length && !endSent) {
            PacketHandler.sendPacketToServer(new PacketImportEnd(sessionId, seq - 1));
            endSent = true;
        }
        return true;
    }

    /**
     * Sends a batch as one packet, or splits it until every packet stays under {@link #MAX_PAYLOAD}: packets above
     * CreativeCore's split threshold go through a shared queue that is not safe for back-to-back sending.
     */
    private void sendBatch(List<ChunkCodec.Block> batch) {
        byte[] payload = ChunkCodec.encode(batch);
        if (payload.length <= MAX_PAYLOAD) {
            PacketHandler.sendPacketToServer(new PacketImportChunk(sessionId, seq++, payload));
            return;
        }
        if (batch.size() > 1) {
            int mid = batch.size() / 2;
            sendBatch(new ArrayList<>(batch.subList(0, mid)));
            sendBatch(new ArrayList<>(batch.subList(mid, batch.size())));
            return;
        }
        // one block with too many boxes: split its boxes, the server appends the second half to the same block
        ChunkCodec.Block b = batch.get(0);
        if (b.boxes.length < 2) {
            PacketHandler.sendPacketToServer(new PacketImportChunk(sessionId, seq++, payload));
            return;
        }
        int mid = b.boxes.length / 2;
        long[] first = new long[mid], second = new long[b.boxes.length - mid];
        System.arraycopy(b.boxes, 0, first, 0, mid);
        System.arraycopy(b.boxes, mid, second, 0, second.length);
        List<ChunkCodec.Block> a = new ArrayList<>(1), c = new ArrayList<>(1);
        a.add(new ChunkCodec.Block(b.key, b.grid, first));
        c.add(new ChunkCodec.Block(b.key, b.grid, second));
        sendBatch(a);
        sendBatch(c);
    }

    public int totalBlocks() {
        return entryKeys.length;
    }
}
