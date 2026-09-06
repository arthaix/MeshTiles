package ru.arthaix.meshtiles.network;

import com.creativemd.creativecore.common.packet.CreativeCorePacket;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import ru.arthaix.meshtiles.MeshTiles;
import ru.arthaix.meshtiles.server.ImportJob;

/** Server → client: progress of the player's import, also used as the "send more" signal. */
public class PacketImportStatus extends CreativeCorePacket {

    public int sessionId;
    public int state;
    public int received, placed, total, skipped;
    public long tiles;
    public int lastSeq;
    public boolean requestMore;
    public String message = "";

    public PacketImportStatus() {}

    public PacketImportStatus(ImportJob job, String message) {
        this.sessionId = job.id;
        this.state = job.state.ordinal();
        this.received = job.received;
        this.placed = job.placed;
        this.total = job.totalBlocks;
        this.skipped = job.skippedProtected + job.skippedDense + job.skippedHeight;
        this.tiles = job.tilesPlaced;
        this.lastSeq = job.lastSeq;
        this.requestMore = job.wantsMore();
        this.message = message == null ? "" : message;
    }

    public ImportJob.State state() {
        ImportJob.State[] v = ImportJob.State.values();
        return state >= 0 && state < v.length ? v[state] : ImportJob.State.CANCELLED;
    }

    @Override
    public void writeBytes(ByteBuf buf) {
        buf.writeInt(sessionId);
        buf.writeByte(state);
        buf.writeInt(received);
        buf.writeInt(placed);
        buf.writeInt(total);
        buf.writeInt(skipped);
        buf.writeLong(tiles);
        buf.writeInt(lastSeq);
        buf.writeBoolean(requestMore);
        ByteBufUtils.writeUTF8String(buf, message);
    }

    @Override
    public void readBytes(ByteBuf buf) {
        sessionId = buf.readInt();
        state = buf.readByte();
        received = buf.readInt();
        placed = buf.readInt();
        total = buf.readInt();
        skipped = buf.readInt();
        tiles = buf.readLong();
        lastSeq = buf.readInt();
        requestMore = buf.readBoolean();
        message = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void executeClient(EntityPlayer player) {
        MeshTiles.proxy.onImportStatus(this);
    }

    @Override
    public void executeServer(EntityPlayer player) {}
}
