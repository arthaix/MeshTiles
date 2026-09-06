package ru.arthaix.meshtiles.voxel;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

import javax.imageio.ImageIO;

import ru.arthaix.meshtiles.model.MtlLibrary;

/**
 * Resolves the colour of a voxel for a material: none (-1), flat Kd, or a texture sample at (u,v).
 * Textures are loaded once per material into int[] pixel arrays; sampling is nearest-neighbour with wrap.
 * Colours are quantised to {@code levels} steps per channel so that neighbouring voxels share ids and merge into boxes.
 */
public final class ColorSampler {

    private final MaterialSetup[] setups;
    private final int[] flat;          // colour used when there is no texture
    private final int[][] pixels;      // per material, null when not textured
    private final int[] texW, texH;
    private final int levels;
    private final int[] quantTable = new int[256];

    public ColorSampler(List<MaterialSetup> materials, MtlLibrary mtl, File objFile, int levels, List<String> problems) {
        int n = materials.size();
        this.setups = materials.toArray(new MaterialSetup[n]);
        this.flat = new int[n];
        this.pixels = new int[n][];
        this.texW = new int[n];
        this.texH = new int[n];
        this.levels = Math.max(2, Math.min(256, levels));
        for (int c = 0; c < 256; c++) {
            int q = Math.round(c * (this.levels - 1) / 255f);
            quantTable[c] = Math.round(q * 255f / (this.levels - 1));
        }
        File dir = objFile.getAbsoluteFile().getParentFile();
        for (int i = 0; i < n; i++) {
            MaterialSetup s = setups[i];
            MtlLibrary.Material m = mtl == null ? null : mtl.materials.get(s.name);
            switch (s.mode) {
                case NONE:
                    flat[i] = -1;
                    break;
                case KD:
                    flat[i] = s.color;
                    break;
                case TEXTURE: {
                    String path = s.texturePath;
                    if ((path == null || path.isEmpty()) && m != null) path = m.mapKd;
                    flat[i] = s.color;
                    if (path == null || path.isEmpty()) {
                        problems.add("material '" + s.name + "': no texture (map_Kd) found, using flat colour");
                        break;
                    }
                    File f = new File(path);
                    if (!f.isAbsolute()) f = new File(dir, path);
                    try {
                        BufferedImage img = ImageIO.read(f);
                        if (img == null) throw new IOException("unsupported image format");
                        int w = img.getWidth(), h = img.getHeight();
                        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
                        // Kd and the material alpha tint the texture (Wavefront convention)
                        if (m != null) {
                            float kr = clamp(m.kdR), kg = clamp(m.kdG), kb = clamp(m.kdB), ka = clamp(m.alpha);
                            if (kr != 1f || kg != 1f || kb != 1f || ka != 1f) {
                                for (int p = 0; p < px.length; p++) {
                                    int c = px[p];
                                    int a = Math.round(((c >>> 24) & 255) * ka);
                                    int r = Math.round(((c >>> 16) & 255) * kr);
                                    int g = Math.round(((c >>> 8) & 255) * kg);
                                    int b = Math.round((c & 255) * kb);
                                    px[p] = (a << 24) | (r << 16) | (g << 8) | b;
                                }
                            }
                        }
                        pixels[i] = px;
                        texW[i] = w;
                        texH[i] = h;
                    } catch (IOException e) {
                        problems.add("material '" + s.name + "': cannot load texture " + f + " (" + e.getMessage() + "), using flat colour");
                    }
                    break;
                }
            }
        }
    }

    private static float clamp(float f) {
        return f < 0 ? 0 : f > 1 ? 1 : f;
    }

    public boolean isTextured(int material) {
        return pixels[material] != null;
    }

    public boolean isSkipped(int material) {
        return skipMask != null ? skipMask[material] : setups[material].skip;
    }

    private boolean[] skipMask;

    /** A view of this sampler that reports the given materials as skipped (shares the loaded textures). */
    public ColorSampler withSkipped(boolean[] mask) {
        ColorSampler c = new ColorSampler(this);
        c.skipMask = mask;
        return c;
    }

    private ColorSampler(ColorSampler o) {
        this.setups = o.setups;
        this.flat = o.flat;
        this.pixels = o.pixels;
        this.texW = o.texW;
        this.texH = o.texH;
        this.levels = o.levels;
        System.arraycopy(o.quantTable, 0, this.quantTable, 0, 256);
    }

    /** Colour for a material without uv information (or with mode NONE / KD). */
    public int flatColor(int material) {
        int c = flat[material];
        return c == -1 ? -1 : quantize(c);
    }

    /** Colour for a textured material at (u, v). Falls back to the flat colour when not textured. */
    public int sample(int material, float u, float v) {
        int[] px = pixels[material];
        if (px == null) return flatColor(material);
        int w = texW[material], h = texH[material];
        float fu = u - (float) Math.floor(u);
        float fv = v - (float) Math.floor(v);
        int x = (int) (fu * w);
        int y = (int) ((1f - fv) * h);
        if (x >= w) x = w - 1;
        if (y >= h) y = h - 1;
        if (y < 0) y = 0;
        return quantize(px[y * w + x]);
    }

    public int quantize(int argb) {
        if (levels >= 256) return argb;
        int a = quantTable[(argb >>> 24) & 255];
        int r = quantTable[(argb >>> 16) & 255];
        int g = quantTable[(argb >>> 8) & 255];
        int b = quantTable[argb & 255];
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
