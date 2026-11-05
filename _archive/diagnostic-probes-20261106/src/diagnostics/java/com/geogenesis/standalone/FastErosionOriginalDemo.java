package com.geogenesis.standalone;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;

/**
 * Reference render of the approved <b>Fast Erosion + Derivatives</b> prototype.
 *
 * <p>Source of truth (unmodified):
 * {@code 参考/sources/骨架侵蚀参考项目/Fast Erosion + Derivatives（快速侵蚀 + 导数）/}
 * {@code Common.txt} + {@code lmage.txt}. This class only supplies the prototype's own base noise
 * and display mapping; the erosion itself lives in {@link FastErosionFilter}, which terrain types
 * reuse with their own height and derivatives.</p>
 *
 * <p>The base noise here is <b>not</b> project terrain — it exists so the algorithm can be
 * reproduced exactly as published. Do not treat these numbers as a landform.</p>
 *
 * <h3>Fidelity rules</h3>
 * <ul>
 *   <li>32-bit {@code float} math in the original statement order.</li>
 *   <li>{@code uint}/{@code uvec2} arithmetic emulated on Java {@code int}.</li>
 *   <li>Transcendentals use {@link StrictMath} (fdlibm) so a rerun is reproducible.</li>
 *   <li>Output is the height field only, using the prototype's {@code 0.5 + 0.5*h} mapping.</li>
 * </ul>
 */
public final class FastErosionOriginalDemo {

    /** Frozen window and timestamp of the approved reference frame. */
    static final int WIDTH = 1165, HEIGHT = 654;
    static final float TIME = 67.57f;
    /** Prototype base-noise constants (base amplitude and frequency of the reference). */
    static final float BASE_AMP = 4e-4f;
    static final int BASE_FREQ = 9;

