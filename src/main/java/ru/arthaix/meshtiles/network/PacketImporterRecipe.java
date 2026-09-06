package ru.arthaix.meshtiles.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.creativemd.creativecore.common.packet.CreativeCorePacket;
import com.creativemd.creativecore.common.packet.PacketHandler;
import com.creativemd.littletiles.LittleTiles;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.text.TextFormatting;
import ru.arthaix.meshtiles.server.ImportJobManager;

/**
 * Client → server: hand the player a LittleTiles recipe item built from the voxelized model.
 * The recipe NBT is sent in parts that each stay under the packet size limit and are reassembled on the server.
 */
public class PacketImporterRecipe extends CreativeCorePacket {

    /** Payload bytes per part, safely below CreativeCore's split threshold (31767) with headers. */
    public static final int PART_BYTES = 30_000;
    /** Absolute cap for a reassembled recipe (recipeMaxBoxes tiles never come near this). */
    private static final int MAX_TOTAL_BYTES = 64 << 20;

    private static final class Pending {
        final int id;
        final byte[][] parts;
        int received;

        Pending(int id, int total) {
            this.id = id;
            this.parts = new byte[total][];
        }
    }

    private static final Map<UUID, Pending> pending = new HashMap<>();

    private int id, part, total;
    private byte[] data;

    public PacketImporterRecipe() {}

    private PacketImporterRecipe(int id, int part, int total, byte[] data) {
        this.id = id;
        this.part = part;
        this.total = total;
        this.data = data;
    }

    /** Serialises the recipe and sends it as as many packets as needed. */
    public static void send(NBTTagCompound recipe) {
        byte[] all;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CompressedStreamTools.writeCompressed(recipe, out);
            all = out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("could not serialise recipe: " + e.getMessage(), e);
        }
        int total = Math.max(1, (all.length + PART_BYTES - 1) / PART_BYTES);
        int id = (int) (System.nanoTime() & 0x7fffffff);
        List<PacketImporterRecipe> packets = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            int from = i * PART_BYTES, to = Math.min(all.length, from + PART_BYTES);
            byte[] slice = new byte[to - from];
            System.arraycopy(all, from, slice, 0, slice.length);
            packets.add(new PacketImporterRecipe(id, i, total, slice));
        }
        for (PacketImporterRecipe p : packets) PacketHandler.sendPacketToServer(p);
    }

    @Override
    public void writeBytes(ByteBuf buf) {
        buf.writeInt(id);
        buf.writeInt(part);
        buf.writeInt(total);
        buf.writeInt(data.length);
        buf.writeBytes(data);
    }

    @Override
    public void readBytes(ByteBuf buf) {
        id = buf.readInt();
        part = buf.readInt();
        total = buf.readInt();
        int len = buf.readInt();
        if (len < 0 || len > PART_BYTES || total < 1 || part < 0 || part >= total || (long) total * PART_BYTES > MAX_TOTAL_BYTES)
            throw new IllegalArgumentException("bad recipe part " + part + "/" + total + " (" + len + " bytes)");
        data = new byte[len];
        buf.readBytes(data);
    }

    @Override
    public void executeClient(EntityPlayer player) {}

    @Override
    public void executeServer(EntityPlayer player) {
        EntityPlayerMP mp = (EntityPlayerMP) player;
        if (!ImportJobManager.isAllowed(mp)) {
            ImportJobManager.chat(mp, TextFormatting.RED + "You need creative mode or op to import models.");
            return;
        }
        Pending p = pending.get(mp.getUniqueID());
        if (p == null || p.id != id) {
            p = new Pending(id, total);
            pending.put(mp.getUniqueID(), p);
        }
        if (p.parts.length != total) return;
        if (p.parts[part] == null) p.received++;
        p.parts[part] = data;
        if (p.received < total) return;
        pending.remove(mp.getUniqueID());

        NBTTagCompound recipe;
        try {
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            for (byte[] piece : p.parts) all.write(piece);
            recipe = CompressedStreamTools.readCompressed(new ByteArrayInputStream(all.toByteArray()));
        } catch (IOException | RuntimeException e) {
            ImportJobManager.chat(mp, TextFormatting.RED + "Recipe data is corrupt: " + e.getMessage());
            return;
        }
        if (recipe.getInteger("count") <= 0) return;
        ItemStack stack = new ItemStack(LittleTiles.recipeAdvanced);
        stack.setTagCompound(recipe);
        if (!mp.inventory.addItemStackToInventory(stack))
            mp.dropItem(stack, false);
        mp.inventoryContainer.detectAndSendChanges();
        ImportJobManager.chat(mp, TextFormatting.GREEN + "Recipe item created (" + recipe.getInteger("count") + " tiles).");
    }

    /** Forgets a half-received recipe of a player who left. */
    public static void forget(UUID player) {
        pending.remove(player);
    }
}
