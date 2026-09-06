package ru.arthaix.meshtiles;

import ru.arthaix.meshtiles.network.PacketImportStatus;

/** Side-specific hooks; the client proxy overrides these. */
public class CommonProxy {

    public void preInit() {}

    public void init() {}

    public void onImportStatus(PacketImportStatus status) {}
}
