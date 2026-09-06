package ru.arthaix.meshtiles.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.creativemd.littletiles.common.tile.math.vec.LittleVec;
import com.creativemd.littletiles.common.tile.preview.LittlePreview;
import com.creativemd.littletiles.common.util.grid.LittleGridContext;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import ru.arthaix.meshtiles.voxel.BlockKey;
import ru.arthaix.meshtiles.voxel.MaterialSetup;
import ru.arthaix.meshtiles.voxel.MultiModel;
import ru.arthaix.meshtiles.voxel.PackedBox;
import ru.arthaix.meshtiles.voxel.VoxelModel;

/**
 * Builds the NBT of a LittleTiles "advanced recipe" item straight from a {@link MultiModel}:
 * one group per (block, colour) ({tile:{block,color}, boxes:[[I;...]...]}), coordinates in the finest grid of the
 * model relative to its minimum block. Linear time, no LittlePreview objects.
 */
public final class RecipeBuilder {

    private RecipeBuilder() {}

    public static NBTTagCompound build(MultiModel model, List<MaterialSetup> materials) {
        int grid = model.maxGrid;
        int minBx = model.minBlockX, minBy = model.minBlockY, minBz = model.minBlockZ;
        Map<String, NBTTagList> groups = new LinkedHashMap<>();
        Map<String, Integer> colors = new LinkedHashMap<>();
        Map<String, String> blocks = new LinkedHashMap<>();
        NBTTagList pos = new NBTTagList();
        java.util.HashSet<Long> posSeen = new java.util.HashSet<>();
        long count = 0;
        int maxX = 0, maxY = 0, maxZ = 0;

        for (VoxelModel part : model.parts) {
            int scale = grid / part.grid;
            int paletteSize = part.palette.size();
            for (Map.Entry<Long, long[]> e : part.blocks().entrySet()) {
                long key = e.getKey();
                int ox = (BlockKey.x(key) - minBx) * grid;
                int oy = (BlockKey.y(key) - minBy) * grid;
                int oz = (BlockKey.z(key) - minBz) * grid;
                if (posSeen.add(key))
                    pos.appendTag(new NBTTagIntArray(new int[] { BlockKey.x(key) - minBx, BlockKey.y(key) - minBy, BlockKey.z(key) - minBz }));
                for (long box : e.getValue()) {
                    int id = PackedBox.id(box);
                    if (id <= 0 || id >= paletteSize) continue;
                    int m = part.palette.material(id);
                    MaterialSetup setup = m >= 0 && m < materials.size() ? materials.get(m) : null;
                    String block = setup == null ? "littletiles:ltcoloredblock" : setup.blockId;
                    int color = part.palette.color(id);
                    String gk = block + "#" + color;
                    NBTTagList list = groups.get(gk);
                    if (list == null) {
                        groups.put(gk, list = new NBTTagList());
                        colors.put(gk, color);
                        blocks.put(gk, block);
                    }
                    int x1 = ox + PackedBox.maxX(box) * scale, y1 = oy + PackedBox.maxY(box) * scale, z1 = oz + PackedBox.maxZ(box) * scale;
                    list.appendTag(new NBTTagIntArray(new int[] { ox + PackedBox.minX(box) * scale, oy + PackedBox.minY(box) * scale, oz + PackedBox.minZ(box) * scale, x1, y1, z1 }));
                    if (x1 > maxX) maxX = x1;
                    if (y1 > maxY) maxY = y1;
                    if (z1 > maxZ) maxZ = z1;
                    count++;
                }
            }
        }

        NBTTagCompound root = new NBTTagCompound();
        LittleGridContext.get(grid).set(root);
        new LittleVec(maxX, maxY, maxZ).writeToNBT("size", root);
        new LittleVec(0, 0, 0).writeToNBT("min", root);
        if (count >= LittlePreview.lowResolutionMode) root.setTag("pos", pos);

        NBTTagList tiles = new NBTTagList();
        for (Map.Entry<String, NBTTagList> e : groups.entrySet()) {
            NBTTagCompound tile = new NBTTagCompound();
            tile.setString("block", blocks.get(e.getKey()));
            int color = colors.get(e.getKey());
            if (color != -1) tile.setInteger("color", color);
            NBTTagCompound group = new NBTTagCompound();
            group.setTag("tile", tile);
            group.setTag("boxes", e.getValue());
            tiles.appendTag(group);
        }
        root.setTag("tiles", tiles);
        root.setInteger("count", (int) Math.min(Integer.MAX_VALUE, count));
        return root;
    }
}
