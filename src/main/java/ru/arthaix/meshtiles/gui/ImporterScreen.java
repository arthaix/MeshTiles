package ru.arthaix.meshtiles.gui;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.creativemd.creativecore.common.packet.PacketHandler;
import com.creativemd.littletiles.common.util.grid.LittleGridContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.client.config.GuiSlider;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import ru.arthaix.meshtiles.MeshTilesConfig;
import ru.arthaix.meshtiles.client.BlenderSettings;
import ru.arthaix.meshtiles.client.BlockListExporter;
import ru.arthaix.meshtiles.client.ClientPrefs;
import ru.arthaix.meshtiles.client.FileChooserHelper;
import ru.arthaix.meshtiles.client.ImportSession;
import ru.arthaix.meshtiles.client.RecipeBuilder;
import ru.arthaix.meshtiles.common.BlockRef;
import ru.arthaix.meshtiles.common.ImportSettings;
import ru.arthaix.meshtiles.common.ImportSpeed;
import ru.arthaix.meshtiles.model.MtlLibrary;
import ru.arthaix.meshtiles.model.ObjMesh;
import ru.arthaix.meshtiles.model.ObjStreamParser;
import ru.arthaix.meshtiles.network.PacketImportControl;
import ru.arthaix.meshtiles.network.PacketImportStatus;
import ru.arthaix.meshtiles.network.PacketImporterRecipe;
import ru.arthaix.meshtiles.network.PacketImporterSettings;
import ru.arthaix.meshtiles.server.ImportJob;
import ru.arthaix.meshtiles.voxel.MaterialSetup;
import ru.arthaix.meshtiles.voxel.MultiModel;

/** The importer's screen: model file, placement, one row per material, voxelize / place / undo. */
@SideOnly(Side.CLIENT)
public class ImporterScreen extends GuiScreen implements GuiSlider.ISlider {

    private static final int W = 440, H = 262;
    private static final int LIST_Y = 92, LIST_H = 96, ROW_H = 20;
    private static final int ACCENT = 0xFF00B8CC;
    private static final String[] MODE_NAMES = { "None", "Kd", "Texture" };
    private static final String[] ROT_NAMES = { "0°", "90°", "180°", "270°" };

    /** Columns of a material row, relative to the list's left edge. */
    private static final int C_BLOCK = 96, C_BLOCK_W = 140, C_GRID = 240, C_GRID_W = 26, C_MODE = 270, C_MODE_W = 48,
        C_SWATCH = 322, C_SWATCH_W = 16, C_MC = 342, C_MC_W = 24, C_SKIP = 370, C_SKIP_W = 30, C_UP = 404, C_DOWN = 416, C_ARROW_W = 11;

    private static final int B_BROWSE = 1, B_SCAN = 2, B_AXIS = 3, B_ROT = 4, B_MX = 5, B_MY = 6, B_MZ = 7, B_SXZ = 8, B_SY = 9, B_ORIGIN = 10,
        B_DEFBLOCK = 11, B_SPEED = 12, B_VOXELIZE = 20, B_PREVIEW = 21, B_PLACE = 22, B_RECIPE = 23, B_CANCEL = 24, B_UNDO = 25, B_REDO = 26,
        B_TP = 27, B_DONE = 28;

    private final ImporterStructure structure;
    private final ImportSettings settings;
    /** Material names shown in the list, in placement order. */
    private List<String> shown = new ArrayList<>();
    private int left, top, scroll;
    private String note = "";
    private boolean opened, scanning, lastScanHadDefault;
    /** Settings file written by the Blender add-on, as last applied (model path and file stamp). */
    private String blenderModel = "";
    private long blenderStamp;
    /** Last speed step sent to the server, so moving the slider is sent once per step. */
    private int sentSpeed;

    private GuiTextField path, scale, offX, offY, offZ;
    private GuiButton axis, rot, mirX, mirY, mirZ, snapXZ, snapY, origin, defBlock, voxelize, preview, place;
    private GuiSlider speed;

