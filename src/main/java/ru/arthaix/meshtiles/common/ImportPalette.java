package ru.arthaix.meshtiles.common;

import java.util.List;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import ru.arthaix.meshtiles.voxel.MaterialSetup;
import ru.arthaix.meshtiles.voxel.VoxelPalette;

/**
 * Network form of a {@link VoxelPalette}: for every tile id the block id string and the ARGB colour (-1 = untinted).
 * Built on the client from the voxel palette + material setups, resolved on the server to Block/meta.
 */
public final class ImportPalette {

    public final String[] blockIds;
    public final int[] colors;
    /** Per id: place the real Minecraft block instead of a tile (materials imported at grid 1 with the option on). */
    public final boolean[] solid;

    public ImportPalette(String[] blockIds, int[] colors, boolean[] solid) {
        this.blockIds = blockIds;
        this.colors = colors;
        this.solid = solid;
    }

    public int size() {
        return blockIds.length;
    }

    public static ImportPalette fromVoxelPalette(VoxelPalette palette, List<MaterialSetup> materials) {
        int n = palette.size();
        String[] ids = new String[n];
        int[] cols = new int[n];
        boolean[] solid = new boolean[n];
        ids[0] = "";
        cols[0] = -1;
        for (int i = 1; i < n; i++) {
            int m = palette.material(i);
            MaterialSetup setup = m >= 0 && m < materials.size() ? materials.get(m) : null;
            ids[i] = setup == null ? "littletiles:ltcoloredblock" : setup.blockId;
            cols[i] = palette.color(i);
            solid[i] = setup != null && setup.placesSolidBlocks();
        }
        return new ImportPalette(ids, cols, solid);
    }

    public void write(ByteBuf buf) {
        buf.writeInt(blockIds.length);
        for (int i = 0; i < blockIds.length; i++) {
            ByteBufUtils.writeUTF8String(buf, blockIds[i]);
            buf.writeInt(colors[i]);
            buf.writeBoolean(solid[i]);
        }
    }

    public static ImportPalette read(ByteBuf buf) {
        int n = buf.readInt();
        if (n < 1 || n > VoxelPalette.MAX_IDS + 1) throw new IllegalArgumentException("bad palette size " + n);
        String[] ids = new String[n];
        int[] cols = new int[n];
        boolean[] solid = new boolean[n];
        for (int i = 0; i < n; i++) {
            ids[i] = ByteBufUtils.readUTF8String(buf);
            cols[i] = buf.readInt();
            solid[i] = buf.readBoolean();
        }
        return new ImportPalette(ids, cols, solid);
    }
}
