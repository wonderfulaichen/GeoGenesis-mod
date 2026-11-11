package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.GeoPalette;
import com.geogenesis.client.preview.TerrainFieldData;
import com.geogenesis.client.preview.ViewTransition;
import com.geogenesis.client.preview.chunk.CellCache;
import com.geogenesis.client.preview.sandbox.SandboxGeometry;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Locale;

/**
 * <b>运镜过渡的分镜出图</b>（★ v50）。
 *
 * <h2>为什么要有它</h2>
 * <p>用户实机反馈：「切换动画依旧是像图片切换那样，完全没有完成我的设计要求。」
 * 光靠读代码分不清是<b>数学错</b>还是<b>渲染路径错</b>，所以这里用
 * <b>与生产完全相同的公式</b>把 ease = 0 / .25 / .5 / .75 / 1 五帧画出来：</p>
 * <ul>
 *   <li>若分镜看起来是「地形跟着镜头转起来」⇒ 数学对，问题在实机渲染路径；</li>
 *   <li>若分镜看起来仍是「两张图」⇒ 数学/参数错，改公式。</li>
 * </ul>
 *
 * <h2>与生产同源的量</h2>
 * <pre>
 *   yaw   = ViewTransition.yaw(ease, toSandbox, DEFAULT_YAW)
 *   pitch = ViewTransition.pitch(ease, toSandbox)
 *   fill  = SandboxGeometry.fillScale(rotationRows(yaw,pitch), w, h)
 *   sxy   = ViewTransition.scale(ease, toSandbox, w, h, fill)
 *   relief = ViewTransition.relief(ease, toSandbox)   // v55：高度与 alpha 同源
 * </pre>
 *
 * <p>用法：{@code gradlew runTransitionFilmstripProbe} → {@code build/rv2/filmstrip.png}</p>
 */
public final class TransitionFilmstripProbe {

    private static final int SEED = 12345;
    private static final int WINDOW = 512;
    private static final int TEX = WINDOW;
    private static final int FW = 380;      // 每帧宽
    private static final int FH = 300;      // 每帧高
    private static final double SEA = 63.0;
    private static final int STEPS = 8;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        File dir = new File("build/rv2");
        dir.mkdirs();

