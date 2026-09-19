package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.List;

/**
 * 「河宽剖面」探针（2026-09-19）—— 验收用户实机判据：
 *
 * <ol>
 *   <li><b>"有的很宽 / 有的基本不变宽"</b>：量每条河的
 *       {@code 头宽 w0 / 最大宽 wMax / 尾宽 wEnd} 与 {@code wMax/w0}。</li>
 *   <li><b>"好像没有源头"</b>：源头应【细】且【沿程变宽】。
 *       量 {@code w0 / wMax}（越接近 1 ⇒ 头端就越宽 ⇒ 看不出源头）
 *       以及"源头被裁掉"的规模（河线条数 vs 锚点数）。</li>
 * </ol>
 *
 * <p>⚠ 口径纪律（本项目已因口径错多次得假结论）：
 * {@code width} 是<b>半宽(block)</b>，由 {@code out.accum}（汇流面积 wu²）驱动；
 * 本探针只读生产折线本身，<b>不重算</b>。</p>
 *
 * <pre>{@code gradlew runRiverWidthProfileProbe [-PprobeArgs="seed rx0 rz0 rn"]}</pre>
 */
public final class RiverWidthProfileProbe {

    private RiverWidthProfileProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int rx0 = args.length > 1 ? Integer.parseInt(args[1]) : -13;
        int rz0 = args.length > 2 ? Integer.parseInt(args[2]) : -12;
        int rn = args.length > 3 ? Integer.parseInt(args[3]) : 5;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        RiverLineNetwork net = new RiverLineNetwork(gen::terrainEQuick, gen.heightCurve(), seed);

        for (int dx = 0; dx < rn; dx++) {
            for (int dz = 0; dz < rn; dz++) {
                net.region(rx0 + dx, rz0 + dz);
            }
        }
        List<RiverLineRegion> regions = net.cachedList();

        List<double[]> rows = new ArrayList<>();   // {level, n, w0, wMax, wEnd}
        int rivers = 0;
        double sumW0 = 0, sumWMax = 0;
        int headNotThin = 0;      // w0 >= 0.7*wMax ⇒ 看不出源头
        int barelyGrows = 0;      // wMax/w0 < 1.5 ⇒ "基本不变宽"
        int veryWide = 0;         // wMax >= 8 块 ⇒ "很宽"
        int singleNode = 0;

        for (RiverLineRegion r : regions) {
            for (RiverLineRegion.RiverPolyline p : r.rivers) {
                int n = p.width.length;
                if (n == 0) continue;
                rivers++;
                if (n < 3) { singleNode++; continue; }
                double w0 = p.width[0], wEnd = p.width[n - 1], wMax = 0, wMin = Double.MAX_VALUE;
                for (double w : p.width) { wMax = Math.max(wMax, w); wMin = Math.min(wMin, w); }
                rows.add(new double[]{p.level, n, w0, wMax, wEnd});
                sumW0 += w0;
                sumWMax += wMax;
                if (wMax > 1e-9 && w0 >= 0.7 * wMax) headNotThin++;
                if (w0 > 1e-9 && wMax / w0 < 1.5) barelyGrows++;
                if (wMax >= 8.0) veryWide++;
            }
        }

        System.out.printf("=== RiverWidthProfileProbe seed=%d region[%d,%d]..[%d,%d] ===%n",
                seed, rx0, rz0, rx0 + rn - 1, rz0 + rn - 1);
        System.out.printf("    河线条数 = %d（其中节点<3 的 %d 条不入统计）%n%n", rivers, singleNode);

        if (rows.isEmpty()) {
            System.out.println("    本窗口无有效河线（换 region 范围）");
            return;
        }
        System.out.printf("    %-7s %-7s %-9s %-9s %-9s %-9s%n",
                "level", "节点数", "头宽w0", "最大宽", "尾宽", "最大/头");
        System.out.println("    -------------------------------------------------------------");
        for (int i = 0; i < Math.min(16, rows.size()); i++) {
            double[] v = rows.get(i);
            System.out.printf("    %-7.0f %-7.0f %-9.3f %-9.3f %-9.3f %-9.2f%n",
                    v[0], v[1], v[2], v[3], v[4], v[3] / Math.max(1e-9, v[2]));
        }

