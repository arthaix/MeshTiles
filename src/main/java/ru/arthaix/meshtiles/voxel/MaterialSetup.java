package ru.arthaix.meshtiles.voxel;

/**
 * Per-material import settings, indexed like {@link ru.arthaix.meshtiles.model.ObjMesh#materials}.
 * The block choice lives on the Minecraft side ({@code blockId} is only carried through as a string).
 */
public final class MaterialSetup {

    public enum ColorMode {
        /** Plain block, no tint. */
        NONE,
        /** Flat Kd colour from the .mtl (or the colour picked in the GUI). */
        KD,
        /** Sample the material's texture (map_Kd, or an explicit image) per voxel. */
        TEXTURE;

        public static ColorMode fromOrdinal(int o) {
            ColorMode[] v = values();
            return o >= 0 && o < v.length ? v[o] : NONE;
        }
    }

    /** True when this material is imported as real Minecraft blocks (grid 1 and the option ticked). */
    public boolean placesSolidBlocks() {
        return solidBlocks && grid == 1;
    }

    public final String name;
    /** Registry name with optional meta, e.g. "minecraft:stone" or "minecraft:stone:1". */
    public String blockId = "littletiles:ltcoloredblock";
    public ColorMode mode = ColorMode.NONE;
    /** ARGB used in KD mode, and as fallback in TEXTURE mode when the texture is missing. */
    public int color = 0xffffffff;
    /** Absolute or obj-relative image path used in TEXTURE mode; null = use map_Kd from the mtl. */
    public String texturePath;
    /** Skip this material entirely (e.g. glass panes or helper geometry). */
    public boolean skip;
    /** LittleTiles grid (tiles per block) used for this material: 1, 2, 4, 8, 16, 32 or 64. */
    public int grid = 16;
    /** With grid 1: place real Minecraft blocks instead of tiles. */
    public boolean solidBlocks;

    public MaterialSetup(String name) {
        this.name = name;
    }

    public MaterialSetup copy() {
        MaterialSetup m = new MaterialSetup(name);
        m.blockId = blockId;
        m.mode = mode;
        m.color = color;
        m.texturePath = texturePath;
        m.skip = skip;
        m.grid = grid;
        m.solidBlocks = solidBlocks;
        return m;
    }
}
