package ru.arthaix.meshtiles.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.creativemd.creativecore.common.gui.container.SubGui;
import com.creativemd.creativecore.common.gui.controls.gui.GuiButton;
import com.creativemd.creativecore.common.gui.controls.gui.GuiCheckBox;
import com.creativemd.creativecore.common.gui.controls.gui.GuiColorPlate;
import com.creativemd.creativecore.common.gui.controls.gui.GuiComboBox;
import com.creativemd.creativecore.common.gui.controls.gui.GuiLabel;
import com.creativemd.creativecore.common.gui.controls.gui.GuiProgressBar;
import com.creativemd.creativecore.common.gui.controls.gui.GuiScrollBox;
import com.creativemd.creativecore.common.gui.controls.gui.GuiStateButton;
import com.creativemd.creativecore.common.gui.controls.gui.GuiTextfield;
import com.creativemd.creativecore.common.gui.controls.gui.custom.GuiStackSelectorAll;
import com.creativemd.creativecore.common.packet.PacketHandler;
import com.creativemd.creativecore.common.utils.mc.ColorUtils;
import com.creativemd.littletiles.client.gui.LittleSubGuiUtils;
import com.creativemd.littletiles.common.util.grid.LittleGridContext;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import ru.arthaix.meshtiles.MeshTilesConfig;
import ru.arthaix.meshtiles.client.ClientPrefs;
import ru.arthaix.meshtiles.client.FileChooserHelper;
import ru.arthaix.meshtiles.client.ImportSession;
import ru.arthaix.meshtiles.client.RecipeBuilder;
import ru.arthaix.meshtiles.common.BlockRef;
import ru.arthaix.meshtiles.common.ImportSettings;
import ru.arthaix.meshtiles.model.MtlLibrary;
import ru.arthaix.meshtiles.model.ObjMesh;
import ru.arthaix.meshtiles.model.ObjStreamParser;
import ru.arthaix.meshtiles.network.PacketImportControl;
import ru.arthaix.meshtiles.network.PacketImporterRecipe;
import ru.arthaix.meshtiles.network.PacketImporterSettings;
import ru.arthaix.meshtiles.voxel.MaterialSetup;
import ru.arthaix.meshtiles.voxel.MultiModel;

public class ImporterGui extends SubGui {

    private static final String[] MODE_NAMES = { "None", "Kd", "Texture" };
    private static final int ROW_H = 22;

    public final ImporterStructure structure;
    private final ImportSettings settings;

    private GuiTextfield objPath, scale, offX, offY, offZ;
    private GuiStateButton axis, rotation;
    private GuiCheckBox mirrorX, mirrorY, mirrorZ, snapXZ, snapY, modelOrigin;
    private GuiStackSelectorAll defaultBlock;
    private GuiLabel materialsLabel, statusLabel, serverLabel;
    private GuiScrollBox materialBox;
    private GuiProgressBar progress;
    private GuiButton voxelizeButton, previewButton;
    private final List<Row> rows = new ArrayList<>();
    /** Material names currently shown (from the last scan or the parsed mesh). */
    private List<String> shownMaterials = new ArrayList<>();

    private static final class Row {
        MaterialSetup setup;
        GuiStackSelectorAll block;
        GuiStateButton mode;
        GuiStateButton grid;
        GuiColorPlate swatch;
        GuiCheckBox skip;
        GuiCheckBox solid;
    }

    /** Grids offered per material: 1/2/4/8/16/32/64, limited to what LittleTiles has enabled. */
    private static String[] gridNames() {
        List<String> names = new ArrayList<>();
        for (String n : LittleGridContext.getNames())
            if (n.equals("1") || n.equals("2") || n.equals("4") || n.equals("8") || n.equals("16") || n.equals("32") || n.equals("64")) names.add(n);
        if (names.isEmpty()) names.add(LittleGridContext.get().size + "");
        return names.toArray(new String[0]);
    }

    public ImporterGui(ImporterStructure structure) {
        super(440, 252);
        this.structure = structure;
        this.settings = structure.settings().copy();
    }

