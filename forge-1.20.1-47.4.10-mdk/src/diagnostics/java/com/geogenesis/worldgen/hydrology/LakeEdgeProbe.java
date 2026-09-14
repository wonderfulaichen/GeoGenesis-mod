package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.flowaccum.FlowField;
import com.geogenesis.worldgen.hydrology.riverline.MidpointDisplacement;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 湖泊边缘诊断探针（★ 2026-09-15）—— 用户反馈："湖泊填充边缘容易没到地形边缘就结束，
 * 提前在边缘的前一个区块就停止填充"。
 *
 * <h3>要区分的两个假设</h3>
 * <ol>
 *   <li><b>边界泄水</b>：{@code FlowField.computeFill} 把网格<b>最外一圈</b>强制设为出水口
 *       （{@code eFilled = eFill}，FlowField.java:238-245）⇒ 边界格水深恒为 0
 *       ⇒ 贴边界的洼地不成湖。</li>
 *   <li><b>跨 region 不一致</b>：{@code RiverLineNetwork.build} 每个 region 独立建网格、
 *       独立跑 {@code computeFill}（网格 = region ± 50% margin，RiverLineNetwork.java:396-404）
 *       ⇒ 相邻 region 对同一世界坐标算出<b>不同的溢出高程</b> ⇒ 湖在接缝处水位台阶/提前结束。</li>
 * </ol>
 *
 * <h3>为何可精确复现（无需完整 build）</h3>
 * <p>{@code computeFill} 只用 {@code fillSampler}（真实地形 {@code groundYAt}）做
 * <b>8 邻优先队列扩散</b>，<b>不消费 D8 流向/汇流场</b> ⇒ 只需 fillSampler 与生产同口径
 * （{@code RiverLineNetwork.groundYAt}，public），结果即逐位一致；选线场如何影响不影响本诊断。
 * 又因 {@code groundYAt} 在 {@code terrainY == null} 时回退到
 * {@code curve.heightFromE(eSampler.eAt(...))}（RiverLineNetwork.java:2209-2210），
 * 与生产接线等价 ⇒ 可用 3 参构造。</p>
 *
 * <pre>{@code gradlew runLakeEdgeProbe [-PprobeArgs="seed rx rz"]}</pre>
 */
public final class LakeEdgeProbe {

    private LakeEdgeProbe() { }

    /** 镜像 {@code RiverLineNetwork.build} 396-404 行的网格 + origin。 */
    private record Grid(FlowField f, double minX, double minZ) { }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int rX = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int rZ = args.length > 2 ? Integer.parseInt(args[2]) : 0;

