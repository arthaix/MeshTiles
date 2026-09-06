package ru.arthaix.meshtiles.client;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import ru.arthaix.meshtiles.MeshTilesConfig;
import ru.arthaix.meshtiles.MeshTiles;
import ru.arthaix.meshtiles.common.ImportSettings;
import ru.arthaix.meshtiles.model.MtlLibrary;
import ru.arthaix.meshtiles.model.ObjMesh;
import ru.arthaix.meshtiles.model.ObjStreamParser;
import ru.arthaix.meshtiles.network.PacketImportStatus;
import ru.arthaix.meshtiles.server.ImportJob;
import ru.arthaix.meshtiles.voxel.ColorSampler;
import ru.arthaix.meshtiles.voxel.MaterialSetup;
import ru.arthaix.meshtiles.voxel.MultiModel;
import ru.arthaix.meshtiles.voxel.VoxelModel;
import ru.arthaix.meshtiles.voxel.Voxelizer;
import ru.arthaix.meshtiles.voxel.VoxelizerSettings;

/**
 * Client-side state of the importer: the parsed mesh, the voxelized model, the background worker and the
 * progress reported by the server. One instance per client; the GUI and the preview renderer read from it.
 */
public final class ImportSession {

    public static final ImportSession INSTANCE = new ImportSession();

    public enum Stage { IDLE, PARSING, VOXELIZING, READY, UPLOADING, ERROR }

    // worker-visible state
    public volatile Stage stage = Stage.IDLE;
    public volatile float progress;
    public volatile String status = "";
    private final AtomicBoolean cancel = new AtomicBoolean();
    private Thread worker;

    // cached mesh (re-used when only voxelization settings change)
    private ObjMesh mesh;
    private File meshFile;
    private long meshStamp;
    public MtlLibrary mtl;
    public final List<String> problems = new ArrayList<>();

    // result
    private volatile MultiModel model;
    /** Increments whenever {@link #model} changes; the preview renderer uses it to rebuild. */
    public volatile int modelVersion;
    public volatile BlockPos origin = BlockPos.ORIGIN;
    public volatile boolean previewEnabled = true;
    /** True once the current model has been placed by the server (the wireframe is hidden then). */
    public volatile boolean placed;

    // wireframe of the voxelized model: vertices in blocks relative to (wireOriginX, wireOriginY, wireOriginZ)
    public volatile float[] wireVerts;
    public volatile int[] wireTris;
    public int wireOriginX, wireOriginY, wireOriginZ;

    // upload
    public ImportUploader uploader;
    public volatile PacketImportStatus serverStatus;
    public volatile String serverStatusText = "";

    private ImportSession() {}

    public MultiModel model() {
        return model;
    }

    public ObjMesh mesh() {
        return mesh;
    }

    public boolean isBusy() {
        return stage == Stage.PARSING || stage == Stage.VOXELIZING;
    }

    public boolean hasModel() {
        return model != null;
    }

    public synchronized void clearModel() {
        model = null;
        modelVersion++;
    }

    public void cancelWork() {
        cancel.set(true);
    }

    /** Cheap header scan (materials + mtl) used by the GUI "Scan" button. Runs synchronously; fast even for big files. */
    public ObjStreamParser.MaterialScan scanMaterials(String path) throws IOException {
        File f = resolve(path);
        if (!f.isFile()) throw new IOException("model file not found: " + f);
        ObjStreamParser.MaterialScan scan = ObjStreamParser.scanMaterials(f);
        problems.clear();
        mtl = MtlLibrary.load(f, scan.mtlFiles, problems);
        return scan;
    }

    public static File resolve(String path) {
        String p = path == null ? "" : path.trim();
        if (p.startsWith("\"") && p.endsWith("\"") && p.length() >= 2) p = p.substring(1, p.length() - 1);
        return new File(p);
    }

    /** Parses (if needed) and voxelizes on a background thread. */
    public synchronized void startVoxelize(ImportSettings settings, BlockPos origin) {
        if (isBusy()) return;
        final File file = resolve(settings.modelPath);
        if (!file.isFile()) {
            stage = Stage.ERROR;
            status = "Model file not found: " + file;
            return;
        }
        final ImportSettings copy = settings.copy();
        this.origin = origin;
        cancel.set(false);
        clearModel();
        stage = Stage.PARSING;
        progress = 0;
        status = "Reading " + file.getName() + "...";
        worker = new Thread(() -> run(file, copy), "meshtiles-import");
        worker.setDaemon(true);
        worker.start();
    }

