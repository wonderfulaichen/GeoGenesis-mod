package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.List;

/**
 * 「两套水量」探针（2026-09-19）—— <b>「水文与侵蚀是一个整体」的可行性测量</b>。
 *
 * <h2>要回答的问题</h2>
 * <p>项目里并存两套水量，从未对齐：</p>
 * <table border="1">
 *   <tr><th>场</th><th>来源</th><th>算法</th><th>消费方</th></tr>
 *   <tr><td>{@code cell.riverNetDischarge}</td><td>侵蚀引擎</td>
 *       <td>液滴路径重叠计数（随机粒子）</td><td>碎石坡材质、预览流量图</td></tr>
 *   <tr><td>{@code FlowField.accumAt}</td><td>河网层</td>
 *       <td>D8 面积累积（按 e 降序累加）</td><td><b>河宽 / 河深 / threshold</b></td></tr>
 * </table>
 *
 * <p>{@code ErosionEngine:273} 的注释写着目标："河网层复用 discharge 场做 riverMask，
 * <b>取代 D8 流量累积</b>" —— 本探针量"能不能取代"。</p>
 *
 * <h2>判据</h2>
 * <ol>
 *   <li><b>[1] 相关性</b>：两场在【河格】上的秩相关 —— 若高（&gt;0.8）⇒ 可统一；
 *       若低 ⇒ 是两套不同的物理，强行统一会破坏河网标定；</li>
 *   <li><b>[2] 量纲</b>：两场的取值范围（侵蚀 dis 是"粒子重叠计数"，D8 accum 是"面积"）
 *       —— 量纲不同则必须先归一化；</li>
 *   <li><b>[3] 分歧区</b>：两场对"哪里是河"的判断差异（各取 top-N% 的交集/差集）。</li>
 * </ol>
 *
 * <pre>{@code gradlew runTwoWaterBudgetProbe [-PprobeArgs="seed blockX blockZ halfSize step"]}</pre>
 */
public final class TwoWaterBudgetProbe {

