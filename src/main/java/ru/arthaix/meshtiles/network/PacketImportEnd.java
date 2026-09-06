package ru.arthaix.meshtiles.network;

import com.creativemd.creativecore.common.packet.CreativeCorePacket;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import ru.arthaix.meshtiles.server.ImportJobManager;

/** Client → server: all chunks have been sent. */
public class PacketImportEnd extends CreativeCorePacket {

    public int sessionId;
    public int lastSeq;

    public PacketImportEnd() {}

    public PacketImportEnd(int sessionId, int lastSeq) {
        this.sessionId = sessionId;
        this.lastSeq = lastSeq;
    }

    @Override
    public void writeBytes(ByteBuf buf) {
        buf.writeInt(sessionId);
        buf.writeInt(lastSeq);
    }

    @Override
    public void readBytes(ByteBuf buf) {
        sessionId = buf.readInt();
        lastSeq = buf.readInt();
    }

    @Override
    public void executeClient(EntityPlayer player) {}

    @Override
    public void executeServer(EntityPlayer player) {
        ImportJobManager.INSTANCE.end((EntityPlayerMP) player, sessionId, lastSeq);
    }
}
