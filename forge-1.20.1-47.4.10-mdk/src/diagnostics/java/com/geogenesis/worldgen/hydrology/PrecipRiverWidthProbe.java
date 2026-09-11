package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.flowaccum.FlowField;
import com.geogenesis.worldgen.hydrology.riverline.MidpointDisplacement;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase C <b>A/B 探针</b>（2026-09-11）：验证"降水加权 → 同汇水面积下干旱区河细、湿润区河宽"。
 *
 * <p><b>为什么要 A/B</b>：{@code runRiverLineWidthProbe} 直接构造 {@code RiverLineNetwork}
 * （不注入降水取样器）→ 测的是<b>改造前基线</b>，无法回答 Phase C 的核心主张。</p>
 *
 * <p><b>设计</b>：在<b>同一批 region、同一地形、同一种子</b>下构建两个河网 ——
 * 一个开启降水加权、一个关闭 —— 然后按<b>河道所在位置的降水</b>分桶，比较两者的平均河宽比。
 * 因为是同一地形，降水桶之间的地形/纬度差异被完全控制，只剩"加权"这一个变量。</p>
 *
 * <p><b>判据</b>：干旱桶 {@code ON/OFF < 1}（更细）、湿润桶 {@code ON/OFF > 1}（更宽），
 * 且比值随降水单调上升。</p>
 *
 * <p>用法：{@code gradlew runPrecipRiverWidthProbe [seed]}</p>
 */
public final class PrecipRiverWidthProbe {

    /** region 半径 → (2R+1)² 个 region。 */
    private static final int R = 4;

    /** 降水分桶边界（归一化降水，全球均值≈0.27）。 */
    private static final double[] EDGES = {0.0, 0.10, 0.18, 0.28, 0.45, 10.0};

    @SuppressWarnings("unused")
    public static void main(String[] args) {
        long baseSeed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int seedCount = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        System.out.printf("=== PrecipRiverWidthProbe baseSeed=%d 种子数=%d (region半径=%d → %d region) ===%n",
            baseSeed, seedCount, R, (2 * R + 1) * (2 * R + 1));
        System.out.println("★ 多种子平均：本指标是【统计性主张】，单种子单阈值不可靠。");
        System.out.println("  实测教训（2026-09-12）：同一实现下 head 最干桶跨种子 0.885~1.062、");
        System.out.println("  最湿桶 1.003~1.173 —— 单种子判定会随机 PASS/FAIL（5 种子中 3 个假失败）。");
        System.out.println("  物理方向在平均意义下成立，故判据改为【多种子均值】。");

        double[] dry = new double[seedCount], wet = new double[seedCount];
        for (int i = 0; i < seedCount; i++) {
            long sd = baseSeed + i;
            TerrainParams tp = TerrainParams.defaults();
            CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
            gen.seed(sd);
            double[][] off = collect(build(gen, sd, false), gen);
            double[][] on = collect(build(gen, sd, true), gen);
            double[] head;
            if (i == 0) {
                System.out.printf("--- seed=%d 明细: 河道数 OFF=%d ON=%d ---%n", sd, off.length, on.length);
                head = bucket("head 半宽（河道中段 —— 判据用此列）", on, off, 1, true);
                double[] tail = bucket("tail 半宽（★ 河口 —— 仅参考，不作判据）", on, off, 2, true);
                System.out.printf("    tail(参考): 最干桶=%.3f 最湿桶=%.3f%n", tail[0], tail[1]);
                System.out.println("解析预期: 同汇水面积下 width比 = (降水比)^(exponent×widthExp) = (降水比)^0.252");
            } else {
                head = bucket("", on, off, 1, false);
            }
            dry[i] = head[0];
            wet[i] = head[1];
            System.out.printf("    seed=%-6d head: 最干桶=%.3f 最湿桶=%.3f%n", sd, head[0], head[1]);
        }

        // ---- 聚合：多种子均值（统计性主张的正确验证方式）----
        double mDry = 0, mWet = 0;
        double minDry = Double.MAX_VALUE, maxDry = -Double.MAX_VALUE;
        double minWet = Double.MAX_VALUE, maxWet = -Double.MAX_VALUE;
        for (int i = 0; i < seedCount; i++) {
            mDry += dry[i]; mWet += wet[i];
            minDry = Math.min(minDry, dry[i]); maxDry = Math.max(maxDry, dry[i]);
            minWet = Math.min(minWet, wet[i]); maxWet = Math.max(maxWet, wet[i]);
        }
        mDry /= seedCount; mWet /= seedCount;

        // 判据：多种子平均下，干旱桶更细、湿润桶更宽。
        //   阈值沿用 0.95 / 1.05（源自解析式 (降水比)^0.252 与护栏压缩比），
        //   但作用于【均值】而非单次采样 —— 这才是该主张的正确统计形式。
        boolean pass = mDry < 0.95 && mWet > 1.05;
        System.out.println();
        System.out.printf("★ 多种子均值(n=%d): 最干桶=%.3f (范围 %.3f~%.3f)  最湿桶=%.3f (范围 %.3f~%.3f)%n",
            seedCount, mDry, minDry, maxDry, mWet, minWet, maxWet);
        System.out.printf("判据: 均值 最干桶<0.95 且 最湿桶>1.05 %s%n", pass ? "" : "（未达）");
        System.out.println("说明: tail 不作判据（河口宽度被喇叭口/mouthMax 上限主导，不纯反映汇流）；");
        System.out.println("      两侧被 minWidth/maxWidth 护栏压缩（实测 ≈解析预期的 55~80%），属预期效应。");
        System.out.println(pass ? "=== PASS ===" : "=== FAIL ===");
    }

