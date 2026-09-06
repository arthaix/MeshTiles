package ru.arthaix.meshtiles.common;

import com.creativemd.littletiles.LittleTiles;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/** "modid:name[:meta]" &lt;-&gt; Block + meta helpers (the same string format LittleTiles uses in tile NBT). */
public final class BlockRef {

    public final Block block;
    public final int meta;

    public BlockRef(Block block, int meta) {
        this.block = block;
        this.meta = meta;
    }

    public static BlockRef parse(String id) {
        if (id == null || id.isEmpty()) return fallback();
        String[] parts = id.split(":");
        if (parts.length < 2) return fallback();
        Block block = Block.REGISTRY.getObject(new ResourceLocation(parts[0], parts[1]));
        if (block == null || block == net.minecraft.init.Blocks.AIR) return fallback();
        int meta = 0;
        if (parts.length >= 3) {
            try {
                meta = Integer.parseInt(parts[2]);
            } catch (NumberFormatException ignored) {}
        }
        return new BlockRef(block, meta);
    }

    public static BlockRef fallback() {
        return new BlockRef(LittleTiles.dyeableBlock, 0);
    }

    public static String toId(ItemStack stack) {
        Block block = Block.getBlockFromItem(stack.getItem());
        if (block == null || block == net.minecraft.init.Blocks.AIR) return "littletiles:ltcoloredblock";
        return toId(block, stack.getMetadata());
    }

    public static String toId(Block block, int meta) {
        ResourceLocation name = block.getRegistryName();
        String s = name == null ? "littletiles:ltcoloredblock" : name.toString();
        return meta != 0 ? s + ":" + meta : s;
    }

    public ItemStack toStack() {
        return new ItemStack(block, 1, meta);
    }

    /** The "block" string as written into LittleTiles tile NBT. */
    public String tileString() {
        return toId(block, meta);
    }
}
