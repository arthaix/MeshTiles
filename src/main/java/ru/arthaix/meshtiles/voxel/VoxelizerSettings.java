package ru.arthaix.meshtiles.voxel;

/** All numeric knobs of a voxelization run. Plain data, safe to copy between threads. */
public final class VoxelizerSettings {

    public enum UpAxis { Y_UP, Z_UP }

    /** 1 obj unit = scale blocks. */
    public double scale = 1.0;
    /** Tiles per block edge (LittleTiles grid), e.g. 16 / 32 / 64. */
    public int grid = 16;
    public UpAxis upAxis = UpAxis.Y_UP;
    /** Rotation around the vertical axis in quarter turns (0..3). */
    public int rotation = 0;
    /** Mirror the model inside its bounding box (the box itself stays where it is), applied before the rotation. */
    public boolean mirrorX = false, mirrorY = false, mirrorZ = false;
    /** World block that the model is placed relative to (the importer block). */
    public int originX, originY, originZ;
    /** Additional offset in blocks added to the origin. */
    public int offsetX, offsetY, offsetZ;
    /** When true the model's bounding-box minimum corner lands on the origin; otherwise raw model coordinates are used. */
    public boolean alignXZ = true;
    /** Put the model's lowest point on the origin block; off = height comes from the model's own coordinates. */
    public boolean alignY = false;
    /** Colour steps per channel used for quantisation (2..256). Fewer steps = far better box merging. */
    public int colorLevels = 32;
    /** Worker threads; 0 = number of cores. */
    public int threads = 0;
    /**
     * Priority per mesh material index (lower value wins where two materials claim the same voxel).
     * null = first triangle wins.
     */
    public int[] materialPriority;
    /** Materials excluded from the import (per mesh material index); their vertices do not count for alignment. */
    public boolean[] skippedMaterials;
    /**
     * Materials whose vertices define the bounding box used for alignment/mirroring. When null, the non-skipped
     * materials are used. Set it when voxelizing a model in several passes so every pass aligns identically.
     */
    public boolean[] alignmentMaterials;

    public VoxelizerSettings copy() {
        VoxelizerSettings s = new VoxelizerSettings();
        s.scale = scale;
        s.grid = grid;
        s.upAxis = upAxis;
        s.rotation = rotation;
        s.mirrorX = mirrorX;
        s.mirrorY = mirrorY;
        s.mirrorZ = mirrorZ;
        s.originX = originX;
        s.originY = originY;
        s.originZ = originZ;
        s.offsetX = offsetX;
        s.offsetY = offsetY;
        s.offsetZ = offsetZ;
        s.alignXZ = alignXZ;
        s.alignY = alignY;
        s.colorLevels = colorLevels;
        s.threads = threads;
        s.materialPriority = materialPriority == null ? null : materialPriority.clone();
        s.skippedMaterials = skippedMaterials == null ? null : skippedMaterials.clone();
        s.alignmentMaterials = alignmentMaterials == null ? null : alignmentMaterials.clone();
        return s;
    }
}
