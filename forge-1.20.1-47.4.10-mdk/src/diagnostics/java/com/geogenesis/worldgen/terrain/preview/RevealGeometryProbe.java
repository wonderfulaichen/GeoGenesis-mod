package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.GeoPalette;
import com.geogenesis.client.preview.TerrainFieldData;
import com.geogenesis.client.preview.chunk.CellCache;
import com.geogenesis.client.preview.sandbox.SandboxGeometry;
import com.geogenesis.client.reveal.PerspectiveCamera;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Locale;

/**
 * <b>落成动画几何探针</b>（★ v46）。
 *
 * <h2>它守的是什么</h2>
 * <p>实机反馈「落成动画是一屏细长垂直线条」。根因是
 * {@code RevealWorldRenderer} 把 {@link SandboxGeometry.Column} 的
 * <b>模型单位</b>（{@code x0..y1 ∈ [-1,1]}、{@code top ∈ [0.02,0.40]}）
 * 当成了<b>世界方块</b>用：</p>
 * <ul>
 *   <li>{@code hx = (x1-x0)*0.5*bpc} ⇒ 柱宽只剩 1/112 ⇒ 细线；</li>
 *   <li>{@code top} 与邻居 {@code top} 混着方块单位比 ⇒ 侧墙判据恒真 ⇒ 密集竖线。</li>
 * </ul>
 *
 * <p>本探针用<b>与实机同一个</b> {@code SandboxGeometry.buildColumns}
 * 和同一个换算（模型 → 方块）复现正确几何，并出 PNG 供人眼判读。</p>
 *
 * <h2>判据</h2>
 * <ol>
 *   <li><b>[1]</b> 柱宽占屏幕比例合理：盘面横向覆盖 ≥ 60% 视口宽；</li>
 *   <li><b>[2]</b> 侧墙只在一部分柱子上出现（比例 ∈ (0, 60%]）—— 恒真/恒假都是 bug；</li>
 *   <li><b>[3]</b> 柱高跨度：最高柱 ≥ 平均柱高的 2 倍（否则是平板）。</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runRevealGeometryProbe [-PrevealPng=build\\rv]}</p>
 */
public final class RevealGeometryProbe {

    private static final int SEED = 12345;
    private static final int WORLD_W = 900;
    private static final int GRID = 224;          // 112 柱 × 2 细格
    private static final int VIEW_W = 854;
    private static final int VIEW_H = 451;
    private static final double SEA_LEVEL = 63.0;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        boolean pass = true;

