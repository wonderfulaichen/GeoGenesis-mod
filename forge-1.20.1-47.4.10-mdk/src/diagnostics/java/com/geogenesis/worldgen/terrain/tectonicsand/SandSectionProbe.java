package com.geogenesis.worldgen.terrain.tectonicsand;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 沙箱剖面探针：沿<b>边界法向</b>切一条剖面，逐项画出高度分量。
 *
 * <p><b>为什么必须有这个</b>：只看俯视图调参数会做出"俯视看着热闹、剖面完全不合地质"
 * 的地形。只有剖面能一眼看出"这个峰是谁给的、符号对不对"。</p>
 *
 * <p>分量见 {@link SandTerrain.Parts}（2026-11-14 清理后只剩 4 项：
 * base / orogen / trench / detail，旧版另 6 项恒为 0 已删）。</p>
 *
 * <p>用法：{@code SandSectionProbe <outDir> <kind>}，kind ∈ {ridge, trench, orogen}：</p>
 * <ul>
 *   <li>{@code ridge}  —— 洋壳、强拉张边界（洋中脊剖面）</li>
 *   <li>{@code trench} —— 洋壳、强挤压边界（俯冲带剖面）</li>
 *   <li>{@code orogen} —— 陆壳、强挤压边界（碰撞造山剖面）</li>
 * </ul>
 *
 * <p>产出：{@code section_<kind>.png}（曲线图）+ 控制台数值表。</p>
 */
public final class SandSectionProbe {

