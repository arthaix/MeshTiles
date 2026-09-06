package ru.arthaix.meshtiles.common;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import ru.arthaix.meshtiles.voxel.MaterialSetup;
import ru.arthaix.meshtiles.voxel.VoxelizerSettings;

/** Everything the importer structure remembers, serialisable to NBT (structure storage and packets). */
public final class ImportSettings {

    public String modelPath = "";
    public double scale = 1.0;
    /**
     * Which axis points up in the .obj FILE. false = Y up (Blender's default export already converts to Y up),
     * true = Z up (exported with Forward Y / Up Z). The GUI always uses Blender axes (Z up) regardless.
     */
    public boolean zUp = false;
    public int rotation = 0;
    public boolean mirrorX = false, mirrorY = false, mirrorZ = false;
    public int offsetX, offsetY, offsetZ;
    /** Off by default: the Blender scene origin lands on the importer block, so re-exports never shift the model. */
    /** Horizontal snap: the model's min X/Z corner lands on the importer block. */
    public boolean snapXZ = true;
    /** Vertical snap: the model's lowest point lands on the importer block. Off = height from the Blender origin (stable across re-exports). */
    public boolean snapY = false;
    /** "Origin as in model": ignore both snaps, the Blender scene origin lands on the importer block. */
    public boolean modelOrigin = false;
    public boolean previewEnabled = true;
    public String defaultBlock = "littletiles:ltcoloredblock";
    public final List<MaterialSetup> materials = new ArrayList<>();

    public VoxelizerSettings toVoxelizerSettings(int originX, int originY, int originZ, int colorLevels, int threads) {
        VoxelizerSettings s = new VoxelizerSettings();
        s.scale = scale;
        s.upAxis = zUp ? VoxelizerSettings.UpAxis.Z_UP : VoxelizerSettings.UpAxis.Y_UP;
        s.rotation = rotation;
        s.originX = originX;
        s.originY = originY;
        s.originZ = originZ;
        // GUI values are always in Blender axes (X right, Y forward, Z up); Minecraft: y = Blender z, z = -Blender y
        s.mirrorX = mirrorX;
        s.mirrorY = mirrorZ;
        s.mirrorZ = mirrorY;
        s.offsetX = offsetX;
        s.offsetY = offsetZ;
        s.offsetZ = -offsetY;
        s.alignXZ = snapXZ && !modelOrigin;
        s.alignY = snapY && !modelOrigin;
        s.colorLevels = colorLevels;
        s.threads = threads;
        return s;
    }

    public MaterialSetup material(String name) {
        for (MaterialSetup m : materials)
            if (m.name.equals(name)) return m;
        return null;
    }

    /** Returns the setup for the name, creating a default one when unknown. */
    public MaterialSetup materialOrCreate(String name) {
        MaterialSetup m = material(name);
        if (m == null) {
            m = new MaterialSetup(name);
            m.blockId = defaultBlock;
            materials.add(m);
        }
        return m;
    }

    public ImportSettings copy() {
        return read(write(new NBTTagCompound()));
    }

    public NBTTagCompound write(NBTTagCompound nbt) {
        nbt.setString("mt_model", modelPath);
        nbt.setDouble("mt_scale", scale);
        nbt.setBoolean("mt_zup", zUp);
        nbt.setInteger("mt_rot", rotation);
        nbt.setBoolean("mt_mirror", mirrorX);
        nbt.setBoolean("mt_mirrory", mirrorY);
        nbt.setBoolean("mt_mirrorz", mirrorZ);
        nbt.setInteger("mt_offx", offsetX);
        nbt.setInteger("mt_offy", offsetY);
        nbt.setInteger("mt_offz", offsetZ);
        nbt.setBoolean("mt_align", snapXZ);
        nbt.setBoolean("mt_aligny", snapY);
        nbt.setBoolean("mt_origin", modelOrigin);
        nbt.setBoolean("mt_preview", previewEnabled);
        nbt.setString("mt_defblock", defaultBlock);
        NBTTagList list = new NBTTagList();
        for (MaterialSetup m : materials) {
            NBTTagCompound t = new NBTTagCompound();
            t.setString("name", m.name);
            t.setString("blk", m.blockId);
            t.setInteger("mode", m.mode.ordinal());
            t.setInteger("tint", m.color);
            if (m.texturePath != null) t.setString("tex", m.texturePath);
            t.setBoolean("skip", m.skip);
            t.setInteger("detail", m.grid);
            if (m.solidBlocks) t.setBoolean("solid", true);
            list.appendTag(t);
        }
        nbt.setTag("mt_materials", list);
        return nbt;
    }

    public static ImportSettings read(NBTTagCompound nbt) {
        ImportSettings s = new ImportSettings();
        s.modelPath = nbt.getString("mt_model");
        if (nbt.hasKey("mt_scale")) s.scale = nbt.getDouble("mt_scale");
        s.zUp = nbt.getBoolean("mt_zup");
        s.rotation = nbt.getInteger("mt_rot") & 3;
        s.mirrorX = nbt.getBoolean("mt_mirror");
        s.mirrorY = nbt.getBoolean("mt_mirrory");
        s.mirrorZ = nbt.getBoolean("mt_mirrorz");
        s.offsetX = nbt.getInteger("mt_offx");
        s.offsetY = nbt.getInteger("mt_offy");
        s.offsetZ = nbt.getInteger("mt_offz");
        s.snapXZ = !nbt.hasKey("mt_align") || nbt.getBoolean("mt_align");
        s.snapY = nbt.getBoolean("mt_aligny");
        s.modelOrigin = nbt.getBoolean("mt_origin");
        s.previewEnabled = !nbt.hasKey("mt_preview") || nbt.getBoolean("mt_preview");
        if (nbt.hasKey("mt_defblock")) s.defaultBlock = nbt.getString("mt_defblock");
        NBTTagList list = nbt.getTagList("mt_materials", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound t = list.getCompoundTagAt(i);
            MaterialSetup m = new MaterialSetup(t.getString("name"));
            m.blockId = t.getString("blk");
            m.mode = MaterialSetup.ColorMode.fromOrdinal(t.getInteger("mode"));
            m.color = t.getInteger("tint");
            m.texturePath = t.hasKey("tex") ? t.getString("tex") : null;
            m.skip = t.getBoolean("skip");
            if (t.hasKey("detail")) m.grid = t.getInteger("detail");
            m.solidBlocks = t.getBoolean("solid");
            s.materials.add(m);
        }
        return s;
    }
}
