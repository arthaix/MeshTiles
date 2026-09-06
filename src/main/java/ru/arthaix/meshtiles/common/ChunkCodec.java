package ru.arthaix.meshtiles.common;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Serialises a batch of blocks (block key + grid + packed boxes) into a deflated byte array for streaming packets.
 * Layout before compression: int blockCount, then per block: long key, int grid, int boxCount, boxCount × long box.
 */
public final class ChunkCodec {

    public static final class Block {
        public final long key;
        /** LittleTiles grid the boxes are expressed in. */
        public final int grid;
        public final long[] boxes;

        public Block(long key, int grid, long[] boxes) {
            this.key = key;
            this.grid = grid;
            this.boxes = boxes;
        }
    }

    private ChunkCodec() {}

    public static byte[] encode(List<Block> blocks) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream(blocks.size() * 256);
            DataOutputStream out = new DataOutputStream(raw);
            out.writeInt(blocks.size());
            for (Block b : blocks) {
                out.writeLong(b.key);
                out.writeInt(b.grid);
                out.writeInt(b.boxes.length);
                for (long box : b.boxes) out.writeLong(box);
            }
            out.flush();
            byte[] data = raw.toByteArray();
            Deflater deflater = new Deflater(Deflater.BEST_SPEED);
            deflater.setInput(data);
            deflater.finish();
            byte[] buf = new byte[data.length + 64];
            ByteArrayOutputStream comp = new ByteArrayOutputStream(data.length / 2 + 64);
            while (!deflater.finished()) {
                int n = deflater.deflate(buf);
                comp.write(buf, 0, n);
            }
            deflater.end();
            byte[] result = new byte[4 + comp.size()];
            ByteBuffer.wrap(result).putInt(data.length);
            System.arraycopy(comp.toByteArray(), 0, result, 4, comp.size());
            return result;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<Block> decode(byte[] payload) throws DataFormatException {
        int rawLen = ByteBuffer.wrap(payload).getInt();
        if (rawLen < 4 || rawLen > (64 << 20)) throw new DataFormatException("bad length " + rawLen);
        Inflater inflater = new Inflater();
        inflater.setInput(payload, 4, payload.length - 4);
        byte[] data = new byte[rawLen];
        int off = 0;
        while (off < rawLen && !inflater.finished()) {
            int n = inflater.inflate(data, off, rawLen - off);
            if (n == 0 && inflater.needsInput()) break;
            off += n;
        }
        inflater.end();
        if (off != rawLen) throw new DataFormatException("truncated chunk payload");
        ByteBuffer in = ByteBuffer.wrap(data);
        int count = in.getInt();
        if (count < 0 || count > 1 << 20) throw new DataFormatException("bad block count " + count);
        List<Block> blocks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long key = in.getLong();
            int grid = in.getInt();
            int n = in.getInt();
            if (grid < 1 || grid > 128 || n < 0 || n > 1 << 20) throw new DataFormatException("bad block entry");
            long[] boxes = new long[n];
            for (int k = 0; k < n; k++) boxes[k] = in.getLong();
            blocks.add(new Block(key, grid, boxes));
        }
        return blocks;
    }
}