    @Override
    public void createControls() {
        // row 1: model path, scan
        controls.add(new GuiLabel("Model", 6, 9));
        objPath = new GuiTextfield("objpath", settings.modelPath == null ? "" : settings.modelPath, 44, 6, 280, 14);
        objPath.maxLength = 32768;
        objPath.setCustomTooltip("Full path to the .obj file (on this computer)");
        controls.add(objPath);
        controls.add(new GuiButton("browse", "...", 330, 6, 22, 14) {
            @Override
            public void onClicked(int x, int y, int button) {
                FileChooserHelper.choose(objPath.text, ClientPrefs.recent(), path -> {
                    objPath.text = path;
                    objPath.setCursorPositionEnd();
                    scan();
                });
            }
        }.setCustomTooltip("Browse for a .obj file (recent files are listed in the dialog)"));
        controls.add(new GuiButton("scan", "Scan", 358, 6, 52, 14) {
            @Override
            public void onClicked(int x, int y, int button) {
                scan();
            }
        });

        // row 2: scale, axis, rotation, mirror, align
        controls.add(new GuiLabel("Scale", 6, 33));
        scale = new GuiTextfield("scale", trimNumber(settings.scale), 44, 30, 44, 14);
        scale.setFloatOnly();
        scale.setCustomTooltip("Blocks per model unit (1.0 = model units are metres)");
        controls.add(scale);
        axis = new GuiStateButton("axis", settings.zUp ? 1 : 0, 94, 30, 44, 14, "File Y", "File Z");
        axis.setCustomTooltip("Which axis is UP in the .obj file.", "File Y: Blender's default export (Forward -Z, Up Y). Use this unless you changed the export settings.", "File Z: exported with Forward Y, Up Z.", "Offset and Mirror below are always in Blender axes (Z = up).");
        controls.add(axis);
        rotation = new GuiStateButton("rot", settings.rotation & 3, 144, 30, 46, 14, "0°", "90°", "180°", "270°");
        rotation.setCustomTooltip("Rotation around the importer block (the whole model, frame included)");
        controls.add(rotation);
        controls.add(new GuiLabel("Mirror", 198, 33));
        mirrorX = new GuiCheckBox("mirrorx", "X", 232, 31, settings.mirrorX);
        mirrorY = new GuiCheckBox("mirrory", "Y", 256, 31, settings.mirrorY);
        mirrorZ = new GuiCheckBox("mirrorz", "Z", 280, 31, settings.mirrorZ);
        for (GuiCheckBox c : new GuiCheckBox[] { mirrorX, mirrorY, mirrorZ }) {
            c.setCustomTooltip("Flip the model inside its bounding box (the box stays in place); Blender axes, Z = up");
            controls.add(c);
        }
        controls.add(new GuiLabel("Snap", 304, 33));
        snapXZ = new GuiCheckBox("snapxz", "XZ", 330, 31, settings.snapXZ);
        snapXZ.setCustomTooltip("Horizontal: the model's min X/Z corner is put on the importer block.", "Off: horizontal position from the Blender origin (importer block = scene origin).");
        controls.add(snapXZ);
        snapY = new GuiCheckBox("snapy", "Y", 360, 31, settings.snapY);
        snapY.setCustomTooltip("Vertical: the model's lowest point is put on the importer block (changes when the model's bottom changes).", "Off (default): height from the Blender origin, stable across re-exports; use Offset to lift the model.");
        controls.add(snapY);
        modelOrigin = new GuiCheckBox("origin", "Origin", 386, 31, settings.modelOrigin);
        modelOrigin.setCustomTooltip("Origin as in the model: no snapping at all, the Blender scene origin (0,0,0) is the importer block.", "Rotation and mirror still work around that block.");
        controls.add(modelOrigin);

        // row 3: offset + default block
        controls.add(new GuiLabel("Offset", 6, 57));
        offX = numberField("offx", settings.offsetX, 44, 54);
        offY = numberField("offy", settings.offsetY, 94, 54);
        offZ = numberField("offz", settings.offsetZ, 144, 54);
        offX.setCustomTooltip("Offset X in blocks (Blender X)");
        offY.setCustomTooltip("Offset Y in blocks (Blender Y = forward)");
        offZ.setCustomTooltip("Offset Z in blocks (Blender Z = up)");
        controls.add(offX);
        controls.add(offY);
        controls.add(offZ);
        controls.add(new GuiLabel("Block", 198, 57));
        defaultBlock = new GuiStackSelectorAll("defblock", 228, 54, 182, getPlayer(), new GuiStackSelectorAll.CreativeCollector(new LittleSubGuiUtils.LittleBlockSelector()), true);
        defaultBlock.setSelectedForce(BlockRef.parse(settings.defaultBlock).toStack());
        controls.add(defaultBlock);

        // materials
        materialsLabel = new GuiLabel("Materials: press Scan", 6, 80);
        controls.add(materialsLabel);
        materialBox = new GuiScrollBox("materials", 6, 92, 404, 82);
        controls.add(materialBox);

        // progress + status
        progress = new GuiProgressBar("progress", 6, 182, 404, 6, 1, 0);
        controls.add(progress);
        statusLabel = new GuiLabel("", 6, 192);
        controls.add(statusLabel);

        // buttons
        voxelizeButton = new GuiButton("voxelize", "Voxelize", 6, 208, 54, 14) {
            @Override
            public void onClicked(int x, int y, int button) {
                voxelize();
            }
        };
        controls.add(voxelizeButton);
        previewButton = new GuiButton("preview", previewCaption(), 66, 208, 64, 14) {
            @Override
            public void onClicked(int x, int y, int button) {
                ImportSession.INSTANCE.previewEnabled = !ImportSession.INSTANCE.previewEnabled;
                settings.previewEnabled = ImportSession.INSTANCE.previewEnabled;
                setCaption(previewCaption());
            }
        };
        controls.add(previewButton);
        controls.add(new GuiButton("place", "Place", 136, 208, 46, 14) {
            @Override
            public void onClicked(int x, int y, int button) {
                place();
            }
        });
        controls.add(new GuiButton("recipe", "Recipe", 188, 208, 48, 14) {
            @Override
            public void onClicked(int x, int y, int button) {
                recipe();
            }
        });
        controls.add(new GuiButton("cancel", "Cancel", 242, 208, 46, 14) {
            @Override
            public void onClicked(int x, int y, int button) {
                cancel();
            }
        });
        controls.add(new GuiButton("undo", "Undo", 294, 208, 34, 14) {
            @Override
            public void onClicked(int x, int y, int button) {
                PacketHandler.sendPacketToServer(new PacketImportControl(PacketImportControl.UNDO));
            }
        });
        controls.add(new GuiButton("redo", "Redo", 332, 208, 34, 14) {
            @Override
            public void onClicked(int x, int y, int button) {
                PacketHandler.sendPacketToServer(new PacketImportControl(PacketImportControl.REDO));
            }
        });
        controls.add(new GuiButton("teleport", "TP", 370, 208, 30, 14) {
            @Override
            public void onClicked(int x, int y, int button) {
                MultiModel m = ImportSession.INSTANCE.model();
                if (m == null) {
                    statusLabel.setCaption(TextFormatting.YELLOW + "Voxelize the model first");
                    return;
                }
                double cx = (m.minBlockX + m.maxBlockX + 1) / 2.0, cz = (m.minBlockZ + m.maxBlockZ + 1) / 2.0;
                PacketHandler.sendPacketToServer(new PacketImportControl(cx, Math.min(255, m.maxBlockY + 3), cz));
            }
        }.setCustomTooltip("Teleport above the centre of the model"));
        serverLabel = new GuiLabel("", 6, 231);
        controls.add(serverLabel);

        ImportSession.INSTANCE.previewEnabled = settings.previewEnabled;
        previewButton.setCaption(previewCaption());

        // show materials we already know about (previous scan / parsed mesh)
        ImportSession session = ImportSession.INSTANCE;
        ObjMesh mesh = session.mesh();
        if (mesh != null && sameFile(session, settings.modelPath)) {
            rebuildRows(mesh.materials, session.mtl);
        } else if (!settings.materials.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (MaterialSetup m : settings.materials) names.add(m.name);
            rebuildRows(names, null);
        }
        refreshStatus();
    }

