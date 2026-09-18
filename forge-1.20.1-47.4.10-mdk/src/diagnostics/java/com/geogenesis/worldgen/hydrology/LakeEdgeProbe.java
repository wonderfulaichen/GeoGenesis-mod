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

    /**
     * 镜像网格 + origin。
     *
     * <p>⚠ <b>2026-09-19 起本类的 {@link #buildGrid} 是【遗留（未对齐）公式】，
     * 不再是生产现状</b> —— 生产已改为对齐原点（见 {@link #buildAlignedGrid} 与
     * {@code RiverLineNetwork.build} 的修复注释）。保留未对齐版作【对照组】，
     * 以便随时复现"对齐前 → 对齐后"的差距。生产现状用 {@link #buildAlignedGrid}。</p>
     */
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

        // ---------- ⑤ ★ T2.4 修复候选（零风险验证）：网格原点【全球对齐】 ----------
        //   假说：跨 region 不一致的【直接机制】是网格原点 = rx*regionSize − margin（region 相关）
        //        ⇒ 同一世界点在两个 region 落在【不同的格】上 ⇒ filledAt 必然不同。
        //   项目在 PRECIP 上已有正确范式（"格点取 k·PRECIP_STEP_WU（世界坐标），与 region 无关
        //   ⇒ 杜绝 region 相关伪影"）—— 填洼网格没这么做。
        //   做法：只把 minX/minZ 向下取整到 cell 的倍数（格距不变），构造器无需改动。
        Grid ca = buildAlignedGrid(rX, rZ, regionSize, cell, margin, eSampler);
        ca.f().computeFill(net::groundYAt, curve.seaLevelY());
        Grid cb = buildAlignedGrid(rX + 1, rZ, regionSize, cell, margin, eSampler);
        cb.f().computeFill(net::groundYAt, curve.seaLevelY());
        FlowField na = ca.f(), nb = cb.f();

        // 网格原点 = 第 0 格中心（FlowField 未暴露 origin 访问器，用 cellCenter 反推）
        double aLoX = Math.max(na.cellCenterX(0), nb.cellCenterX(0));
        double aHiX = Math.min(na.cellCenterX(na.cols() - 1), nb.cellCenterX(nb.cols() - 1));
        double aLoZ = Math.max(na.cellCenterZ(0), nb.cellCenterZ(0));
        double aHiZ = Math.min(na.cellCenterZ((na.rows() - 1) * na.cols()),
                nb.cellCenterZ((nb.rows() - 1) * nb.cols()));

        long ov2 = 0, dif2 = 0;
        double max2 = 0, sum2 = 0;
        for (double wz = aLoZ; wz <= aHiZ + 1e-9; wz += cell) {
            for (double wx = aLoX; wx <= aHiX + 1e-9; wx += cell) {
                int ia = na.indexOf(wx, wz), ib = nb.indexOf(wx, wz);
                ov2++;
                double d = Math.abs(na.filledAt(ia) - nb.filledAt(ib));
                sum2 += d;
                if (d > 1e-6) dif2++;
                max2 = Math.max(max2, d);
            }
        }
        System.out.printf("%n[5] ★ 全球对齐网格（= 【2026-09-19 起的生产现状】；"
                + "原点取整到 %.0f wu 的倍数）：%n", cell);
        System.out.printf("    网格 A %d×%d，B %d×%d；重叠区取样=%d%n",
                na.cols(), na.rows(), nb.cols(), nb.rows(), ov2);
        System.out.printf("    不一致=%d（%.1f%%）  最大差=%.4f block  平均差=%.4f block%n",
                dif2, 100.0 * dif2 / Math.max(1, ov2), max2, sum2 / Math.max(1, ov2));
        System.out.printf("    对照（未对齐，见 [3]）：不一致=%d（%.1f%%）  最大差=%.4f block%n",
                differ, 100.0 * differ / Math.max(1, overlap), maxAbsDiff);
        System.out.printf("    判据3【对齐后跨 region 一致】: %s%n",
                dif2 == 0 ? "PASS（对齐即解决）"
                        : String.format("仍差 %d 格 ⇒ 对齐不是全部原因", dif2));

        // ---------- ⑥ ★ 接缝线一致性（生产真正关心的位置）----------
        //   口径修正：[2]/[3]/[5] 的"重叠区"= [rxS+0.5S, rxS+1.5S]，而
        //   A 的本体只到 rxS+S、B 的本体从 rxS+S 起 ⇒ 重叠区里有大量格是
        //   "一个 region 的本体" vs "另一个 region 的 margin 外围"——**外围生产不用**。
        //   玩家看得见的是【region 接缝线】两侧的水位是否连续（跨缝跳变 = 湖在此截断）。
        double seamX = (rX + 1) * regionSize;
        long seamN = 0, seamDiff = 0;
        double seamMax = 0, seamSum = 0;
        double loSz = rZ * regionSize, hiSz = loSz + regionSize;
        for (double wz = loSz; wz <= hiSz + 1e-9; wz += cell) {
            int ia = na.indexOf(seamX, wz);
            int ib = nb.indexOf(seamX, wz);
            if (ia < 0 || ib < 0) continue;
            seamN++;
            double d = Math.abs(na.filledAt(ia) - nb.filledAt(ib));
            seamSum += d;
            if (d > 1e-6) seamDiff++;
            seamMax = Math.max(seamMax, d);
        }
        System.out.printf("%n[6] ★ 接缝线一致性（wx = %.0f，即 region 分界；"
                + "两侧各属一个 region 的【本体】）：%n", seamX);
        System.out.printf("    取样=%d  不一致=%d（%.1f%%）  最大差=%.4f block  平均差=%.4f block%n",
                seamN, seamDiff, 100.0 * seamDiff / Math.max(1, seamN), seamMax,
                seamSum / Math.max(1, seamN));
        // 对照：同一位置（接缝线）在【未对齐网格】下的表现 —— 才能量化修复收益
        long s2N = 0, s2Diff = 0;
        double s2Max = 0, s2Sum = 0;
        for (double wz = loSz; wz <= hiSz + 1e-9; wz += cell) {
            int ia = ga.f().indexOf(seamX, wz);
            int ib = gb.f().indexOf(seamX, wz);
            if (ia < 0 || ib < 0) continue;
            s2N++;
            double d = Math.abs(ga.f().filledAt(ia) - gb.f().filledAt(ib));
            s2Sum += d;
            if (d > 1e-6) s2Diff++;
            s2Max = Math.max(s2Max, d);
        }
        System.out.printf("    对照（未对齐网格，同一条线）：不一致=%d（%.1f%%）  "
                        + "最大差=%.4f block  平均差=%.4f block%n",
                s2Diff, 100.0 * s2Diff / Math.max(1, s2N), s2Max,
                s2Sum / Math.max(1, s2N));
        System.out.printf("    判读：接缝线是玩家唯一看得见 region 划分的位置 —— "
                + "此处一致 ⇒ 无缝；此处跳变 ⇒ 湖/河在缝上被截断。%n");

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

    /**
     * ★ 与 {@link #buildGrid} 同范围，但原点【向下取整到 {@code cell} 的倍数】
     * ⇒ 格点 = {@code k·cell}（世界坐标）⇒ 与 region 无关。
     *
     * <p>格距不变，只是把网格平移到一个全球对齐的原点。</p>
     */
    private static Grid buildAlignedGrid(int rx, int rz, double regionSize, double cell,
                                         double margin, MidpointDisplacement.ElevationSampler sampler) {
        double minX = rx * regionSize - margin, maxX = rx * regionSize + regionSize + margin;
        double minZ = rz * regionSize - margin, maxZ = rz * regionSize + regionSize + margin;
        double ax = Math.floor(minX / cell) * cell;
        double az = Math.floor(minZ / cell) * cell;
        double bx = Math.ceil(maxX / cell) * cell;
        double bz = Math.ceil(maxZ / cell) * cell;
        return new Grid(new FlowField(ax, az, bx, bz, cell, sampler), ax, az);
    }
}
