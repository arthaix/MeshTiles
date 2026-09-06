package ru.arthaix.meshtiles.client;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;
import ru.arthaix.meshtiles.common.ImportSettings;
import ru.arthaix.meshtiles.voxel.MaterialSetup;

/**
 * Material settings written by the MeshTiles Blender add-on next to the model (model.meshtiles.json): block, detail,
 * colour mode, vanilla-block flag and skip per material, the placement order, and the up axis of the export.
 */
public final class BlenderSettings {

    public static final String SUFFIX = ".meshtiles.json";

    /** Replaceable for offline tests; in the game it asks the block registry. */
    static Predicate<String> blockExists = BlenderSettings::registryHasBlock;

    public static final class Result {
        public int applied;
        public final List<String> unknownBlocks = new ArrayList<>();
        public final List<String> missingMaterials = new ArrayList<>();
    }

    private BlenderSettings() {}

    public static File sidecar(File obj) {
        String name = obj.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return new File(obj.getAbsoluteFile().getParentFile(), base + SUFFIX);
    }

    /** Changes whenever the settings file is written again; 0 when there is none. */
    public static long stamp(File obj) {
        File f = sidecar(obj);
        return f.isFile() ? f.lastModified() ^ (f.length() << 20) : 0;
    }

    /** Blender's OBJ export writes spaces in material names as underscores. */
    static String normalize(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s", "_");
    }

    /** Applies the settings file of the model, if there is one. Returns null when the model has none. */
    public static Result applyIfPresent(File obj, ImportSettings settings, List<String> objMaterials) throws IOException {
        File f = sidecar(obj);
        if (!f.isFile()) return null;
        JsonObject root;
        try {
            root = new JsonParser().parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException(f.getName() + " is not valid: " + e.getMessage());
        }
        Result result = new Result();

        String up = string(root, "up_axis");
        if ("Z".equalsIgnoreCase(up)) settings.zUp = true;
        else if ("Y".equalsIgnoreCase(up)) settings.zUp = false;

        Map<String, String> byName = new HashMap<>();
        for (String n : objMaterials) byName.put(normalize(n), n);

        List<MaterialSetup> ordered = new ArrayList<>();
        JsonArray list = root.has("materials") && root.get("materials").isJsonArray() ? root.getAsJsonArray("materials") : new JsonArray();
        for (JsonElement element : list) {
            if (!element.isJsonObject()) continue;
            JsonObject o = element.getAsJsonObject();
            String objName = byName.get(normalize(string(o, "name")));
            if (objName == null) {
                result.missingMaterials.add(string(o, "name"));
                continue;
            }
            MaterialSetup m = settings.materialOrCreate(objName);
            String block = string(o, "block");
            if (block != null && !block.trim().isEmpty()) {
                String id = canonicalBlockId(block);
                if (blockExists.test(id)) m.blockId = id;
                else result.unknownBlocks.add(block.trim());
            }
            if (o.has("grid")) {
                int grid = o.get("grid").getAsInt();
                if (grid >= 1 && grid <= 64 && Integer.bitCount(grid) == 1) m.grid = grid;
            }
            String mode = string(o, "mode");
            if ("kd".equalsIgnoreCase(mode)) m.mode = MaterialSetup.ColorMode.KD;
            else if ("texture".equalsIgnoreCase(mode)) m.mode = MaterialSetup.ColorMode.TEXTURE;
            else if ("none".equalsIgnoreCase(mode)) m.mode = MaterialSetup.ColorMode.NONE;
            if (o.has("mc")) m.solidBlocks = o.get("mc").getAsBoolean();
            if (o.has("skip")) m.skip = o.get("skip").getAsBoolean();
            if (!ordered.contains(m)) ordered.add(m);
            result.applied++;
        }

        // placement order: the materials listed in Blender come first, in that order; others keep theirs
        List<MaterialSetup> rest = new ArrayList<>(settings.materials);
        rest.removeAll(ordered);
        settings.materials.clear();
        settings.materials.addAll(ordered);
        settings.materials.addAll(rest);
        return result;
    }

    private static String string(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    /** "stone", "concrete:15" and "minecraft:concrete:15" become the importer's form "minecraft:concrete:15" (no ":0"). */
    static String canonicalBlockId(String id) {
        String[] parts = id.trim().toLowerCase(Locale.ROOT).split(":");
        if (parts.length == 1) return "minecraft:" + parts[0];
        if (parts.length == 2 && parts[1].matches("\\d+")) return "0".equals(parts[1]) ? "minecraft:" + parts[0] : "minecraft:" + parts[0] + ":" + parts[1];
        if (parts.length >= 3 && "0".equals(parts[2])) return parts[0] + ":" + parts[1];
        return String.join(":", parts);
    }

    private static boolean registryHasBlock(String canonicalId) {
        String[] parts = canonicalId.split(":");
        if (parts.length < 2) return false;
        Block block = Block.REGISTRY.getObject(new ResourceLocation(parts[0], parts[1]));
        return block != null && block != Blocks.AIR;
    }
}
