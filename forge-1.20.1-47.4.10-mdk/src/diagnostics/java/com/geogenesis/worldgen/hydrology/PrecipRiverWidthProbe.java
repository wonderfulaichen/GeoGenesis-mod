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
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        System.out.printf("=== PrecipRiverWidthProbe seed=%d (region半径=%d → %d region) ===%n",
            seed, R, (2 * R + 1) * (2 * R + 1));

        double[][] off = collect(build(gen, seed, false), gen);
        double[][] on = collect(build(gen, seed, true), gen);
        System.out.printf("河道数: OFF=%d  ON=%d%n", off.length, on.length);

        double[] head = bucket("head 半宽（贴 minWidth，信号被压弱）", on, off, 1);
        System.out.println();
        double[] tail = bucket("tail 半宽（信号最强，推荐看这个）", on, off, 2);

        System.out.println();
        System.out.println("解析预期: 同汇水面积下 width比 = (降水比)^(exponent×widthExp) = (降水比)^0.252");
        boolean pass = head[0] < 0.95 && head[1] > 1.05 && tail[0] < 1.0 && tail[1] > 1.0;
        System.out.printf("head: 最干桶=%.3f 最湿桶=%.3f   tail: 最干桶=%.3f 最湿桶=%.3f%n",
            head[0], head[1], tail[0], tail[1]);
        System.out.println("判据: head 最干桶<0.95 且最湿桶>1.05，且 tail 最干桶<1.0 且最湿桶>1.0");
        System.out.println("（逐桶单调性不作判据：最小桶 n≈25，均值抖动 ~±0.03，仅列作趋势参考）");
        System.out.println(pass ? "=== PASS ===" : "=== FAIL ===");
    }

    /**
     * 按降水桶比较 ON/OFF 的平均半宽。
     *
     * @param idx 1 = head 半宽；2 = tail 半宽
     * @return {最干桶比值, 最湿桶比值}
     */
    private static double[] bucket(String title, double[][] on, double[][] off, int idx) {
        System.out.println("--- " + title + " ---");
        System.out.println("降水桶              | ON 河数    平均 | OFF 河数    平均 | ON/OFF");
        System.out.println("--------------------|----------------|----------------|-------");
        double first = Double.NaN, last = Double.NaN;
        for (int i = 0; i < EDGES.length - 1; i++) {
            double lo = EDGES[i], hi = EDGES[i + 1];
            double sOn = 0, sOff = 0;
            int nOn = 0, nOff = 0;
            for (double[] r : on) if (r[0] >= lo && r[0] < hi) { sOn += r[idx]; nOn++; }
            for (double[] r : off) if (r[0] >= lo && r[0] < hi) { sOff += r[idx]; nOff++; }
            if (nOn == 0 || nOff == 0) continue;
            double mOn = sOn / nOn, mOff = sOff / nOff;
            double ratio = mOn / Math.max(1e-9, mOff);
            System.out.printf("%.2f~%-14.2f | %5d %11.3f | %5d %11.3f | %.3f%n",
                lo, hi, nOn, mOn, nOff, mOff, ratio);
            if (Double.isNaN(first)) first = ratio;
            last = ratio;
        }
        return new double[]{first, last};
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
