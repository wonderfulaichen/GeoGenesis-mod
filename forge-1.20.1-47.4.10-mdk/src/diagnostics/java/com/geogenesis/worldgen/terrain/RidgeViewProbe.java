package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.terrain.morph.MorphTerrainSampler;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * <b>洋中脊「俯视 + 剖面」双视图（2026-11-17）</b>——用户要求两个视图必须成对。
 *
 * <p>左：俯视 hillshade（真实坡度 × 固定垂直夸张，跨缩放一致）。<br>
 * 右：横剖面曲线（最终 e / 年龄律 / 脊分量 / 构造资格），横轴为「垂直于脊轴的
 * 有符号距离」。</p>
 *
 * <p>轴心定位：先找深海（e&lt;−0.18）ridge 最大点（脊肩），再沿垂直方向找
 * ridge 最小点（谷底）作为剖面原点。剖面从谷底向两侧取 ±{@code half}wu。</p>
 *
 * <p>用法：{@code gradlew runRidgeViewProbe}（默认 (1600,1600) ±400wu）。</p>
 */
public final class RidgeViewProbe {

    private static final long SEED = 9139912035078620160L;
    private static final int TOP_N = 520;
    private static final int SEC_W = 620;
    private static final int H = 520;

    public static void main(String[] args) throws Exception {
        double cx = args.length > 0 ? Double.parseDouble(args[0]) : 1600;
        double cz = args.length > 1 ? Double.parseDouble(args[1]) : 1600;
        double half = args.length > 2 ? Double.parseDouble(args[2]) : 400;

        MorphTerrainSampler smp = new MorphTerrainSampler(SEED,
                TerrainParams.defaults(), -64, 320);
        TectonicField tectonic = new TectonicField(SEED);

        // ---- 0) 无显式坐标时：全世界扫描「孤立离散边界」----
        //   判据（只用构造场 + 少量 CrustField，便宜）：
        //     ① distRaw < 15wu（在边界上） ② -stress > 0.10（离散体制）
        //     ③ 两侧 ±550wu：distRaw 单调上升（无第二条边界）
        //     ④ 两侧 550wu 处 eBase < −0.25（深海，远离陆架）
        boolean autoMode = args.length == 0;
        if (autoMode) {
            double bScore = -1e9, bX = 0, bZ = 0;
            for (double zz = -8000; zz <= 8000; zz += 150) {
                for (double xx = -8000; xx <= 8000; xx += 150) {
                    var t0 = tectonic.sample(xx, zz);
                    if (t0.distRaw() > 15 || -t0.stress() < 0.10) {
                        continue;
                    }
                    // ★ 法向用 distRaw 梯度（唯一可靠定义），并要求两侧距离
                    //   近似线性增长（= 边中段；交汇点两侧会突变/不对称）。
                    double h = 8;
                    double gx = tectonic.sample(xx + h, zz).distRaw()
                            - tectonic.sample(xx - h, zz).distRaw();
                    double gz = tectonic.sample(xx, zz + h).distRaw()
                            - tectonic.sample(xx, zz - h).distRaw();
                    double gl = Math.hypot(gx, gz);
                    if (gl < 1e-6) { continue; }
                    double ppx = gx / gl, ppz = gz / gl;
                    double prev = t0.distRaw(), drop = 0, sum = 0;
                    boolean midEdge = true;
                    boolean deepOk = true;
                    for (double t = 50; t <= 550; t += 50) {
                        var ta = tectonic.sample(xx + ppx * t, zz + ppz * t);
                        var tb = tectonic.sample(xx - ppx * t, zz - ppz * t);
                        if (ta.distRaw() < prev * 0.85 || tb.distRaw() < prev * 0.85) {
                            drop++;
                        }
                        prev = Math.min(prev, Math.min(ta.distRaw(), tb.distRaw()));
                        sum += Math.min(ta.distRaw(), tb.distRaw());
                        // 边中段：两侧距离 ≈ 对称、≥0.6t（无交汇/弯曲）
                        if (Math.abs(ta.distRaw() - tb.distRaw()) > 0.5 * t
                                || ta.distRaw() < 0.6 * t || tb.distRaw() < 0.6 * t) {
                            midEdge = false;
                        }
                        if (t == 550) {
                            // 深海 + 两侧都不是陆地（eBase 只管洋底，陆地在 land 层）
                            deepOk = CrustField.of(ta, xx + ppx * t, zz + ppz * t, SEED).eBase() < -0.25
                                    && CrustField.of(tb, xx - ppx * t, zz - ppz * t, SEED).eBase() < -0.25
                                    && smp.sampleColumn(xx + ppx * t, zz + ppz * t).e < -0.20
                                    && smp.sampleColumn(xx - ppx * t, zz - ppz * t).e < -0.20;
                        }
                    }
                    if (!deepOk || !midEdge) {
                        continue;
                    }
                    double score = sum - drop * 1e5;
                    if (score > bScore) {
                        bScore = score;
                        bX = xx;
                        bZ = zz;
                    }
                }
            }
            if (bScore < -1e8) {
                System.out.println("[FAIL] 全世界没有孤立离散深海边界");
                System.exit(1);
            }
            cx = bX;
            cz = bZ;
            half = Math.max(half, 500);
            System.out.printf(Locale.ROOT,
                    "[worldScan] isolated divergent boundary @ (%.0f,%.0f) score %.0f -> window ±%.0fwu%n",
                    cx, cz, bScore, half);
        }

        // ---- 1) 找深海脊肩（ridge 最大）----
        int M = 160;
        double bestR = -1, bx = cx, bz = cz;
        for (int j = 0; j < M; j++) {
            double wz = cz - half + 2 * half * j / (M - 1);
            for (int i = 0; i < M; i++) {
                double wx = cx - half + 2 * half * i / (M - 1);
                var c = smp.sampleColumn(wx, wz);
                // ★ 判深用「裸构造基面」（c.e 含脊抬升，脊肩处会 > −0.18 而误排除）
                if (c.oceanFeat == null || c.oceanFeat.baseE > -0.22) {
                    continue;
                }
                if (c.oceanFeat.ridge > bestR) {
                    bestR = c.oceanFeat.ridge;
                    bx = wx;
                    bz = wz;
                }
            }
        }
        if (bestR < 0) {
            System.out.println("[FAIL] 窗口内无深海脊");
            System.exit(1);
        }
        var ts0 = tectonic.sample(bx, bz);
        double px = -ts0.tangentZ(), pz = ts0.tangentX();
        double len = Math.hypot(px, pz);
        if (len < 1e-6) { px = 1; pz = 0; } else { px /= len; pz /= len; }

        double axX = cx, axZ = cz;
        double bestV = 9, bestScore = -1e9;
        if (!autoMode) {
        // ---- 2) 轴心选点（仅显式坐标时；自动模式已由 worldScan 给出边中段点）----
        //   三个约束缺一不可：
        //   ① 深海（e < −0.18）：浅水处 ridgeFade 已把脊压没；
        //   ② 在边界上（distRaw < 40wu）；
        //   ③ **孤立边界**：沿垂直方向 ±550wu 内 distRaw 单调上升（≤1 次小回落）
        //      —— 否则剖面中途会切到另一条边界，age 律/剖面全乱
        //      （实测窗口内多条边界，剖面前后自相矛盾）。
        //   ⚠ 前两版都错：
        //     · 沿垂直方向扫 ridge 最小 → 跑到带外坡脚（profile=0 也是最小值）
        //     · 带内 ridge 最小 → 也是坡脚（0 < 谷底 0.06）
        //   轴心不是「ridge 最小」，是「distRaw=0」——用构造距离直接定位。
        double pX0 = px, pZ0 = pz;
        for (int j = 0; j < M; j++) {
            double wz = cz - half + 2 * half * j / (M - 1);
            for (int i = 0; i < M; i++) {
                double wx = cx - half + 2 * half * i / (M - 1);
                var c = smp.sampleColumn(wx, wz);
                if (c.oceanFeat == null || c.oceanFeat.baseE > -0.22) {
                    continue;
                }
                var tsC = tectonic.sample(wx, wz);
                double dr = tsC.distRaw();
                if (dr > 40) {
                    continue;
                }
                // ③ 孤立性：沿垂直方向 distRaw 应单调上升
                double ppx = -tsC.tangentZ(), ppz = tsC.tangentX();
                double l2 = Math.hypot(ppx, ppz);
                if (l2 < 1e-6) { ppx = 1; ppz = 0; } else { ppx /= l2; ppz /= l2; }
                double prev = dr, drop = 0, inc = 0;
                for (double t = 25; t <= 550; t += 25) {
                    double d1 = tectonic.sample(wx + ppx * t, wz + ppz * t).distRaw();
                    double d2 = tectonic.sample(wx - ppx * t, wz - ppz * t).distRaw();
                    if (d1 < prev * 0.8 || d2 < prev * 0.8) {
                        drop++;
                    }
                    prev = Math.min(prev, Math.min(d1, d2));
                    inc += Math.min(d1, d2);
                }
                double score = inc - drop * 1e4;   // 无回落优先，再比距离总和
                if (score > bestScore) {
                    bestScore = score;
                    bestV = dr;
                    axX = wx;
                    axZ = wz;
                    pX0 = ppx;
                    pZ0 = ppz;
                }
            }
        }
        px = pX0;
        pz = pZ0;
        }   // end !autoMode

        if (autoMode) {
            axX = cx;
            axZ = cz;
            px = 1;
            pz = 0;
        }
        // ★ 法向改用 distRaw 的数值梯度（唯一可靠的定义：距离增长最快的方向
        //   就是垂直于边界的方向）。tangentX/Z 是场量、在边界弯折处不可靠。
        {
            double h = 8;
            double gx = tectonic.sample(axX + h, axZ).distRaw()
                    - tectonic.sample(axX - h, axZ).distRaw();
            double gz = tectonic.sample(axX, axZ + h).distRaw()
                    - tectonic.sample(axX, axZ - h).distRaw();
            double gl = Math.hypot(gx, gz);
            if (gl > 1e-6) {
                px = gx / gl;
                pz = gz / gl;
            }
        }
        System.out.printf(Locale.ROOT, "[normal] perpendicular = (%.3f, %.3f)%n", px, pz);
        System.out.printf(Locale.ROOT,
                "[locate] shoulder %.3fe @(%.0f,%.0f)  axis: distRaw %.1f @(%.0f,%.0f)  isolationScore %.0f%n",
                bestR, bx, bz, bestV, axX, axZ, bestScore);

        // ---- 3) 俯视图（以轴心为中心）----
        double[] eT = new double[TOP_N * TOP_N];
        double[] rT = new double[TOP_N * TOP_N];
        double eMin = 9, eMax = -9;
        for (int j = 0; j < TOP_N; j++) {
            double wz = axZ - half + 2 * half * j / (TOP_N - 1);
            for (int i = 0; i < TOP_N; i++) {
                double wx = axX - half + 2 * half * i / (TOP_N - 1);
                var c = smp.sampleColumn(wx, wz);
                int k = j * TOP_N + i;
                eT[k] = c.e;
                rT[k] = c.oceanFeat == null ? 0 : c.oceanFeat.ridge;
                eMin = Math.min(eMin, c.e);
                eMax = Math.max(eMax, c.e);
            }
        }
        BufferedImage top = new BufferedImage(TOP_N, TOP_N, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < TOP_N; j++) {
            for (int i = 0; i < TOP_N; i++) {
                int k = j * TOP_N + i;
                double t = (eT[k] - eMin) / Math.max(1e-9, eMax - eMin);
                int r = (int) (24 + 200 * t * t);
                int g = (int) (50 + 170 * t);
                int b = (int) (110 + 130 * t);
                top.setRGB(i, j, (r << 16) | (g << 8) | b);
            }
        }
        double cellWu = 2 * half / (TOP_N - 1);
        double blocksPerCell = cellWu * 2.0;
        for (int j = 0; j < TOP_N; j++) {
            for (int i = 0; i < TOP_N; i++) {
                int i0 = Math.max(0, i - 1), i1 = Math.min(TOP_N - 1, i + 1);
                int j0 = Math.max(0, j - 1), j1 = Math.min(TOP_N - 1, j + 1);
                double gx = (eT[j * TOP_N + i1] - eT[j * TOP_N + i0]) * 127 / blocksPerCell
                        * 12 / Math.max(1, i1 - i0);
                double gy = (eT[j1 * TOP_N + i] - eT[j0 * TOP_N + i]) * 127 / blocksPerCell
                        * 12 / Math.max(1, j1 - j0);
                double nx = -gx, ny = -gy, nz = 1;
                double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
                double dd = (-nx * 0.6 - ny * 0.6 + nz * 0.53) / l;
                double sh = 0.45 + 0.9 * Math.max(0, Math.min(1, dd));
                int c = top.getRGB(i, j);
                int r = Math.min(255, (int) (((c >> 16) & 0xFF) * sh));
                int g = Math.min(255, (int) (((c >> 8) & 0xFF) * sh));
                int b = Math.min(255, (int) ((c & 0xFF) * sh));
                top.setRGB(i, j, (r << 16) | (g << 8) | b);
            }
        }

        // ---- 4) 剖面（沿 -perp..+perp）----
        int NS = 401;
        double[] eS = new double[NS], aS = new double[NS], rS = new double[NS], uS = new double[NS];
        double lo = 9, hi = -9;
        for (int k = 0; k < NS; k++) {
            double t = -half + 2 * half * k / (NS - 1);
            double wx = axX + px * t, wz = axZ + pz * t;
            var c = smp.sampleColumn(wx, wz);
            var ts = tectonic.sample(wx, wz);
            var cs = CrustField.of(ts, wx, wz, SEED);
            eS[k] = c.e;
            aS[k] = cs.eBase();
            rS[k] = c.oceanFeat == null ? 0 : c.oceanFeat.ridge;
            uS[k] = c.oceanFeat == null ? 0 : c.oceanFeat.ridgeEnvelope;
            lo = Math.min(lo, Math.min(eS[k], aS[k]));
            hi = Math.max(hi, Math.max(eS[k], aS[k]));
        }
        double pad = 0.02;
        lo -= pad;
        hi += pad;

        // ---- 5) 拼图 ----
        BufferedImage out = new BufferedImage(TOP_N + SEC_W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), H);
        g.drawImage(top, 0, 60, null);

