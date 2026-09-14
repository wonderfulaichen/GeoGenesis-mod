package com.geogenesis.worldgen.terrain;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 碎石坡「脊 / 沟」区分度探针（★ 2026-09-14）。
 *
 * <h3>用户反馈</h3>
 * <p>「碎石堆好像都在山脊上，正常不应该在山谷、山沟壑处吗？」
 * —— <b>该观察在物理上是对的</b>：真实 talus（岩屑坡）是重力碎屑在<b>凹坡坡脚</b>堆积；
 * 脊线是凸地形，碎屑会滚落，故脊上不应有碎石坡。
 *
 * <h3>根因假设（已被代码证实）</h3>
 * <p>现行判据只有<b>坡度幅值</b>：
 * </p>
 * <pre>
 *   cell.gradient = √(dhx² + dhz²)        // CellGenerator:1660
 *   steepened &gt; SCREE_GRADIENT(0.30)    // GeoGenesisGenerator:507
 * </pre>
 * <p>而 {@code √(dhx²+dhz²)} <b>恒为正</b> ⇒ 山脊坡面与沟谷侧壁数值相同
 * ⇒ 一起被判为碎石坡。<b>判据缺"凹凸"维度</b>。
 *
 * <h3>本探针要回答</h3>
 * <ol>
 *   <li>陡坡像素中，脊 / 沟各占多少？（现状 = 无区分）</li>
 *   <li>{@code cell.riverNetDischarge}（液滴汇流累积）<b>能否</b>区分脊与沟？</li>
 *   <li>若能，门控取什么阈值，可使"脊上碎石"显著减少、而沟中保留？</li>
 *   <li>作为对照：局部曲率（二阶差分）的区分度如何？</li>
 * </ol>
 *
 * <h3>为何 discharge 是首选</h3>
 * <ul>
 *   <li><b>物理正确</b>：汇流累积 = 收敛度，沟壑高、脊线低</li>
 *   <li><b>零额外采样</b>：字段已存在（{@code Cell.riverNetDischarge}）</li>
 *   <li><b>与 gradient 同源</b>：两者都只在完整管线（{@code applyTileDelta}）里有
 *       ⇒ "轻量路径不判碎石"是一致行为，<b>不会造成预览≠游戏</b></li>
 * </ul>
 *
 * <h3>输出（build/scree/）</h3>
 * <ul>
 *   <li>{@code curv.png} —— 曲率（红=脊 / 蓝=沟 / 绿=平）</li>
 *   <li>{@code steep_current.png} —— 现状碎石判定（陡坡）</li>
 *   <li>{@code steep_gated.png} —— 加 discharge 门控后的碎石判定</li>
 * </ul>
 *
 * <p>用法：{@code gradlew runScreePlacementProbe [-PprobeArgs="seed chunks ox oz"]}</p>
 */
public final class ScreePlacementProbe {

    /** 与生产一致的坡度阈值（{@code GeoGenesisGenerator.SCREE_GRADIENT}）。 */
    private static final float SCREE_GRADIENT = 0.30f;

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int chunks = args.length > 1 ? Integer.parseInt(args[1]) : 12;
        int ox = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        int oz = args.length > 3 ? Integer.parseInt(args[3]) : 0;

