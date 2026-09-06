package ru.arthaix.meshtiles.gui;

import com.creativemd.creativecore.common.gui.container.SubContainer;
import com.creativemd.creativecore.common.gui.container.SubGui;
import com.creativemd.littletiles.client.gui.handler.LittleStructureGuiHandler;
import com.creativemd.littletiles.common.structure.LittleStructure;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** Opens the importer screen: the server side gets a container, the client side the GUI on top of it. */
public final class ImporterGuiOpener extends LittleStructureGuiHandler {

    @Override
    public SubContainer getContainer(EntityPlayer player, NBTTagCompound nbt, LittleStructure structure) {
        return new ImporterContainer(player, (ImporterStructure) structure);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public SubGui getGui(EntityPlayer player, NBTTagCompound nbt, LittleStructure structure) {
        return new ImporterGui((ImporterStructure) structure);
    }
}
