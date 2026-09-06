package ru.arthaix.meshtiles.network;

import java.util.List;
import java.util.zip.DataFormatException;

import com.creativemd.creativecore.common.packet.CreativeCorePacket;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextFormatting;
import ru.arthaix.meshtiles.common.ChunkCodec;
import ru.arthaix.meshtiles.server.ImportJobManager;

/** Client → server: one batch of blocks (deflated, see {@link ChunkCodec}). */
public class PacketImportChunk extends CreativeCorePacket {

    public int sessionId;
    public int seq;
    public byte[] payload;

    public PacketImportChunk() {}

    public PacketImportChunk(int sessionId, int seq, byte[] payload) {
        this.sessionId = sessionId;
        this.seq = seq;
        this.payload = payload;
    }

    @Override
    public void writeBytes(ByteBuf buf) {
        buf.writeInt(sessionId);
        buf.writeInt(seq);
        buf.writeInt(payload.length);
        buf.writeBytes(payload);
    }

    @Override
    public void readBytes(ByteBuf buf) {
        sessionId = buf.readInt();
        seq = buf.readInt();
        int len = buf.readInt();
        if (len < 0 || len > (32 << 20)) throw new IllegalArgumentException("chunk payload too large: " + len);
        payload = new byte[len];
        buf.readBytes(payload);
    }

    @Override
    public void executeClient(EntityPlayer player) {}

    @Override
    public void executeServer(EntityPlayer player) {
        EntityPlayerMP mp = (EntityPlayerMP) player;
        if (ImportJobManager.INSTANCE.get(mp) == null) return;
        List<ChunkCodec.Block> blocks;
        try {
            blocks = ChunkCodec.decode(payload);
        } catch (DataFormatException | RuntimeException e) {
            ImportJobManager.chat(mp, TextFormatting.RED + "Import chunk " + seq + " is corrupt: " + e.getMessage());
            ImportJobManager.INSTANCE.cancel(mp.getUniqueID(), mp);
            return;
        }
        ImportJobManager.INSTANCE.receiveChunk(mp, sessionId, seq, blocks);
    }
}
