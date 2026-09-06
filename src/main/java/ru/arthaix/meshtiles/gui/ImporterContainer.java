package ru.arthaix.meshtiles.gui;

import com.creativemd.creativecore.common.gui.container.SubContainer;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;

/** Server-side half of the importer GUI. It has no slots: recipe items are given to the player directly. */
public class ImporterContainer extends SubContainer {

    public final ImporterStructure structure;

    public ImporterContainer(EntityPlayer player, ImporterStructure structure) {
        super(player);
        this.structure = structure;
    }

    @Override
    public void createControls() {}

    @Override
    public void onPacketReceive(NBTTagCompound nbt) {}
}