        TerrainParams params = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(params, params.minY(), params.maxY(), true);
        gen.seed(SEED);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);

        int originX = -WINDOW / 2, originZ = -WINDOW / 2;
        int cx0 = originX >> 4, cz0 = originZ >> 4;
        int nChunks = (WINDOW >> 4) + 2;
        CellCache cache = new CellCache();
        for (int i = 0; i < nChunks; i++) {
            for (int j = 0; j < nChunks; j++) {
                cache.put(cx0 + i, cz0 + j, terrain.getChunkCells(cx0 + i, cz0 + j), 1);
            }
        }
        TerrainFieldData data = TerrainFieldData.fromCellCache(
                cache, originX, originZ, WINDOW, WINDOW, TEX, TEX, 1L);

        // 平面纹理（含坡向山体阴影，与 PlanePainter 同算法）
        int[] tex = new int[TEX * TEX];
        Cell scratch = new Cell();
        int minY = params.minY(), maxY = params.maxY();
        for (int tz = 0; tz < TEX; tz++) {
            for (int tx = 0; tx < TEX; tx++) {
                Cell c = data.toDisplayCell(tx, tz, scratch);
                int wx = originX + tx, wz = originZ + tz;
                tex[tz * TEX + tx] = c == null ? 0xFF202030
                        : GeoPalette.color(GeoPalette.PreviewLayer.ELEVATION, c, wx, wz,
                                minY, maxY, true);
            }
        }
        Hillshade.apply(tex, data, TEX);

        double base = SEA - 4.0;
        double[] hs = new double[TEX * TEX];
        int n = 0;
        for (int i = 0; i < hs.length; i++) {
            double h = data.height[i];
            if (!Double.isNaN(h)) hs[n++] = h;
        }
        java.util.Arrays.sort(hs, 0, n);
        double p95 = hs[(int) (n * 0.95)];
        double span = Math.max(20.0, Math.max(60.0, p95 - base));

        SandboxGeometry.Columns cols = SandboxGeometry.buildColumns(
                data, GeoPalette.PreviewLayer.ELEVATION, 2, (float) span,
                Integer.MIN_VALUE, Integer.MAX_VALUE, SEA, 0L, 0L,
                (gx, gz, snap) -> {
                    Cell c = snap.toDisplayCell(gx, gz, new Cell());
                    return c == null ? 0x202030
                            : GeoPalette.color(GeoPalette.PreviewLayer.ELEVATION, c, 0, 0,
                                    -64, 320, true) & 0xFFFFFF;
                });
        System.out.printf("地形 %d×%d，柱 %d×%d，span=%.1f%n",
                data.gridW, data.gridH, cols.colCount, cols.colRows, span);

        // ★ v56：尺度用「目标姿态」算一次，整段沿用（逐帧算会让镜头"呼吸"）
        float[] rEnd = SandboxGeometry.rotationRows(
                ViewTransition.yaw(1f, true, SandboxGeometry.DEFAULT_YAW),
                ViewTransition.pitch(1f, true));
        float fillTarget = SandboxGeometry.fillScale(rEnd, FW, FH);

        BufferedImage strip = new BufferedImage(FW * STEPS, FH, BufferedImage.TYPE_INT_RGB);
        boolean pass = true;
        System.out.println("\nease   pitch   yaw     fill    sx      sy      relief");
        System.out.println("--------------------------------------------------------");
        for (int k = 0; k < STEPS; k++) {
            float ease = k / (float) (STEPS - 1);
            float yaw = ViewTransition.yaw(ease, true, SandboxGeometry.DEFAULT_YAW);
            float pitch = ViewTransition.pitch(ease, true);
            float[] r = SandboxGeometry.rotationRows(yaw, pitch);
            float fill = fillTarget;
            float[] sxy = new float[2];
            ViewTransition.scale(ease, true, FW, FH, fill, sxy);
            float relief = ViewTransition.relief(ease, true);
            System.out.printf("%.2f   %.3f   %.3f   %.1f    %.1f    %.1f    %.2f%n",
                    ease, pitch, yaw, fill, sxy[0], sxy[1], relief);

            BufferedImage frame = renderFrame(tex, data, span, cols, r, sxy[0], sxy[1], relief, ease);
            int ox = k * FW;
            for (int y = 0; y < FH; y++) {
                for (int xx = 0; xx < FW; xx++) strip.setRGB(ox + xx, y, frame.getRGB(xx, y));
            }
            if (k > 0) {
                for (int y = 0; y < FH; y++) strip.setRGB(ox - 1, y, 0xFFAA00);
            }
        }
        ImageIO.write(strip, "png", new File(dir, "filmstrip.png"));
        System.out.println("\n出图 build/rv2/filmstrip.png（左→右：平面 → 沙盘）");

        // ---- 判据：地面在过渡中必须真的被"转/压"----
        float e0 = 0f, e1 = 1f;
        float[] r0 = SandboxGeometry.rotationRows(
                ViewTransition.yaw(e0, true, SandboxGeometry.DEFAULT_YAW),
                ViewTransition.pitch(e0, true));
        float[] r1 = SandboxGeometry.rotationRows(
                ViewTransition.yaw(e1, true, SandboxGeometry.DEFAULT_YAW),
                ViewTransition.pitch(e1, true));
        System.out.println("\n[1] 地面变换必须真的改变（否则就是两张图）");
        System.out.printf("    ease=0  r1=%.3f r4=%.3f%n", r0[1], r0[4]);
        System.out.printf("    ease=1  r1=%.3f r4=%.3f%n", r1[1], r1[4]);
        boolean changed = Math.abs(r1[1] - r0[1]) > 0.1f || Math.abs(r1[4] - r0[4]) > 0.1f;
        if (!changed) {
            System.out.println("    FAIL: 地面变换几乎没变 ⇒ 观感必然是图片切换");
            pass = false;
        } else {
            System.out.println("    OK（剪切/压缩显著，地面会跟着镜头转）");
        }

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    // ------------------------------------------------------------------

    private static BufferedImage renderFrame(int[] tex, TerrainFieldData data, double span,
                                             SandboxGeometry.Columns cols,
                                             float[] r, float sx, float sy, float relief,
                                             float ease) {
        BufferedImage img = new BufferedImage(FW, FH, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < FH; y++) {
            for (int x = 0; x < FW; x++) img.setRGB(x, y, 0x0A0C12);
        }
        double cx = FW / 2.0, cy = FH / 2.0;

        // ---- 1) 高精度平面图（v57：与生产的 blitPlaneOnly 同义 —— 正俯视铺满整帧）----
        //   ★ 生产已删掉 96×96 地面曲面：pitch=0 时高度不参与投影，
        //     第一段只需把"逐像素精确的平面纹理"与"体素正俯视"交叉淡变，
        //     两者在 (w/2,h/2) 尺度下完全重合。
        float texA = ViewTransition.planeTextureAlpha(ease, true);
        if (texA > 0.004f) {
            for (int y = 0; y < FH; y++) {
                int v = (int) (y * (TEX - 1) / (double) (FH - 1));
                for (int x = 0; x < FW; x++) {
                    int u = (int) (x * (TEX - 1) / (double) (FW - 1));
                    int src = tex[v * TEX + u];
                    img.setRGB(x, y, blend(img.getRGB(x, y), src, (int) (255 * texA)));
                }
            }
        }

        // ---- 2) 柱体（高度与 alpha 同为 relief，与生产同一个相机）----
        if (relief > 0.004f) {
            Integer[] order = new Integer[cols.readyCount];
            for (int k = 0; k < cols.readyCount; k++) order[k] = cols.ready[k];
            final SandboxGeometry.Columns cc = cols;
            java.util.Arrays.sort(order, (a, b) -> {
                double da = r[6] * SandboxGeometry.modelOf(cc.colCount, a / cc.colRows)
                        + r[7] * SandboxGeometry.modelOf(cc.colRows, a % cc.colRows);
                double db = r[6] * SandboxGeometry.modelOf(cc.colCount, b / cc.colRows)
                        + r[7] * SandboxGeometry.modelOf(cc.colRows, b % cc.colRows);
                return Double.compare(da, db);
            });
            int a255 = (int) (255 * relief);
            for (Integer col : order) {
                SandboxGeometry.Column c = cols.items[col];
                if (c == null || c.top < 0f) continue;
                int rgb = c.rgb & 0xFFFFFF;
                double t = c.top * relief;   // 与生产 draw(heightScale) 同式
                fillQuadFlat(img, r, sx, sy, cx, cy, a255,
                        c.x0, c.y0, t, c.x1, c.y0, t, c.x1, c.y1, t, c.x0, c.y1, t, rgb);
                fillQuadFlat(img, r, sx, sy, cx, cy, a255,
                        c.x1, c.y0, t, c.x1, c.y1, t, c.x1, c.y1, 0, c.x1, c.y0, 0,
                        shade(rgb, 0.82f));
                fillQuadFlat(img, r, sx, sy, cx, cy, a255,
                        c.x0, c.y1, t, c.x1, c.y1, t, c.x1, c.y1, 0, c.x0, c.y1, 0,
                        shade(rgb, 0.70f));
            }
        }
        return img;
    }

    // ---- 光栅化（与 HeightfieldLookProbe 同实现）----

    /** 地面网格分辨率（每边四边形数）。过渡期间每帧重建，取 96 折中精度与成本。 */
    private static final int GROUND_M = 96;

    /**
     * <b>按高度位移的地面</b>：把平面纹理贴到高度场曲面上（★ v50 关键）。
     *
     * <p>顶点高度共享 ⇒ 曲面连续、无缝隙；{@code pitch=0} 时所有顶点共面，
     * 投影结果<b>逐像素等于平面图</b>（{@code r5=0}）。</p>
     */
    private static double mnorm(double h, double span) {
        return Math.max((h - (SEA - 4.0)) / Math.max(20.0, span), 0.05) * SandboxGeometry.RELIEF;
    }

    private static void fillTexQuad(BufferedImage img, float[] r, float sx, float sy,
                                    double cx, double cy, int[] tex, int texW,
                                    double ax, double ay, double az, double au, double av,
                                    double bx, double by, double bz, double bu, double bv,
                                    double dx, double dy, double dz, double du, double dv,
                                    double ex, double ey, double ez, double eu, double ev,
                                    float mul) {
        double[] p0 = proj(r, sx, sy, cx, cy, ax, ay, az);
        double[] p1 = proj(r, sx, sy, cx, cy, bx, by, bz);
        double[] p2 = proj(r, sx, sy, cx, cy, dx, dy, dz);
        double[] p3 = proj(r, sx, sy, cx, cy, ex, ey, ez);
        fillTexTri(img, tex, texW, p0, au, av, p1, bu, bv, p2, du, dv, mul);
        fillTexTri(img, tex, texW, p0, au, av, p2, du, dv, p3, eu, ev, mul);
    }

    private static void fillTexTri(BufferedImage img, int[] tex, int texW,
                                   double[] a, double au, double av,
                                   double[] b, double bu, double bv,
                                   double[] c, double cu, double cv, float mul) {
        int minX = (int) Math.max(0, Math.floor(Math.min(a[0], Math.min(b[0], c[0]))));
        int maxX = (int) Math.min(img.getWidth() - 1, Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
        int minY = (int) Math.max(0, Math.floor(Math.min(a[1], Math.min(b[1], c[1]))));
        int maxY = (int) Math.min(img.getHeight() - 1, Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
        double d = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1]);
        if (Math.abs(d) < 1e-9) return;
        final double eps = 1e-6 * (1 + Math.abs(d));
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double l1 = ((b[1] - c[1]) * (x - c[0]) + (c[0] - b[0]) * (y - c[1])) / d;
                double l2 = ((c[1] - a[1]) * (x - c[0]) + (a[0] - c[0]) * (y - c[1])) / d;
                double l3 = 1 - l1 - l2;
                if (l1 < -eps || l2 < -eps || l3 < -eps) continue;
                double u = l1 * au + l2 * bu + l3 * cu;
                double v = l1 * av + l2 * bv + l3 * cv;
                int tx = (int) Math.max(0, Math.min(texW - 1, u * (texW - 1)));
                int tz = (int) Math.max(0, Math.min(texW - 1, v * (texW - 1)));
                img.setRGB(x, y, shade(tex[tz * texW + tx], mul));
            }
        }
    }

    private static void fillQuadFlat(BufferedImage img, float[] r, float sx, float sy,
                                     double cx, double cy, int a255,
                                     double ax, double ay, double az,
                                     double bx, double by, double bz,
                                     double dx, double dy, double dz,
                                     double ex, double ey, double ez, int rgb) {
        double[] p0 = proj(r, sx, sy, cx, cy, ax, ay, az);
        double[] p1 = proj(r, sx, sy, cx, cy, bx, by, bz);
        double[] p2 = proj(r, sx, sy, cx, cy, dx, dy, dz);
        double[] p3 = proj(r, sx, sy, cx, cy, ex, ey, ez);
        fillTri(img, p0, p1, p2, rgb, a255);
        fillTri(img, p0, p2, p3, rgb, a255);
    }

    private static void fillTri(BufferedImage img, double[] a, double[] b, double[] c,
                                int rgb, int a255) {
        int minX = (int) Math.max(0, Math.floor(Math.min(a[0], Math.min(b[0], c[0]))));
        int maxX = (int) Math.min(img.getWidth() - 1, Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
        int minY = (int) Math.max(0, Math.floor(Math.min(a[1], Math.min(b[1], c[1]))));
        int maxY = (int) Math.min(img.getHeight() - 1, Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
        double d = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1]);
        if (Math.abs(d) < 1e-9) return;
        final double eps = 1e-6 * (1 + Math.abs(d));
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double l1 = ((b[1] - c[1]) * (x - c[0]) + (c[0] - b[0]) * (y - c[1])) / d;
                double l2 = ((c[1] - a[1]) * (x - c[0]) + (a[0] - c[0]) * (y - c[1])) / d;
                double l3 = 1 - l1 - l2;
                if (l1 < -eps || l2 < -eps || l3 < -eps) continue;
                img.setRGB(x, y, blend(img.getRGB(x, y), rgb, a255));
            }
        }
    }

    private static int blend(int dst, int src, int a255) {
        if (a255 >= 255) return src & 0xFFFFFF;
        int dr = (dst >> 16) & 0xFF, dg = (dst >> 8) & 0xFF, db = dst & 0xFF;
        int sr = (src >> 16) & 0xFF, sg = (src >> 8) & 0xFF, sb = src & 0xFF;
        int r = (sr * a255 + dr * (255 - a255)) / 255;
        int g = (sg * a255 + dg * (255 - a255)) / 255;
        int b = (sb * a255 + db * (255 - a255)) / 255;
        return (r << 16) | (g << 8) | b;
    }

    private static double[] proj(float[] r, float sx, float sy, double cx, double cy,
                                 double mx, double my, double mz) {
        return new double[]{
                cx + sx * (r[0] * mx + r[1] * my + r[2] * mz),
                cy + sy * (r[3] * mx + r[4] * my + r[5] * mz)};
    }

    private static int shade(int rgb, float mul) {
        int rr = clamp((int) (((rgb >> 16) & 0xFF) * mul));
        int gg = clamp((int) (((rgb >> 8) & 0xFF) * mul));
        int bb = clamp((int) ((rgb & 0xFF) * mul));
        return (rr << 16) | (gg << 8) | bb;
    }

    private static int clamp(int v) { return v < 0 ? 0 : Math.min(v, 255); }

    /** 坡向山体阴影（与 PlanePainter.applySlopeShading 同算法）。 */
    static final class Hillshade {
        private Hillshade() { }

        static void apply(int[] tex, TerrainFieldData data, int size) {
            float lx = -0.5f, ly = 1.0f, lz = -0.3f;
            float len = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
            lx /= len; ly /= len; lz /= len;
            int total = size * size;
            int[] effH = new int[total];
            boolean[] valid = new boolean[total];
            boolean[] water = new boolean[total];
            for (int tz = 0; tz < size; tz++) {
                for (int tx = 0; tx < size; tx++) {
                    int ti = tz * size + tx;
                    int di = tx * data.gridH + tz;
                    double h = data.height[di];
                    if (Double.isNaN(h)) continue;
                    boolean w = data.e[di] < 0.0;
                    water[ti] = w;
                    effH[ti] = w ? (int) SEA : (int) h;
                    valid[ti] = true;
                }
            }
            int[] smooth = effH.clone();
            for (int tz = 1; tz < size - 1; tz++) {
                for (int tx = 1; tx < size - 1; tx++) {
                    int idx = tz * size + tx;
                    if (!valid[idx]) continue;
                    long sum = 0;
                    int cnt = 0;
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int j = idx + dz * size + dx;
                            if (valid[j]) { sum += effH[j]; cnt++; }
                        }
                    }
                    if (cnt >= 3) smooth[idx] = (int) (sum / cnt);
                }
            }
            for (int tz = 1; tz < size - 1; tz++) {
                for (int tx = 1; tx < size - 1; tx++) {
                    int idx = tz * size + tx;
                    if (water[idx] || !valid[idx] || !valid[idx - 1] || !valid[idx + 1]
                            || !valid[idx - size] || !valid[idx + size]) continue;
                    float dhdx = (smooth[idx + 1] - smooth[idx - 1]) * 0.5f;
                    float dhdz = (smooth[idx + size] - smooth[idx - size]) * 0.5f;
                    float nx = -dhdx, nyv = 1.0f, nz = -dhdz;
                    float nl = (float) Math.sqrt(nx * nx + nyv * nyv + nz * nz);
                    if (nl < 0.001f) continue;
                    nx /= nl; nyv /= nl; nz /= nl;
                    float diff = Math.max(0, nx * lx + nyv * ly + nz * lz) * 0.6f + 0.4f;
                    tex[idx] = shade(tex[idx], diff);
                }
            }
        }
    }

    private TransitionFilmstripProbe() { }
}