    public ImporterScreen(ImporterStructure structure) {
        this.structure = structure;
        this.settings = structure.settings().copy();
        ImportSession.INSTANCE.previewEnabled = settings.previewEnabled;
        ImportSession.INSTANCE.speed = settings.speed;
        sentSpeed = settings.speed;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        left = (width - W) / 2;
        top = (height - H) / 2;
        buttonList.clear();

        path = field(1, left + 44, top + 8, 300, 32767, settings.modelPath);
        add(new GuiButton(B_BROWSE, left + 350, top + 6, 22, 16, "..."));
        add(new GuiButton(B_SCAN, left + 376, top + 6, 58, 16, "Scan"));

        scale = field(2, left + 44, top + 32, 40, 16, trimNumber(settings.scale));
        axis = add(new GuiButton(B_AXIS, left + 90, top + 30, 46, 16, ""));
        rot = add(new GuiButton(B_ROT, left + 140, top + 30, 36, 16, ""));
        mirX = add(new GuiButton(B_MX, left + 218, top + 30, 18, 16, ""));
        mirY = add(new GuiButton(B_MY, left + 238, top + 30, 18, 16, ""));
        mirZ = add(new GuiButton(B_MZ, left + 258, top + 30, 18, 16, ""));
        snapXZ = add(new GuiButton(B_SXZ, left + 312, top + 30, 26, 16, ""));
        snapY = add(new GuiButton(B_SY, left + 340, top + 30, 18, 16, ""));
        origin = add(new GuiButton(B_ORIGIN, left + 362, top + 30, 72, 16, ""));

        offX = field(3, left + 44, top + 56, 40, 8, String.valueOf(settings.offsetX));
        offY = field(4, left + 90, top + 56, 40, 8, String.valueOf(settings.offsetY));
        offZ = field(5, left + 136, top + 56, 40, 8, String.valueOf(settings.offsetZ));
        defBlock = add(new GuiButton(B_DEFBLOCK, left + 222, top + 54, 212, 16, ""));

        int py = top + LIST_Y + LIST_H + 6;
        speed = new GuiSlider(B_SPEED, left + W - 146, py + 7, 140, 20, "", "", 0, ImportSpeed.MAX_INDEX, settings.speed, false, false, this);
        add(speed);
        speedLabel();

        int by = top + H - 24;
        voxelize = add(new GuiButton(B_VOXELIZE, left + 6, by, 56, 20, "Voxelize"));
        preview = add(new GuiButton(B_PREVIEW, left + 66, by, 66, 20, ""));
        place = add(new GuiButton(B_PLACE, left + 136, by, 44, 20, "Place"));
        add(new GuiButton(B_RECIPE, left + 184, by, 46, 20, "Recipe"));
        add(new GuiButton(B_CANCEL, left + 234, by, 46, 20, "Cancel"));
        add(new GuiButton(B_UNDO, left + 284, by, 38, 20, "Undo"));
        add(new GuiButton(B_REDO, left + 326, by, 38, 20, "Redo"));
        add(new GuiButton(B_TP, left + 368, by, 24, 20, "TP"));
        add(new GuiButton(B_DONE, left + 396, by, 38, 20, "Done"));
        refreshLabels();

        if (!opened) {
            opened = true;
            File blockList = BlockListExporter.exportOnce(mc.player);
            if (blockList != null) ImportSession.chat("Block list for the Blender add-on saved: " + blockList.getAbsolutePath());
            ImportSession session = ImportSession.INSTANCE;
            ObjMesh mesh = session.mesh();
            if (mesh != null && !settings.modelPath.isEmpty()) {
                refreshRows(mesh.materials, session.mtl);
            } else if (!settings.modelPath.isEmpty() && ImportSession.resolve(settings.modelPath).isFile()) {
                scan(null);
            } else {
                List<String> names = new ArrayList<>();
                for (MaterialSetup m : settings.materials) names.add(m.name);
                refreshRows(names, null);
            }
        }
    }

    private <T extends GuiButton> T add(T b) {
        buttonList.add(b);
        return b;
    }

    private GuiTextField field(int id, int x, int y, int w, int maxLength, String text) {
        GuiTextField f = new GuiTextField(id, fontRenderer, x, y, w, 12);
        f.setMaxStringLength(maxLength);
        f.setText(text == null ? "" : text);
        return f;
    }

    private List<GuiTextField> fields() {
        return Arrays.asList(path, scale, offX, offY, offZ);
    }