    private void run(File file, ImportSettings settings) {
        try {
            long t0 = System.currentTimeMillis();
            ObjMesh m;
            boolean cached = mesh != null && file.equals(meshFile) && meshStamp == stamp(file);
            if (cached) {
                m = mesh;
            } else {
                mesh = null;
                m = ObjStreamParser.parse(file, (read, total) -> {
                    progress = total > 0 ? (float) read / total : 0;
                    status = "Reading " + file.getName() + " " + (read >> 20) + " MB";
                    return !cancel.get();
                });
                meshFile = file;
                meshStamp = stamp(file);
                mesh = m;
            }
            if (cancel.get()) {
                finish(Stage.IDLE, "Cancelled");
                return;
            }
            long t1 = System.currentTimeMillis();
            if (m.isEmpty()) {
                finish(Stage.ERROR, "The model has no faces");
                return;
            }
            problems.clear();
            mtl = MtlLibrary.load(file, m.mtlFiles, problems);

            List<MaterialSetup> setups = new ArrayList<>();
            for (String name : m.materials) {
                MaterialSetup s = settings.material(name);
                if (s == null) {
                    s = new MaterialSetup(name);
                    s.blockId = settings.defaultBlock;
                    MtlLibrary.Material mm = mtl.materials.get(name);
                    if (mm != null) {
                        s.mode = mm.mapKd != null ? MaterialSetup.ColorMode.TEXTURE : MaterialSetup.ColorMode.KD;
                        s.color = mm.kdArgb();
                    }
                    ClientPrefs.applyPreset(s); // the user's remembered choice for this material name wins
                }
                setups.add(s);
            }
            ColorSampler sampler = new ColorSampler(setups, mtl, file, MeshTilesConfig.colorLevels, problems);

            stage = Stage.VOXELIZING;
            progress = 0;
            status = "Voxelizing " + m.triangleCount + " triangles...";
            VoxelizerSettings vs = settings.toVoxelizerSettings(origin.getX(), origin.getY(), origin.getZ(), MeshTilesConfig.colorLevels, MeshTilesConfig.voxelThreads);
            // list order in the GUI = priority: the material higher in the list wins where surfaces overlap
            int[] priority = new int[setups.size()];
            for (int i = 0; i < setups.size(); i++) {
                int idx = settings.materials.indexOf(setups.get(i));
                priority[i] = idx < 0 ? Integer.MAX_VALUE - 1 : idx;
            }
            vs.materialPriority = priority;
            boolean[] skipped = new boolean[setups.size()];
            for (int i = 0; i < setups.size(); i++) skipped[i] = setups.get(i).skip;
            vs.skippedMaterials = skipped;
            // every grid pass must align to the same bounding box: the one of all imported materials
            boolean[] aligned = new boolean[setups.size()];
            for (int i = 0; i < setups.size(); i++) aligned[i] = !setups.get(i).skip;
            vs.alignmentMaterials = aligned;
            // one voxelizer pass per grid: materials of other grids are treated as skipped in that pass
            java.util.TreeSet<Integer> grids = new java.util.TreeSet<>();
            for (int i = 0; i < setups.size(); i++) if (!setups.get(i).skip) grids.add(setups.get(i).grid);
            if (grids.isEmpty()) grids.add(16);
            Voxelizer.Stats stats = new Voxelizer.Stats();
            List<VoxelModel> parts = new ArrayList<>();
            int pass = 0;
            for (int grid : grids) {
                VoxelizerSettings gs = vs.copy();
                gs.grid = grid;
                boolean[] skipHere = new boolean[setups.size()];
                for (int i = 0; i < setups.size(); i++) skipHere[i] = setups.get(i).skip || setups.get(i).grid != grid;
                gs.skippedMaterials = skipHere;
                ColorSampler passSampler = sampler.withSkipped(skipHere);
                final int passIndex = pass++, passCount = grids.size();
                Voxelizer.Stats passStats = new Voxelizer.Stats();
                VoxelModel part = Voxelizer.run(m, gs, passSampler, (st, done, total) -> {
                    progress = (passIndex + (total > 0 ? (float) done / total : 0)) / passCount;
                    return !cancel.get();
                }, passStats);
                if (part == null) {
                    finish(Stage.IDLE, "Cancelled");
                    return;
                }
                stats.regions += passStats.regions;
                stats.trianglesDegenerate += passStats.trianglesDegenerate;
                parts.add(part);
            }
            MultiModel result = new MultiModel(parts);
            long t2 = System.currentTimeMillis();
            float[] wv = buildWireframe(m, vs, result, sampler);
            synchronized (this) {
                model = result;
                wireVerts = wv;
                placed = false;
                modelVersion++;
            }
            int sx = result.maxBlockX - result.minBlockX + 1, sy = result.maxBlockY - result.minBlockY + 1, sz = result.maxBlockZ - result.minBlockZ + 1;
            String summary = String.format("%,d blk, %,d tiles, %dx%dx%d, %.1f s", result.blockCount(), result.boxCount(), sx, sy, sz, (t2 - t0) / 1000.0);
            String details = String.format("%,d blocks, %,d tiles (%,d voxels), max %d tiles/block, size %dx%dx%d blocks | parse %d ms, voxelize %d ms",
                result.blockCount(), result.boxCount(), result.voxelCount(), result.maxBoxesPerBlock(), sx, sy, sz, t1 - t0, t2 - t1);
            MeshTiles.logger.info("Voxelized " + file + ": " + details + " (regions " + stats.regions + ", degenerate " + stats.trianglesDegenerate + ")");
            chat("Voxelized: " + details);
            chat(String.format("Model bounds: X %d..%d, Y %d..%d, Z %d..%d (centre %d %d %d)", result.minBlockX, result.maxBlockX, result.minBlockY, result.maxBlockY,
                result.minBlockZ, result.maxBlockZ, (result.minBlockX + result.maxBlockX) / 2, (result.minBlockY + result.maxBlockY) / 2, (result.minBlockZ + result.maxBlockZ) / 2));
            if (!problems.isEmpty())
                for (String p : problems) MeshTiles.logger.warn("import: " + p);
            finish(Stage.READY, summary);
        } catch (OutOfMemoryError oom) {
            mesh = null;
            clearModel();
            finish(Stage.ERROR, "Out of memory: give the client more RAM (-Xmx) or use a coarser grid");
        } catch (Throwable t) {
            MeshTiles.logger.error("import failed", t);
            finish(Stage.ERROR, "Failed: " + t);
        }
    }

