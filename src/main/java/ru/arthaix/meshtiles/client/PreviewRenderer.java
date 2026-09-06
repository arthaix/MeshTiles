package ru.arthaix.meshtiles.client;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import ru.arthaix.meshtiles.voxel.MultiModel;

/**
 * World overlay for the importer: the yellow bounding box of the voxelized model, and (until the model has been placed)
 * a wireframe of the mesh at its target position, coloured per material.
 */
public final class PreviewRenderer {

    /** Wireframes above this many triangles are drawn with a stride so the display list stays reasonable. */
    private static final int MAX_WIRE_TRIANGLES = 600_000;

    private int wireList = -1;
    private int wireVersion = -1;

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        ImportSession session = ImportSession.INSTANCE;
        MultiModel model = session.model();
        Minecraft mc = Minecraft.getMinecraft();
        if (model == null || !session.previewEnabled || mc.player == null) {
            if (model == null) clear();
            return;
        }

        Entity view = mc.getRenderViewEntity() == null ? mc.player : mc.getRenderViewEntity();
        float pt = event.getPartialTicks();
        double px = view.lastTickPosX + (view.posX - view.lastTickPosX) * pt;
        double py = view.lastTickPosY + (view.posY - view.lastTickPosY) * pt;
        double pz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * pt;

        GlStateManager.pushMatrix();
        GlStateManager.translate(-px, -py, -pz);
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.depthMask(false);

        if (!session.placed && session.wireVerts != null) {
            if (wireVersion != session.modelVersion) {
                clear();
                wireList = buildWireList(session);
                wireVersion = session.modelVersion;
            }
            if (wireList >= 0) {
                GlStateManager.pushMatrix();
                GlStateManager.translate(session.wireOriginX, session.wireOriginY, session.wireOriginZ);
                GlStateManager.glLineWidth(1f);
                GlStateManager.callList(wireList);
                GlStateManager.popMatrix();
            }
        }

        GlStateManager.glLineWidth(2f);
        AxisAlignedBB bb = new AxisAlignedBB(model.minBlockX, model.minBlockY, model.minBlockZ, model.maxBlockX + 1, model.maxBlockY + 1, model.maxBlockZ + 1);
        RenderGlobal.drawSelectionBoundingBox(bb, 1f, 0.9f, 0.2f, 0.8f);

        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) clear();
    }

    private int buildWireList(ImportSession session) {
        float[] v = session.wireVerts;
        int[] tris = session.wireTris;
        if (v == null || tris == null) return -1;
        int triCount = tris.length / 4;
        int stride = Math.max(1, (triCount + MAX_WIRE_TRIANGLES - 1) / MAX_WIRE_TRIANGLES);
        int list = GLAllocation.generateDisplayLists(1);
        GlStateManager.glNewList(list, GL11.GL_COMPILE);
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        for (int t = 0; t < triCount; t += stride) {
            int a = tris[4 * t], b = tris[4 * t + 1], c = tris[4 * t + 2];
            int color = materialColor(tris[4 * t + 3]);
            float r = ((color >> 16) & 255) / 255f, g = ((color >> 8) & 255) / 255f, bl = (color & 255) / 255f;
            line(buf, v, a, b, r, g, bl);
            line(buf, v, b, c, r, g, bl);
            line(buf, v, c, a, r, g, bl);
        }
        tess.draw();
        GlStateManager.glEndList();
        return list;
    }

    private static void line(BufferBuilder buf, float[] v, int i, int j, float r, float g, float b) {
        buf.pos(v[3 * i], v[3 * i + 1], v[3 * i + 2]).color(r, g, b, 0.85f).endVertex();
        buf.pos(v[3 * j], v[3 * j + 1], v[3 * j + 2]).color(r, g, b, 0.85f).endVertex();
    }

    /** Stable bright colour per material index. */
    private static int materialColor(int material) {
        int h = (material * 0x9E3779B1) >>> 8;
        int r = 110 + (h & 0x7f), g = 110 + ((h >> 7) & 0x7f), b = 110 + ((h >> 14) & 0x7f);
        return (r << 16) | (g << 8) | b;
    }

    private void clear() {
        if (wireList >= 0) GLAllocation.deleteDisplayLists(wireList);
        wireList = -1;
        wireVersion = -1;
    }
}
