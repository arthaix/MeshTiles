package ru.arthaix.meshtiles.client;

import net.minecraftforge.common.MinecraftForge;
import ru.arthaix.meshtiles.CommonProxy;
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
}