        int M2 = 52;
        int x0 = TOP_N + M2;
        int plotW = SEC_W - 2 * M2;
        int plotH = H - 2 * M2;
        g.setColor(new Color(235, 235, 235));
        for (int k = 0; k <= 10; k++) {
            int yy = M2 + plotH * k / 10;
            g.drawLine(x0, yy, x0 + plotW, yy);
        }
        // 轴心竖线
        g.setColor(new Color(180, 180, 180));
        int xc = x0 + plotW / 2;
        g.drawLine(xc, M2, xc, M2 + plotH);
        // ridge 分量：单独缩放到底部 35% 区域（它不是 e 绝对值，只是增量）
        double maxR = 1e-9;
        for (double v : rS) {
            maxR = Math.max(maxR, v);
        }
        double[] rScaled = new double[NS];
        for (int k = 0; k < NS; k++) {
            rScaled[k] = lo + (rS[k] / maxR) * (hi - lo) * 0.35;
        }
        drawCurve(g, rScaled, NS, x0, plotW, M2, plotH, lo, hi, new Color(30, 90, 200));
        // age
        drawCurve(g, aS, NS, x0, plotW, M2, plotH, lo, hi, new Color(155, 155, 155));
        // final e
        drawCurve(g, eS, NS, x0, plotW, M2, plotH, lo, hi, Color.BLACK);

        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        g.setColor(Color.BLACK);
        g.drawString(String.format(Locale.ROOT,
                "TOP VIEW  %.0fx%.0f wu  (%.1f wu/px)  center=(%.0f,%.0f)",
                2 * half, 2 * half, 2 * half / TOP_N, axX, axZ), 8, 22);
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.drawString("e range [%.3f, %.3f]e  (127 blocks/e)".formatted(eMin, eMax), 8, 40);
        g.drawString("CROSS SECTION (perpendicular to axis)", x0 - 10, 22);
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        g.setColor(Color.BLACK);
        g.drawString("black = final e", x0, 40);
        g.setColor(new Color(30, 90, 200));
        g.drawString("blue = ridge component", x0 + 110, 40);
        g.setColor(new Color(130, 130, 130));
        g.drawString("gray = age-law base", x0 + 270, 40);
        g.setColor(Color.BLACK);
        g.drawString(String.format(Locale.ROOT, "%.3fe", hi), 6, M2 + 4);
        g.drawString(String.format(Locale.ROOT, "%.3fe", lo), 6, M2 + plotH);
        g.drawString(String.format(Locale.ROOT, "-%.0fwu", half), x0 - 14, H - 12);
        g.drawString("0 (axis)", xc - 16, H - 12);
        g.drawString(String.format(Locale.ROOT, "+%.0fwu", half), x0 + plotW - 40, H - 12);
        g.dispose();

        Path dir = Path.of("build/ridge-view");
        Files.createDirectories(dir);
        Path file = dir.resolve("ridge-view.png");
        ImageIO.write(out, "png", file.toFile());
        System.out.println("image -> " + file.toAbsolutePath());

        // ---- 6) 剖面关键值表 ----
        System.out.println("  off(wu)     e     age    ridge   envelope");
        for (int k = 0; k < NS; k += NS / 20) {
            double t = -half + 2 * half * k / (NS - 1);
            System.out.printf(Locale.ROOT, "%9.0f  %6.3f  %6.3f  %6.3f  %8.2f%n",
                    t, eS[k], aS[k], rS[k], uS[k]);
        }
    }

    private static void drawCurve(Graphics2D g, double[] v, int n, int x0, int w,
                                  int y0, int h, double lo, double hi, Color col) {
        g.setColor(col);
        int px = -1, py = -1;
        for (int k = 0; k < n; k++) {
            int xx = x0 + w * k / (n - 1);
            int yy = (int) (y0 + h - (v[k] - lo) / Math.max(1e-9, hi - lo) * h);
            if (px >= 0) {
                g.drawLine(px, py, xx, yy);
            }
            px = xx;
            py = yy;
        }
    }

    private RidgeViewProbe() { }
}