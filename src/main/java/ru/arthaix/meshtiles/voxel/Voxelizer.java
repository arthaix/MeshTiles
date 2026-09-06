package ru.arthaix.meshtiles.voxel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import ru.arthaix.meshtiles.model.ObjMesh;

/**
 * Converts an {@link ObjMesh} into a {@link VoxelModel}.
 *
 * <p>The cell space (world coordinates in grid units) is split into cubic regions of {@link #REGION} cells.
 * Every triangle is binned into the regions its bounding box touches; regions are then processed
 * independently (and in parallel): each one is rasterised into a dense {@code char[]} buffer, which makes
 * de-duplication free, and afterwards every block inside the region is greedily merged into boxes.</p>
 *
 * <p>Rasterisation is exact per cell: a cell column (in the triangle's dominant-axis projection) is filled
 * only if the unit square overlaps the projected triangle (separating axis test), and the depth range
 * is the exact min/max of the plane over the clipped square, so the result is a watertight, 26-connected
 * shell without the over-thickening of naive conservative voxelisation.</p>
 */
public final class Voxelizer {

    /** Region edge in cells. Must be a multiple of every supported grid (1..64). */
    public static final int REGION = 128;
    private static final double EPS = 1e-9;

    public interface Progress {
        /** Return false to cancel. */
        boolean onProgress(String stage, int done, int total);
    }

    public static final class Stats {
        public int trianglesDegenerate;
        public int trianglesSkippedMaterial;
        public int regions;
        public long paletteOverflows;
        public long timeTransformMs, timeBinMs, timeRasterMs;
    }

    private Voxelizer() {}

