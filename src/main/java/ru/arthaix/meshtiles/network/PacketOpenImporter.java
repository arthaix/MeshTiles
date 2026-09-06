package ru.arthaix.meshtiles.network;

import com.creativemd.creativecore.common.packet.CreativeCorePacket;
import com.creativemd.littletiles.common.tile.math.location.StructureLocation;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import ru.arthaix.meshtiles.MeshTiles;
import ru.arthaix.meshtiles.gui.ImporterStructure;

/** Server → client: open the importer screen of the structure at this location. */
public class PacketOpenImporter extends CreativeCorePacket {

    private NBTTagCompound loc;

    public PacketOpenImporter() {}

    public PacketOpenImporter(ImporterStructure structure) {
        this.loc = new StructureLocation(structure).write();
    }

    @Override
    public void writeBytes(ByteBuf buf) {
        writeNBT(buf, loc);
    }

    @Override
    public void readBytes(ByteBuf buf) {
        loc = readNBT(buf);
    }

    @Override
    public void executeClient(EntityPlayer player) {
        MeshTiles.proxy.openImporter(player, loc);
    }

    @Override
    public void executeServer(EntityPlayer player) {}
}