    private boolean meshMaterialsShown(ObjMesh mesh) {
        List<String> expected = new ArrayList<>(mesh.materials);
        if (!usesDefault()) expected.remove(ObjStreamParser.ObjMesh_DEFAULT);
        return new java.util.HashSet<>(expected).equals(new java.util.HashSet<>(shownMaterials));
    }

    private static boolean sameFile(ImportSession session, String path) {
        ObjMesh mesh = session.mesh();
        return mesh != null && path != null && !path.isEmpty();
    }

    private String previewCaption() {
        return ImportSession.INSTANCE.previewEnabled ? "Preview: on" : "Preview: off";
    }

    private static GuiTextfield numberField(String name, int value, int x, int y) {
        GuiTextfield f = new GuiTextfield(name, value + "", x, y, 40, 14);
        f.setNumbersIncludingNegativeOnly();
        return f;
    }

    private static String trimNumber(double d) {
        String s = Double.toString(d);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    // ---- material rows --------------------------------------------------------------------------------

    private void rebuildRows(List<String> names, MtlLibrary mtl) {
        for (Row r : rows) {
            materialBox.removeControl(r.block);
            materialBox.removeControl(r.mode);
            materialBox.removeControl(r.grid);
            materialBox.removeControl(r.solid);
            materialBox.removeControl(r.swatch);
            materialBox.removeControl(r.skip);
        }
        materialBox.controls.clear();
        rows.clear();
        shownMaterials = new ArrayList<>(names);
        // the implicit "default" material only exists when faces precede the first usemtl
        if (!usesDefault()) {
            shownMaterials.remove(ObjStreamParser.ObjMesh_DEFAULT);
            settings.materials.removeIf(m -> m.name.equals(ObjStreamParser.ObjMesh_DEFAULT));
        }
        // make sure every shown material has a setup, then display them in priority order
        for (String name : shownMaterials) {
            if (settings.material(name) != null) continue;
            MaterialSetup created = settings.materialOrCreate(name);
            MtlLibrary.Material mm = mtl == null ? null : mtl.materials.get(name);
            if (mm != null) {
                created.mode = mm.mapKd != null ? MaterialSetup.ColorMode.TEXTURE : MaterialSetup.ColorMode.KD;
                created.color = mm.kdArgb();
            }
            ClientPrefs.applyPreset(created); // remembered choice for this material name (from earlier imports)
        }
        List<String> ordered = orderedShownNames();
        int y = 2;
        int shown = 0;
        for (String name : ordered) {
            MaterialSetup setup = settings.material(name);
            if (setup == null) {
                setup = settings.materialOrCreate(name);
                MtlLibrary.Material mm = mtl == null ? null : mtl.materials.get(name);
                if (mm != null) {
                    setup.mode = mm.mapKd != null ? MaterialSetup.ColorMode.TEXTURE : MaterialSetup.ColorMode.KD;
                    setup.color = mm.kdArgb();
                }
                ClientPrefs.applyPreset(setup); // remembered choice for this material name (from earlier imports)
            }
            final Row row = new Row();
            row.setup = setup;
            String label = name.length() > 11 ? name.substring(0, 10) + "…" : name;
            GuiLabel nameLabel = new GuiLabel(label, 2, y + 3);
            nameLabel.setCustomTooltip(name);
            materialBox.addControl(nameLabel);
            row.block = new GuiStackSelectorAll("mb_" + shown, 70, y, 110, getPlayer(), new GuiStackSelectorAll.CreativeCollector(new LittleSubGuiUtils.LittleBlockSelector()), true);
            row.block.setSelectedForce(BlockRef.parse(setup.blockId).toStack());
            materialBox.addControl(row.block);
            String[] grids = gridNames();
            int gridIndex = java.util.Arrays.asList(grids).indexOf(setup.grid + "");
            row.grid = new GuiStateButton("mg_" + shown, Math.max(0, gridIndex), 210, y, 26, 14, grids);
            row.grid.setCustomTooltip("Tiles per block for this material (1 = whole blocks, 64 = finest)");
            materialBox.addControl(row.grid);
            row.mode = new GuiStateButton("mm_" + shown, setup.mode.ordinal(), 240, y, 48, 14, MODE_NAMES);
            row.mode.setCustomTooltip("None: plain block, no tint", "Kd: flat colour from the .mtl ('..' changes it)", "Texture: colour sampled from map_Kd per tile");
            materialBox.addControl(row.mode);
            row.swatch = new GuiColorPlate("mc_" + shown, 294, y, 12, 14, ColorUtils.IntToRGBA(setup.color));
            materialBox.addControl(row.swatch);
            final String matName = name;
            materialBox.addControl(new GuiButton("mp_" + shown, "..", 310, y, 14, 14) {
                @Override
                public void onClicked(int x, int yy, int button) {
                    collect();
                    openClientLayer(new SubGuiColorDialog(matName, row.setup.color));
                }
            });
            row.skip = new GuiCheckBox("ms_" + shown, "", 327, y + 1, setup.skip);
            row.skip.setCustomTooltip("Skip this material");
            materialBox.addControl(row.skip);
            final MaterialSetup moving = setup;
            materialBox.addControl(new GuiButton("mu_" + shown, "^", 341, y, 11, 14) {
                @Override
                public void onClicked(int x, int yy, int button) {
                    moveMaterial(moving, -1);
                }
            });
            materialBox.addControl(new GuiButton("md_" + shown, "v", 353, y, 11, 14) {
                @Override
                public void onClicked(int x, int yy, int button) {
                    moveMaterial(moving, 1);
                }
            });
            row.solid = new GuiCheckBox("mv_" + shown, "MC", 367, y + 1, setup.solidBlocks);
            row.solid.setCustomTooltip("Place real Minecraft blocks instead of LittleTiles (detail 1 only)");
            materialBox.addControl(row.solid);
            rows.add(row);
            y += ROW_H;
            shown++;
        }
        materialsLabel.setCaption("Materials: " + shown + "  (placed top to bottom; top wins overlaps)");
    }

    /** Moves a material one step up (-1) or down (+1) in the priority list and rebuilds the rows. */
    private void moveMaterial(MaterialSetup setup, int dir) {
        collect();
        int i = settings.materials.indexOf(setup);
        if (i < 0) return;
        // step over materials that are not shown (they keep their relative place)
        int j = i + dir;
        while (j >= 0 && j < settings.materials.size() && !shownMaterials.contains(settings.materials.get(j).name)) j += dir;
        if (j < 0 || j >= settings.materials.size()) return;
        settings.materials.remove(i);
        settings.materials.add(j, setup);
        rebuildRows(new ArrayList<>(shownMaterials), ImportSession.INSTANCE.mtl);
    }

    /** Shown material names in the order of the priority list. */
    private List<String> orderedShownNames() {
        List<String> names = new ArrayList<>();
        for (MaterialSetup m : settings.materials)
            if (shownMaterials.contains(m.name)) names.add(m.name);
        for (String n : shownMaterials)
            if (!names.contains(n)) names.add(n);
        return names;
    }

    /** True when the model has faces before its first usemtl (the implicit "default" material). */
    private boolean usesDefault() {
        ObjMesh mesh = ImportSession.INSTANCE.mesh();
        if (mesh == null) return lastScanHadDefault;
        for (int i = 0; i < mesh.triangleCount; i++)
            if (mesh.triMaterial[i] == 0) return true;
        return false;
    }

    private boolean lastScanHadDefault;

    @Override
    public void onLayerClosed(SubGui gui, NBTTagCompound nbt) {
        super.onLayerClosed(gui, nbt);
        if (gui instanceof SubGuiColorDialog && nbt.hasKey("color")) {
            String name = nbt.getString("material");
            for (Row r : rows)
                if (r.setup.name.equals(name)) {
                    r.setup.color = nbt.getInteger("color");
                    r.swatch.setColor(ColorUtils.IntToRGBA(r.setup.color));
                    if (r.setup.mode == MaterialSetup.ColorMode.NONE) {
                        r.setup.mode = MaterialSetup.ColorMode.KD;
                        r.mode.setState(1);
                    }
                }
        }
    }

    // ---- actions ---------------------------------------------------------------------------------------

    private void scan() {
        collect();
        try {
            ObjStreamParser.MaterialScan scan = ImportSession.INSTANCE.scanMaterials(settings.modelPath);
            lastScanHadDefault = scan.hasDefaultMaterial;
            rebuildRows(scan.materialNames(), ImportSession.INSTANCE.mtl);
            String extra = ImportSession.INSTANCE.problems.isEmpty() ? "" : " (" + ImportSession.INSTANCE.problems.get(0) + ")";
            statusLabel.setCaption(TextFormatting.GRAY + "Found " + rows.size() + " materials" + extra);
        } catch (IOException e) {
            statusLabel.setCaption(TextFormatting.RED + e.getMessage());
        }
    }

    private void voxelize() {
        ImportSession session = ImportSession.INSTANCE;
        if (session.isBusy()) return;
        collect();
        sendSettings();
        session.startVoxelize(settings, structure.getPos());
        refreshStatus();
    }

    private void place() {
        ImportSession session = ImportSession.INSTANCE;
        collect();
        MultiModel model = session.model();
        if (model == null || session.isBusy()) {
            statusLabel.setCaption(TextFormatting.YELLOW + "Voxelize the model first");
            return;
        }
        List<MaterialSetup> mats = materialsForMesh();
        int[] rank = new int[mats.size()];
        for (int i = 0; i < mats.size(); i++) {
            int idx = settings.materials.indexOf(mats.get(i));
            rank[i] = idx < 0 ? Integer.MAX_VALUE : idx;
        }
        if (!session.startUpload(mats, rank)) {
            statusLabel.setCaption(TextFormatting.YELLOW + "An upload is still running");
            return;
        }
        sendSettings();
        serverLabel.setCaption("Sending " + model.blockCount() + " blocks...");
    }

    private void recipe() {
        ImportSession session = ImportSession.INSTANCE;
        collect();
        MultiModel model = session.model();
        if (model == null || session.isBusy()) {
            statusLabel.setCaption(TextFormatting.YELLOW + "Voxelize the model first");
            return;
        }
        if (model.boxCount() > MeshTilesConfig.recipeMaxBoxes) {
            statusLabel.setCaption(TextFormatting.RED + "Too many tiles for a recipe item (" + model.boxCount() + " > " + MeshTilesConfig.recipeMaxBoxes + "), use Place");
            return;
        }
        NBTTagCompound nbt = RecipeBuilder.build(model, materialsForMesh());
        PacketImporterRecipe.send(nbt);
        statusLabel.setCaption(TextFormatting.GREEN + "Recipe sent (" + model.boxCount() + " tiles)");
    }

    private void cancel() {
        ImportSession session = ImportSession.INSTANCE;
        if (session.isBusy()) {
            session.cancelWork();
            statusLabel.setCaption("Cancelling...");
        } else {
            PacketHandler.sendPacketToServer(new PacketImportControl(PacketImportControl.CANCEL));
        }
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

    // ---- settings sync ---------------------------------------------------------------------------------

    /** Reads every control back into {@link #settings}. */
    private void collect() {
        settings.modelPath = objPath.text.trim();
        try {
            settings.scale = Double.parseDouble(scale.text.trim());
            if (!(settings.scale > 0)) settings.scale = 1;
        } catch (NumberFormatException e) {
            settings.scale = 1;
        }
        settings.zUp = axis.getState() == 1;
        settings.rotation = Math.max(0, rotation.getState()) & 3;
        settings.mirrorX = mirrorX.value;
        settings.mirrorY = mirrorY.value;
        settings.mirrorZ = mirrorZ.value;
        settings.snapXZ = snapXZ.value;
        settings.snapY = snapY.value;
        settings.modelOrigin = modelOrigin.value;
        settings.offsetX = parseInt(offX.text);
        settings.offsetY = parseInt(offY.text);
        settings.offsetZ = parseInt(offZ.text);
        ItemStack def = defaultBlock.getSelected();
        if (def != null && !def.isEmpty()) settings.defaultBlock = BlockRef.toId(def);
        settings.previewEnabled = ImportSession.INSTANCE.previewEnabled;
        for (Row r : rows) {
            ItemStack stack = r.block.getSelected();
            if (stack != null && !stack.isEmpty()) r.setup.blockId = BlockRef.toId(stack);
            r.setup.mode = MaterialSetup.ColorMode.fromOrdinal(Math.max(0, r.mode.getState()));
            r.setup.skip = r.skip.value;
            r.setup.solidBlocks = r.solid.value;
            try {
                r.setup.grid = Integer.parseInt(r.grid.getCaption());
            } catch (NumberFormatException e) {
                r.setup.grid = 16;
            }
        }
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

    @Override
    public void onClosed() {
        super.onClosed();
        collect();
        sendSettings();
    }

    // ---- live status -----------------------------------------------------------------------------------

    @Override
    public void onTick() {
        super.onTick();
        refreshStatus();
    }

    private void refreshStatus() {
        ImportSession session = ImportSession.INSTANCE;
        progress.pos = session.isBusy() ? session.progress : (session.hasModel() ? 1 : 0);
        String text;
        switch (session.stage) {
            case PARSING:
            case VOXELIZING:
                text = TextFormatting.YELLOW + session.status;
                break;
            case READY:
            case UPLOADING:
                text = TextFormatting.GREEN + session.status;
                break;
            case ERROR:
                text = TextFormatting.RED + session.status;
                break;
            default:
                text = TextFormatting.GRAY + "Set the path, Scan materials, then Voxelize";
        }
        if (text.length() > 64) text = text.substring(0, 62) + "…";
        statusLabel.setCaption(text);
        voxelizeButton.setCaption(session.isBusy() ? "Working…" : "Voxelize");
        serverLabel.setCaption(TextFormatting.AQUA + session.serverStatusText);

        // material rows appear automatically once the mesh is parsed
        ObjMesh mesh = session.mesh();
        if (mesh != null && !session.isBusy() && sameFile(session, settings.modelPath) && !meshMaterialsShown(mesh))
            rebuildRows(mesh.materials, session.mtl);
    }
}