        System.out.printf("=== ScreePlacementProbe seed=%d chunks=%d origin=(%d,%d) ===%n",
                seed, chunks, ox, oz);

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);

        int px = chunks * 16;
        int baseCX = Math.floorDiv(ox, 16), baseCZ = Math.floorDiv(oz, 16);

        // ---------- 采样完整管线（含 gradient / discharge / 侵蚀后 height）----------
        double[][] height = new double[px][px];
        float[][] grad = new float[px][px];
        double[][] disch = new double[px][px];
        double[][] eLand = new double[px][px];
        for (int cx = 0; cx < chunks; cx++) {
            for (int cz = 0; cz < chunks; cz++) {
                Cell[] cells = terrain.getChunkCells(baseCX + cx, baseCZ + cz);
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        Cell c = cells[lx * 16 + lz];
                        int gx = cx * 16 + lx, gz = cz * 16 + lz;
                        if (c == null) continue;
                        height[gz][gx] = c.height;
                        grad[gz][gx] = c.gradient;
                        disch[gz][gx] = c.riverNetDischarge;
                        eLand[gz][gx] = c.eLand;
                    }
                }
            }
        }

        // ---------- 曲率（二阶差分）：凹为正（沟）、凸为负（脊）----------
        //   5 点 Laplacian 的负：curv = mean(4 邻) − center
        double[][] curv = new double[px][px];
        for (int y = 1; y < px - 1; y++) {
            for (int x = 1; x < px - 1; x++) {
                double mean = (height[y][x + 1] + height[y][x - 1]
                        + height[y + 1][x] + height[y - 1][x]) * 0.25;
                curv[y][x] = mean - height[y][x];   // 正 = 凹（沟/坡脚）
            }
        }

        // ---------- 统计 ----------
        //   分两组：陡坡像素（现状碎石候选）中的【脊】与【沟】
        //   分组依据用曲率符号（曲率是"真值"参照，discharge 是被检验的判据）
        List<Double> dRidge = new ArrayList<>();   // 陡 + 凸（脊）
        List<Double> dValley = new ArrayList<>();  // 陡 + 凹（沟）
        List<Double> cRidge = new ArrayList<>();
        List<Double> cValley = new ArrayList<>();
        int nSteep = 0, nLand = 0;
        final double CURV_EPS = 0.02;   // 避免把纯平面当脊/沟
        for (int y = 2; y < px - 2; y++) {
            for (int x = 2; x < px - 2; x++) {
                if (eLand[y][x] <= 0.0) continue;    // 只统计陆地
                nLand++;
                if (grad[y][x] <= SCREE_GRADIENT) continue;
                nSteep++;
                double c = curv[y][x];
                double d = disch[y][x];
                if (c < -CURV_EPS) { dRidge.add(d); cRidge.add(c); }
                else if (c > CURV_EPS) { dValley.add(d); cValley.add(c); }
            }
        }
        System.out.printf("[1] 陆地像素=%d  陡坡(>%.2f)像素=%d (%.1f%% of 陆地)%n",
                nLand, (double) SCREE_GRADIENT, nSteep, 100.0 * nSteep / Math.max(1, nLand));
        if (nSteep == 0) {
            System.out.println("    该区域无陡坡像素 —— 换 origin 或增大 chunks 重试。");
            return;
        }
        System.out.printf("    其中 脊(凸)像素=%d (%.1f%%)  沟(凹)像素=%d (%.1f%%)  平坦=%d%n",
                dRidge.size(), 100.0 * dRidge.size() / nSteep,
                dValley.size(), 100.0 * dValley.size() / nSteep,
                nSteep - dRidge.size() - dValley.size());

        double[] ridgeArr = toArr(dRidge);
        double[] valleyArr = toArr(dValley);
        java.util.Arrays.sort(ridgeArr);
        java.util.Arrays.sort(valleyArr);

        System.out.println("[2] discharge（液滴汇流累积）在脊/沟上的分布:");
        System.out.printf("    脊  median=%.1f  p90=%.1f  max=%.1f  n=%d%n",
                quantile(ridgeArr, 0.5), quantile(ridgeArr, 0.9), last(ridgeArr), ridgeArr.length);
        System.out.printf("    沟  median=%.1f  p90=%.1f  max=%.1f  n=%d%n",
                quantile(valleyArr, 0.5), quantile(valleyArr, 0.9), last(valleyArr), valleyArr.length);

        // AUC：discharge 作为"判为沟"的判别器（1.0 = 完美，0.5 = 无效）
        double auc = auc(valleyArr, ridgeArr);
        System.out.printf("    AUC(discharge 区分 沟>脊) = %.3f   %s%n", auc,
                auc > 0.75 ? "→ 强判别力，可用作门控 ✓"
                        : (auc > 0.62 ? "→ 中等判别力，可用但需配阈值调优"
                        : "→ 判别力不足，需引入曲率等更强判据"));

        // ---------- 门控模拟 ----------
        //   规则：scree = steep && discharge > D。D 取"脊的 p50 / p75 / p90"
        System.out.println("[3] 门控模拟（scree = 陡 && discharge > D）:");
        double[] cands = {
                quantile(ridgeArr, 0.50),
                quantile(ridgeArr, 0.75),
                quantile(ridgeArr, 0.90),
                quantile(valleyArr, 0.25)
        };
        for (double d : cands) {
            int keepRidge = 0, keepValley = 0;
            for (double v : ridgeArr) if (v > d) keepRidge++;
            for (double v : valleyArr) if (v > d) keepValley++;
            System.out.printf("    D=%7.2f  →  保留脊 %5d/%5d (%4.1f%%)   保留沟 %5d/%5d (%4.1f%%)%n",
                    d, keepRidge, ridgeArr.length, 100.0 * keepRidge / Math.max(1, ridgeArr.length),
                    keepValley, valleyArr.length, 100.0 * keepValley / Math.max(1, valleyArr.length));
        }
        System.out.println("    期望：高档位 D 应【大量砍掉脊、保留多数沟】——即脊/沟保留率之比显著 <1。");

        // ---------- 曲率作为对照判据 ----------
        System.out.println("[4] 对照：曲率判据（scree = 陡 && 凹/平，即 curv > -eps）:");
        System.out.printf("    保留脊 0/%d (0%%，因定义排除凸)   保留沟 %d/%d (100%%，因定义含凹)%n",
                ridgeArr.length, valleyArr.length, valleyArr.length);
        System.out.println("    注：曲率需邻域高度（tile 网格）—— 若采用需确认轻量路径可取得性。");

        // ---------- 输出图 ----------
        File outDir = new File("build/scree");
        outDir.mkdirs();
        writeCurv(outDir, "curv", curv, px, CURV_EPS);
        writeSteep(outDir, "steep_current", grad, eLand, px, 0.0);
        // 用中位脊值当门控阈值画示意
        double dMid = quantile(ridgeArr, 0.75);
        writeSteep(outDir, "steep_gated", grad, eLand, px, dMid);
        // 需要 discharge 参与 → 单独画一版
        writeGated(outDir, "steep_gated", grad, disch, eLand, px, dMid);
        System.out.println("[5] 输出目录: " + outDir.getAbsolutePath());
    }

    private static double[] toArr(List<Double> l) {
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    private static double last(double[] a) { return a.length == 0 ? 0 : a[a.length - 1]; }

    private static double quantile(double[] sorted, double q) {
        if (sorted.length == 0) return 0;
        int i = (int) Math.round(q * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    /**
     * AUC：{@code pos}（沟）的取值应整体大于 {@code neg}（脊）。
     * 用秩和公式，O(n log n)。
     */
    private static double auc(double[] pos, double[] neg) {
        if (pos.length == 0 || neg.length == 0) return 0.5;
        double[] all = new double[pos.length + neg.length];
        System.arraycopy(pos, 0, all, 0, pos.length);
        System.arraycopy(neg, 0, all, pos.length, neg.length);
        Integer[] idx = new Integer[all.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(all[a], all[b]));
        double[] rank = new double[all.length];
        int i = 0;
        while (i < idx.length) {
            int j = i;
            while (j + 1 < idx.length && all[idx[j + 1]] == all[idx[i]]) j++;
            double avg = (i + j) / 2.0 + 1.0;
            for (int k = i; k <= j; k++) rank[idx[k]] = avg;
            i = j + 1;
        }
        double rankSumPos = 0;
        for (int k = 0; k < pos.length; k++) rankSumPos += rank[k];
        double n1 = pos.length, n0 = neg.length;
        return (rankSumPos - n1 * (n1 + 1) / 2.0) / (n1 * n0);
    }

    // ------------------------------ 渲染 ------------------------------

    private static void writeCurv(File dir, String name, double[][] curv, int px, double eps)
            throws Exception {
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
        double mx = eps * 6;
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                double c = Math.max(-mx, Math.min(mx, curv[y][x]));
                int t = (int) Math.round(255.0 * Math.abs(c) / mx);
                int r = c > 0 ? 40 : 255 - t;           // 凹=蓝偏
                int g = 120;
                int b = c > 0 ? 255 : 40;
                if (c > 0) { r = 255 - t; g = 160; b = 255; }
                else { r = 255; g = 160 - t / 2; b = 255 - t; }
                img.setRGB(x, y, (clamp(r) << 16) | (clamp(g) << 8) | clamp(b));
            }
        }
        ImageIO.write(img, "png", new File(dir, name + ".png"));
    }

    private static void writeSteep(File dir, String name, float[][] grad, double[][] eLand,
                                   int px, double unusedD) throws Exception {
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                int col = 0x202020;
                if (eLand[y][x] > 0) col = 0x3A5A3A;
                if (grad[y][x] > SCREE_GRADIENT) col = 0xE0A020;
                if (grad[y][x] > 0.40f) col = 0xD04020;
                img.setRGB(x, y, col);
            }
        }
        ImageIO.write(img, "png", new File(dir, name + ".png"));
    }

    private static void writeGated(File dir, String name, float[][] grad, double[][] disch,
                                   double[][] eLand, int px, double dThresh) throws Exception {
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                int col = 0x202020;
                if (eLand[y][x] > 0) col = 0x3A5A3A;
                if (grad[y][x] > SCREE_GRADIENT) {
                    col = disch[y][x] > dThresh ? 0xE0A020 : 0x6A6A6A;   // 通过=橙，被砍=灰
                }
                img.setRGB(x, y, col);
            }
        }
        ImageIO.write(img, "png", new File(dir, name + ".png"));
    }

    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
}