    private FastErosionOriginalDemo() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("usage: <outDir> [INTENT|ONES|MINUS_ONE]");
        }
        Path out = Path.of(args[0]);
        if (Files.exists(out)) throw new IllegalArgumentException("refuse overwrite " + out);
        if (args.length > 1) FastErosionFilter.wrapMode = FastErosionFilter.WrapMode.valueOf(args[1]);
        Files.createDirectories(out);
        long start = System.nanoTime();
        int[] argb = renderAll();
        long millis = (System.nanoTime() - start) / 1_000_000;
        Path png = out.resolve("height.png");
        writePng(png, argb, WIDTH, HEIGHT);
        Files.write(out.resolve("height.rgba"), toRgba(argb));
        String report = "window: " + WIDTH + "x" + HEIGHT + " samples, iTime=" + TIME + "\n"
                + "display: full-frame grayscale height, gray = 0.5 + 0.5*h\n"
                + "base noise: prototype's own simplex (amp " + BASE_AMP + ", freq " + BASE_FREQ
                + ") -- reference only, NOT project terrain\n"
                + "erosion: FastErosionFilter.ORIGINAL (unchanged published parameters)\n"
                + "wrapMode: " + FastErosionFilter.wrapMode + "\n"
                + "render time: " + millis + " ms\n"
                + "png sha256: " + sha(png) + "\n";
        Files.writeString(out.resolve("render-report.txt"), report);
        System.out.print(report);
    }

    // ================= frame rendering =================
    static int[] renderAll() {
        int[] pixels = new int[WIDTH * HEIGHT];
        IntStream.range(0, HEIGHT).parallel().forEach(row ->
                IntStream.range(0, WIDTH).forEach(col ->
                        pixels[row * WIDTH + col] = shade(col, row)));
        return pixels;
    }

    private static int shade(int col, int row) {
        float fx = col + 0.5f, fy = (HEIGHT - 1 - row) + 0.5f;
        int px = gridX(fx), pz = gridZ(fy);
        float[] height = heightAt(px, pz);
        int gray = unorm(0.5f + height[0] * 0.5f);
        return 0xFF000000 | (gray << 16) | (gray << 8) | gray;
    }

    static int unorm(float v) {
        float c = v < 0f ? 0f : (v > 1f ? 1f : v);
        return Math.round(c * 255f);
    }

    // ================= lmage.txt: prototype geometry =================
    static int gridX(float fragX) {
        int px = (int) fragX - (WIDTH >> 1);
        return px < 0 ? (1 << 29) + px : px;
    }

    static int gridZ(float fragY) {
        int pz = (int) (fragY + TIME * 50f) - (HEIGHT >> 1);
        return pz < 0 ? (1 << 28) + pz : pz;
    }

    /** Height channels {value, d/dx, d/dy} of the prototype frame at one sample. */
    static float[] heightAt(int px, int pz) {
        float[] base = new float[4];
        wrappedSnoise2(px, pz, BASE_FREQ, base);
        for (int i = 0; i < 4; i++) base[i] *= BASE_AMP;
        float[] erosion = new float[3];
        FastErosionFilter.erosion(px, pz, FastErosionOriginalDemo::baseDerivatives,
                FastErosionFilter.ORIGINAL, erosion);
        // Kept as the prototype writes it: base/BASE_NOISE_AMP, so the float round trip stays.
        return new float[]{
                base[0] / BASE_AMP + erosion[0] * FastErosionFilter.ORIGINAL.amplitude(),
                base[1] / BASE_AMP + erosion[1] * FastErosionFilter.ORIGINAL.amplitude(),
                base[2] / BASE_AMP + erosion[2] * FastErosionFilter.ORIGINAL.amplitude()};
    }

    /** Derivative probe for {@link FastErosionFilter}: the prototype base noise, scaled. */
    static void baseDerivatives(int px, int pz, float[] out) {
        float[] n = new float[4];
        wrappedSnoise2(px, pz, BASE_FREQ, n);
        out[0] = n[1] * BASE_AMP;
        out[1] = n[2] * BASE_AMP;
        out[2] = n[3] * BASE_AMP;
    }

    // ================= Common.txt: wrapped simplex noise =================
    static final int[] PERM = {
            151,160,137,91,90,15,131,13,201,95,96,53,194,233,7,225,140,36,103,30,69,142,8,99,37,
            240,21,10,23,190,6,148,247,120,234,75,0,26,197,62,94,252,219,203,117,35,11,32,57,177,
            33,88,237,149,56,87,174,20,125,136,171,168,68,175,74,165,71,134,139,48,27,166,77,146,
            158,231,83,111,229,122,60,211,133,230,220,105,92,41,55,46,245,40,244,102,143,54,65,25,
            63,161,1,216,80,73,209,76,132,187,208,89,18,169,200,196,135,130,116,188,159,86,164,100,
            109,198,173,186,3,64,52,217,226,250,124,123,5,202,38,147,118,126,255,82,85,212,207,206,
            59,227,47,16,58,17,182,189,28,42,223,183,170,213,119,248,152,2,44,154,163,70,221,153,
            101,155,167,43,172,9,129,22,39,253,19,98,108,110,79,113,224,232,178,185,112,104,218,
            246,97,228,251,34,242,193,238,210,144,12,191,179,162,241,81,51,145,235,249,14,239,107,
            49,192,214,31,181,199,106,157,184,84,204,176,115,121,50,45,127,4,150,254,138,236,205,
            93,222,114,67,29,24,72,243,141,128,195,78,66,215,61,156,180,
            151,160,137,91,90,15,131,13,201,95,96,53,194,233,7,225,140,36,103,30,69,142,8,99,37,
            240,21,10,23,190,6,148,247,120,234,75,0,26,197,62,94,252,219,203,117,35,11,32,57,177,
            33,88,237,149,56,87,174,20,125,136,171,168,68,175,74,165,71,134,139,48,27,166,77,146,
            158,231,83,111,229,122,60,211,133,230,220,105,92,41,55,46,245,40,244,102,143,54,65,25,
            63,161,1,216,80,73,209,76,132,187,208,89,18,169,200,196,135,130,116,188,159,86,164,100,
            109,198,173,186,3,64,52,217,226,250,124,123,5,202,38,147,118,126,255,82,85,212,207,206,
            59,227,47,16,58,17,182,189,28,42,223,183,170,213,119,248,152,2,44,154,163,70,221,153,
            101,155,167,43,172,9,129,22,39,253,19,98,108,110,79,113,224,232,178,185,112,104,218,
            246,97,228,251,34,242,193,238,210,144,12,191,179,162,241,81,51,145,235,249,14,239,107,
            49,192,214,31,181,199,106,157,184,84,204,176,115,121,50,45,127,4,150,254,138,236,205,
            93,222,114,67,29,24,72,243,141,128,195,78,66,215,61,156,180
    };

    static final float[][] GRAD2 = {
            {-1f, -1f}, {1f, 0f}, {-1f, 0f}, {1f, 1f},
            {-1f, 1f}, {0f, -1f}, {0f, 1f}, {1f, -1f}
    };

    /** Writes {@code vec4(final, gradient.xy, laplacian)} into {@code dst}. */
    static void wrappedSnoise2(int p, int q, int freqShift, float[] dst) {
        float frequency = 1f / (float) (1 << freqShift);

        int t = uIf2(p, q);
        int ijx = (p + t) >>> freqShift;
        int ijz = (q + t) >>> freqShift;

        int vx = ijx << freqShift, vz = ijz << freqShift;
        int ig = uIg2(vx, vz);
        int p0x = vx - ig, p0z = vz - ig;
        int ic0x = p - p0x, ic0z = q - p0z;

        boolean xGreater = ic0x > ic0z;
        int jx = ijx + (xGreater ? 1 : 0), jz = ijz + (xGreater ? 0 : 1);
        int ux = jx << freqShift, uz = jz << freqShift;
        int ig1 = uIg2(ux, uz);
        int p1x = ux - ig1, p1z = uz - ig1;
        int ic1x = p - p1x, ic1z = q - p1z;

        int wx = (ijx + 1) << freqShift, wz = (ijz + 1) << freqShift;
        int ig2 = uIg2(wx, wz);
        int p2x = wx - ig2, p2z = wz - ig2;
        int ic2x = p - p2x, ic2z = q - p2z;

        p0x &= (1 << 29) - 1;
        p0z &= (1 << 28) - 1;
        p1x &= (1 << 29) - 1;
        p1z &= (1 << 28) - 1;
        p2x &= (1 << 29) - 1;
        p2z &= (1 << 28) - 1;

        float c0x = ic0x * frequency, c0z = ic0z * frequency;
        float c1x = ic1x * frequency, c1z = ic1z * frequency;
        float c2x = ic2x * frequency, c2z = ic2z * frequency;

        int ij0x = ((p0x + uIf2(p0x, p0z)) >>> freqShift) & 255;
        int ij0z = ((p0z + uIf2(p0x, p0z)) >>> freqShift) & 255;
        int ij1x = ((p1x + uIf2(p1x, p1z)) >>> freqShift) & 255;
        int ij1z = ((p1z + uIf2(p1x, p1z)) >>> freqShift) & 255;
        int ij2x = ((p2x + uIf2(p2x, p2z)) >>> freqShift) & 255;
        int ij2z = ((p2z + uIf2(p2x, p2z)) >>> freqShift) & 255;

        float s0 = c0x * c0x + c0z * c0z;
        float s1 = c1x * c1x + c1z * c1z;
        float s2 = c2x * c2x + c2z * c2z;
        float d0x = 0.5f - s0, d1x = 0.5f - s1, d2x = 0.5f - s2;
        float d0y = d0x * d0x, d0z = d0y * d0y;
        float d1y = d1x * d1x, d1z = d1y * d1y;
        float d2y = d2x * d2x, d2z = d2y * d2y;

        float g0x = 0, g0z = 0, g1x = 0, g1z = 0, g2x = 0, g2z = 0;
        float n0 = 0, n1 = 0, n2 = 0;
        if (d0x >= 0f) {
            int h = PERM[ij0x + PERM[ij0z]] & 7;
            g0x = GRAD2[h][0];
            g0z = GRAD2[h][1];
            n0 = g0x * c0x + g0z * c0z;
        }
        if (d1x >= 0f) {
            int h = PERM[ij1x + PERM[ij1z]] & 7;
            g1x = GRAD2[h][0];
            g1z = GRAD2[h][1];
            n1 = g1x * c1x + g1z * c1z;
        }
        if (d2x >= 0f) {
            int h = PERM[ij2x + PERM[ij2z]] & 7;
            g2x = GRAD2[h][0];
            g2z = GRAD2[h][1];
            n2 = g2x * c2x + g2z * c2z;
        }

        float gx = (c0x * d0x * d0y * n0 + c1x * d1x * d1y * n1 + c2x * d2x * d2y * n2) * -8f
                + (d0z * g0x + d1z * g1x + d2z * g2x);
        float gz = (c0z * d0x * d0y * n0 + c1z * d1x * d1y * n1 + c2z * d2x * d2y * n2) * -8f
                + (d0z * g0z + d1z * g1z + d2z * g2z);
        gx *= 40f * frequency;
        gz *= 40f * frequency;

        float lap = (n0 * (6f * s0 - 4f) * d0y + n1 * (6f * s1 - 4f) * d1y
                + n2 * (6f * s2 - 4f) * d2y) * 40f * -8f * frequency;

        dst[3] = lap;
        dst[2] = gz;
        dst[1] = gx;
        dst[0] = 40f * (n0 * d0z + n1 * d1z + n2 * d2z);
    }

    /** GLSL {@code IF2}: {@code ((n)*3u)/8u} with 32-bit unsigned wrap-around. */
    private static int uIf2(int a, int b) {
        long sum = ((a & 0xFFFFFFFFL) + (b & 0xFFFFFFFFL)) & 0xFFFFFFFFL;
        return (int) (((sum * 3L) & 0xFFFFFFFFL) / 8L);
    }

    /** GLSL {@code IG2}: {@code ((n)*3u)/14u} with 32-bit unsigned wrap-around. */
    private static int uIg2(int a, int b) {
        long sum = ((a & 0xFFFFFFFFL) + (b & 0xFFFFFFFFL)) & 0xFFFFFFFFL;
        return (int) (((sum * 3L) & 0xFFFFFFFFL) / 14L);
    }

    // ================= output helpers =================
    static byte[] toRgba(int[] argb) {
        byte[] rgba = new byte[argb.length * 4];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            rgba[i * 4] = (byte) (p >>> 16);
            rgba[i * 4 + 1] = (byte) (p >>> 8);
            rgba[i * 4 + 2] = (byte) p;
            rgba[i * 4 + 3] = (byte) (p >>> 24);
        }
        return rgba;
    }

    static void writePng(Path path, int[] argb, int w, int h) throws IOException {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, w, h, argb, 0, w);
        ImageIO.write(image, "png", path.toFile());
    }

    static String sha(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
    }
}
