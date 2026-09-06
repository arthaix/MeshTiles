package ru.arthaix.meshtiles.network;

import com.creativemd.creativecore.common.packet.CreativeCorePacket;
import com.creativemd.littletiles.common.action.LittleActionException;
import com.creativemd.littletiles.common.structure.LittleStructure;
import com.creativemd.littletiles.common.tile.math.location.StructureLocation;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import ru.arthaix.meshtiles.MeshTiles;
import ru.arthaix.meshtiles.common.ImportSettings;
import ru.arthaix.meshtiles.gui.ImporterStructure;

/** Client → server: store the GUI settings in the importer structure (persisted with the world). */
public class PacketImporterSettings extends CreativeCorePacket {

    private NBTTagCompound loc;
    private NBTTagCompound settings;

    public PacketImporterSettings() {}

    public PacketImporterSettings(ImporterStructure structure, ImportSettings settings) {
        this.loc = new StructureLocation(structure).write();
        this.settings = settings.write(new NBTTagCompound());
    }

    @Override
    public void writeBytes(ByteBuf buf) {
        writeNBT(buf, loc);
        writeNBT(buf, settings);
    }

    @Override
    public void readBytes(ByteBuf buf) {
        loc = readNBT(buf);
        settings = readNBT(buf);
    }

    @Override
    public void executeClient(EntityPlayer player) {}

    @Override
    public void executeServer(EntityPlayer player) {
        try {
            LittleStructure structure = new StructureLocation(loc).find(player.world);
            if (!(structure instanceof ImporterStructure)) return;
            ImporterStructure importer = (ImporterStructure) structure;
            importer.setSettings(ImportSettings.read(settings));
            importer.updateStructure();
        } catch (LittleActionException e) {
            MeshTiles.logger.warn("Importer settings packet: structure not found (" + e.getMessage() + ")");
        }
    }
}
