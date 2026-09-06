package ru.arthaix.meshtiles.network;

import com.creativemd.creativecore.common.packet.CreativeCorePacket;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextFormatting;
import ru.arthaix.meshtiles.server.ImportJobManager;

/** Client → server: cancel the running import, undo or redo the last one, or teleport to the model. */
public class PacketImportControl extends CreativeCorePacket {

    public static final int CANCEL = 0;
    public static final int UNDO = 1;
    public static final int TELEPORT = 2;
    public static final int REDO = 3;

    public int action;
    public double x, y, z;

    public PacketImportControl() {}

    public PacketImportControl(int action) {
        this.action = action;
    }

    public PacketImportControl(double x, double y, double z) {
        this.action = TELEPORT;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public void writeBytes(ByteBuf buf) {
        buf.writeByte(action);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
    }

    @Override
    public void readBytes(ByteBuf buf) {
        action = buf.readByte();
        x = buf.readDouble();
        y = buf.readDouble();
        z = buf.readDouble();
    }

    @Override
    public void executeClient(EntityPlayer player) {}

    @Override
    public void executeServer(EntityPlayer player) {
        EntityPlayerMP mp = (EntityPlayerMP) player;
        if (!ImportJobManager.isAllowed(mp)) {
            ImportJobManager.chat(mp, TextFormatting.RED + "You need creative mode or op to do that.");
            return;
        }
        if (action == CANCEL) ImportJobManager.INSTANCE.cancel(mp.getUniqueID(), mp);
        else if (action == UNDO) ImportJobManager.INSTANCE.undo(mp, 0);
        else if (action == REDO) ImportJobManager.INSTANCE.redo(mp, 0);
        else if (action == TELEPORT) {
            if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000 || y < 0 || y > 300) return;
            mp.dismountRidingEntity();
            mp.connection.setPlayerLocation(x, y, z, mp.rotationYaw, mp.rotationPitch);
        }
    }
}