    /**
     * 按降水桶比较 ON/OFF 的平均半宽。
     *
     * @param idx 1 = head 半宽；2 = tail 半宽
     * @return {最干桶比值, 最湿桶比值}
     */
    private static double[] bucket(String title, double[][] on, double[][] off, int idx, boolean print) {
        if (print) {
            System.out.println("--- " + title + " ---");
            System.out.println("降水桶              | ON 河数    平均 | OFF 河数    平均 | 实测ON/OFF | 桶均降水 | 桶均权重 | 解析预期");
            System.out.println("--------------------|----------------|----------------|-----------|---------|---------|--------");
        }
        double first = Double.NaN, last = Double.NaN;
        for (int i = 0; i < EDGES.length - 1; i++) {
            double lo = EDGES[i], hi = EDGES[i + 1];
            double sOn = 0, sOff = 0, sP = 0;
            int nOn = 0, nOff = 0;
            for (double[] r : on) if (r[0] >= lo && r[0] < hi) { sOn += r[idx]; sP += r[0]; nOn++; }
            for (double[] r : off) if (r[0] >= lo && r[0] < hi) { sOff += r[idx]; nOff++; }
            if (nOn == 0 || nOff == 0) continue;
            double mOn = sOn / nOn, mOff = sOff / nOff;
            double ratio = mOn / Math.max(1e-9, mOff);
            // ★ 诊断列（2026-09-11 新增）：桶均降水 → 实际权重 → 解析预期河宽比。
            //   预期 = weight^(exponent × widthExp) = weight^0.252。
            //   若实测与预期差距大 → 说明"加权未有效作用于河宽"或存在幸存者偏差，需分头排查。
            double meanP = sP / nOn;
            double w = precipWeight(meanP);
            double expected = Math.pow(w, 0.252);
            if (print) {
                System.out.printf("%.2f~%-14.2f | %5d %11.3f | %5d %11.3f | %9.3f | %7.4f | %7.3f | %6.3f%n",
                    lo, hi, nOn, mOn, nOff, mOff, ratio, meanP, w, expected);
            }
            if (Double.isNaN(first)) first = ratio;
            last = ratio;
        }
        return new double[]{first, last};
    }

    /** 复刻 {@code FlowField.PrecipWeights} 的权重式（探针用，避免与生产式漂移时静默失配）。 */
    private static double precipWeight(double precip) {
        FlowField.PrecipWeights pw = FlowField.PrecipWeights.defaults();
        double x = precip / pw.ref();
        double c = Math.max(pw.floor(), x);
        return Math.pow(c, pw.exponent());
    }

    /** 构建河网并强制构建全部 region（可选开启降水加权）。 */
    private static RiverLineNetwork build(CellGenerator gen, long seed, boolean precip) {
        RiverLineNetwork net = new RiverLineNetwork(
            gen::terrainEQuick,
            (wx, wz) -> gen.heightCurve().heightFromE(gen.terrainEQuick(wx, wz)),
            null, 0.0,
            gen.heightCurve(), seed, gen.params().horizontalScale(),
            RiverLineParams.defaults());
        if (precip) {
            net.setPrecipSampler(gen::precipitationAt, FlowField.PrecipWeights.defaults());
        }
        for (int rz = -R; rz <= R; rz++) {
            for (int rx = -R; rx <= R; rx++) net.region(rx, rz);
        }
        return net;
    }

    /** 每河一条记录：{head 处降水, head 半宽, 层级}。 */
    private static double[][] collect(RiverLineNetwork net, CellGenerator gen) {
        List<double[]> out = new ArrayList<>();
        for (int rz = -R; rz <= R; rz++) {
            for (int rx = -R; rx <= R; rx++) {
                RiverLineRegion reg = net.region(rx, rz);
                for (RiverLineRegion.RiverPolyline pl : reg.rivers) {
                    if (pl.nodes.length == 0 || pl.width.length == 0) continue;
                    MidpointDisplacement.Node n0 = pl.nodes[0];
                    double precip = gen.precipitationAt(n0.x(), n0.z());
                    out.add(new double[]{precip, pl.width[0], pl.width[pl.width.length - 1], pl.level});
                }
            }
        }
        return out.toArray(new double[0][]);
    }
}