        // ================= ★ 长度分布 + 每 region 河数（"没有源头"的可观测证据）=================
        //   判读：若【短河极少】（小河被 minDischargeArea/minRiverNodes 裁光），
        //         则河网上只有中等/大河流，观感就是"河流凭空出现、看不到上游源头/支流"。
        int[] bucket = new int[6];       // <5, <10, <20, <40, <80, >=80 节点
        for (double[] v : rows) {
            int nodes = (int) v[1];
            if (nodes < 5) bucket[0]++;
            else if (nodes < 10) bucket[1]++;
            else if (nodes < 20) bucket[2]++;
            else if (nodes < 40) bucket[3]++;
            else if (nodes < 80) bucket[4]++;
            else bucket[5]++;
        }
        System.out.printf("%n[长度分布] 节点数：<5=%d  <10=%d  <20=%d  <40=%d  <80=%d  >=80=%d%n",
                bucket[0], bucket[1], bucket[2], bucket[3], bucket[4], bucket[5]);
        System.out.printf("[密度] %d 个 region 共 %d 条河 ⇒ 平均 %.1f 条/region%n",
                regions.size(), rows.size(), rows.size() / (double) Math.max(1, regions.size()));

        int n = rows.size();
        System.out.printf("%n[汇总] n=%d%n", n);
        System.out.printf("    平均头宽 w0 = %.3f 块 ；平均最大宽 = %.3f 块%n", sumW0 / n, sumWMax / n);
        System.out.printf("    ★ 头宽 ≥ 0.7×最大宽（看不出源头）        = %d 条（%.1f%%）%n",
                headNotThin, 100.0 * headNotThin / n);
        System.out.printf("    ★ 最大/头 < 1.5（基本不变宽的河）        = %d 条（%.1f%%）%n",
                barelyGrows, 100.0 * barelyGrows / n);
        System.out.printf("    ★ 最大宽 ≥ 8 块（很宽的河）             = %d 条（%.1f%%）%n",
                veryWide, 100.0 * veryWide / n);
        System.out.println();
        System.out.println("判读：源头应【细】(w0 小) 且【沿程明显变宽】(wMax/w0 大，曲流河常 3~10)。");
        System.out.println("      若『头宽≥0.7×最大宽』占比高 ⇒ 头端不细 ⇒ 用户观感『没有源头』成立。");

        // ================= 定点诊断：打印【离给定块坐标最近那条河】的逐节点剖面 =================
        if (args.length > 5) {
            int cbx = Integer.parseInt(args[4]), cbz = Integer.parseInt(args[5]);
            double twx = cbx / 2.0, twz = cbz / 2.0;      // block → wu（hs = 2.0）
            double bestD = Double.POSITIVE_INFINITY;
            RiverLineRegion.RiverPolyline bp = null;
            int bestNode = -1;
            for (RiverLineRegion r : regions) {
                for (RiverLineRegion.RiverPolyline p : r.rivers) {
                    for (int i = 0; i < p.nodes.length; i++) {
                        double dx = p.nodes[i].x() - twx, dz = p.nodes[i].z() - twz;
                        double d = Math.sqrt(dx * dx + dz * dz);
                        if (d < bestD) { bestD = d; bp = p; bestNode = i; }
                    }
                }
            }
            System.out.printf("%n[定点] 块(%d,%d) = wu(%.1f,%.1f) 最近河节点 idx=%d 距 %.1f wu%n",
                    cbx, cbz, twx, twz, bestNode, bestD);
            if (bp == null) {
                System.out.println("    未找到河线（该窗口无河）");
                return;
            }
            int m = bp.width.length;
            System.out.printf("    该河：节点数=%d level=%d%n", m, bp.level);
            System.out.printf("    %-6s %-10s %-10s %-10s %-10s%n", "idx", "wuX", "wuZ", "半宽w", "水面Y");
            System.out.println("    ------------------------------------------------------------");
            int step2 = Math.max(1, m / 24);
            for (int i = 0; i < m; i += step2) {
                String mark = (i == 0) ? "  ← 河头" : (i + step2 >= m ? "  ← 河尾" : "");
                System.out.printf("    %-6d %-10.1f %-10.1f %-10.3f %-10.3f%s%n",
                        i, bp.nodes[i].x(), bp.nodes[i].z(), bp.width[i], bp.surfaceY[i], mark);
            }
            // 头端淡出体检：头几个节点是否真的在收窄
            int hn = Math.min(6, m);
            StringBuilder sb = new StringBuilder("    头 6 节点半宽：");
            for (int i = 0; i < hn; i++) sb.append(String.format("%.2f ", bp.width[i]));
            System.out.println(sb);
            System.out.printf("    ★ 头端/最大 = %.2f（若 ≈1.0 ⇒ 河头与满宽一样粗 ⇒ 看不出源头）%n",
                    bp.width[0] / Math.max(1e-9, maxOf(bp.width)));
        }
    }

    private static double maxOf(double[] a) {
        double mx = 0;
        for (double v : a) mx = Math.max(mx, v);
        return mx;
    }
}
