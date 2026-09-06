package ru.arthaix.meshtiles;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import ru.arthaix.meshtiles.network.PacketImportStatus;

/** Side-specific hooks; the client proxy overrides these. */
public class CommonProxy {

    public void preInit() {}

    public void init() {}

    public void onImportStatus(PacketImportStatus status) {}

    /** Opens the importer screen of the structure at the given location (client only). */
    public void openImporter(EntityPlayer player, NBTTagCompound structureLocation) {}
}