    /** @return the model, or null when cancelled. */
    public static VoxelModel run(ObjMesh mesh, VoxelizerSettings settings, ColorSampler sampler, Progress progress, Stats stats) throws InterruptedException {
        if (stats == null) stats = new Stats();
        final int grid = settings.grid;
        if (REGION % grid != 0) throw new IllegalArgumentException("grid " + grid + " does not divide region " + REGION);

        long t0 = System.currentTimeMillis();
        // ---- 1. transform vertices into cell coordinates -------------------------------------------------
        final double[] cells = transform(mesh, settings);
        long t1 = System.currentTimeMillis();
        stats.timeTransformMs = t1 - t0;

        // ---- 2. bin triangles by region -----------------------------------------------------------------
        final Map<Long, IntList> bins = new HashMap<>();
        final int triCount = mesh.triangleCount;
        final int[] tv = mesh.triVertex;
        final int[] tm = mesh.triMaterial;
        for (int t = 0; t < triCount; t++) {
            if (sampler.isSkipped(tm[t])) {
                stats.trianglesSkippedMaterial++;
                continue;
            }
            int a = 3 * tv[3 * t], b = 3 * tv[3 * t + 1], c = 3 * tv[3 * t + 2];
            double minX = min3(cells[a], cells[b], cells[c]), maxX = max3(cells[a], cells[b], cells[c]);
            double minY = min3(cells[a + 1], cells[b + 1], cells[c + 1]), maxY = max3(cells[a + 1], cells[b + 1], cells[c + 1]);
            double minZ = min3(cells[a + 2], cells[b + 2], cells[c + 2]), maxZ = max3(cells[a + 2], cells[b + 2], cells[c + 2]);
            if (Double.isNaN(minX + minY + minZ + maxX + maxY + maxZ)) {
                stats.trianglesDegenerate++;
                continue;
            }
            // a face lying exactly on a cell boundary (min == max == integer) still touches the cell below it
            int rx0 = Math.floorDiv((int) Math.floor(minX) - 1, REGION), rx1 = Math.floorDiv(Math.max(lastCell(maxX), (int) Math.floor(minX)), REGION);
            int ry0 = Math.floorDiv((int) Math.floor(minY) - 1, REGION), ry1 = Math.floorDiv(Math.max(lastCell(maxY), (int) Math.floor(minY)), REGION);
            int rz0 = Math.floorDiv((int) Math.floor(minZ) - 1, REGION), rz1 = Math.floorDiv(Math.max(lastCell(maxZ), (int) Math.floor(minZ)), REGION);
            for (int rz = rz0; rz <= rz1; rz++)
                for (int ry = ry0; ry <= ry1; ry++)
                    for (int rx = rx0; rx <= rx1; rx++) {
                        long key = BlockKey.pack(rx, ry, rz);
                        IntList list = bins.get(key);
                        if (list == null) bins.put(key, list = new IntList());
                        list.add(t);
                    }
            if (progress != null && (t & 0xfffff) == 0 && !progress.onProgress("bin", t, triCount)) return null;
        }
        long t2 = System.currentTimeMillis();
        stats.timeBinMs = t2 - t1;
        stats.regions = bins.size();

        // ---- 3. rasterise regions in parallel -----------------------------------------------------------
        final VoxelPalette palette = new VoxelPalette();
        final int materialCount = mesh.materials.size();
        final int[] flatIds = new int[materialCount];
        for (int m = 0; m < materialCount; m++)
            flatIds[m] = Math.max(1, palette.idFor(m, sampler.flatColor(m)));

        final VoxelModel model = new VoxelModel(grid, palette);
        final List<Long> regionKeys = new ArrayList<>(bins.keySet());
        final AtomicInteger done = new AtomicInteger();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicLong overflows = new AtomicLong();
        final int total = regionKeys.size();
        int threads = settings.threads > 0 ? settings.threads : Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        threads = Math.min(threads, Math.max(1, total));
        final AtomicInteger next = new AtomicInteger();
        final Stats fstats = stats;
        // an odd number of mirrors inverts the winding, so outward normals point inward: compensate
        final boolean flipNormals = settings.mirrorX ^ settings.mirrorY ^ settings.mirrorZ;

        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread th = new Thread(r, "meshtiles-voxelizer");
            th.setDaemon(true);
            return th;
        });
        List<Future<?>> futures = new ArrayList<>();
        for (int w = 0; w < threads; w++) {
            futures.add(pool.submit(() -> {
                Worker worker = new Worker(mesh, cells, sampler, palette, flatIds, grid, model, overflows, settings.materialPriority, flipNormals);
                int idx;
                while (!cancelled.get() && (idx = next.getAndIncrement()) < total) {
                    long key = regionKeys.get(idx);
                    worker.processRegion(BlockKey.x(key), BlockKey.y(key), BlockKey.z(key), bins.get(key));
                    int d = done.incrementAndGet();
                    if (progress != null && !progress.onProgress("raster", d, total))
                        cancelled.set(true);
                }
            }));
        }
        pool.shutdown();
        try {
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                    if (cause instanceof Error) throw (Error) cause;
                    throw new RuntimeException(cause);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        fstats.paletteOverflows = overflows.get();
        fstats.timeRasterMs = System.currentTimeMillis() - t2;
        return cancelled.get() ? null : model;
    }

    // ---------------------------------------------------------------------------------------------------

    /**
     * Applies scale, axis conversion, mirroring, alignment, rotation and origin. Result is in cells (grid units).
     * Mirroring flips the model inside its own bounding box (the box does not move). The importer block (origin)
     * is the pivot: with alignXZ/alignY the model's minimum corner is put on the origin first and the rotation
     * then swings the whole model around that block.
     */
    public static double[] transform(ObjMesh mesh, VoxelizerSettings s) {
        int n = mesh.vertexCount;
        double[] out = new double[3 * n];
        float[] p = mesh.positions;
        // only vertices used by imported triangles define the bounding box (stray/unused vertices and skipped materials don't)
        boolean[] used = new boolean[n];
        boolean anyUsed = false;
        for (int t = 0; t < mesh.triangleCount; t++) {
            int m = mesh.triMaterial[t];
            if (s.alignmentMaterials != null) {
                if (m < s.alignmentMaterials.length && !s.alignmentMaterials[m]) continue;
            } else if (s.skippedMaterials != null && m < s.skippedMaterials.length && s.skippedMaterials[m]) continue;
            used[mesh.triVertex[3 * t]] = used[mesh.triVertex[3 * t + 1]] = used[mesh.triVertex[3 * t + 2]] = true;
            anyUsed = true;
        }
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            double x = p[3 * i] * s.scale, y = p[3 * i + 1] * s.scale, z = p[3 * i + 2] * s.scale;
            if (s.upAxis == VoxelizerSettings.UpAxis.Z_UP) {
                double ny = z, nz = -y;
                y = ny;
                z = nz;
            }
            out[3 * i] = x;
            out[3 * i + 1] = y;
            out[3 * i + 2] = z;
            if (anyUsed && !used[i]) continue;
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
        }
        if (n > 0 && (s.mirrorX || s.mirrorY || s.mirrorZ)) {
            for (int i = 0; i < n; i++) {
                if (s.mirrorX) out[3 * i] = minX + maxX - out[3 * i];
                if (s.mirrorY) out[3 * i + 1] = minY + maxY - out[3 * i + 1];
                if (s.mirrorZ) out[3 * i + 2] = minZ + maxZ - out[3 * i + 2];
            }
        }
        double ax = s.alignXZ && n > 0 ? minX : 0;
        double ay = s.alignY && n > 0 ? minY : 0;
        double az = s.alignXZ && n > 0 ? minZ : 0;
        double ox = s.originX + s.offsetX, oy = s.originY + s.offsetY, oz = s.originZ + s.offsetZ;
        int rot = s.rotation & 3;
        for (int i = 0; i < n; i++) {
            double x = out[3 * i] - ax, y = out[3 * i + 1] - ay, z = out[3 * i + 2] - az;
            for (int r = 0; r < rot; r++) {
                double nx = -z, nz = x;
                x = nx;
                z = nz;
            }
            out[3 * i] = (x + ox) * s.grid;
            out[3 * i + 1] = (y + oy) * s.grid;
            out[3 * i + 2] = (z + oz) * s.grid;
        }
        return out;
    }

    /** Index of the last cell touched by an extent ending at {@code max} (an exact integer boundary touches nothing beyond). */
    static int lastCell(double max) {
        return (int) Math.ceil(max) - 1;
    }

    private static double min3(double a, double b, double c) {
        return Math.min(a, Math.min(b, c));
    }

    private static double max3(double a, double b, double c) {
        return Math.max(a, Math.max(b, c));
    }

    static final class IntList {
        int[] data = new int[16];
        int size;

        void add(int v) {
            if (size == data.length) data = Arrays.copyOf(data, size * 2);
            data[size++] = v;
        }
    }

    // ---------------------------------------------------------------------------------------------------

    /** Per-thread rasteriser with its own dense region buffer. */
    private static final class Worker {
        private final ObjMesh mesh;
        private final double[] cells;
        private final ColorSampler sampler;
        private final VoxelPalette palette;
        private final int[] flatIds;
        private final int grid;
        private final VoxelModel model;
        private final AtomicLong overflows;

        private final char[] buf = new char[REGION * REGION * REGION];
        private final boolean[] touched;
        private final int blocksPerRegion;
        private final boolean[] scratch;
        private final long[][] boxesOut = new long[][] { new long[1024] };

        // direct-mapped palette cache
        private final int[] cacheMat = new int[4096];
        private final int[] cacheCol = new int[4096];
        private final int[] cacheId = new int[4096];

        // material priority per tile id (lower wins); filled lazily, ids only ever come from this worker
        private final int[] priority;
        private final int[] idPriority = new int[VoxelPalette.MAX_IDS + 1];
        private final boolean flipNormals;

        // current region origin in cells
        private int rx0, ry0, rz0;

        // per-triangle state (dominant-axis projection)
        private double ua, va, wa, ub, vb, wb, uc, vc, wc;
        private double gu, gv;          // plane gradients: w = wa + gu*(u-ua) + gv*(v-va)
        private double e0A, e0B, e0C;   // edge functions E(u,v) = A*v - B*u + C  (>= 0 inside)
        private double e1A, e1B, e1C;
        private double e2A, e2B, e2C;
        private double area2;
        private final double[] cand = new double[24];

        Worker(ObjMesh mesh, double[] cells, ColorSampler sampler, VoxelPalette palette, int[] flatIds, int grid, VoxelModel model, AtomicLong overflows, int[] priority, boolean flipNormals) {
            this.mesh = mesh;
            this.priority = priority;
            this.flipNormals = flipNormals;
            Arrays.fill(idPriority, Integer.MAX_VALUE);
            for (int m = 0; m < flatIds.length; m++)
                idPriority[flatIds[m]] = priorityOf(m);
            this.cells = cells;
            this.sampler = sampler;
            this.palette = palette;
            this.flatIds = flatIds;
            this.grid = grid;
            this.model = model;
            this.overflows = overflows;
            this.blocksPerRegion = REGION / grid;
            this.touched = new boolean[blocksPerRegion * blocksPerRegion * blocksPerRegion];
            this.scratch = new boolean[grid * grid * grid];
            Arrays.fill(cacheMat, -1);
        }

        void processRegion(int rx, int ry, int rz, IntList tris) {
            Arrays.fill(buf, (char) 0);
            Arrays.fill(touched, false);
            rx0 = rx * REGION;
            ry0 = ry * REGION;
            rz0 = rz * REGION;
            for (int i = 0; i < tris.size; i++)
                rasterize(tris.data[i]);

            int bpr = blocksPerRegion;
            for (int bz = 0; bz < bpr; bz++)
                for (int by = 0; by < bpr; by++)
                    for (int bx = 0; bx < bpr; bx++) {
                        if (!touched[bx + bpr * (by + bpr * bz)]) continue;
                        int n = BoxMerger.mergeBlock(buf, REGION, bx * grid, by * grid, bz * grid, grid, scratch, boxesOut);
                        if (n == 0) continue;
                        long[] boxes = Arrays.copyOf(boxesOut[0], n);
                        model.putBlock(rx * bpr + bx, ry * bpr + by, rz * bpr + bz, boxes);
                    }
        }

        private int priorityOf(int material) {
            return priority == null || material >= priority.length ? Integer.MAX_VALUE : priority[material];
        }

        private int paletteId(int material, int color) {
            int h = (material * 31 + color * 0x9E3779B1) & 4095;
            if (cacheMat[h] == material && cacheCol[h] == color) return cacheId[h];
            int id;
            synchronized (palette) {
                id = palette.idFor(material, color);
            }
            if (id < 0) {
                overflows.incrementAndGet();
                id = flatIds[material];
            }
            idPriority[id] = priorityOf(material);
            cacheMat[h] = material;
            cacheCol[h] = color;
            cacheId[h] = id;
            return id;
        }

        private void rasterize(int t) {
            int[] tv = mesh.triVertex;
            int ia = 3 * tv[3 * t], ib = 3 * tv[3 * t + 1], ic = 3 * tv[3 * t + 2];
            double ax = cells[ia], ay = cells[ia + 1], az = cells[ia + 2];
            double bx = cells[ib], by = cells[ib + 1], bz = cells[ib + 2];
            double cx = cells[ic], cy = cells[ic + 1], cz = cells[ic + 2];
            double nx = (by - ay) * (cz - az) - (bz - az) * (cy - ay);
            double ny = (bz - az) * (cx - ax) - (bx - ax) * (cz - az);
            double nz = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
            double anx = Math.abs(nx), any = Math.abs(ny), anz = Math.abs(nz);
            if (anx < EPS && any < EPS && anz < EPS) return; // degenerate

            int material = mesh.triMaterial[t];
            int axis; // dominant axis = w
            double nu, nv, nw;
            if (anx >= any && anx >= anz) { // w = X, u = Y, v = Z
                axis = 0;
                ua = ay; va = az; wa = ax; ub = by; vb = bz; wb = bx; uc = cy; vc = cz; wc = cx;
                nu = ny; nv = nz; nw = nx;
            } else if (any >= anz) {        // w = Y, u = Z, v = X
                axis = 1;
                ua = az; va = ax; wa = ay; ub = bz; vb = bx; wb = by; uc = cz; vc = cx; wc = cy;
                nu = nz; nv = nx; nw = ny;
            } else {                        // w = Z, u = X, v = Y
                axis = 2;
                ua = ax; va = ay; wa = az; ub = bx; vb = by; wb = bz; uc = cx; vc = cy; wc = cz;
                nu = nx; nv = ny; nw = nz;
            }
            gu = -nu / nw;
            gv = -nv / nw;

            // ensure counter-clockwise winding in (u,v)
            area2 = (ub - ua) * (vc - va) - (vb - va) * (uc - ua);
            boolean swapped = false;
            if (area2 < 0) {
                double tu = ub, tvv = vb, tw = wb;
                ub = uc; vb = vc; wb = wc;
                uc = tu; vc = tvv; wc = tw;
                area2 = -area2;
                swapped = true;
            }
            if (area2 < EPS) return;

            // edge functions: E_ab(p) = (ub-ua)*(p.v-va) - (vb-va)*(p.u-ua) = A*p.v - B*p.u + C
            e0A = ub - ua; e0B = vb - va; e0C = -e0A * va + e0B * ua;
            e1A = uc - ub; e1B = vc - vb; e1C = -e1A * vb + e1B * ub;
            e2A = ua - uc; e2B = va - vc; e2C = -e2A * vc + e2B * uc;

            // uv for colour sampling
            boolean textured = sampler.isTextured(material);
            float uva = 0, uvb = 0, uvc = 0, vva = 0, vvb = 0, vvc = 0;
            int flatId = flatIds[material];
            if (textured) {
                int[] tuv = mesh.triUv;
                int ta = tuv[3 * t], tb = tuv[3 * t + 1], tc = tuv[3 * t + 2];
                if (ta < 0 || tb < 0 || tc < 0) {
                    textured = false;
                } else {
                    if (swapped) {
                        int tmp = tb;
                        tb = tc;
                        tc = tmp;
                    }
                    float[] tex = mesh.texCoords;
                    uva = tex[2 * ta]; vva = tex[2 * ta + 1];
                    uvb = tex[2 * tb]; vvb = tex[2 * tb + 1];
                    uvc = tex[2 * tc]; vvc = tex[2 * tc + 1];
                }
            }

            // region bounds in (u,v,w)
            int ru0, rv0, rw0;
            if (axis == 0) { ru0 = ry0; rv0 = rz0; rw0 = rx0; }
            else if (axis == 1) { ru0 = rz0; rv0 = rx0; rw0 = ry0; }
            else { ru0 = rx0; rv0 = ry0; rw0 = rz0; }

            double umin = min3(ua, ub, uc), umax = max3(ua, ub, uc);
            double vmin = min3(va, vb, vc), vmax = max3(va, vb, vc);
            int iu0 = Math.max((int) Math.floor(umin), ru0), iu1 = Math.min(lastCell(umax), ru0 + REGION - 1);
            int iv0 = Math.max((int) Math.floor(vmin), rv0), iv1 = Math.min(lastCell(vmax), rv0 + REGION - 1);
            if (iu0 > iu1 || iv0 > iv1) return;
            int kw0 = rw0, kw1 = rw0 + REGION - 1;

            for (int j = iv0; j <= iv1; j++) {
                double v0 = j, v1 = j + 1;
                for (int i = iu0; i <= iu1; i++) {
                    double u0 = i, u1 = i + 1;
                    // SAT: for each edge the square's most-inside corner must be inside
                    if (e0A * (e0A > 0 ? v1 : v0) - e0B * (e0B < 0 ? u1 : u0) + e0C <= EPS) continue;
                    if (e1A * (e1A > 0 ? v1 : v0) - e1B * (e1B < 0 ? u1 : u0) + e1C <= EPS) continue;
                    if (e2A * (e2A > 0 ? v1 : v0) - e2B * (e2B < 0 ? u1 : u0) + e2C <= EPS) continue;

                    // exact depth range of the plane over the clipped square
                    int nc = collectCandidates(u0, u1, v0, v1);
                    double wmin, wmax;
                    if (nc == 0) {
                        wmin = wmax = wAt(u0 + 0.5, v0 + 0.5);
                    } else {
                        wmin = Double.POSITIVE_INFINITY;
                        wmax = Double.NEGATIVE_INFINITY;
                        for (int q = 0; q < nc; q++) {
                            double w = cand[q];
                            if (w < wmin) wmin = w;
                            if (w > wmax) wmax = w;
                        }
                    }
                    int k0 = (int) Math.floor(wmin);
                    int k1 = lastCell(wmax);
                    if (k1 < k0) {
                        // plane lies exactly on a cell boundary: put the voxel on the solid side (opposite the normal)
                        if ((nw > 0) != flipNormals) k0 = k1; else k1 = k0;
                    }
                    if (k0 < kw0) k0 = kw0;
                    if (k1 > kw1) k1 = kw1;
                    if (k0 > k1) continue;

                    int id = flatId;
                    if (textured) {
                        // barycentric coordinates of the cell centre, clamped into the triangle
                        double pu = u0 + 0.5, pv = v0 + 0.5;
                        double lc = (e0A * pv - e0B * pu + e0C) / area2; // weight of c (opposite edge ab)
                        double la = (e1A * pv - e1B * pu + e1C) / area2; // weight of a (opposite edge bc)
                        double lb = (e2A * pv - e2B * pu + e2C) / area2; // weight of b (opposite edge ca)
                        if (la < 0) la = 0;
                        if (lb < 0) lb = 0;
                        if (lc < 0) lc = 0;
                        double sum = la + lb + lc;
                        if (sum <= 0) { la = 1; lb = 0; lc = 0; sum = 1; }
                        float u = (float) ((la * uva + lb * uvb + lc * uvc) / sum);
                        float vv = (float) ((la * vva + lb * vvb + lc * vvc) / sum);
                        id = paletteId(material, sampler.sample(material, u, vv));
                    }

                    int lu = i - ru0, lv = j - rv0;
                    int prio = idPriority[id];
                    // a lower-priority surface never covers a higher-priority one: if any cell of this column already
                    // holds a higher-priority material (coplanar road markings on asphalt, for example), skip the column
                    boolean blocked = false;
                    for (int k = k0; k <= k1 && !blocked; k++) {
                        int lw = k - rw0;
                        int x, y, z;
                        if (axis == 0) { x = lw; y = lu; z = lv; }
                        else if (axis == 1) { x = lv; y = lw; z = lu; }
                        else { x = lu; y = lv; z = lw; }
                        int cur = buf[x + REGION * (y + REGION * z)];
                        if (cur != 0 && idPriority[cur] < prio) blocked = true;
                    }
                    if (blocked) continue;
                    for (int k = k0; k <= k1; k++) {
                        int lw = k - rw0;
                        int x, y, z;
                        if (axis == 0) { x = lw; y = lu; z = lv; }
                        else if (axis == 1) { x = lv; y = lw; z = lu; }
                        else { x = lu; y = lv; z = lw; }
                        int idx = x + REGION * (y + REGION * z);
                        int cur = buf[idx];
                        if (cur == 0) {
                            buf[idx] = (char) id;
                            touched[(x / grid) + blocksPerRegion * ((y / grid) + blocksPerRegion * (z / grid))] = true;
                        } else if (prio < idPriority[cur]) {
                            buf[idx] = (char) id; // higher-priority material overrides
                        }
                    }
                }
            }
        }

        private double wAt(double u, double v) {
            return wa + gu * (u - ua) + gv * (v - va);
        }

        private boolean inside(double u, double v) {
            return e0A * v - e0B * u + e0C >= -EPS
                && e1A * v - e1B * u + e1C >= -EPS
                && e2A * v - e2B * u + e2C >= -EPS;
        }

        /** Vertices of (square ∩ triangle): square corners inside, triangle vertices inside, edge/side crossings. */
        private int collectCandidates(double u0, double u1, double v0, double v1) {
            int n = 0;
            if (inside(u0, v0)) cand[n++] = wAt(u0, v0);
            if (inside(u1, v0)) cand[n++] = wAt(u1, v0);
            if (inside(u0, v1)) cand[n++] = wAt(u0, v1);
            if (inside(u1, v1)) cand[n++] = wAt(u1, v1);
            if (ua >= u0 && ua <= u1 && va >= v0 && va <= v1) cand[n++] = wa;
            if (ub >= u0 && ub <= u1 && vb >= v0 && vb <= v1) cand[n++] = wb;
            if (uc >= u0 && uc <= u1 && vc >= v0 && vc <= v1) cand[n++] = wc;
            n = edgeCrossings(ua, va, ub, vb, u0, u1, v0, v1, n);
            n = edgeCrossings(ub, vb, uc, vc, u0, u1, v0, v1, n);
            n = edgeCrossings(uc, vc, ua, va, u0, u1, v0, v1, n);
            return n;
        }

        private int edgeCrossings(double pu, double pv, double qu, double qv, double u0, double u1, double v0, double v1, int n) {
            double du = qu - pu, dv = qv - pv;
            if (du != 0) {
                double t = (u0 - pu) / du;
                if (t >= 0 && t <= 1) {
                    double v = pv + t * dv;
                    if (v >= v0 && v <= v1 && n < cand.length) cand[n++] = wAt(u0, v);
                }
                t = (u1 - pu) / du;
                if (t >= 0 && t <= 1) {
                    double v = pv + t * dv;
                    if (v >= v0 && v <= v1 && n < cand.length) cand[n++] = wAt(u1, v);
                }
            }
            if (dv != 0) {
                double t = (v0 - pv) / dv;
                if (t >= 0 && t <= 1) {
                    double u = pu + t * du;
                    if (u >= u0 && u <= u1 && n < cand.length) cand[n++] = wAt(u, v0);
                }
                t = (v1 - pv) / dv;
                if (t >= 0 && t <= 1) {
                    double u = pu + t * du;
                    if (u >= u0 && u <= u1 && n < cand.length) cand[n++] = wAt(u, v1);
                }
            }
            return n;
        }
    }
}
