package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 水文预览探针（2026-09-30，用户要求"好好渲染一个预览图"+"水文是 3D 的"）。
 *
 * <p><b>数据源 = 生产落块结果</b>（{@code GeoGenesisTerrain.getChunkCells}，
 * 即玩家在游戏里看到的 {@code cell.height / riverType / riverSurfaceY / isLake}），
 * 不重跑任何水文、不做二次推导 ⇒ 所见即游戏。</p>
 *
 * <h3>输出（1 px = 1 block）</h3>
 * <ul>
 *   <li>{@code preview_top.png} — 三联：地形山体阴影 ｜ 地形+水（<b>按水深着色</b>）｜ 水层+
 *       湖域轮廓；</li>
 *   <li>{@code preview_sections.png} — <b>垂直剖面</b>若干条（横切最有水的行/列）：
 *       地形线 + 水面填色，<b>直接看"水是否坐在谷里 / 有没有悬空 / 河谷有没有落差"</b>；
 *       垂直方向放大 3×（否则 200 块水平跨度下 100 块高差被压平）。</li>
 * </ul>
 *
 * <p>运行：{@code gradlew runHydroPreviewProbe [-PprobeArgs="seed bx bz radius"]}</p>
 */
public final class HydroPreviewProbe {

    /** 垂直放大倍数（剖面用；水平 1 块 = 1 px，垂直 1 块 = EXAG px）。 */
    private static final int EXAG = 3;

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -140;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : 137;
        // ★ 2026-09-30【尺寸对齐参照图】：参照（ref_flow_0925.png）是 1401×1401
        //   = 半径 700、1px=1块；此前默认 160 只有 321² ⇒ 看的面积小 19 倍（用户判据
        //   "看的面积明显小了"）。默认改为 700，与参照同窗口同尺度，可直接并排比对。
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 700;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);
        gt.seed(seed);
        double sea = gt.seaLevel();

        int n = 2 * radius + 1;
        double[] h = new double[n * n];
        double[] wTop = new double[n * n];      // 水面 Y（NaN = 无水）
        boolean[] lake = new boolean[n * n];
        boolean[] seaCell = new boolean[n * n];
        boolean[] river = new boolean[n * n];
        int waterPx = 0, lakePx = 0, riverPx = 0, seaPx = 0;

        long t0 = System.nanoTime();
        for (int j = 0; j < n; j++) {
            int z = bz - radius + j;
            if ((j & 127) == 0) {
                System.out.printf("  ...采样 %d/%d 行（%.0fs）%n", j, n,
                        (System.nanoTime() - t0) / 1e9);
            }
            for (int i = 0; i < n; i++) {
                int x = bx - radius + i;
                Cell c = gt.getChunkCells(x >> 4, z >> 4)
                        [Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
                int p = j * n + i;
                h[p] = c.height;
                double wt = Double.NaN;
                boolean seaP = c.height < sea;          // 海：地面低于海平面
                if (c.riverType != 0 && c.riverSurfaceY > c.height + 0.5) {
                    wt = c.riverSurfaceY;
                    if (seaP) { seaCell[p] = true; seaPx++; }
                    else if (c.isLake) { lake[p] = true; lakePx++; }
                    else { river[p] = true; riverPx++; }
                } else if (seaP) {
                    wt = sea;
                    seaCell[p] = true; seaPx++;
                }
                if (!Double.isNaN(wt)) { wTop[p] = wt; waterPx++; }
            }
        }
        long t1 = System.nanoTime();

        File dir = new File("build/hydropreview");
        dir.mkdirs();

        // ---- 俯视主图（★ 与参照图同口径：山体阴影为底 + 海深蓝 + 湖浅蓝 + 河青）----
        //   尺寸 = (2r+1)²（默认 1401²，与 ref_flow_0925.png 同窗口同尺度，可直接并排比对）。
        BufferedImage shade = shade(h, n);
        BufferedImage over = deepCopy(shade);
        BufferedImage wlayer = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int p = j * n + i;
                int col;
                if (seaCell[p]) col = 0x1F4E79;
                else if (lake[p]) col = 0x4A90E2;
                else if (river[p]) col = 0x00E5D0;
                else continue;
                over.setRGB(i, j, col);
                wlayer.setRGB(i, j, col);
            }
        }
        ImageIO.write(over, "png", new File(dir, "preview.png"));
        ImageIO.write(wlayer, "png", new File(dir, "preview_water.png"));

        // ---- 垂直剖面：挑【河】最多的 3 行 + 3 列（海/湖不参与取样，否则全是海）----
        int[] rows = topLines(river, n, true, 3);
        int[] cols = topLines(river, n, false, 3);
        BufferedImage sec = sections(h, wTop, sea, n, bx, bz, radius, rows, cols);
        ImageIO.write(sec, "png", new File(dir, "preview_sections.png"));

        System.out.printf("=== HydroPreviewProbe seed=%d 中心(%d,%d) r=%d 海平面=%.1f ===%n",
                seed, bx, bz, radius, sea);
        System.out.printf("  采样 %.1fs（%d 列，1px=1块）· 水 %d 格（海 %d / 湖 %d / 河 %d）· 水占 %.2f%%%n",
                (t1 - t0) / 1e9, n * n, waterPx, seaPx, lakePx, riverPx,
                100.0 * waterPx / (n * (double) n));
        System.out.println("  输出：build/hydropreview/preview.png（" + n + "×" + n
                + "，与参照图同口径：山体阴影+海深蓝+湖浅蓝+河青）");
        System.out.println("        build/hydropreview/preview_water.png（仅水层）");
        System.out.println("        build/hydropreview/preview_sections.png（垂直剖面，地形线+水面，垂直放大 "
                + EXAG + "×）");
        System.out.println("        ★ 剖面看点：水面是否贴着地形起伏（贴谷）· 有没有悬空水体 · 河谷是否有落差");

        // ---- 诊断：生产落块 cell  vs  车床直调（两条路径必须一致；实测不一致 ⇒ 水位口径错位）----
        HydrologyExperimentEngine eng = new HydrologyExperimentEngine(gen, seed);
        int shown = 0, rtN = 0, rtWet = 0, mism = 0;
        for (int j = 0; j < n && shown < 8; j++) {
            for (int i = 0; i < n && shown < 8; i++) {
                int x = bx - radius + i, z = bz - radius + j;
                Cell c = gt.getChunkCells(x >> 4, z >> 4)
                        [Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
                if (c.riverType == 0) continue;
                rtN++;
                boolean wet = c.riverSurfaceY > c.height + 0.5;
                if (wet) rtWet++;
                var col = HydrologyBlockCarver.carveColumnAt(eng, x, z, c.height, 2.0);
                boolean engWet = col != null && col.fillWater()
                        && col.waterSurfaceY() > col.carvedGroundY() + 0.5;
                if (wet != engWet) mism++;
                if (shown < 8) {
                    System.out.printf("    [对照] (%d,%d) 生产: h=%.2f surf=%.2f rt=%d wet=%s ｜ 车床: carved=%.2f surf=%.2f wet=%s%n",
                            x, z, c.height, c.riverSurfaceY, c.riverType, wet,
                            col == null ? Double.NaN : col.carvedGroundY(),
                            col == null ? Double.NaN : col.waterSurfaceY(), engWet);
                    shown++;
                }
            }
        }
        System.out.printf("  诊断：riverType≠0 共 %d 格 · 其中生产判湿 %d · 两条路径判定不一致 %d%n",
                rtN, rtWet, mism);

        // ---- 河道连续性（方向无关口径）----
        //   ⚠ 沿行统计游程会受河流走向影响（南北向河在行扫描下天然短）⇒ 不可用。
        //   改用【每河格的 8 邻河中数】：连续河大多有 2+ 个河邻；断续点状则大量 0~1。
        int[] nbHist = new int[9];
        int riverCore = 0;
        for (int j = 1; j < n - 1; j++) {
            for (int i = 1; i < n - 1; i++) {
                if (!river[j * n + i]) continue;
                int nb = 0;
                for (int dj = -1; dj <= 1; dj++) {
                    for (int di = -1; di <= 1; di++) {
                        if (di == 0 && dj == 0) continue;
                        if (river[(j + dj) * n + (i + di)]) nb++;
                    }
                }
                nbHist[nb]++;
                if (nb >= 2) riverCore++;
            }
        }
        int rivTotal = riverPx;
        System.out.printf("  河道连续性（8 邻口径）：河 %d 格 · 邻河数分布 [0]=%d [1]=%d [2+]=%d"
                        + " ⇒ 连续性 %.1f%%（连续河应 >80%%）%n",
                rivTotal, nbHist[0], nbHist[1], riverCore,
                rivTotal == 0 ? 0 : 100.0 * riverCore / rivTotal);
        System.out.println("    ★ [0] 高 ⇒ 河被断成孤立点（方块感根因：水面与地形逐格错位）");

        // ---- 4 邻（共边）连续性：区分"真连续"与"对角点链" ----
        //   D8 折线走对角时，相邻河格只【角接触】⇒ 图上呈"点状虚线"（用户"方块拼起来"）。
        int face2 = 0, face0 = 0;
        for (int j = 1; j < n - 1; j++) {
            for (int i = 1; i < n - 1; i++) {
                if (!river[j * n + i]) continue;
                int f = 0;
                if (river[j * n + i - 1]) f++;
                if (river[j * n + i + 1]) f++;
                if (river[(j - 1) * n + i]) f++;
                if (river[(j + 1) * n + i]) f++;
                if (f >= 2) face2++;
                if (f == 0) face0++;
            }
        }
        System.out.printf("  4 邻（共边）连续性：共边邻≥2 的河格 %d (%.1f%%) · 共边邻=0（仅对角）%d (%.1f%%)%n",
                face2, rivTotal == 0 ? 0 : 100.0 * face2 / rivTotal,
                face0, rivTotal == 0 ? 0 : 100.0 * face0 / rivTotal);
        System.out.println("    ★ 共边邻=0 占比高 ⇒ 河是对角点链（视觉上就是断续虚线）");

        // ---- 贴谷率（河是否走在谷底）----
        //   指标：河格高度 − 其 8 邻平均高度。谷底的河恒为负（低于两侧）；
        //   沿坡横切的河接近 0 或为正。现实河网应绝大多数显著为负。
        double sumDiff = 0;
        int nDiff = 0, inValley = 0;
        for (int j = 1; j < n - 1; j++) {
            for (int i = 1; i < n - 1; i++) {
                if (!river[j * n + i]) continue;
                double s = 0;
                int c = 0;
                for (int dj = -1; dj <= 1; dj++) {
                    for (int di = -1; di <= 1; di++) {
                        if (di == 0 && dj == 0) continue;
                        s += h[(j + dj) * n + (i + di)];
                        c++;
                    }
                }
                double diff = h[j * n + i] - s / c;
                sumDiff += diff;
                nDiff++;
                if (diff < -0.5) inValley++;
            }
        }
        System.out.printf("  贴谷率：河格 h − 8邻均值 平均 %.2f 块 · 低于邻域 >0.5 块的占 %.1f%%（贴谷率）%n",
                nDiff == 0 ? 0 : sumDiff / nDiff,
                nDiff == 0 ? 0 : 100.0 * inValley / nDiff);
        System.out.println("    ★ 贴谷率低（<50%）⇒ 河走在坡上而不是谷底（选线场与真实沟谷错位）");
    }

    /** 取含【河】最多的 k 行/列（剖面取样位置；海/湖不参与，否则挑到的全是海）。 */
    private static int[] topLines(boolean[] river, int n, boolean row, int k) {
        int[] cnt = new int[n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int p = j * n + i;
                if (river[p]) cnt[row ? j : i]++;
            }
        }
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Integer[] boxed = new Integer[n];
        for (int i = 0; i < n; i++) boxed[i] = idx[i];
        java.util.Arrays.sort(boxed, (a, b) -> Integer.compare(cnt[b], cnt[a]));
        int[] out = new int[Math.min(k, n)];
        for (int i = 0; i < out.length; i++) out[i] = boxed[i];
        java.util.Arrays.sort(out);
        return out;
    }

    /**
     * 垂直剖面：每行/列一条，画 地形线（灰）+ 水面填色（蓝，按水深渐变）
     * + 海平面虚线。垂直放大 {@link #EXAG}×。
     */
    private static BufferedImage sections(double[] h, double[] wTop, double sea, int n,
                                          int bx, int bz, int radius, int[] rows, int[] cols) {
        int lines = rows.length + cols.length;
        int secH = 0;
        // 统一垂直范围（全局 min/max，便于横向比较）
        double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
        for (int p = 0; p < n * n; p++) {
            mn = Math.min(mn, h[p]);
            mx = Math.max(mx, h[p]);
            if (!Double.isNaN(wTop[p])) mx = Math.max(mx, wTop[p]);
        }
        secH = (int) Math.ceil((mx - mn) * EXAG) + 8;
        int gap = 8;
        int panelW = n;
        BufferedImage img = new BufferedImage(panelW, (secH + gap) * lines,
                BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(new java.awt.Color(0x14, 0x14, 0x1A));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        int li = 0;
        for (int r : rows) {
            drawSection(g, h, wTop, sea, n, mn, mx, r, true, li * (secH + gap), secH,
                    String.format("横切 z=%d", bz - radius + r));
            li++;
        }
        for (int c : cols) {
            drawSection(g, h, wTop, sea, n, mn, mx, c, false, li * (secH + gap), secH,
                    String.format("纵切 x=%d", bx - radius + c));
            li++;
        }
        g.dispose();
        return img;
    }

    private static void drawSection(java.awt.Graphics2D g, double[] h, double[] wTop, double sea,
                                    int n, double mn, double mx, int line, boolean row,
                                    int y0, int secH, String label) {
        g.setColor(new java.awt.Color(0x8A, 0x8A, 0x92));
        g.drawString(label, 3, y0 + 11);
        g.setColor(new java.awt.Color(0x60, 0x60, 0x70));
        // 海平面
        int seaY = y0 + 2 + (int) ((mx - sea) * EXAG);
        if (seaY > y0 && seaY < y0 + secH) g.drawLine(0, seaY, n - 1, seaY);
        int prevY = -1;
        int lo = y0 + 1, hi = y0 + secH - 1;
        for (int i = 0; i < n; i++) {
            int p = row ? line * n + i : i * n + line;
            int gy = clamp(y0 + 2 + (int) ((mx - h[p]) * EXAG), lo, hi);
            // 水面：从地形到水面填色（必须夹到本面板内，否则溢出覆盖相邻面板）
            if (!Double.isNaN(wTop[p])) {
                int wy = clamp(y0 + 2 + (int) ((mx - wTop[p]) * EXAG), lo, hi);
                double depth = wTop[p] - h[p];
                int col = depth < 0.6 ? 0x7FE8D8 : depth < 2 ? 0x39B7E0
                        : depth < 6 ? 0x1F6FD0 : 0x123A80;
                g.setColor(new java.awt.Color(col));
                g.fillRect(i, Math.min(wy, gy), 1, Math.max(1, Math.abs(gy - wy)));
            }
            // 地形线
            g.setColor(new java.awt.Color(0xC8, 0xC8, 0xD0));
            if (prevY >= 0) g.drawLine(i - 1, prevY, i, gy);
            prevY = gy;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ===== 图像工具 =====

    private static BufferedImage gray(double[] h, int n) {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
        for (double v : h) { mn = Math.min(mn, v); mx = Math.max(mx, v); }
        for (int p = 0; p < n * n; p++) {
            int g = (int) (40 + 170 * (h[p] - mn) / Math.max(1e-9, mx - mn));
            img.setRGB(p % n, p / n, (g << 16) | (g << 8) | g);
        }
        return img;
    }

    /** 山体阴影（NW 光源，与项目其它探针同口径）。 */
    private static BufferedImage shade(double[] h, int n) {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        double lx = -0.5, ly = 0.7, lz = 0.51;
        double ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= ll; ly /= ll; lz /= ll;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int i0 = Math.max(0, i - 1), i1 = Math.min(n - 1, i + 1);
                int j0 = Math.max(0, j - 1), j1 = Math.min(n - 1, j + 1);
                double dx = (h[j * n + i1] - h[j * n + i0]) / (i1 - i0);
                double dz = (h[j1 * n + i] - h[j0 * n + i]) / (j1 - j0);
                double nx = -dx, ny = 1, nz = -dz;
                double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
                double d = (nx * lx + ny * ly + nz * lz) / nl;
                int g = (int) Math.max(0, Math.min(255, 60 + 175 * Math.max(0, d)));
                img.setRGB(i, j, (g << 16) | (g << 8) | g);
            }
        }
        return img;
    }

    private static BufferedImage deepCopy(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < src.getHeight(); j++) {
            for (int i = 0; i < src.getWidth(); i++) out.setRGB(i, j, src.getRGB(i, j));
        }
        return out;
    }

    private static void writeStrip(BufferedImage a, BufferedImage b, BufferedImage c,
                                   int n, File out) throws java.io.IOException {
        BufferedImage strip = new BufferedImage(n * 3 + 12, n, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                strip.setRGB(i, j, a.getRGB(i, j));
                strip.setRGB(n + 6 + i, j, b.getRGB(i, j));
                strip.setRGB(2 * n + 12 + i, j, c.getRGB(i, j));
            }
        }
        ImageIO.write(strip, "png", out);
    }

    private HydroPreviewProbe() { }
}