    /** Transformed vertices in block units relative to the model's min block, plus the triangle list (skipped materials left out). */
    private float[] buildWireframe(ObjMesh m, VoxelizerSettings vs, MultiModel result, ColorSampler sampler) {
        wireOriginX = result.minBlockX;
        wireOriginY = result.minBlockY;
        wireOriginZ = result.minBlockZ;
        VoxelizerSettings unit = vs.copy();
        unit.grid = 1; // cells == blocks
        double[] cells = Voxelizer.transform(m, unit);
        float[] v = new float[3 * m.vertexCount];
        for (int i = 0; i < m.vertexCount; i++) {
            v[3 * i] = (float) (cells[3 * i] - wireOriginX);
            v[3 * i + 1] = (float) (cells[3 * i + 1] - wireOriginY);
            v[3 * i + 2] = (float) (cells[3 * i + 2] - wireOriginZ);
        }
        // triangles of skipped materials are left out
        int n = 0;
        for (int t = 0; t < m.triangleCount; t++) if (!sampler.isSkipped(m.triMaterial[t])) n++;
        int[] tris = new int[4 * n];
        int k = 0;
        for (int t = 0; t < m.triangleCount; t++) {
            if (sampler.isSkipped(m.triMaterial[t])) continue;
            tris[k++] = m.triVertex[3 * t];
            tris[k++] = m.triVertex[3 * t + 1];
            tris[k++] = m.triVertex[3 * t + 2];
            tris[k++] = m.triMaterial[t];
        }
        wireTris = tris;
        return v;
    }

    private void finish(Stage s, String text) {
        stage = s;
        status = text;
        progress = 1;
    }

    private static long stamp(File f) {
        return f.lastModified() ^ (f.length() << 20);
    }

    // ---- upload -------------------------------------------------------------------------------------

    /** Called by the GUI "Place" button on the client thread. */
    public boolean startUpload(List<MaterialSetup> materials, int[] materialRank) {
        MultiModel m = model;
        if (m == null || isBusy()) return false;
        if (uploader != null && !uploader.isFinished()) return false;
        uploader = new ImportUploader(m, materials, materialRank);
        stage = Stage.UPLOADING;
        serverStatusText = "Sending...";
        uploader.start();
        return true;
    }

    /** Called from the packet handler on the client thread. */
    public void onStatus(PacketImportStatus st) {
        serverStatus = st;
        ImportJob.State state = st.state();
        String text;
        switch (state) {
            case RECEIVING:
                text = "Placing " + st.placed + "/" + st.total + " blocks (" + (st.total == 0 ? 100 : 100L * st.placed / st.total) + "%)";
                break;
            case DONE:
                text = "Done: " + st.placed + " blocks, " + st.tiles + " tiles" + (st.skipped > 0 ? ", " + st.skipped + " skipped" : "");
                break;
            case CANCELLED:
                text = "Cancelled after " + st.placed + " blocks";
                break;
            case UNDOING:
                text = "Undoing...";
                break;
            case UNDONE:
                text = "Undone";
                break;
            default:
                text = state.toString();
        }
        if (!st.message.isEmpty()) text += " - " + st.message;
        serverStatusText = text;
        if (uploader != null) {
            uploader.onStatus(st);
            if (uploader.isFinished() && stage == Stage.UPLOADING) stage = Stage.READY;
        }
        if (state == ImportJob.State.DONE && stage == Stage.UPLOADING) stage = Stage.READY;
        if (state == ImportJob.State.DONE) placed = true;
    }

    public static void chat(String text) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player != null) mc.player.sendMessage(new TextComponentString(TextFormatting.GRAY + "[MeshTiles] " + TextFormatting.RESET + text));
    }
}
