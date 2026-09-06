package ru.arthaix.meshtiles.client;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.minecraft.client.Minecraft;
import ru.arthaix.meshtiles.MeshTiles;
import ru.arthaix.meshtiles.voxel.MaterialSetup;

/**
 * Per-computer preferences: recently used model files and material presets (material name → block / colour mode),
 * stored in config/meshtiles-client.json.
 */
public final class ClientPrefs {

    public static final int MAX_RECENT = 12;

    public static final class Preset {
        public String block;
        public int mode;
        public int color;
        public int grid;
        public boolean solid;
    }

    private static final class Data {
        List<String> recent = new ArrayList<>();
        Map<String, Preset> materials = new LinkedHashMap<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Data data;

    private ClientPrefs() {}

    private static File file() {
        return new File(Minecraft.getMinecraft().gameDir, "config/meshtiles-client.json");
    }

    private static synchronized Data data() {
        if (data == null) {
            data = new Data();
            File f = file();
            if (f.isFile()) {
                try {
                    Data d = GSON.fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), Data.class);
                    if (d != null) {
                        if (d.recent != null) data.recent = d.recent;
                        if (d.materials != null) data.materials = d.materials;
                    }
                } catch (Exception e) {
                    MeshTiles.logger.warn("Cannot read " + f + ": " + e);
                }
            }
        }
        return data;
    }

    private static synchronized void save() {
        File f = file();
        try {
            f.getParentFile().mkdirs();
            Files.write(f.toPath(), GSON.toJson(data()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            MeshTiles.logger.warn("Cannot write " + f + ": " + e);
        }
    }

    public static List<String> recent() {
        return new ArrayList<>(data().recent);
    }

    public static void addRecent(String path) {
        if (path == null || path.trim().isEmpty()) return;
        List<String> r = data().recent;
        r.remove(path);
        r.add(0, path);
        while (r.size() > MAX_RECENT) r.remove(r.size() - 1);
        save();
    }

    /** Applies a remembered preset to a material that has no saved setup yet. Returns true when one existed. */
    public static boolean applyPreset(MaterialSetup setup) {
        Preset p = data().materials.get(setup.name);
        if (p == null) return false;
        if (p.block != null && !p.block.isEmpty()) setup.blockId = p.block;
        setup.mode = MaterialSetup.ColorMode.fromOrdinal(p.mode);
        setup.color = p.color;
        if (p.grid > 0) setup.grid = p.grid;
        setup.solidBlocks = p.solid;
        return true;
    }

    /** Remembers the block / colour mode of every listed material by name. */
    public static void rememberPresets(Iterable<MaterialSetup> setups) {
        boolean changed = false;
        for (MaterialSetup s : setups) {
            Preset p = data().materials.get(s.name);
            if (p == null) {
                p = new Preset();
                data().materials.put(s.name, p);
                changed = true;
            }
            if (!s.blockId.equals(p.block) || p.mode != s.mode.ordinal() || p.color != s.color || p.grid != s.grid || p.solid != s.solidBlocks) {
                p.block = s.blockId;
                p.mode = s.mode.ordinal();
                p.color = s.color;
                p.grid = s.grid;
                p.solid = s.solidBlocks;
                changed = true;
            }
        }
        if (changed) save();
    }
}
