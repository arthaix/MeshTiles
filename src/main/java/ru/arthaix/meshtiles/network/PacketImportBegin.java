package ru.arthaix.meshtiles.network;

import com.creativemd.creativecore.common.packet.CreativeCorePacket;
import com.creativemd.littletiles.common.util.grid.LittleGridContext;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextFormatting;
import ru.arthaix.meshtiles.common.ImportPalette;
import ru.arthaix.meshtiles.server.ImportJobManager;

/** Client → server: start a streamed import. */
public class PacketImportBegin extends CreativeCorePacket {

    public int sessionId;
    public int grid;
    public int totalBlocks;
    public long totalBoxes;
    public ImportPalette palette;

    public PacketImportBegin() {}

    public PacketImportBegin(int sessionId, int grid, int totalBlocks, long totalBoxes, ImportPalette palette) {
        this.sessionId = sessionId;
        this.grid = grid;
        this.totalBlocks = totalBlocks;
        this.totalBoxes = totalBoxes;
        this.palette = palette;
    }

    @Override
    public void writeBytes(ByteBuf buf) {
        buf.writeInt(sessionId);
        buf.writeInt(grid);
        buf.writeInt(totalBlocks);
        buf.writeLong(totalBoxes);
        palette.write(buf);
    }

    @Override
    public void readBytes(ByteBuf buf) {
        sessionId = buf.readInt();
        grid = buf.readInt();
        totalBlocks = buf.readInt();
        totalBoxes = buf.readLong();
        palette = ImportPalette.read(buf);
    }

    @Override
    public void executeClient(EntityPlayer player) {}

    @Override
    public void executeServer(EntityPlayer player) {
        EntityPlayerMP mp = (EntityPlayerMP) player;
        if (!ImportJobManager.isAllowed(mp)) {
            ImportJobManager.chat(mp, TextFormatting.RED + "You need creative mode or op to import models.");
            return;
        }
        try {
            LittleGridContext.get(grid);
        } catch (RuntimeException e) {
            ImportJobManager.chat(mp, TextFormatting.RED + "Grid " + grid + " is not enabled on this server.");
            return;
        }
        ImportJobManager.INSTANCE.begin(mp, sessionId, grid, totalBlocks, totalBoxes, palette);
    }
}