        TerrainParams params = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(params, params.minY(), params.maxY(), true);
        gen.seed(SEED);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);

        CellCache cache = new CellCache();
        int originX = -WORLD_W / 2;
        int originZ = -WORLD_W / 2;
        int chunks = WORLD_W / 16 + 2;
        int cx0 = originX / 16, cz0 = originZ / 16;
        for (int i = 0; i < chunks; i++) {
            for (int j = 0; j < chunks; j++) {
                cache.put(cx0 + i, cz0 + j, terrain.getChunkCells(cx0 + i, cz0 + j), 1);
            }
        }

        TerrainFieldData data = TerrainFieldData.fromCellCache(
                cache, originX, originZ, WORLD_W, WORLD_W, GRID, GRID, 7L);
        System.out.printf("数据 %d×%d，每细格 %.3f 方块%n",
                data.gridW, data.gridH, data.blocksPerCell());

        // 竖向比例分母（与沙盘同式：max(20, p95 - base)）
        double base = SEA_LEVEL - 4.0;
        double[] hs = new double[data.gridW * data.gridH];
        int n = 0;
        double maxH = base;
        for (int i = 0; i < hs.length; i++) {
            double h = data.height[i];
            if (!Double.isNaN(h)) {
                hs[n++] = h;
                if (h > maxH) maxH = h;
            }
        }
        java.util.Arrays.sort(hs, 0, n);
        double p95 = hs[(int) (n * 0.95)];
        double span = Math.max(20.0, Math.max(60.0, p95 - base));
        System.out.printf("基准 %.1f，最高 %.1f，p95 %.1f ⇒ span=%.1f%n", base, maxH, p95, span);

        // 用高程色带（与沙盘 ELEVATION 图层同思路），避免整片同色看不出起伏
        SandboxGeometry.ColorSource elevSrc = (gx, gz, s) -> {
            int i = s.index(gx, gz);
            if (i < 0) return 0x505050;
            double e = s.e[i];
            double t = Math.max(0.0, Math.min(1.0, (e + 1.0) * 0.5));
            return elevRamp(t);
        };
        SandboxGeometry.Columns cols = SandboxGeometry.buildColumns(
                data, GeoPalette.PreviewLayer.ELEVATION, 2, (float) span,
                Integer.MIN_VALUE, Integer.MAX_VALUE, SEA_LEVEL, 0L, 0L, elevSrc);
        System.out.printf("柱子 %d 根（%d×%d），就绪 %d%n",
                cols.colCount * cols.colRows, cols.colCount, cols.colRows, cols.readyCount);

        // ---- 模型 → 世界方块（与 RevealWorldRenderer 同一换算）----
        double worldHalf = WORLD_W / 2.0;
        double[] topsWorld = new double[cols.items.length];
        double sumTop = 0, maxTop = 0;
        int okCols = 0;
        for (int i = 0; i < cols.items.length; i++) {
            SandboxGeometry.Column c = cols.items[i];
            if (c == null || c.top < 0f) { topsWorld[i] = -1; continue; }
            double t = c.top * span / SandboxGeometry.RELIEF;
            topsWorld[i] = t;
            sumTop += t;
            maxTop = Math.max(maxTop, t);
            okCols++;
        }
        double avgTop = okCols > 0 ? sumTop / okCols : 0;

        // ---- [2] 侧墙比例（判据与实机一致：top > max(邻居,0)）----
        int wallCount = 0;
        for (int ci = 0; ci < cols.colCount; ci++) {
            for (int cj = 0; cj < cols.colRows; cj++) {
                int col = ci * cols.colRows + cj;
                double t = topsWorld[col];
                if (t < 0) continue;
                double low = 0;
                if (ci + 1 < cols.colCount) low = Math.max(low, Math.max(0, topsWorld[col + cols.colRows]));
                if (ci > 0) low = Math.max(low, Math.max(0, topsWorld[col - cols.colRows]));
                if (cj + 1 < cols.colRows) low = Math.max(low, Math.max(0, topsWorld[col + 1]));
                if (cj > 0) low = Math.max(low, Math.max(0, topsWorld[col - 1]));
                if (t > low) wallCount++;
            }
        }
        double wallFrac = okCols > 0 ? wallCount / (double) okCols : 0;

        // ---- [1] 柱宽占屏比 ----
        //   模型空间 [-1,1] 对应世界 [centerX-half, centerX+half]，
        //   所以一根柱的模型宽 (x1-x0) 对应方块 (x1-x0)*half。
        //   全部柱子合计应 ≈ 2*half = WORLD_W（可能因 overlap 略超）。
        SandboxGeometry.Column sample = null;
        for (SandboxGeometry.Column c : cols.items) {
            if (c != null && c.top >= 0f) { sample = c; break; }
        }
        double pillarBlocks = 0, totalBlocks = 0;
        int counted = 0;
        for (int ci = 0; ci < cols.colCount; ci++) {
            SandboxGeometry.Column c = cols.items[ci * cols.colRows];
            if (c == null || c.top < 0f) continue;
            double bw = (c.x1 - c.x0) * worldHalf;
            if (pillarBlocks == 0) pillarBlocks = bw;
            totalBlocks += bw;
            counted++;
        }
        double coverFrac = totalBlocks / WORLD_W;

        System.out.println();
        System.out.println("[1] 柱宽（盘面横向覆盖率）");
        System.out.printf("    每柱 %.2f 方块（%d 柱有效）⇒ 合计覆盖 %.0f%% 窗口%n",
                pillarBlocks, counted, 100 * coverFrac);
        if (coverFrac < 0.60) {
            System.out.printf("    FAIL: 盘面只覆盖 %.0f%% ⇒ 柱子过窄（细线观感）%n", 100 * coverFrac);
            pass = false;
        } else {
            System.out.printf("    OK（覆盖 %.0f%%）%n", 100 * coverFrac);
        }

        System.out.println("\n[2] 侧墙比例");
        System.out.printf("    %d/%d 根柱画侧墙 = %.1f%%%n", wallCount, okCols, 100 * wallFrac);
        if (wallFrac <= 0.0 || wallFrac > 0.60) {
            System.out.printf("    FAIL: 侧墙比例 %.1f%% 不合理（恒真 ⇒ 密集竖线；恒假 ⇒ 无立体感）%n",
                    100 * wallFrac);
            pass = false;
        } else {
            System.out.println("    OK（只在高差处出现）");
        }

        System.out.println("\n[3] 柱高分布");
        System.out.printf("    平均 %.1f 方块，最高 %.1f 方块 ⇒ 比值 %.2f%n",
                avgTop, maxTop, avgTop > 1e-6 ? maxTop / avgTop : 0);
        if (avgTop <= 1e-6 || maxTop / avgTop < 2.0) {
            System.out.println("    FAIL: 最高柱不到平均的 2 倍 ⇒ 一片平板，没有起伏");
            pass = false;
        } else {
            System.out.println("    OK（有明显起伏）");
        }

        // ---- 出图（正交视角，与动画开场一帧等价）----
        String outDir = System.getProperty("revealPng", "build/rv");
        File dir = new File(outDir);
        dir.mkdirs();
        writePreview(cols, topsWorld, span, new File(dir, "reveal_geometry.png"));
        System.out.println("\n出图：" + new File(dir, "reveal_geometry.png").getPath());

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    /** 正交投影出图（与人眼看到的"沙盘一帧"等价）。 */
    private static void writePreview(SandboxGeometry.Columns cols, double[] topsWorld,
                                     double span, File out) throws Exception {
        int w = VIEW_W, h = VIEW_H;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int bg = 0x0A0C12;
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) img.setRGB(xx, yy, bg);
        }

        float[] r = SandboxGeometry.rotationRows(SandboxGeometry.DEFAULT_YAW, SandboxGeometry.DEFAULT_PITCH);
        float[] box = SandboxGeometry.planeBox(r);
        float scale = SandboxGeometry.fitScale(r, w, h);
        double ox = w / 2.0;
        double oy = h / 2.0;

        // 按深度排序（远→近）
        double[] depth = new double[cols.items.length];
        for (int i = 0; i < cols.items.length; i++) {
            int ci = i / cols.colRows, cj = i % cols.colRows;
            double mx = SandboxGeometry.modelOf(cols.colCount, ci);
            double my = SandboxGeometry.modelOf(cols.colRows, cj);
            depth[i] = r[6] * mx + r[7] * my;
        }
        Integer[] order = new Integer[cols.items.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(depth[a], depth[b]));

        double base = 63.0 - 4.0;
        for (Integer idx : order) {
            int col = idx;
            SandboxGeometry.Column c = cols.items[col];
            if (c == null || c.top < 0f) continue;
            int ci = col / cols.colRows, cj = col % cols.colRows;
            double t = topsWorld[col] / span * SandboxGeometry.RELIEF;  // 回到模型单位
            double low = 0;
            if (ci + 1 < cols.colCount) low = Math.max(low, Math.max(0, topsWorld[col + cols.colRows]));
            if (ci > 0) low = Math.max(low, Math.max(0, topsWorld[col - cols.colRows]));
            if (cj + 1 < cols.colRows) low = Math.max(low, Math.max(0, topsWorld[col + 1]));
            if (cj > 0) low = Math.max(low, Math.max(0, topsWorld[col - 1]));
            double lowModel = low / span * SandboxGeometry.RELIEF;

            int rgb = colorFor(c);
            // 顶面
            fillQuad(img, r, scale, ox, oy,
                    c.x0, c.y0, t, c.x1, c.y0, t, c.x1, c.y1, t, c.x0, c.y1, t, rgb);
            if (t > lowModel) {
                // 东南面（可见的那两面）
                fillQuad(img, r, scale, ox, oy,
                        c.x1, c.y0, t, c.x1, c.y1, t, c.x1, c.y1, lowModel, c.x1, c.y0, lowModel,
                        shade(rgb, 0.82f));
                fillQuad(img, r, scale, ox, oy,
                        c.x0, c.y1, t, c.x1, c.y1, t, c.x1, c.y1, lowModel, c.x0, c.y1, lowModel,
                        shade(rgb, 0.70f));
            }
        }
        ImageIO.write(img, "png", out);
    }

    private static int colorFor(SandboxGeometry.Column c) {
        return c.rgb & 0xFFFFFF;
    }

    private static void fillQuad(BufferedImage img, float[] r, float s, double ox, double oy,
                                 double ax, double ay, double az, double bx, double by, double bz,
                                 double cx, double cy, double cz, double dx, double dy, double dz,
                                 int rgb) {
        double[] p0 = proj(r, s, ox, oy, ax, ay, az);
        double[] p1 = proj(r, s, ox, oy, bx, by, bz);
        double[] p2 = proj(r, s, ox, oy, cx, cy, cz);
        double[] p3 = proj(r, s, ox, oy, dx, dy, dz);
        fillTri(img, p0, p1, p2, rgb);
        fillTri(img, p0, p2, p3, rgb);
    }

    private static double[] proj(float[] r, float s, double ox, double oy,
                                 double mx, double my, double mz) {
        return new double[]{
                ox + s * (r[0] * mx + r[1] * my + r[2] * mz),
                oy + s * (r[3] * mx + r[4] * my + r[5] * mz)};
    }

    private static void fillTri(BufferedImage img, double[] a, double[] b, double[] c, int rgb) {
        int minX = (int) Math.max(0, Math.floor(Math.min(a[0], Math.min(b[0], c[0]))));
        int maxX = (int) Math.min(img.getWidth() - 1, Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
        int minY = (int) Math.max(0, Math.floor(Math.min(a[1], Math.min(b[1], c[1]))));
        int maxY = (int) Math.min(img.getHeight() - 1, Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
        double d = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1]);
        if (Math.abs(d) < 1e-9) return;
        // 容差放宽到半个像素：quad 拆两个三角形时共享对角线会因浮点误差
        // 两侧都判"外侧"，留下 1px 黑缝（出图上的黑色麻点）。
        // GPU 有 top-left 填充规则不会出现；软件光栅化须自己放宽。
        final double eps = 1e-6 * (1 + Math.abs(d));
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double l1 = ((b[1] - c[1]) * (x - c[0]) + (c[0] - b[0]) * (y - c[1])) / d;
                double l2 = ((c[1] - a[1]) * (x - c[0]) + (a[0] - c[0]) * (y - c[1])) / d;
                double l3 = 1 - l1 - l2;
                if (l1 >= -eps && l2 >= -eps && l3 >= -eps) img.setRGB(x, y, rgb);
            }
        }
    }

    private static int shade(int rgb, float mul) {
        int rr = clamp((int) (((rgb >> 16) & 0xFF) * mul));
        int gg = clamp((int) (((rgb >> 8) & 0xFF) * mul));
        int bb = clamp((int) ((rgb & 0xFF) * mul));
        return (rr << 16) | (gg << 8) | bb;
    }

    private static int clamp(int v) { return v < 0 ? 0 : Math.min(v, 255); }

    /** 高程色带（深蓝 → 青 → 绿 → 黄 → 棕 → 白），与沙盘 ELEVATION 图层同思路。 */
    private static int elevRamp(double t) {
        double[][] stops = {
                {0.00, 0x1B3A6B}, {0.30, 0x2E7FA8}, {0.45, 0x3E8B4A},
                {0.65, 0xA8A040}, {0.82, 0x8B5A2B}, {1.00, 0xE8E8E8}
        };
        for (int k = 0; k < stops.length - 1; k++) {
            double a = stops[k][0], b = stops[k + 1][0];
            if (t <= b) {
                double f = (b - a) < 1e-9 ? 0 : (t - a) / (b - a);
                f = Math.max(0, Math.min(1, f));
                int ca = (int) stops[k][1], cb = (int) stops[k + 1][1];
                int r = (int) (((ca >> 16 & 0xFF) * (1 - f)) + ((cb >> 16 & 0xFF) * f));
                int g = (int) (((ca >> 8 & 0xFF) * (1 - f)) + ((cb >> 8 & 0xFF) * f));
                int bl = (int) (((ca & 0xFF) * (1 - f)) + ((cb & 0xFF) * f));
                return (r << 16) | (g << 8) | bl;
            }
        }
        return 0xE8E8E8;
    }

    private RevealGeometryProbe() { }
}
