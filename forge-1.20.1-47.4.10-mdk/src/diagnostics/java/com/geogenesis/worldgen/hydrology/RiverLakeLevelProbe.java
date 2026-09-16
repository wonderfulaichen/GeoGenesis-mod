package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 「河成湖摊平」验证探针（2026-09-17）—— <b>先验证，不动产出</b>。
 *
 * <h2>待验证的假说</h2>
 * <p>实测发现一个 <b>13,685 格、水位恒定 166.627</b> 的水体，其下地形由 166 平滑降到 131
 * （水深最深 20 块）。`RiverLineNetwork` 里能造成「整段恒定水面」的只有一处：
 * {@code detectLakeReaches}（河成湖）——</p>
 * <pre>
 *   double level = rs[end];                 // 整段取【下游端】水位
 *   for (k=start..end) lv[k] = level;       // 整段摊平
 *   width = max(width, 2.0) * LAKE_WIDEN;   // 展宽 3 倍
 *   frozen = true;                          // 硬切不混合
 * </pre>
 * <p>而其判定条件<b>只有「水面坡度 &lt; 0.002」</b>，而水面本身已被 PAVA 单调回归压平
 * ⇒ <b>自我循环</b>：平台被自己的算法制造、又被当作「湖」摊平展宽。</p>
 *
 * <h2>本探针做什么</h2>
 * <ol>
 *   <li>打印本 region 内 【LakeNode】（真·洼地湖）数量；</li>
 *   <li>逐条河打印 {@code lakeLevel} 中非 NaN 的**段**（起止节点、长度、水位、该段河床降幅）；</li>
 *   <li>对给定查询点附近，打印它是否落在某个「河成湖段」内。</li>
 * </ol>
 *
 * <p><b>判读</b>：若目标点落在非 NaN 的 {@code lakeLevel} 段内 ⇒ 假说成立
 * （恒定水面来自河成湖摊平）⇒ 可删 {@code detectLakeReaches} 并用品尺验收。</p>
 *
 * <pre>{@code gradlew runRiverLakeLevelProbe [-PprobeArgs="seed rx rz qx qz"]}</pre>
 */
public final class RiverLakeLevelProbe {

    private RiverLakeLevelProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 5436529513624899584L;
        int rx = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int rz = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        double qx = args.length > 3 ? Double.parseDouble(args[3]) : 12.0;    // 用户报告坐标 wu
        double qz = args.length > 4 ? Double.parseDouble(args[4]) : 316.0;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        RiverLineParams rp = RiverLineParams.defaults();
        double hs = tp.horizontalScale();

        // ⚠ 必须用【廉价地形口径】：terrainY 若传 sampleWu，构建 region 会对整片网格
        //   触发侵蚀 tile 冷生成（实测数分钟）—— 这正是本会话反复踩的性能陷阱。
        //   廉价口径 = heightFromE(terrainEQuick)，与 groundYAt 同口径；
        //   河成湖段的【结构】（哪段被摊平）不受影响，只影响绝对值。
        RiverLineNetwork net = new RiverLineNetwork(
                (wx, wz) -> gen.terrainEQuick(wx, wz),
                (wx, wz) -> gen.heightCurve().heightFromE(gen.terrainEQuick(wx, wz)),
                gen.heightCurve(), seed, hs, rp);

        // 查询点所在 region 与相邻（河跨 region）
        int qrx = (int) Math.floor(qx / rp.regionSize());
        int qrz = (int) Math.floor(qz / rp.regionSize());
        System.out.printf("=== RiverLakeLevelProbe seed=%d 查询 wu(%.0f,%.0f) → region(%d,%d) ===%n",
                seed, qx, qz, qrx, qrz);