    private static String trimNumber(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String on(boolean b) {
        return (b ? TextFormatting.GREEN : TextFormatting.DARK_GRAY).toString();
    }

    private void refreshLabels() {
        axis.displayString = settings.zUp ? "File Z" : "File Y";
        rot.displayString = ROT_NAMES[settings.rotation & 3];
        mirX.displayString = on(settings.mirrorX) + "X";
        mirY.displayString = on(settings.mirrorY) + "Y";
        mirZ.displayString = on(settings.mirrorZ) + "Z";
        snapXZ.displayString = on(settings.snapXZ && !settings.modelOrigin) + "XZ";
        snapY.displayString = on(settings.snapY && !settings.modelOrigin) + "Y";
        origin.displayString = on(settings.modelOrigin) + "Origin";
        preview.displayString = ImportSession.INSTANCE.previewEnabled ? "Preview: on" : "Preview: off";
        boolean busy = ImportSession.INSTANCE.isBusy();
        voxelize.displayString = busy ? "Working" : "Voxelize";
        voxelize.enabled = !busy && !scanning;
        place.enabled = !busy && !scanning;
    }

    private void speedLabel() {
        speed.displayString = "Speed: " + ImportSpeed.label(speed.getValueInt());
    }

    @Override
    public void onChangeSliderValue(GuiSlider slider) {
        if (slider != speed) return;
        speedLabel();
        int step = ImportSpeed.clamp(slider.getValueInt());
        if (step != sentSpeed) {
            sentSpeed = step;
            settings.speed = step;
            ImportSession.INSTANCE.speed = step;
            // a running import on the server picks the new pace up at once; without one this is ignored
            PacketHandler.sendPacketToServer(new PacketImportControl(PacketImportControl.SPEED, step));
        }
    }

    // ---- settings -------------------------------------------------------------------------------------

    /** Reads the text fields back into the settings (buttons and rows change the settings directly). */
    private void collect() {
        if (path == null) return;
        settings.modelPath = path.getText().trim();
        try {
            double sc = Double.parseDouble(scale.getText().trim().replace(',', '.'));
            if (sc > 0 && !Double.isInfinite(sc)) settings.scale = sc;
        } catch (NumberFormatException ignored) {
            // keep the previous scale
        }
        settings.offsetX = parseInt(offX.getText());
        settings.offsetY = parseInt(offY.getText());
        settings.offsetZ = parseInt(offZ.getText());
        settings.speed = ImportSpeed.clamp(speed.getValueInt());
        settings.previewEnabled = ImportSession.INSTANCE.previewEnabled;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void sendSettings() {
        ClientPrefs.rememberPresets(settings.materials);
        ClientPrefs.addRecent(settings.modelPath);
        structure.setSettings(settings.copy());
        PacketHandler.sendPacketToServer(new PacketImporterSettings(structure, settings));
    }

    private MaterialSetup setup(String name) {
        MaterialSetup m = settings.material(name);
        if (m == null) {
            m = settings.materialOrCreate(name);
            ClientPrefs.applyPreset(m);
        }
        return m;
    }

    // ---- material list data -----------------------------------------------------------------------------

    /** Shows the given materials: every one gets a setup (.mtl colour, remembered preset), then placement order. */
    private void refreshRows(List<String> names, MtlLibrary mtl) {
        List<String> list = new ArrayList<>(names);
        // the implicit "default" material only exists when faces precede the first usemtl
        if (!usesDefault()) {
            list.remove(ObjStreamParser.ObjMesh_DEFAULT);
            settings.materials.removeIf(m -> m.name.equals(ObjStreamParser.ObjMesh_DEFAULT));
        }
        for (String name : list) {
            if (settings.material(name) != null) continue;
            MaterialSetup created = settings.materialOrCreate(name);
            MtlLibrary.Material mm = mtl == null ? null : mtl.materials.get(name);
            if (mm != null) {
                // a texture wins; without one the block keeps its own colours and Kd stays a manual choice
                created.mode = mm.mapKd != null ? MaterialSetup.ColorMode.TEXTURE : MaterialSetup.ColorMode.NONE;
                created.color = mm.kdArgb();
            }
            ClientPrefs.applyPreset(created); // remembered choice for this material name (from earlier imports)
        }
        shown = list;
        shown = orderedShownNames();
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    /** Shown material names in the order of the priority list. */
    private List<String> orderedShownNames() {
        List<String> names = new ArrayList<>();
        for (MaterialSetup m : settings.materials)
            if (shown.contains(m.name) && !names.contains(m.name)) names.add(m.name);
        for (String n : shown)
            if (!names.contains(n)) names.add(n);
        return names;
    }

    /** Moves a material one step up (-1) or down (+1) in the placement order. */
    private void moveMaterial(MaterialSetup setup, int dir) {
        int i = settings.materials.indexOf(setup);
        if (i < 0) return;
        // step over materials that are not shown (they keep their relative place)
        int j = i + dir;
        while (j >= 0 && j < settings.materials.size() && !shown.contains(settings.materials.get(j).name)) j += dir;
        if (j < 0 || j >= settings.materials.size()) return;
        settings.materials.remove(i);
        settings.materials.add(j, setup);
        shown = orderedShownNames();
    }

    private boolean meshMaterialsShown(ObjMesh mesh) {
        List<String> expected = new ArrayList<>(mesh.materials);
        if (!usesDefault()) expected.remove(ObjStreamParser.ObjMesh_DEFAULT);
        return new HashSet<>(expected).equals(new HashSet<>(shown));
    }

    /** True when the model has faces before its first usemtl (the implicit "default" material). */
    private boolean usesDefault() {
        ObjMesh mesh = ImportSession.INSTANCE.mesh();
        if (mesh == null) return lastScanHadDefault;
        for (int i = 0; i < mesh.triangleCount; i++)
            if (mesh.triMaterial[i] == 0) return true;
        return false;
    }

    /** True when the Blender add-on wrote new settings for the model since they were last applied. */
    private boolean blenderSettingsChanged() {
        File model = ImportSession.resolve(settings.modelPath);
        long stamp = BlenderSettings.stamp(model);
        return stamp != 0 && (stamp != blenderStamp || !model.getAbsolutePath().equals(blenderModel));
    }

    /** Available detail levels (tiles per block), limited to the grids LittleTiles has enabled. */
    private static int[] gridSizes() {
        List<Integer> list = new ArrayList<>();
        for (String n : LittleGridContext.getNames()) {
            try {
                int g = Integer.parseInt(n);
                if (g >= 1 && g <= 64 && Integer.bitCount(g) == 1 && !list.contains(g)) list.add(g);
            } catch (NumberFormatException ignored) {
                // not a plain grid size
            }
        }
        if (list.isEmpty()) list.add(LittleGridContext.get().size);
        Collections.sort(list);
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    // ---- actions ----------------------------------------------------------------------------------------

    /** Reads the model's material names in the background (the file can be hundreds of MB), then shows them. */
    private void scan(Runnable after) {
        collect();
        final String p = settings.modelPath;
        if (p.isEmpty()) {
            note = TextFormatting.RED + "Choose a .obj file first";
            return;
        }
        if (scanning) return;
        scanning = true;
        note = TextFormatting.GRAY + "Reading materials...";
        Thread t = new Thread(() -> {
            ObjStreamParser.MaterialScan result = null;
            String error = null;
            try {
                result = ImportSession.INSTANCE.scanMaterials(p);
            } catch (IOException e) {
                error = e.getMessage();
            }
            final ObjStreamParser.MaterialScan sc = result;
            final String err = error;
            Minecraft.getMinecraft().addScheduledTask(() -> {
                scanning = false;
                if (!p.equals(settings.modelPath)) return;
                if (sc == null) {
                    note = TextFormatting.RED + err;
                    return;
                }
                applyScan(sc);
                if (after != null) after.run();
            });
        }, "meshtiles-scan");
        t.setDaemon(true);
        t.start();
    }

    private void applyScan(ObjStreamParser.MaterialScan sc) {
        lastScanHadDefault = sc.hasDefaultMaterial;
        File model = ImportSession.resolve(settings.modelPath);
        BlenderSettings.Result blender = null;
        String blenderError = null;
        try {
            blender = BlenderSettings.applyIfPresent(model, settings, sc.materialNames());
        } catch (IOException e) {
            blenderError = e.getMessage();
        }
        blenderModel = model.getAbsolutePath();
        blenderStamp = BlenderSettings.stamp(model);
        refreshRows(sc.materialNames(), ImportSession.INSTANCE.mtl);
        List<String> problems = ImportSession.INSTANCE.problems;
        String extra = problems.isEmpty() ? "" : " (" + problems.get(0) + ")";
        if (blender != null) {
            extra = ", settings from Blender for " + blender.applied + extra;
            if (!blender.unknownBlocks.isEmpty()) extra += TextFormatting.RED + ", unknown block " + blender.unknownBlocks.get(0);
        }
        if (blenderError != null) extra += TextFormatting.RED + ", " + blenderError;
        note = TextFormatting.GRAY + "Found " + shown.size() + " materials" + extra;
        refreshLabels();
    }

    private void voxelize() {
        if (ImportSession.INSTANCE.isBusy() || scanning) return;
        collect();
        if (blenderSettingsChanged()) {
            scan(this::voxelizeNow); // exported again from Blender: take its settings before voxelizing
            return;
        }
        voxelizeNow();
    }

    private void voxelizeNow() {
        ImportSession session = ImportSession.INSTANCE;
        if (session.isBusy()) return;
        note = "";
        sendSettings();
        session.startVoxelize(settings, structure.getPos());
    }

    private void place() {
        ImportSession session = ImportSession.INSTANCE;
        MultiModel model = session.model();
        if (model == null || session.isBusy()) {
            note = TextFormatting.YELLOW + "Voxelize the model first";
            return;
        }
        if (blenderSettingsChanged()) {
            scan(() -> note = TextFormatting.YELLOW + "Settings from Blender changed: press Voxelize again");
            return;
        }
        List<MaterialSetup> mats = materialsForMesh();
        int[] rank = new int[mats.size()];
        for (int i = 0; i < mats.size(); i++) {
            int idx = settings.materials.indexOf(mats.get(i));
            rank[i] = idx < 0 ? Integer.MAX_VALUE : idx;
        }
        session.speed = settings.speed;
        if (!session.startUpload(mats, rank)) {
            note = TextFormatting.YELLOW + "An upload is still running";
            return;
        }
        note = "";
        sendSettings();
    }

    private void recipe() {
        ImportSession session = ImportSession.INSTANCE;
        MultiModel model = session.model();
        if (model == null || session.isBusy()) {
            note = TextFormatting.YELLOW + "Voxelize the model first";
            return;
        }
        if (model.boxCount() > MeshTilesConfig.recipeMaxBoxes) {
            note = TextFormatting.RED + "Too many tiles for a recipe item (" + model.boxCount() + " > " + MeshTilesConfig.recipeMaxBoxes + "), use Place";
            return;
        }
        NBTTagCompound nbt = RecipeBuilder.build(model, materialsForMesh());
        PacketImporterRecipe.send(nbt);
        note = TextFormatting.GREEN + "Recipe sent (" + model.boxCount() + " tiles)";
    }

    private void cancel() {
        ImportSession session = ImportSession.INSTANCE;
        if (session.isBusy()) {
            session.cancelWork();
            note = "Cancelling...";
        } else {
            PacketHandler.sendPacketToServer(new PacketImportControl(PacketImportControl.CANCEL));
        }
    }

    private void teleport() {
        MultiModel m = ImportSession.INSTANCE.model();
        if (m == null) {
            note = TextFormatting.YELLOW + "Voxelize the model first";
            return;
        }
        double cx = (m.minBlockX + m.maxBlockX + 1) / 2.0, cz = (m.minBlockZ + m.maxBlockZ + 1) / 2.0;
        PacketHandler.sendPacketToServer(new PacketImportControl(cx, Math.min(255, m.maxBlockY + 3), cz));
    }

    /** Material setups in mesh order (palette material indices refer to this order). */
    private List<MaterialSetup> materialsForMesh() {
        List<MaterialSetup> list = new ArrayList<>();
        ObjMesh mesh = ImportSession.INSTANCE.mesh();
        if (mesh != null)
            for (String name : mesh.materials) {
                MaterialSetup s = settings.material(name);
                if (s == null) {
                    s = new MaterialSetup(name);
                    s.blockId = settings.defaultBlock;
                    // the unused implicit "default" material must not end up in the saved list
                    if (!name.equals(ObjStreamParser.ObjMesh_DEFAULT) || usesDefault()) settings.materials.add(s);
                }
                list.add(s);
            }
        return list;
    }

    // ---- drawing ------------------------------------------------------------------------------------------

    @Override
    public void drawScreen(int mx, int my, float partialTicks) {
        drawDefaultBackground();
        drawRect(left, top, left + W, top + H, 0xF0101010);
        drawRect(left, top, left + W, top + 1, ACCENT);
        drawString(fontRenderer, "Model", left + 6, top + 10, 0xFFFFFF);
        drawString(fontRenderer, "Scale", left + 6, top + 34, 0xFFFFFF);
        drawString(fontRenderer, "Mirror", left + 184, top + 34, 0xFFFFFF);
        drawString(fontRenderer, "Snap", left + 284, top + 34, 0xFFFFFF);
        drawString(fontRenderer, "Offset", left + 6, top + 58, 0xFFFFFF);
        drawString(fontRenderer, "Block", left + 190, top + 58, 0xFFFFFF);
        for (GuiTextField f : fields()) f.drawTextBox();
        drawList(mx, my);

        ImportSession session = ImportSession.INSTANCE;
        int py = top + LIST_Y + LIST_H + 6;
        drawRect(left + 6, py, left + W - 6, py + 4, 0xFF2A2A2A);
        float p = progress();
        if (p > 0) drawRect(left + 6, py, left + 6 + (int) ((W - 12) * Math.min(1f, p)), py + 4, ACCENT);
        int textW = W - 12 - 152;
        drawString(fontRenderer, fontRenderer.trimStringToWidth(statusText(), textW), left + 6, py + 9, 0xFFFFFF);
        drawString(fontRenderer, fontRenderer.trimStringToWidth(TextFormatting.AQUA + session.serverStatusText, textW), left + 6, py + 21, 0xFFFFFF);

        super.drawScreen(mx, my, partialTicks);

        ItemStack def = BlockRef.parse(settings.defaultBlock).toStack();
        RenderHelper.enableGUIStandardItemLighting();
        itemRender.renderItemAndEffectIntoGUI(def, defBlock.x + 3, defBlock.y);
        RenderHelper.disableStandardItemLighting();
        drawString(fontRenderer, "Default: " + fontRenderer.trimStringToWidth(def.getDisplayName(), 150), defBlock.x + 22, defBlock.y + 4, 0xFFFFFF);
        tooltips(mx, my);
    }

    private float progress() {
        ImportSession session = ImportSession.INSTANCE;
        if (session.isBusy()) return session.progress;
        PacketImportStatus st = session.serverStatus;
        if (st != null && st.state() == ImportJob.State.RECEIVING && st.total > 0) return st.placed / (float) st.total;
        return session.hasModel() ? 1 : 0;
    }

    private String statusText() {
        ImportSession session = ImportSession.INSTANCE;
        if (!note.isEmpty() && !session.isBusy()) return note;
        switch (session.stage) {
            case PARSING:
            case VOXELIZING:
                return TextFormatting.YELLOW + session.status;
            case READY:
            case UPLOADING:
                return TextFormatting.GREEN + session.status;
            case ERROR:
                return TextFormatting.RED + session.status;
            default:
                return TextFormatting.GRAY + "Choose a model, Scan, then Voxelize";
        }
    }

    private void drawList(int mx, int my) {
        int x0 = left + 6, y0 = top + LIST_Y, w = W - 12;
        String header = "Materials: " + shown.size() + (shown.isEmpty() ? "   choose a model and press Scan" : "   top is placed first and wins overlaps");
        drawString(fontRenderer, header, x0, y0 - 11, 0xFFFFFF);
        drawRect(x0, y0, x0 + w, y0 + LIST_H, 0xFF000000);
        int sf = new ScaledResolution(mc).getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x0 * sf, mc.displayHeight - (y0 + LIST_H) * sf, w * sf, LIST_H * sf);
        boolean inList = mx >= x0 && mx < x0 + w && my >= y0 && my < y0 + LIST_H;
        for (int i = 0; i < shown.size(); i++) {
            int ry = y0 + i * ROW_H - scroll;
            if (ry + ROW_H <= y0 || ry >= y0 + LIST_H) continue;
            MaterialSetup m = setup(shown.get(i));
            boolean rowHover = inList && my >= ry && my < ry + ROW_H;
            int rx = mx - x0;
            if (rowHover) drawRect(x0, ry, x0 + w, ry + ROW_H, 0xFF1C1C1C);
            drawString(fontRenderer, (m.skip ? TextFormatting.DARK_GRAY.toString() : "") + fontRenderer.trimStringToWidth(m.name, 90), x0 + 4, ry + 6, 0xFFFFFF);

            cell(x0 + C_BLOCK, ry + 2, C_BLOCK_W, 16, rowHover && over(rx, C_BLOCK, C_BLOCK_W));
            ItemStack stack = BlockRef.parse(m.blockId).toStack();
            RenderHelper.enableGUIStandardItemLighting();
            itemRender.renderItemAndEffectIntoGUI(stack, x0 + C_BLOCK + 1, ry + 2);
            RenderHelper.disableStandardItemLighting();
            boolean solidBlocks = m.placesSolidBlocks();
            drawString(fontRenderer, fontRenderer.trimStringToWidth(stack.getDisplayName(), C_BLOCK_W - 22), x0 + C_BLOCK + 19, ry + 6, m.skip ? 0x707070 : 0xFFFFFF);

            cell(x0 + C_GRID, ry + 2, C_GRID_W, 16, rowHover && over(rx, C_GRID, C_GRID_W));
            drawCenteredString(fontRenderer, String.valueOf(m.grid), x0 + C_GRID + C_GRID_W / 2, ry + 6, 0xFFFFFF);

            cell(x0 + C_MODE, ry + 2, C_MODE_W, 16, rowHover && over(rx, C_MODE, C_MODE_W));
            drawCenteredString(fontRenderer, solidBlocks ? TextFormatting.DARK_GRAY + "block" : MODE_NAMES[m.mode.ordinal()], x0 + C_MODE + C_MODE_W / 2, ry + 6, 0xFFFFFF);

            boolean swatchHover = rowHover && over(rx, C_SWATCH, C_SWATCH_W);
            drawRect(x0 + C_SWATCH, ry + 2, x0 + C_SWATCH + C_SWATCH_W, ry + 18, swatchHover ? 0xFFFFFFFF : 0xFF606060);
            drawRect(x0 + C_SWATCH + 1, ry + 3, x0 + C_SWATCH + C_SWATCH_W - 1, ry + 17, 0xFF000000 | (m.color & 0xFFFFFF));
            if (m.mode == MaterialSetup.ColorMode.NONE || solidBlocks) drawRect(x0 + C_SWATCH + 1, ry + 3, x0 + C_SWATCH + C_SWATCH_W - 1, ry + 17, 0xA0000000);

            cell(x0 + C_MC, ry + 2, C_MC_W, 16, rowHover && over(rx, C_MC, C_MC_W));
            TextFormatting mcColor = m.solidBlocks ? (m.grid == 1 ? TextFormatting.GREEN : TextFormatting.DARK_GREEN) : TextFormatting.DARK_GRAY;
            drawCenteredString(fontRenderer, mcColor + "MC", x0 + C_MC + C_MC_W / 2, ry + 6, 0xFFFFFF);

            cell(x0 + C_SKIP, ry + 2, C_SKIP_W, 16, rowHover && over(rx, C_SKIP, C_SKIP_W));
            drawCenteredString(fontRenderer, (m.skip ? TextFormatting.RED : TextFormatting.DARK_GRAY) + "Skip", x0 + C_SKIP + C_SKIP_W / 2, ry + 6, 0xFFFFFF);

            boolean first = i == 0, last = i == shown.size() - 1;
            cell(x0 + C_UP, ry + 2, C_ARROW_W, 16, rowHover && !first && over(rx, C_UP, C_ARROW_W));
            drawCenteredString(fontRenderer, (first ? TextFormatting.DARK_GRAY : TextFormatting.WHITE) + "▲", x0 + C_UP + C_ARROW_W / 2 + 1, ry + 6, 0xFFFFFF);
            cell(x0 + C_DOWN, ry + 2, C_ARROW_W, 16, rowHover && !last && over(rx, C_DOWN, C_ARROW_W));
            drawCenteredString(fontRenderer, (last ? TextFormatting.DARK_GRAY : TextFormatting.WHITE) + "▼", x0 + C_DOWN + C_ARROW_W / 2 + 1, ry + 6, 0xFFFFFF);
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        int max = maxScroll();
        if (max > 0) {
            int barH = Math.max(12, LIST_H * LIST_H / (shown.size() * ROW_H));
            int barY = y0 + (LIST_H - barH) * scroll / max;
            drawRect(x0 + w - 1, barY, x0 + w, barY + barH, 0xFF808080);
        }
    }

    private static boolean over(int rx, int col, int colW) {
        return rx >= col && rx < col + colW;
    }

    private void cell(int x, int y, int w, int h, boolean hover) {
        drawRect(x, y, x + w, y + h, hover ? 0xFF505050 : 0xFF303030);
    }

    private int maxScroll() {
        return Math.max(0, shown.size() * ROW_H - LIST_H);
    }

    private void tooltips(int mx, int my) {
        for (GuiButton b : buttonList) {
            if (!b.isMouseOver()) continue;
            String[] lines;
            switch (b.id) {
                case B_BROWSE: lines = new String[] { "Choose a .obj file", "(recent files are listed in the dialog)" }; break;
                case B_SCAN: lines = new String[] { "Read the materials of the model", "and the settings exported from Blender" }; break;
                case B_AXIS: lines = new String[] { "Which axis is UP in the .obj file", "File Y: Blender's default export", "File Z: exported with Up = Z", "Offset and Mirror are always in Blender axes (Z = up)" }; break;
                case B_ROT: lines = new String[] { "Rotation around the importer block", "(the whole model, frame included)" }; break;
                case B_MX: case B_MY: case B_MZ: lines = new String[] { "Flip the model inside its bounding box", "(Blender axes, Z = up)" }; break;
                case B_SXZ: lines = new String[] { "The model's min X/Z corner goes onto the importer block", "Off: horizontal position from the Blender origin" }; break;
                case B_SY: lines = new String[] { "The model's lowest point goes onto the importer block", "Off: height from the Blender origin (stable across re-exports)" }; break;
                case B_ORIGIN: lines = new String[] { "Blender scene origin (0,0,0) = importer block, no snapping" }; break;
                case B_DEFBLOCK: lines = new String[] { "Block for materials that have no setup yet" }; break;
                case B_SPEED: lines = new String[] { "Placement speed, from 1 block per second to Max", "Lower it to watch the build rise from the ground up", "Works while an import is running" }; break;
                case B_VOXELIZE: lines = new String[] { "Turn the model into tiles and show the wireframe", "where it will stand; nothing is sent yet" }; break;
                case B_PREVIEW: lines = new String[] { "Show or hide the wireframe and frame in the world" }; break;
                case B_PLACE: lines = new String[] { "Send the tiles to the server and build the model" }; break;
                case B_RECIPE: lines = new String[] { "A LittleTiles recipe item of the model (small models)" }; break;
                case B_CANCEL: lines = new String[] { "Stop voxelizing, or stop the running import" }; break;
                case B_UNDO: lines = new String[] { "Take your last import back out of the world", "/meshtiles list shows older ones" }; break;
                case B_REDO: lines = new String[] { "Put the last undone import back" }; break;
                case B_TP: lines = new String[] { "Teleport above the centre of the model" }; break;
                default: lines = null;
            }
            if (lines != null) drawHoveringText(Arrays.asList(lines), mx, my);
            return;
        }
        int x0 = left + 6, y0 = top + LIST_Y;
        if (mx >= x0 && mx < x0 + W - 12 && my >= y0 && my < y0 + LIST_H) {
            int i = (my - y0 + scroll) / ROW_H;
            if (i < 0 || i >= shown.size()) return;
            int rx = mx - x0;
            String[] lines = null;
            if (rx < C_BLOCK) lines = new String[] { shown.get(i) };
            else if (over(rx, C_BLOCK, C_BLOCK_W)) lines = new String[] { "Block for this material", "Click to choose" };
            else if (over(rx, C_GRID, C_GRID_W)) lines = new String[] { "Detail: tiles per block edge", "1 = whole blocks, 64 = finest", "Right-click: previous" };
            else if (over(rx, C_MODE, C_MODE_W)) lines = new String[] { "None: plain block, no tint", "Kd: flat colour (the swatch)", "Texture: colour sampled from map_Kd per tile", "Right-click: previous" };
            else if (over(rx, C_SWATCH, C_SWATCH_W)) lines = new String[] { "Colour for Kd mode, and the fallback", "when a texture is missing" };
            else if (over(rx, C_MC, C_MC_W)) lines = new String[] { "Real Minecraft blocks instead of LittleTiles", "(only with detail 1)" };
            else if (over(rx, C_SKIP, C_SKIP_W)) lines = new String[] { "Leave this material out" };
            else if (over(rx, C_UP, C_ARROW_W) || over(rx, C_DOWN, C_ARROW_W)) lines = new String[] { "Placement order: the top material is placed first", "and wins where surfaces overlap" };
            if (lines != null) drawHoveringText(Arrays.asList(lines), mx, my);
        }
    }

    // ---- input ------------------------------------------------------------------------------------------------

    @Override
    protected void actionPerformed(GuiButton b) throws IOException {
        collect();
        ImportSession session = ImportSession.INSTANCE;
        switch (b.id) {
            case B_BROWSE:
                FileChooserHelper.choose(settings.modelPath, ClientPrefs.recent(), picked -> Minecraft.getMinecraft().addScheduledTask(() -> {
                    settings.modelPath = picked;
                    if (path != null) path.setText(picked);
                    scan(null);
                }));
                break;
            case B_SCAN:
                scan(null);
                break;
            case B_AXIS:
                settings.zUp = !settings.zUp;
                break;
            case B_ROT:
                settings.rotation = (settings.rotation + 1) & 3;
                break;
            case B_MX:
                settings.mirrorX = !settings.mirrorX;
                break;
            case B_MY:
                settings.mirrorY = !settings.mirrorY;
                break;
            case B_MZ:
                settings.mirrorZ = !settings.mirrorZ;
                break;
            case B_SXZ:
                settings.snapXZ = !settings.snapXZ;
                break;
            case B_SY:
                settings.snapY = !settings.snapY;
                break;
            case B_ORIGIN:
                settings.modelOrigin = !settings.modelOrigin;
                break;
            case B_DEFBLOCK:
                mc.displayGuiScreen(new GuiBlockPicker(this, settings.defaultBlock, id -> settings.defaultBlock = id));
                return;
            case B_VOXELIZE:
                voxelize();
                break;
            case B_PREVIEW:
                session.previewEnabled = !session.previewEnabled;
                settings.previewEnabled = session.previewEnabled;
                break;
            case B_PLACE:
                place();
                break;
            case B_RECIPE:
                recipe();
                break;
            case B_CANCEL:
                cancel();
                break;
            case B_UNDO:
                note = "";
                PacketHandler.sendPacketToServer(new PacketImportControl(PacketImportControl.UNDO));
                break;
            case B_REDO:
                note = "";
                PacketHandler.sendPacketToServer(new PacketImportControl(PacketImportControl.REDO));
                break;
            case B_TP:
                teleport();
                break;
            case B_DONE:
                mc.displayGuiScreen(null);
                return;
            default:
                break;
        }
        refreshLabels();
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) throws IOException {
        super.mouseClicked(mx, my, button);
        if (mc.currentScreen != this) return;
        for (GuiTextField f : fields()) f.mouseClicked(mx, my, button);
        int x0 = left + 6, y0 = top + LIST_Y;
        if (mx >= x0 && mx < x0 + W - 12 && my >= y0 && my < y0 + LIST_H) {
            int i = (my - y0 + scroll) / ROW_H;
            if (i >= 0 && i < shown.size()) clickRow(i, setup(shown.get(i)), mx - x0, button);
        }
    }

    private void clickRow(int index, MaterialSetup m, int rx, int button) {
        if (over(rx, C_BLOCK, C_BLOCK_W)) {
            collect();
            mc.displayGuiScreen(new GuiBlockPicker(this, m.blockId, id -> m.blockId = id));
            return;
        }
        if (over(rx, C_SWATCH, C_SWATCH_W)) {
            collect();
            mc.displayGuiScreen(new GuiColorPicker(this, m.color, c -> {
                m.color = c;
                if (m.mode == MaterialSetup.ColorMode.NONE) m.mode = MaterialSetup.ColorMode.KD;
            }));
            return;
        }
        int dir = button == 1 ? -1 : 1;
        if (over(rx, C_GRID, C_GRID_W)) {
            int[] grids = gridSizes();
            int at = 0;
            for (int k = 0; k < grids.length; k++) if (grids[k] == m.grid) at = k;
            m.grid = grids[(at + dir + grids.length) % grids.length];
        } else if (over(rx, C_MODE, C_MODE_W)) {
            m.mode = MaterialSetup.ColorMode.fromOrdinal((m.mode.ordinal() + dir + 3) % 3);
        } else if (over(rx, C_MC, C_MC_W)) {
            m.solidBlocks = !m.solidBlocks;
        } else if (over(rx, C_SKIP, C_SKIP_W)) {
            m.skip = !m.skip;
        } else if (over(rx, C_UP, C_ARROW_W) && index > 0) {
            moveMaterial(m, -1);
        } else if (over(rx, C_DOWN, C_ARROW_W) && index < shown.size() - 1) {
            moveMaterial(m, 1);
        } else {
            return;
        }
        mc.getSoundHandler().playSound(PositionedSoundRecord.getMasterRecord(SoundEvents.UI_BUTTON_CLICK, 1f));
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d != 0) scroll = Math.max(0, Math.min(maxScroll(), scroll - Integer.signum(d) * ROW_H));
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (key == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }
        if ((key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) && path.isFocused()) {
            scan(null);
            return;
        }
        for (GuiTextField f : fields()) {
            if (f.isFocused()) {
                f.textboxKeyTyped(c, key);
                return;
            }
        }
    }

    @Override
    public void updateScreen() {
        for (GuiTextField f : fields()) f.updateCursorCounter();
        refreshLabels();
        // material rows follow the parsed mesh once voxelizing has read it
        ImportSession session = ImportSession.INSTANCE;
        ObjMesh mesh = session.mesh();
        if (mesh != null && !session.isBusy() && !scanning && !settings.modelPath.isEmpty() && !meshMaterialsShown(mesh))
            refreshRows(mesh.materials, session.mtl);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        collect();
        sendSettings();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