        System.out.printf("=== LakeEdgeProbe seed=%d region=(%d,%d) ===%n", seed, rX, rZ);

        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, p.minY(), p.maxY());
        gen.seed(seed);
        RiverLineParams params = RiverLineParams.defaults();
        HeightCurve curve = gen.heightCurve();

        MidpointDisplacement.ElevationSampler eSampler = gen::terrainEQuick;
        // terrainY 传 null ⇒ groundYAt 走 curve.heightFromE(eSampler) 分支（与生产等价）
        RiverLineNetwork net = new RiverLineNetwork(eSampler, curve, seed);

        double regionSize = params.regionSize();
        double cell = params.gridCell();
        double margin = regionSize * 0.5;
        System.out.printf("regionSize=%.0f wu  gridCell=%.0f wu  margin=%.0f wu（region 的 50%%）%n",
                regionSize, cell, margin);

        // ---------- ① 单 region：检查边界圈是否被强制泄水 ----------
        Grid ga = buildGrid(rX, rZ, regionSize, cell, margin, eSampler);
        ga.f().computeFill(net::groundYAt, curve.seaLevelY());
        FlowField a = ga.f();
        int nx = a.cols(), nz = a.rows();
        System.out.printf("[1] 网格 %d×%d（含边界圈）%n", nx, nz);

        int rimTotal = 0, rimZero = 0, innerBasin = 0, innerTotal = 0;
        double rimMaxDepth = 0;
        for (int j = 0; j < nz; j++) {
            for (int i = 0; i < nx; i++) {
                int idx = j * nx + i;
                boolean rim = (i == 0 || i == nx - 1 || j == 0 || j == nz - 1);
                double d = a.basinDepthAt(idx);
                if (rim) {
                    rimTotal++;
                    if (d <= 0) rimZero++;
                    rimMaxDepth = Math.max(rimMaxDepth, d);
                } else {
                    innerTotal++;
                    if (a.isBasinCell(idx)) innerBasin++;
                }
            }
        }
        System.out.printf("[2] 边界圈: 格数=%d  水深==0 的=%d（%.1f%%）  最大水深=%.4f%n",
                rimTotal, rimZero, 100.0 * rimZero / rimTotal, rimMaxDepth);
        System.out.printf("    内部: 格数=%d  洼地格=%d（%.2f%%）%n",
                innerTotal, innerBasin, 100.0 * innerBasin / innerTotal);
        System.out.println("    → 若边界圈 100% 水深为 0，则【假设1 边界泄水】成立："
                + "贴近网格边界的洼地永远不成湖。");

        // ---------- ② 相邻 region：同一世界坐标的溢出高程是否一致 ----------
        Grid gb = buildGrid(rX + 1, rZ, regionSize, cell, margin, eSampler);
        gb.f().computeFill(net::groundYAt, curve.seaLevelY());
        FlowField b = gb.f();

        double loX = Math.max(ga.minX(), gb.minX());
        double hiX = Math.min(ga.minX() + (nx - 1) * cell,
                gb.minX() + (b.cols() - 1) * cell);
        double loZ = Math.max(ga.minZ(), gb.minZ());
        double hiZ = Math.min(ga.minZ() + (nz - 1) * cell,
                gb.minZ() + (b.rows() - 1) * cell);

        long overlap = 0, differ = 0;
        double maxAbsDiff = 0, sumDiff = 0;
        double worstWx = 0, worstWz = 0, worstA = 0, worstB = 0;
        for (double wx = loX; wx <= hiX; wx += cell) {
            for (double wz = loZ; wz <= hiZ; wz += cell) {
                int ia = a.indexOf(wx, wz);
                int ib = b.indexOf(wx, wz);
                if (ia < 0 || ib < 0) continue;
                overlap++;
                double fa = a.filledAt(ia), fb = b.filledAt(ib);
                double d = Math.abs(fa - fb);
                sumDiff += d;
                if (d > 1e-6) differ++;
                if (d > maxAbsDiff) {
                    maxAbsDiff = d; worstWx = wx; worstWz = wz; worstA = fa; worstB = fb;
                }
            }
        }
        System.out.printf("[3] 相邻 region(%d,%d) vs (%d,%d) 重叠区: 取样=%d  水位不一致=%d"
                        + "（%.1f%%）%n", rX, rZ, rX + 1, rZ, overlap, differ,
                100.0 * differ / Math.max(1, overlap));
        System.out.printf("    最大差异=%.4f block（在 wu(%.0f,%.0f)：A=%.3f vs B=%.3f）%n",
                maxAbsDiff, worstWx, worstWz, worstA, worstB);
        System.out.printf("    平均差异=%.4f block%n", sumDiff / Math.max(1, overlap));
        System.out.println("    → 若不一致比例显著，则【假设2 跨 region 不一致】成立："
                + "湖跨越 region 接缝时水位会跳变/被截断。");

        // ---------- ③ 沿一条穿过 region 边界的剖面打印 ----------
        double seam = (rX + 1) * regionSize;
        System.out.println("[4] 剖面（沿 X 穿过两 region 接缝，接缝在 wx=" + seam + "）:");
        System.out.printf("    %10s %10s %10s %10s %8s %8s%n",
                "wx", "eFill", "eFilled", "水深", "A洼地", "B洼地");
        double z0 = rZ * regionSize + regionSize * 0.5;
        for (double wx = seam - 6 * cell; wx <= seam + 6 * cell; wx += cell) {
            int ia = a.indexOf(wx, z0);
            int ib = b.indexOf(wx, z0);
            if (ia < 0) continue;
            double eF = a.fillEAt(ia), eFd = a.filledAt(ia), dep = a.basinDepthAt(ia);
            boolean ba = a.isBasinCell(ia);
            boolean bb = ib >= 0 && b.isBasinCell(ib);
            System.out.printf("    %10.0f %10.3f %10.3f %10.3f %8s %8s%n",
                    wx, eF, eFd, dep, ba ? "Y" : "-", ib < 0 ? "n/a" : (bb ? "Y" : "-"));
        }

        // ---------- 判据 ----------
        boolean leak = rimZero == rimTotal;
        System.out.printf("[判据1] 边界圈水深恒为 0（= 确认边界泄水缺陷）: %s（%d/%d）%n",
                leak ? "确认" : "未确认（说明另有原因）", rimZero, rimTotal);

        boolean consistent = differ == 0;
        System.out.printf("[判据2] 相邻 region 水位完全一致（= 无跨 region 不一致）: %s%n",
                consistent ? "PASS（该假设可排除）" : "FAIL（假设2 成立）");

        System.out.println();
        if (leak && !consistent) {
            System.out.println("  ★ 两个假设【都成立】：边界泄水 + 跨 region 不一致。"
                    + " 修复需同时处理（网格全球对齐扩边 + 哨兵出水口）。");
        } else if (leak && consistent) {
            System.out.println("  ★ 仅【边界泄水】成立。修复重点：网格扩边 + 用哨兵代替'边界即出口'。");
        } else if (!leak) {
            System.out.println("  ★ 边界圈并非全 0 —— 需重新审视 computeFill 的实现。");
        }
    }

    private static Grid buildGrid(int rx, int rz, double regionSize, double cell,
                                  double margin, MidpointDisplacement.ElevationSampler sampler) {
        double minX = rx * regionSize - margin, maxX = rx * regionSize + regionSize + margin;
        double minZ = rz * regionSize - margin, maxZ = rz * regionSize + regionSize + margin;
        return new Grid(new FlowField(minX, minZ, maxX, maxZ, cell, sampler), minX, minZ);
    }
}