        int lakeSegs = 0, lakeNodes = 0, totalNodes = 0;
        double lakeLenSum = 0;
        RiverLineRegion near = null;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                RiverLineRegion r = net.region(qrx + dx, qrz + dz);
                if (r == null) continue;
                if (dx == 0 && dz == 0) near = r;
                System.out.printf("%nregion(%d,%d)：河 %d 条，LakeNode(真洼地湖) %d 个%n",
                        r.rx, r.rz, r.rivers.size(), r.lakes.size());
                for (int i = 0; i < r.rivers.size(); i++) {
                    RiverLineRegion.RiverPolyline pl = r.rivers.get(i);
                    // ★ 河宽分布（定位"宽 54 wu"的异常）：正常河宽仅数 wu
                    double wmax = 0, wmin = Double.MAX_VALUE, wsum = 0;
                    int wi = 0;
                    for (int t = 0; t < pl.width.length; t++) {
                        double w = pl.width[t];
                        wsum += w;
                        if (w > wmax) { wmax = w; wi = t; }
                        wmin = Math.min(wmin, w);
                    }
                    if (wmax > 20) {   // 只报异常宽的河
                        System.out.printf("    ★河[%d] 长度 %d 节点，宽 min=%.2f max=%.2f(节点%d) 均值=%.2f wu"
                                        + " ⇒ 异常宽（正常仅数 wu）%n",
                                i, pl.width.length, wmin, wmax, wi, wsum / pl.width.length);
                    }
                    if (pl.lakeLevel == null) continue;
                    // 扫描非 NaN 段
                    int k = 0;
                    while (k < pl.lakeLevel.length) {
                        if (Double.isNaN(pl.lakeLevel[k])) { k++; continue; }
                        int s = k;
                        double lv = pl.lakeLevel[k];
                        while (k < pl.lakeLevel.length && !Double.isNaN(pl.lakeLevel[k])) k++;
                        int e = k - 1;
                        double len = 0;
                        for (int t = s; t < e; t++) {
                            len += Math.hypot(pl.nodes[t + 1].x() - pl.nodes[t].x(),
                                    pl.nodes[t + 1].z() - pl.nodes[t].z());
                        }
                        // 该段河床降幅（首末节点地形）
                        double yS = pl.terrainY != null ? pl.terrainY[s] : Double.NaN;
                        double yE = pl.terrainY != null ? pl.terrainY[e] : Double.NaN;
                        lakeSegs++;
                        lakeNodes += e - s + 1;
                        lakeLenSum += len;
                        System.out.printf("    河[%d] 成湖段 节点 %d..%d（%d 节点，长 %.0f wu）"
                                        + " 湖面 %.3f  首末地形 %.2f→%.2f（降 %.2f）%n",
                                i, s, e, e - s + 1, len, lv, yS, yE, yS - yE);
                    }
                    totalNodes += pl.nodes.length;
                }
            }
        }

        System.out.println();
        System.out.printf("汇总：河成湖段 %d 段（%d 节点 / 总 %d），累计长 %.0f wu%n",
                lakeSegs, lakeNodes, totalNodes, lakeLenSum);

        // 查询点：是否落在河成湖段内 + 该处河面 vs 地形
        if (near != null) {
            RiverLineNetwork.RiverLineHit hit = net.sample(qx, qz);
            System.out.printf("查询点 wu(%.0f,%.0f)：%s%n", qx, qz,
                    hit == null ? "无河线命中" :
                            String.format("命中河线（距中心 %.2f，宽 %.2f，水面 %.3f，isLake=%s）",
                                    hit.distToCenter(), hit.width(), hit.surfaceY(), hit.isLake()));
            double g = gen.heightCurve().heightFromE(gen.terrainEQuick(qx, qz));
            System.out.printf("  该点地形 %.3f ⇒ 若命中，水面−地形 = %.3f 块%n",
                    g, hit == null ? 0.0 : hit.surfaceY() - g);
        }
        System.out.println();
        System.out.println("判读：若上面出现【长距离成湖段】且其下「首末地形降幅」很大（数十块）");
        System.out.println("      ⇒ 假说成立：恒定水面来自 detectLakeReaches 的整段摊平。");
    }
}