    private static final long SEED = 9139912035078620160L;
    private static final double R = 1750.0;
    private static final double SCAN_STEP = 20.0;
    /** 剖面半长（wu）与采样步长（wu）。 */
    private static final double HALF = 700.0;
    private static final double STEP = 4.0;
    private static final double BLOCKS_PER_E = 236.0;

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "build/sand-tectonic");
        String kind = args.length > 1 ? args[1] : "ridge";
        Files.createDirectories(out);

        SandPlateField.Sample found = pick(kind);
        if (found == null) {
            System.out.println("!! 未找到满足条件的边界点: " + kind);
            return;
        }
        double px = found.warpedX(), pz = found.warpedZ();
        System.out.printf(Locale.ROOT,
                "=== section %s  anchor=(%.1f, %.1f)  n=(%.3f, %.3f)  crust0=%.2f  divN0=%.2f ===%n",
                kind, px, pz, found.nx(), found.nz(),
                SandTerrain.crust01(found.seed(), px, pz),
                SandTerrain.boundary(found).div() / SandTerrain.DIV_SCALE);

        int n = (int) (2 * HALF / STEP) + 1;
        double[] t = new double[n];
        SandTerrain.Parts[] ps = new SandTerrain.Parts[n];
        double[] tot = new double[n];
        double[] crust = new double[n];
        double[] divArr = new double[n];

        for (int i = 0; i < n; i++) {
            t[i] = -HALF + i * STEP;
            double qx = px + t[i] * found.nx();
            double qz = pz + t[i] * found.nz();
            SandPlateField.Sample s = SandPlateField.sample(SEED, qx, qz);
            ps[i] = SandTerrain.partsOf(s);
            tot[i] = ps[i].sum();
            crust[i] = SandTerrain.crust01(s.seed(), s.warpedX(), s.warpedZ());
            divArr[i] = SandTerrain.boundary(s).div() / SandTerrain.DIV_SCALE;
        }

        // 数值表（每 40wu 一行，避免刷屏）
        System.out.printf(Locale.ROOT, "%7s %8s %8s %8s %8s %8s %6s %6s%n",
                "t", "h(格)", "base", "orogen", "trench", "detail", "crust", "divN");
        for (int i = 0; i < n; i += (int) (40 / STEP)) {
            SandTerrain.Parts p = ps[i];
            System.out.printf(Locale.ROOT,
                    "%7.0f %8.1f %8.3f %8.3f %8.3f %8.3f %6.2f %6.2f%n",
                    t[i], tot[i] * BLOCKS_PER_E, p.base(), p.orogen(),
                    p.trench(), p.detail(), crust[i], divArr[i]);
        }

        draw(out.resolve("section_" + kind + ".png"), kind, t, ps, tot, crust, divArr);
        System.out.println("section -> " + out.resolve("section_" + kind + ".png"));
    }

    /** 扫描区域，挑一个"最典型"的边界点（满足类型条件且 |divN| 最大）。 */
    private static SandPlateField.Sample pick(String kind) {
        SandPlateField.Sample best = null;
        double bestScore = -1e9;
        // 「找不到」时把候选分布打出来，免得盲调阈值（2026-11-14 加了这条才看出 div 恒负）
        double divMinSea = 1e9, divMaxSea = -1e9, divMinAll = 1e9, divMaxAll = -1e9;
        int nSea = 0;
        for (double wz = -R; wz <= R; wz += SCAN_STEP) {
            for (double wx = -R; wx <= R; wx += SCAN_STEP) {
                SandPlateField.Sample s = SandPlateField.sample(SEED, wx, wz);
                if (s.dist() > 14.0 * SandTerrain.DEG_WU) {
                    // ⚠ 单位是 wu：原来直接写 14（本意是 14°）⇒ 只扫到 14wu 的窄带，
                //   全图只剩 34 个候选，且全在深海俯冲带 ⇒ "ridge" 永远找不到。
                    continue;
                }
                SandTerrain.Boundary bd = SandTerrain.boundary(s);
                double divN = bd.div() / SandTerrain.DIV_SCALE;
                double land = SandTerrain.crust01(s.seed(), s.warpedX(), s.warpedZ());
                divMinAll = Math.min(divMinAll, divN);
                divMaxAll = Math.max(divMaxAll, divN);
                if (land <= 0.15) {
                    nSea++;
                    divMinSea = Math.min(divMinSea, divN);
                    divMaxSea = Math.max(divMaxSea, divN);
                }
                double score;
                switch (kind) {
                    case "ridge" -> {
                        // 0.9 太严：加权速度场的 div 在低陆区很少超过 0.9（实测全图找不到）
                        if (divN < 0.1 || land > 0.15) {
                            continue;
                        }
                        score = divN - land;
                    }
                    case "trench" -> {
                        if (divN > -0.9 || land > 0.15) {
                            continue;
                        }
                        score = -divN - land;
                    }
                    case "orogen" -> {
                        if (divN > -0.9 || land < 0.45) {
                            continue;
                        }
                        score = -divN + land;
                    }
                    default -> throw new IllegalArgumentException("kind: " + kind);
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = s;
                }
            }
        }
        if (best == null) {
            System.out.printf(Locale.ROOT,
                    "   [候选分布] divN 全边界 min=%.2f max=%.2f；其中低壳值(land≤0.15) %d 点 "
                            + "min=%.2f max=%.2f%n",
                    divMinAll, divMaxAll, nSea, divMinSea, divMaxSea);
        }
        return best;
    }

    // ============================================================ 绘图

    /** 顺序 = 总高度 + {@link #comps} 的 4 项 + crust。 */
    private static final Color[] COLORS = {
            new Color(30, 30, 30),      // total
            new Color(120, 120, 120),   // base
            new Color(200, 60, 0),      // orogen
            new Color(0, 140, 200),     // trench
            new Color(220, 200, 120),   // detail
            new Color(0, 180, 60)       // crust（右轴语义，仅作参照）
    };
    private static final String[] NAMES = {"总高度", "板基准", "构造抬升", "构造下沉", "细节", "壳值 c"};

    private static void draw(Path p, String kind, double[] t, SandTerrain.Parts[] ps,
                             double[] tot, double[] crust, double[] divN) throws Exception {
        int w = 1400, h = 760;
        int ml = 90, mr = 90, mt = 70, mb = 80;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);

        // 值域（格）
        double lo = 1e9, hi = -1e9;
        for (int i = 0; i < tot.length; i++) {
            lo = Math.min(lo, tot[i] * BLOCKS_PER_E);
            hi = Math.max(hi, tot[i] * BLOCKS_PER_E);
            for (double c : comps(ps[i])) {
                lo = Math.min(lo, c * BLOCKS_PER_E);
                hi = Math.max(hi, c * BLOCKS_PER_E);
            }
        }
        double pad = Math.max(6, (hi - lo) * 0.08);
        lo -= pad;
        hi += pad;
        final double loF = lo, hiF = hi;

        int pw = w - ml - mr, ph = h - mt - mb;
        java.util.function.DoubleUnaryOperator X =
                tx -> ml + (tx - t[0]) / (t[t.length - 1] - t[0]) * pw;
        java.util.function.DoubleUnaryOperator Y =
                vy -> mt + (hiF - vy) / (hiF - loF) * ph;

        // 网格 + 轴标签
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.setColor(new Color(230, 230, 230));
        double yStep = niceStep(hi - lo, 8);
        for (double vy = Math.ceil(lo / yStep) * yStep; vy <= hi; vy += yStep) {
            int yy = (int) Y.applyAsDouble(vy);
            g.drawLine(ml, yy, ml + pw, yy);
            g.setColor(new Color(90, 90, 90));
            g.drawString(String.format(Locale.ROOT, "%.0f", vy), 20, yy + 5);
            g.setColor(new Color(230, 230, 230));
        }
        for (double tx = Math.ceil(t[0] / 100) * 100; tx <= t[t.length - 1]; tx += 100) {
            int xx = (int) X.applyAsDouble(tx);
            g.setColor(new Color(235, 235, 235));
            g.drawLine(xx, mt, xx, mt + ph);
            g.setColor(new Color(90, 90, 90));
            g.drawString(String.format(Locale.ROOT, "%.0f", tx), xx - 12, mt + ph + 22);
        }
        // 海平面
        if (lo < 0 && hi > 0) {
            g.setColor(new Color(120, 160, 220));
            g.setStroke(new BasicStroke(1.4f));
            int y0 = (int) Y.applyAsDouble(0);
            g.drawLine(ml, y0, ml + pw, y0);
            g.drawString("海平面", ml + pw - 60, y0 - 6);
        }
        // 边界位置（t=0）
        g.setColor(new Color(200, 60, 60));
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[]{6f, 5f}, 0f));
        int x0 = (int) X.applyAsDouble(0);
        g.drawLine(x0, mt, x0, mt + ph);
        g.setStroke(new BasicStroke(1f));

        g.setColor(Color.DARK_GRAY);
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g.drawString("沿边界法向距离 t (wu)", ml, h - 28);
        g.drawString("高度 (格)", 20, mt - 30);
        g.setFont(new Font("SansSerif", Font.BOLD, 18));
        g.drawString("剖面: " + kind, ml, 34);

        // 曲线
        double[][] series = new double[5][t.length];
        for (int i = 0; i < t.length; i++) {
            series[0][i] = tot[i] * BLOCKS_PER_E;
            double[] c = comps(ps[i]);
            for (int k = 0; k < 4; k++) {
                series[k + 1][i] = c[k] * BLOCKS_PER_E;
            }
        }
        for (int k = 0; k < series.length; k++) {
            g.setColor(COLORS[k]);
            g.setStroke(new BasicStroke(k == 0 ? 2.6f : 1.5f));
            int prevX = -1, prevY = -1;
            for (int i = 0; i < t.length; i++) {
                int xx = (int) X.applyAsDouble(t[i]);
                int yy = (int) Y.applyAsDouble(series[k][i]);
                yy = Math.max(mt - 40, Math.min(mt + ph + 40, yy));
                if (prevX >= 0) {
                    g.drawLine(prevX, prevY, xx, yy);
                }
                prevX = xx;
                prevY = yy;
            }
        }

        // 图例（壳值 c 只在数值表里看，量纲不同不叠曲线）
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        int lx = ml + 14, ly = mt + 14;
        for (int k = 0; k < 5; k++) {
            g.setColor(COLORS[k]);
            g.fillRect(lx, ly - 9, 22, 4);
            g.setColor(Color.DARK_GRAY);
            g.drawString(NAMES[k], lx + 28, ly);
            ly += 17;
        }
        g.dispose();
        ImageIO.write(img, "png", p.toFile());
    }

    /** 4 个分量（顺序与 {@link SandTerrain.Parts} 一致）。 */
    private static double[] comps(SandTerrain.Parts p) {
        return new double[]{p.base(), p.orogen(), p.trench(), p.detail()};
    }

    private static double niceStep(double range, int target) {
        double raw = range / target;
        double mag = Math.pow(10, Math.floor(Math.log10(Math.max(1e-9, raw))));
        for (double m : new double[]{1, 2, 2.5, 5, 10}) {
            if (raw <= m * mag) {
                return m * mag;
            }
        }
        return 10 * mag;
    }
}