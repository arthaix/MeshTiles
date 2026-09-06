package ru.arthaix.meshtiles.client;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.creativemd.creativecore.common.gui.controls.gui.custom.GuiStackSelectorAll;
import com.creativemd.creativecore.common.utils.type.HashMapList;
import com.creativemd.littletiles.client.gui.LittleSubGuiUtils;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import ru.arthaix.meshtiles.MeshTiles;
import ru.arthaix.meshtiles.common.BlockRef;

/**
 * Writes every block the importer can use (the same list as its block selector) to .minecraft/meshtiles/blocks.json,
 * with display name, creative tab and average texture colour, for the MeshTiles Blender add-on.
 */
@SideOnly(Side.CLIENT)
public final class BlockListExporter {

    private static boolean doneThisSession;

    private BlockListExporter() {}

    public static File file() {
        return new File(Minecraft.getMinecraft().gameDir, "meshtiles/blocks.json");
    }

    /** Once per game session. Returns the file when it was written or changed, null otherwise. */
    public static File exportOnce(EntityPlayer player) {
        if (doneThisSession) return null;
        doneThisSession = true;
        try {
            HashMapList<String, ItemStack> stacks = new GuiStackSelectorAll.CreativeCollector(new LittleSubGuiUtils.LittleBlockSelector()).collect(player);
            JsonArray blocks = new JsonArray();
            Set<String> seen = new HashSet<>();
            for (Map.Entry<String, ArrayList<ItemStack>> e : stacks.entrySet()) {
                String group = I18n.format(e.getKey());
                for (ItemStack stack : e.getValue()) {
                    if (stack == null || stack.isEmpty()) continue;
                    String id = BlockRef.toId(stack);
                    if (!seen.add(id)) continue;
                    JsonObject o = new JsonObject();
                    o.addProperty("id", id);
                    o.addProperty("name", stack.getDisplayName());
                    o.addProperty("group", group);
                    int color = averageColor(stack);
                    if (color != -1) o.addProperty("color", String.format("#%06x", color & 0xffffff));
                    blocks.add(o);
                }
            }
            JsonObject root = new JsonObject();
            root.addProperty("format", 1);
            root.addProperty("minecraft", "1.12.2");
            root.add("blocks", blocks);
            String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n";
            File f = file();
            if (f.isFile() && new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).equals(json)) return null;
            File dir = f.getParentFile();
            if (dir != null) dir.mkdirs();
            Files.write(f.toPath(), json.getBytes(StandardCharsets.UTF_8));
            MeshTiles.logger.info("Block list for the Blender add-on written: " + blocks.size() + " blocks, " + f);
            return f;
        } catch (Throwable t) {
            MeshTiles.logger.warn("Could not write the block list for the Blender add-on: " + t);
            return null;
        }
    }

    /** Average colour of the block's main texture (opaque pixels), or -1. */
    @SuppressWarnings("deprecation")
    private static int averageColor(ItemStack stack) {
        try {
            Block block = Block.getBlockFromItem(stack.getItem());
            if (block == null) return -1;
            IBlockState state = block.getStateFromMeta(stack.getMetadata());
            TextureAtlasSprite sprite = Minecraft.getMinecraft().getBlockRendererDispatcher().getBlockModelShapes().getTexture(state);
            if (sprite == null || sprite.getFrameCount() == 0) return -1;
            int[] pixels = sprite.getFrameTextureData(0)[0];
            long r = 0, g = 0, b = 0, n = 0;
            for (int p : pixels) {
                if (((p >>> 24) & 255) < 16) continue;
                r += (p >> 16) & 255;
                g += (p >> 8) & 255;
                b += p & 255;
                n++;
            }
            if (n == 0) return -1;
            return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
        } catch (Throwable t) {
            return -1;
        }
    }
}
