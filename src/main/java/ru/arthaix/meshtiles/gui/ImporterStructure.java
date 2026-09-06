package ru.arthaix.meshtiles.gui;

import javax.annotation.Nullable;

import com.creativemd.littletiles.client.gui.handler.LittleStructureGuiHandler;
import com.creativemd.littletiles.common.action.LittleActionException;
import com.creativemd.littletiles.common.action.block.LittleActionActivated;
import com.creativemd.littletiles.common.structure.registry.LittleStructureType;
import com.creativemd.littletiles.common.structure.type.premade.LittleStructurePremade;
import com.creativemd.littletiles.common.tile.LittleTile;
import com.creativemd.littletiles.common.tile.parent.IStructureTileList;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import ru.arthaix.meshtiles.MeshTiles;
import ru.arthaix.meshtiles.common.ImportSettings;

/**
 * The importer block: a LittleTiles premade structure that stores the import settings and is the origin
 * (pivot) of the placed model. Right-click opens the importer GUI; the settings travel with the block's NBT.
 */
public class ImporterStructure extends LittleStructurePremade {

    private ImportSettings settings = new ImportSettings();

    public ImporterStructure(LittleStructureType type, IStructureTileList mainBlock) {
        super(type, mainBlock);
    }

    public ImportSettings settings() {
        return settings;
    }

    public void setSettings(ImportSettings settings) {
        this.settings = settings;
    }

    @Override
    protected void loadFromNBTExtra(NBTTagCompound nbt) {
        settings = ImportSettings.read(nbt);
    }

    @Override
    protected void writeToNBTExtra(NBTTagCompound nbt) {
        settings.write(nbt);
    }

    @Override
    public boolean onBlockActivated(World world, LittleTile tile, BlockPos pos, EntityPlayer player, EnumHand hand, @Nullable ItemStack stack, EnumFacing side, float hitX, float hitY, float hitZ, LittleActionActivated action) throws LittleActionException {
        if (world.isRemote) return true;
        sendUpdatePacket(); // the client GUI reads the settings from its copy of the structure
        LittleStructureGuiHandler.openGui(MeshTiles.IMPORTER_ID, new NBTTagCompound(), player, this);
        return true;
    }
}