    private TwoWaterBudgetProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int bx0 = args.length > 1 ? Integer.parseInt(args[1]) : -377;
        int bz0 = args.length > 2 ? Integer.parseInt(args[2]) : -335;
        int half = args.length > 3 ? Integer.parseInt(args[3]) : 64;
        int step = args.length > 4 ? Integer.parseInt(args[4]) : 1;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double hs = tp.horizontalScale();
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);

        System.out.printf("=== TwoWaterBudgetProbe seed=%d 块(%d,%d)±%d 步长=%d ===%n",
                seed, bx0, bz0, half, step);

        // ★ 生产 engine 的 network 用同一份接线（降水采样器等），不自己拼 ——
        //   本项目的教训（WaterDecisionChainProbe 注释）：自建 network 会与生产分叉。
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(gen, seed);
        // D8 accum 只能在 FlowField 里取（Cell 上没有该字段）。
        //   构造一个覆盖本区域的 FlowField，口径 = 河网选线用的 routingE（与 RiverLineNetwork 同源）。
        double regionWu = half / hs + 64;      // 块 → wu，留余量
        double c0x = bx0 / hs - regionWu, c0z = bz0 / hs - regionWu;
        com.geogenesis.worldgen.hydrology.flowaccum.FlowField field =
                new com.geogenesis.worldgen.hydrology.flowaccum.FlowField(
                        c0x, c0z, bx0 / hs + regionWu, bz0 / hs + regionWu,
                        8.0, (wx, wz) -> gen.terrainEQuick(wx, wz), null, null);

        // 逐块收集：侵蚀 discharge（cell 字段，生产口径）与河网 D8 accum
        List<double[]> pairs = new ArrayList<>();      // {dis, accum}
        List<int[]> coords = new ArrayList<>();
        double maxDis = 0, maxAcc = 0;

        for (int dz = -half; dz <= half; dz += step) {
            for (int dx = -half; dx <= half; dx += step) {
                int bx = bx0 + dx, bz = bz0 + dz;
                Cell c = gt.getChunkCells(bx >> 4, bz >> 4)
                        [Math.floorMod(bx, 16) * 16 + Math.floorMod(bz, 16)];
                double dis = c.riverNetDischarge;
                // D8 accum：按 wu 坐标落到 FlowField 的格（与河网同口径）
                double wx = bx / hs, wz = bz / hs;
                int gi = (int) Math.floor((wx - c0x) / 8.0);
                int gj = (int) Math.floor((wz - c0z) / 8.0);
                double acc = field.inBounds(gi, gj) ? field.accumAt(gj * field.cols() + gi) : 0.0;
                if (dis > 0 || acc > 0) {
                    pairs.add(new double[]{dis, acc});
                    coords.add(new int[]{bx, bz});
                    maxDis = Math.max(maxDis, dis);
                    maxAcc = Math.max(maxAcc, acc);
                }
            }
        }

        System.out.printf("[1] 有水量格 = %d（采样 %d 格）%n", pairs.size(),
                ((2 * half / step) + 1) * ((2 * half / step) + 1));
        if (pairs.isEmpty()) {
            System.out.println("    ⚠ 无水量格 —— 该区域无河（换区域或加大 half）");
            return;
        }

        // ---- [2] 量纲 ----
        double[] disArr = pairs.stream().mapToDouble(p -> p[0]).sorted().toArray();
        double[] accArr = pairs.stream().mapToDouble(p -> p[1]).sorted().toArray();
        System.out.printf("%n[2] 量纲对比%n");
        System.out.printf("    侵蚀 dis  : p50=%.2f p90=%.2f max=%.2f%n",
                pct(disArr, 0.50), pct(disArr, 0.90), maxDis);
        System.out.printf("    河网 accum: p50=%.2f p90=%.2f max=%.2f%n",
                pct(accArr, 0.50), pct(accArr, 0.90), maxAcc);

        // ---- [1] 秩相关（Spearman，抗量纲/抗偏斜） ----
        int n = pairs.size();
        double[] rankDis = ranks(pairs.stream().mapToDouble(p -> p[0]).toArray());
        double[] rankAcc = ranks(pairs.stream().mapToDouble(p -> p[1]).toArray());
        double rho = spearman(rankDis, rankAcc);
        System.out.printf("%n[1] 秩相关（Spearman，n=%d）= %.4f%n", n, rho);
        System.out.printf("    判读：>0.8 ⇒ 两套水量高度一致（可统一）；"
                + " 0.4~0.8 ⇒ 相关但不等价（需标定）；<0.4 ⇒ 两套不同物理%n");

        // ---- [3] "哪里是河"的分歧 ----
        System.out.printf("%n[3] 分歧区（各取 top-%d%% 作为\"是河\"）%n", 20);
        double disThr = pct(disArr, 0.80), accThr = pct(accArr, 0.80);
        int both = 0, onlyDis = 0, onlyAcc = 0;
        int shown = 0;
        for (int i = 0; i < n; i++) {
            boolean byDis = pairs.get(i)[0] >= disThr;
            boolean byAcc = pairs.get(i)[1] >= accThr;
            if (byDis && byAcc) both++;
            else if (byDis) {
                onlyDis++;
                if (shown < 5) {
                    System.out.printf("    仅 dis 判为河：块(%d,%d) dis=%.1f accum=%.1f%n",
                            coords.get(i)[0], coords.get(i)[1],
                            pairs.get(i)[0], pairs.get(i)[1]);
                    shown++;
                }
            } else if (byAcc) onlyAcc++;
        }
        System.out.printf("    两者皆是河 = %d   仅 dis = %d   仅 accum = %d%n",
                both, onlyDis, onlyAcc);
        System.out.printf("    交集率 = %.1f%%（相对并集）%n",
                100.0 * both / Math.max(1, both + onlyDis + onlyAcc));
        System.out.println();
        System.out.println("结论口径：若 [1] 高相关 + [3] 高交集 ⇒ 可用 dis 取代 accum（线 B 可做）；");
        System.out.println("          否则须先做【量纲归一化 + 标定】，或保留 accum（线 B 暂缓）。");
    }

    /** 分位数（输入须已升序）。 */
    private static double pct(double[] sorted, double q) {
        if (sorted.length == 0) return 0.0;
        int i = (int) Math.round(q * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    /** 平均秩（并列取平均），用于 Spearman。 */
    private static double[] ranks(double[] v) {
        int n = v.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(v[a], v[b]));
        double[] r = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && v[idx[j + 1]] == v[idx[i]]) j++;
            double avg = (i + j) / 2.0 + 1.0;
            for (int k = i; k <= j; k++) r[idx[k]] = avg;
            i = j + 1;
        }
        return r;
    }

    /** Spearman 相关系数 = 秩的 Pearson 相关。 */
    private static double spearman(double[] a, double[] b) {
        int n = a.length;
        double ma = 0, mb = 0;
        for (int i = 0; i < n; i++) { ma += a[i]; mb += b[i]; }
        ma /= n; mb /= n;
        double sab = 0, sa = 0, sb = 0;
        for (int i = 0; i < n; i++) {
            double da = a[i] - ma, db = b[i] - mb;
            sab += da * db; sa += da * da; sb += db * db;
        }
        double den = Math.sqrt(sa * sb);
        return den < 1e-12 ? 0.0 : sab / den;
    }
}
