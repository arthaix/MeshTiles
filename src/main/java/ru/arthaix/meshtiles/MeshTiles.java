package ru.arthaix.meshtiles;

import org.apache.logging.log4j.Logger;

import com.creativemd.creativecore.common.gui.opener.GuiHandler;
import com.creativemd.creativecore.common.packet.CreativeCorePacket;
import com.creativemd.littletiles.common.structure.type.premade.LittleStructurePremade;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import ru.arthaix.meshtiles.gui.ImporterContainer;
import ru.arthaix.meshtiles.gui.ImporterGuiOpener;
import ru.arthaix.meshtiles.gui.ImporterStructure;
import ru.arthaix.meshtiles.network.PacketImportBegin;
import ru.arthaix.meshtiles.network.PacketImportChunk;
import ru.arthaix.meshtiles.network.PacketImportControl;
import ru.arthaix.meshtiles.network.PacketImportEnd;
import ru.arthaix.meshtiles.network.PacketImportStatus;
import ru.arthaix.meshtiles.network.PacketImporterRecipe;
import ru.arthaix.meshtiles.network.PacketImporterSettings;
import ru.arthaix.meshtiles.server.CommandMeshTiles;
import ru.arthaix.meshtiles.server.HistoryIO;
import ru.arthaix.meshtiles.server.ImportHistory;
import ru.arthaix.meshtiles.server.ImportJobManager;

/**
 * MeshTiles: imports 3D models (.obj) into Minecraft as LittleTiles tiles.
 * Voxelization happens on the client, placement is streamed to the server and written block by block.
 */
@Mod(modid = MeshTiles.MOD_ID, name = MeshTiles.NAME, version = MeshTiles.VERSION, dependencies = "required-after:creativecore;required-after:littletiles", acceptableRemoteVersions = "*")
public class MeshTiles {

    public static final String MOD_ID = "meshtiles";
    public static final String NAME = "MeshTiles";
    public static final String VERSION = "1.0.0";
    /** Premade structure id of the importer block (assets/meshtiles/premade/meshtiles_importer.struct). */
    public static final String IMPORTER_ID = "meshtiles_importer";
    public static Logger logger;

    @SidedProxy(clientSide = "ru.arthaix.meshtiles.client.ClientProxy", serverSide = "ru.arthaix.meshtiles.CommonProxy")
    public static CommonProxy proxy;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
        MeshTilesConfig.load(event.getSuggestedConfigurationFile());
        GuiHandler.registerGuiHandler(IMPORTER_ID, new ImporterGuiOpener());
        proxy.preInit();
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        LittleStructurePremade.registerPremadeStructureType(IMPORTER_ID, MOD_ID, ImporterStructure.class);
        // packet ids are registration indices: this order must be identical on client and server
        CreativeCorePacket.registerPacket(PacketImporterSettings.class);
        CreativeCorePacket.registerPacket(PacketImportBegin.class);
        CreativeCorePacket.registerPacket(PacketImportChunk.class);
        CreativeCorePacket.registerPacket(PacketImportEnd.class);
        CreativeCorePacket.registerPacket(PacketImportControl.class);
        CreativeCorePacket.registerPacket(PacketImportStatus.class);
        CreativeCorePacket.registerPacket(PacketImporterRecipe.class);
        MinecraftForge.EVENT_BUS.register(ImportJobManager.INSTANCE);
        MinecraftForge.EVENT_BUS.register(this);
        proxy.init();
    }

    @EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandMeshTiles());
    }

    @EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        // load the import history now (and move undo data out of old saves) instead of in the middle of play
        WorldServer overworld = DimensionManager.getWorld(0);
        if (overworld != null) ImportHistory.get(overworld);
    }

    @EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        HistoryIO.flush(120_000);
    }

    @EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        ImportJobManager.INSTANCE.onServerStopping();
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            ImportJobManager.INSTANCE.onPlayerLoggedOut((EntityPlayerMP) event.player);
            PacketImporterRecipe.forget(event.player.getUniqueID());
        }
    }
}
