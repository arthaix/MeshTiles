package ru.arthaix.meshtiles.client;

import com.creativemd.littletiles.common.action.LittleActionException;
import com.creativemd.littletiles.common.structure.LittleStructure;
import com.creativemd.littletiles.common.tile.math.location.StructureLocation;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.MinecraftForge;
import ru.arthaix.meshtiles.CommonProxy;
import ru.arthaix.meshtiles.gui.ImporterScreen;
import ru.arthaix.meshtiles.gui.ImporterStructure;
import ru.arthaix.meshtiles.network.PacketImportStatus;

public class ClientProxy extends CommonProxy {

    @Override
    public void init() {
        MinecraftForge.EVENT_BUS.register(new PreviewRenderer());
    }

    @Override
    public void onImportStatus(PacketImportStatus status) {
        ImportSession.INSTANCE.onStatus(status);
    }

    @Override
    public void openImporter(EntityPlayer player, NBTTagCompound structureLocation) {
        try {
            LittleStructure structure = new StructureLocation(structureLocation).find(player.world);
            if (structure instanceof ImporterStructure) Minecraft.getMinecraft().displayGuiScreen(new ImporterScreen((ImporterStructure) structure));
        } catch (LittleActionException e) {
            ImportSession.chat("The importer block is not loaded yet, try again");
        }
    }
}
