package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.flowaccum.FlowField;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 漏灌归因探针（2026-09-23 Phase1，v2）—— 两层判据：
 *
 * <h3>第 1 层【放置水位层】：该有水却干（surf&gt;0 ∧ h&lt;surf−0.5）按成因拆桶</h3>
 * <ol>
 *   <li>B1 湖欠灌·认领域内 / B2 域外；</li>
 *   <li>B3 河欠灌（放置水位与河命中一致）；</li>
 *   <li>B4 水位不符（放置≠命中）—— 附 |Δ| 直方图区分"轻微错位(~1)"与
 *       "被拒列 original 幽灵水位(Δ≈侵蚀差，可达 10+)"；</li>
 *   <li>B5 无命中。</li>
 * </ol>
 *
 * <h3>第 2 层【湖等高线赤字 B6】（v2 新增，补 v1 盲区）</h3>
 * <p>v1 要求 surf&gt;0 才计数 ⇒ 域外从未拿到水位的格完全隐形。
 * B6 不看 surf：干 ∧ 最近命中=湖 ∧ height&lt;湖水位−0.5 ⇒ 真·"该淹到却没水"。
 * 按湖中心聚合（赤字/其中域外/已灌湿），直接给出每湖欠灌率。</p>
 *
 * <p>用法：{@code gradlew runLakeHoleSplitProbe [-PprobeArgs="seed blockX blockZ radius"]}</p>
 *
 * <h3>⚠⚠ 口径警告（2026-09-29 唯一水文管线切换后，必读）</h3>
 * <p><b>只有【第 1 层】与【B9 湖簇】是新口径可信数据</b>（都基于落块后的
 * {@code Cell.riverType/isLake/surf}，即实际进游戏的水）。而 <b>B6 / P2-2 精确靶 /
 * 第 2 层的"应淹"与"命中可达"锚在旧 {@link RiverLineNetwork} 的 {@code net.region().lakes}
 * （旧 LakeNode 湖定义 + 旧 {@code net.sample} 命中）</b> —— 2026-09-29 起生产唯一管线是
 * {@code hydrology/sim/} 新核心，旧链湖表不再参与落块 ⇒ 这些层数字是
 * <b>"旧链湖定义 vs 新核心落块"的口径错配</b>，不代表真实欠灌（实测同窗口：第 1 层
 * 该有水却干仅 0.04%，而 P2-2 却报 108 万块²）。修复方向：把应淹掩码换为新核心
 * LAKE_STORAGE 掩码（{@code HydroWorldSolver}），旧链层数字在那之前【不得当判据】。</p>
 */
public final class LakeHoleSplitProbe {

    private LakeHoleSplitProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -140;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : 137;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 700;
        int w = 2 * radius + 1;

        TerrainParams tp = TerrainParams.defaults();
        double hs = tp.horizontalScale();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);
        gt.seed(seed);
        RiverLineParams rlp = RiverLineParams.defaults();
        RiverLineNetwork net = gt.hydrologyNetwork();
        net.setSeed(seed);
        double domTol = rlp.gridCell() * 0.5;

        int[] bucket = new int[6];      // [1..5]
        int[] b4Hist = new int[4];      // B4 |Δ|：(1,2] (2,5] (5,15] (15,∞)
        int placedTotal = 0;
        List<String> samples = new ArrayList<>();
        Map<String, long[]> byLake = new HashMap<>();   // 湖中心 → [赤字, 其中域外, 湿]
        long[] b6 = new long[2];                        // B6 赤字合计 / 其中域外
        List<String> b6Samples = new ArrayList<>();
        // ---- B8（v5）：以【生产自己的块级掩码】为权威的度量 ----
        //   真欠灌 = 干 ∧ 在某湖掩码内 ∧ h < 湖水位−0.5（掩码已构造保证连通）
        //   过量蓄水 = 湿（湖）∧ 不在最近湖掩码内（假洼地嫌疑）
        long[] b8 = new long[2];                        // [0]=真欠灌 [1]=过量蓄水
        List<String> b8Samples = new ArrayList<>();
        java.util.function.ToDoubleBiFunction<Double, Double> es8 =
                (wx, wz) -> gen.sampleWu(wx, wz).height;   // 与生产 erodedYSampler 同口径
        double floodGrid = RiverLineNetwork.lakeBasinFloodGrid;
        // ---- B7（v3）：按湖心归属，不依赖湖命中可达性（覆盖羽化带外的等高线赤字）----
        double rs = rlp.regionSize();
        List<RiverLineRegion.LakeNode> lakes = new ArrayList<>();
        for (int rx = (int) Math.floor((bx - radius) / hs / rs) - 1;
                rx <= (int) Math.floor((bx + radius) / hs / rs) + 1; rx++) {
            for (int rz = (int) Math.floor((bz - radius) / hs / rs) - 1;
                    rz <= (int) Math.floor((bz + radius) / hs / rs) + 1; rz++) {
                lakes.addAll(net.region(rx, rz).lakes);
            }
        }
        long[] b7 = new long[4];   // [0]=合计 [1]=最近=湖 [2]=最近=河 [3]=无命中
        Map<String, long[]> byLake7 = new HashMap<>();  // 湖中心 → 赤字数
        List<String> b7Samples = new ArrayList<>();

        int x0 = bx - radius, z0 = bz - radius;
        // 湿水掩膜（B9 簇扫描用）：湖 / 河 分开
        boolean[] lakeWetMask = new boolean[w * w];
        boolean[] riverWetMask = new boolean[w * w];
        for (int z = z0; z < z0 + w; z++) {
            for (int x = x0; x < x0 + w; x++) {
                Cell c = gt.getChunkCells(x >> 4, z >> 4)
                        [Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
                boolean dry = c.riverType == 0 && !c.isWater();
                boolean lakeWet = c.riverType != 0 && c.isLake;
                if (c.riverType != 0) {
                    int pi = (z - z0) * w + (x - x0);
                    if (lakeWet) lakeWetMask[pi] = true; else riverWetMask[pi] = true;
                }
                if (!dry && !lakeWet) continue;
                RiverLineNetwork.RiverLineHit hit = net.sample(x / hs, z / hs);

                if (dry && c.riverSurfaceY > 0 && c.height < c.riverSurfaceY - 0.5) {
                    placedTotal++;
                    bucket[classify(hit, c.riverSurfaceY, x, z, hs, domTol,
                            samples, b4Hist)]++;
                }
                accumulateB6(hit, dry, lakeWet, c, x, z, hs, domTol,
                        byLake, b6, b6Samples, es8, floodGrid, rlp.gridCell());
                accumulateB8(lakes, hit, dry, lakeWet, c, x, z, hs,
                        es8, floodGrid, rlp.gridCell(), b8, b8Samples);
            }
        }

        System.out.printf("=== LakeHoleSplitProbe seed=%d 窗口=%d×%d (%d,%d±%d) ===%n",
                seed, w, w, bx, bz, radius);
        System.out.println("⚠ 口径（2026-09-29 起）：仅【第1层/B9】为新管线可信数据；"
                + "B6/P2-2/第2层锚在旧 RiverLineNetwork 湖表 = 口径错配，不得当判据（见类 javadoc）");
        System.out.printf("[第1层·放置水位] 该有水却干 合计=%d（占窗口 %.2f%%）%n",
                placedTotal, 100.0 * placedTotal / (w * (double) w));
        printBucket(1, "湖欠灌·认领域内", bucket[1], placedTotal);
        printBucket(2, "湖欠灌·认领域外", bucket[2], placedTotal);
        printBucket(3, "河欠灌", bucket[3], placedTotal);
        printBucket(4, "水位不符(放置≠命中)", bucket[4], placedTotal);
        printBucket(5, "无命中", bucket[5], placedTotal);
        System.out.printf("  B4 |Δ| 分布: 1~2=%d  2~5=%d  5~15=%d  >15=%d（后两档≈original幽灵）%n",
                b4Hist[0], b4Hist[1], b4Hist[2], b4Hist[3]);
        System.out.printf("[第2层·B6湖等高线赤字] 合计=%d 格，其中认领域外=%d（%.1f%%）%n",
                b6[0], b6[1], b6[0] == 0 ? 0.0 : 100.0 * b6[1] / b6[0]);
        byLake.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .forEach(e -> {
                    long[] v = e.getValue();
                    long tot = v[0] + v[2];
                    System.out.printf("  湖 wu(%s): 赤字=%d 其中域外=%d 湿=%d 欠灌率=%.1f%%%n",
                            e.getKey(), v[0], v[1], v[2],
                            tot == 0 ? 0.0 : 100.0 * v[0] / tot);
                });
        System.out.println("第1层样例：");
        samples.forEach(s -> System.out.println("  " + s));
        System.out.println("B6 样例：");
        b6Samples.forEach(s -> System.out.println("  " + s));
        System.out.printf("[B8·生产掩码权威度量] 真欠灌(干∧在掩码内)=%d  过量蓄水(湿∧不在掩码内)=%d%n",
                b8[0], b8[1]);
        System.out.println("B8 样例：");
        b8Samples.forEach(s -> System.out.println("  " + s));
        analyzeWetClusters(gt, net, lakes, lakeWetMask, riverWetMask, w, x0, z0,
                hs, es8, floodGrid, rlp.gridCell());
        analyzeLakeAndRiverInventory(net, lakes, hs, es8, floodGrid, rlp.gridCell(),
                bx, radius, bz);
        analyzeLakeBasins(gen, lakes, byLake, hs);
        System.out.println("判读：B1+B2 ⇒ 域内欠灌；B3 ⇒ 河；B4(>5) ⇒ 幽灵水位；"
                + "B6 ⇒ 命中可达的真·没填到山边；连通洼地差额 ⇒ P2-2 精确靶。");
    }

    /**
     * ★ v4【B7 升级：逐湖连通洼地精确靶】（2026-09-23）
     *
     * <p>旧 B7 =「湖心 3×半径内低于水位的干地」——含隔脊误计与多湖重复，
     * 只是上界（实测 60,536）。本版对每湖单独建 <b>6wu 细格 priority-flood</b>
     * （{@link LakeBasinProbe} 同款口径：采样器与填洼层都用 Y 空间真值），
     * {@code isBasinCell ∧ 地面 < 湖水位−0.5} 的格 = <b>与该盆连通、该淹未淹</b>的面积，
     * 即 P2-2（块分辨率连通洪泛）的精确靶。生产湿面积沿用 B6 聚合的命中可达湿格数。</p>
     *
     * <p>口径注：① 用 sampleWu（侵蚀后、不触发 tile）≈ 判水发生在侵蚀后地形，与生产一致；
     * ② 水位用 ln.height（hit surfaceY 同源）；③ 6wu 格 × 36wu² = 9 块²/格，块积为近似。</p>
     */
    private static void analyzeLakeBasins(CellGenerator gen,
                                          List<RiverLineRegion.LakeNode> lakes,
                                          Map<String, long[]> byLake, double hs) {
        double seaLevel = gen.heightCurve().seaLevelY();
        System.out.println("[B7'·逐湖连通洼地精确靶]（6wu priority-flood，isBasinCell ∧ 地面<湖水位）");
        long totalGap = 0;
        for (RiverLineRegion.LakeNode ln : lakes) {
            double half = Math.max(ln.radius * 4.0, 256.0);
            FlowField f = new FlowField(ln.x - half, ln.z - half, ln.x + half, ln.z + half,
                    6.0, (wx, wz) -> gen.sampleWu(wx, wz).height);
            f.computeFill((wx, wz) -> gen.sampleWu(wx, wz).height, seaLevel);
            int nx = f.cols(), nz = f.rows();
            long shouldWet = 0;
            for (int idx = 0; idx < nx * nz; idx++) {
                if (f.isBasinCell(idx) && f.eAt(idx) < ln.height - 0.5) shouldWet++;
            }
            long shouldBlocks = shouldWet * 9L;   // 6wu 格 = 36wu² = 9 块²
            long[] agg = byLake.get((int) Math.round(ln.x) + "," + (int) Math.round(ln.z));
            long prodWet = agg == null ? 0 : agg[2];
            long gap = shouldBlocks - prodWet;
            totalGap += Math.max(0, gap);
            System.out.printf("  湖 wu(%.0f,%.0f) r=%.0f 水位=%.2f: 应淹≈%d 块²（%d 格）"
                            + " · 生产湿(命中可达)=%d 块 · 差额≈%d 块²%n",
                    ln.x, ln.z, ln.radius, ln.height, shouldBlocks, shouldWet,
                    prodWet, gap);
        }
        System.out.printf("  ⇒ P2-2 精确靶合计 ≈ %d 块²（连通洼地内该淹未淹）%n", totalGap);
    }

    /** 第 1 层：给 hit 与放置水位，返回桶号 1..5；顺带维护 B4 直方图与样例。 */
    private static int classify(RiverLineNetwork.RiverLineHit hit, double surf,
                                int x, int z, double hs, double domTol,
                                List<String> samples, int[] b4Hist) {
        if (hit == null) {
            addSample(samples, 5, 6, x, z, surf, "hit=null");
            return 5;
        }
        double diff = surf - hit.surfaceY();
        if (Math.abs(diff) > 1.0) {
            double a = Math.abs(diff);
            b4Hist[a <= 2 ? 0 : a <= 5 ? 1 : a <= 15 ? 2 : 3]++;
            addSample(samples, 4, 6, x, z, surf, String.format(
                    "isLake=%b 放置=%.3f 命中=%.3f Δ=%+.3f",
                    hit.isLake(), surf, hit.surfaceY(), diff));
            return 4;
        }
        if (hit.isLake()) {
            RiverLineRegion.LakeNode ln = hit.lake();
            boolean dom = ln != null && ln.inDomain(x / hs, z / hs, domTol);
            int cls = dom ? 1 : 2;
            addSample(samples, cls, 6, x, z, surf, String.format(
                    "dist=%.2f 湖水位=%.3f dom=%b", hit.distToCenter(), hit.surfaceY(), dom));
            return cls;
        }
        addSample(samples, 3, 3, x, z, surf, String.format(
                "dist=%.2f 河水位=%.3f", hit.distToCenter(), hit.surfaceY()));
        return 3;
    }

    /** 第 2 层 B6：按湖聚合赤字/湿格（不依赖 surf 是否放置）。 */
    private static void accumulateB6(RiverLineNetwork.RiverLineHit hit, boolean dry,
                                     boolean lakeWet, Cell c, int x, int z,
                                     double hs, double domTol,
                                     Map<String, long[]> byLake, long[] b6,
                                     List<String> b6Samples,
                                     java.util.function.ToDoubleBiFunction<Double, Double> es,
                                     double floodGrid, double claimGrid) {
        if (hit == null || !hit.isLake()) return;
        RiverLineRegion.LakeNode ln = hit.lake();
        if (ln == null) return;
        String key = (int) Math.round(ln.x) + "," + (int) Math.round(ln.z);
        long[] agg = byLake.computeIfAbsent(key, k -> new long[3]);
        if (dry && c.height < hit.surfaceY() - 0.5) {
            // ★ 2026-09-23【B6 补连通性限定】（v2 注释本就声称"掩码可达"）：
            //   湖命中带 inDomain halo ⇒ 掩码外的【隔离低于水位口袋】也会进桶 ——
            //   lakeFineFlood 关停后这批格从 B8过量(702) 翻成 B6赤字(702)，
            //   而 B8真欠灌恒 282/过量恒 0 ⇒ 掩码权威 = 物理不连通、本就该干。
            //   无连通的格不计赤字（与 inBasinFlood 权威一致）。
            double wuX = x / hs, wuZ = z / hs;
            double lvl0 = hit.surfaceY();
            if (!Double.isNaN(lvl0)
                    && !ln.inBasinFlood(es, lvl0, floodGrid, claimGrid, wuX, wuZ)) {
                agg[2] += lakeWet ? 1 : 0;   // 仅维护湿格统计，不计赤字
                return;
            }
            b6[0]++;
            agg[0]++;
            if (!ln.inDomain(x / hs, z / hs, domTol)) {
                b6[1]++;
                agg[1]++;
            }
            if (b6Samples.size() < 6) {
                b6Samples.add(String.format(
                        "(%d,%d) h=%.2f 湖水位=%.2f dist=%.1f dom=%b surf=%.2f",
                        x, z, c.height, hit.surfaceY(), hit.distToCenter(),
                        ln.inDomain(x / hs, z / hs, domTol), c.riverSurfaceY));
            }
        } else if (lakeWet) {
            agg[2]++;
        }
    }

    /**
     * B8：以【生产自己的块级连通掩码】为权威。
     * 复用生产 LakeNode.inBasinFlood（同一 computeFlood 缓存 ⇒ 掩码与雕刻逐位同源）；
     * 水位复刻生产 finalLakeLevel：min(无侵蚀 spill, escapeWaterLevel(es,24,6,spill))，
     * 与生产共享 escape 缓存（同一 LakeNode 实例）⇒ 取值逐位一致。
     */
    private static void accumulateB8(List<RiverLineRegion.LakeNode> lakes,
                                     RiverLineNetwork.RiverLineHit hit,
                                     boolean dry, boolean lakeWet, Cell c,
                                     int x, int z, double hs,
                                     java.util.function.ToDoubleBiFunction<Double, Double> es,
                                     double floodGrid, double claimGrid,
                                     long[] b8, List<String> b8Samples) {
        double wuX = x / hs, wuZ = z / hs;
        if (dry) {
            for (RiverLineRegion.LakeNode ln : lakes) {
                double dx = ln.x - wuX, dz = ln.z - wuZ;
                if (dx * dx + dz * dz > 640.0 * 640.0) continue;   // 粗筛：>2 region 无关
                double lv = prodLakeLevel(ln, es);
                if (Double.isNaN(lv) || c.height >= lv - 0.5) continue;
                if (ln.inBasinFlood(es, lv, floodGrid, claimGrid, wuX, wuZ)) {
                    b8[0]++;
                    if (b8Samples.size() < 6) {
                        b8Samples.add(String.format(
                                "欠灌 (%d,%d) h=%.2f 湖wu(%.0f,%.0f) 水位=%.2f surf=%.2f",
                                x, z, c.height, ln.x, ln.z, lv, c.riverSurfaceY));
                    }
                    return;
                }
            }
        } else if (lakeWet && hit != null && hit.isLake() && hit.lake() != null) {
            RiverLineRegion.LakeNode ln = hit.lake();
            double lv = prodLakeLevel(ln, es);
            if (!Double.isNaN(lv)
                    && !ln.inBasinFlood(es, lv, floodGrid, claimGrid, wuX, wuZ)) {
                b8[1]++;
                if (b8Samples.size() < 12) {
                    b8Samples.add(String.format(
                            "过量 (%d,%d) h=%.2f 湖wu(%.0f,%.0f) 水位=%.2f",
                            x, z, c.height, ln.x, ln.z, lv));
                }
            }
        }
    }

    /** 复刻生产 finalLakeLevel（es 非空路径）：min(upper, escapeWaterLevel)，共享缓存。 */
    private static double prodLakeLevel(RiverLineRegion.LakeNode ln,
                                        java.util.function.ToDoubleBiFunction<Double, Double> es) {
        double upper = Double.isNaN(ln.height) ? ln.erodedSpill : ln.height;
        try {
            double esc = ln.escapeWaterLevel(es, 24.0, 6.0, upper);
            if (!Double.isNaN(esc)) return Math.min(upper, esc);
        } catch (RuntimeException ignore) {
            // 与生产一致：逃逸失败退回上界
        }
        return upper;
    }

    /**
     * B9【水体簇扫描】（2026-09-23）：把窗口内全部湿水按 4 邻分成连通簇
     * （湖/河分色分别扫），逐簇报中心块坐标、格数、类型，并对小簇（孤斑，
     * 用户红圈目标）给出中心格的判定细节：h / surf / 最近湖与是否在其掩码内。
     * 用于把"坡地小水斑"精确对上成因（河带湖水位 / 掩码外过量 / 合法小湖）。
     */
    private static void analyzeWetClusters(GeoGenesisTerrain gt, RiverLineNetwork net,
                                           List<RiverLineRegion.LakeNode> lakes,
                                           boolean[] lakeWetMask, boolean[] riverWetMask,
                                           int w, int x0, int z0, double hs,
                                           java.util.function.ToDoubleBiFunction<Double, Double> es,
                                           double floodGrid, double claimGrid) {
        // ★ 河距场（多源 BFS，自全部河水格出发，上限 64 格）—— 验证"湖斑贴着河"
        int[] riverDist = new int[w * w];
        java.util.Arrays.fill(riverDist, Integer.MAX_VALUE);
        java.util.ArrayDeque<Integer> rq = new java.util.ArrayDeque<>();
        for (int i = 0; i < w * w; i++) {
            if (riverWetMask[i]) { riverDist[i] = 0; rq.add(i); }
        }
        while (!rq.isEmpty()) {
            int cur = rq.poll();
            int dcur = riverDist[cur];
            if (dcur >= 64) continue;
            int ci = cur % w, cj = cur / w;
            for (int d = 0; d < 4; d++) {
                int ni = ci + (d == 0 ? 1 : d == 1 ? -1 : 0);
                int nj = cj + (d == 2 ? 1 : d == 3 ? -1 : 0);
                if (ni < 0 || ni >= w || nj < 0 || nj >= w) continue;
                int nIdx = nj * w + ni;
                if (riverDist[nIdx] > dcur + 1) {
                    riverDist[nIdx] = dcur + 1;
                    rq.add(nIdx);
                }
            }
        }
        for (int pass = 0; pass < 2; pass++) {
            boolean[] mask = pass == 0 ? lakeWetMask : riverWetMask;
            String type = pass == 0 ? "湖" : "河";
            boolean[] seen = new boolean[w * w];
            int smallCount = 0;
            for (int start = 0; start < w * w; start++) {
                if (!mask[start] || seen[start]) continue;
                // BFS 收簇
                int size = 0, sx = 0, sz = 0;
                int minIx = Integer.MAX_VALUE, maxIx = Integer.MIN_VALUE;
                int minIz = Integer.MAX_VALUE, maxIz = Integer.MIN_VALUE;
                int minRiverD = Integer.MAX_VALUE;
                java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
                q.add(start);
                seen[start] = true;
                while (!q.isEmpty()) {
                    int cur = q.poll();
                    size++;
                    int ci = cur % w, cj = cur / w;
                    sx += ci; sz += cj;
                    minIx = Math.min(minIx, ci); maxIx = Math.max(maxIx, ci);
                    minIz = Math.min(minIz, cj); maxIz = Math.max(maxIz, cj);
                    if (pass == 0 && riverDist[cur] < minRiverD) minRiverD = riverDist[cur];
                    for (int d = 0; d < 4; d++) {
                        int ni = ci + (d == 0 ? 1 : d == 1 ? -1 : 0);
                        int nj = cj + (d == 2 ? 1 : d == 3 ? -1 : 0);
                        if (ni < 0 || ni >= w || nj < 0 || nj >= w) continue;
                        int nIdx = nj * w + ni;
                        if (mask[nIdx] && !seen[nIdx]) {
                            seen[nIdx] = true;
                            q.add(nIdx);
                        }
                    }
                }
                smallCount++;
                if (smallCount > 60) continue;
                int cx = x0 + sx / size, cz = z0 + sz / size;
                Cell cc = gt.getChunkCells(cx >> 4, cz >> 4)
                        [Math.floorMod(cx, 16) * 16 + Math.floorMod(cz, 16)];
                RiverLineNetwork.RiverLineHit hit = net.sample(cx / (double) hs, cz / (double) hs);
                String detail;
                if (hit != null && hit.isLake() && hit.lake() != null) {
                    RiverLineRegion.LakeNode ln = hit.lake();
                    double lv = prodLakeLevel(ln, es);
                    boolean inMask = !Double.isNaN(lv)
                            && ln.inBasinFlood(es, lv, floodGrid, claimGrid,
                                    cx / (double) hs, cz / (double) hs);
                    detail = String.format("最近=湖 wu(%.0f,%.0f) 水位=%.2f dist=%.1f 在掩码=%b",
                            ln.x, ln.z, lv, hit.distToCenter(), inMask);
                } else if (hit != null) {
                    detail = String.format("最近=河 dist=%.1f 河水位=%.2f", hit.distToCenter(),
                            hit.surfaceY());
                } else {
                    detail = "无命中";
                }
                System.out.printf("  [B9-%s簇] 中心块(%d,%d) 格数=%d x跨[%d,%d] z跨[%d,%d]"
                                + " h=%.2f surf=%.2f isLake=%b 距河=%d格 : %s%n",
                        type, cx, cz, size, x0 + minIx, x0 + maxIx, z0 + minIz, z0 + maxIz,
                        cc.height, cc.riverSurfaceY, cc.isLake,
                        minRiverD == Integer.MAX_VALUE ? -1 : minRiverD, detail);
            }
            System.out.printf("[B9-%s簇] 簇总数=%d（最多列 60）%n", type, smallCount);
        }
    }

    /**
     * B10【湖节点 + 河折线携带湖信息 清单】（2026-09-23）—— 回答"蓝色水体到底是谁造的"：
     * <ul>
     *   <li>每个 LakeNode：region/中心块/半径/无侵蚀 spill/生产水位(finalLakeLevel)/
     *       轮廓格数/掩码格数（掩码 = 块级连通洼地）；</li>
     *   <li>每条河折线：是否携带 {@code lakeLevel}（2026-09-07「河成湖」低梯度段）
     *       与 {@code lakeNodes}（2026-09-22 入湖锚点段，发出湖命中）——
     *       <b>这是"河水染成湖色"的可能来源</b>；</li>
     *   <li>合计：带湖信息的折线数 / 节点数（占比）。</li>
     * </ul>
     */
    private static void analyzeLakeAndRiverInventory(
            RiverLineNetwork net, List<RiverLineRegion.LakeNode> lakes, double hs,
            java.util.function.ToDoubleBiFunction<Double, Double> es,
            double floodGrid, double claimGrid, int bx, int radius, int bz) {
        System.out.println("[B10·湖节点清单]（region 范围同窗口 ±1）");
        for (RiverLineRegion.LakeNode ln : lakes) {
            double lv = prodLakeLevel(ln, es);
            // 触发/复用掩码（在湖心处查一次即可建立整张掩码）
            ln.inBasinFlood(es, lv, floodGrid, claimGrid, ln.x, ln.z);
            int maskN = ln.floodX == null ? -1 : ln.floodX.length;
            System.out.printf("  湖 block(%d,%d) r=%.0f 轮廓格=%d spill=%.2f 生产水位=%.2f 掩码格=%d%n",
                    Math.round(ln.x * hs), Math.round(ln.z * hs), ln.radius * hs,
                    ln.hasOutline() ? ln.cellX.length : 0, ln.height, lv, maskN);
        }
        double rs = RiverLineParams.defaults().regionSize();
        int polys = 0, polysLakeLevel = 0, polysLakeNodes = 0, nodesTotal = 0, nodesLL = 0;
        List<String> samples = new ArrayList<>();
        for (int rx = (int) Math.floor((bx - radius) / hs / rs) - 1;
                rx <= (int) Math.floor((bx + radius) / hs / rs) + 1; rx++) {
            for (int rz = (int) Math.floor((bz - radius) / hs / rs) - 1;
                    rz <= (int) Math.floor((bz + radius) / hs / rs) + 1; rz++) {
                for (RiverLineRegion.RiverPolyline pl : net.region(rx, rz).rivers) {
                    polys++;
                    nodesTotal += pl.nodes.length;
                    int llN = 0;
                    double llMin = Double.POSITIVE_INFINITY, llMax = Double.NEGATIVE_INFINITY;
                    if (pl.lakeLevel != null) {
                        for (double v : pl.lakeLevel) {
                            if (!Double.isNaN(v)) {
                                llN++;
                                llMin = Math.min(llMin, v);
                                llMax = Math.max(llMax, v);
                            }
                        }
                    }
                    int lnN = 0;
                    if (pl.lakeNodes != null) {
                        for (RiverLineRegion.LakeNode l : pl.lakeNodes) if (l != null) lnN++;
                    }
                    nodesLL += llN;
                    if (llN > 0) polysLakeLevel++;
                    if (lnN > 0) polysLakeNodes++;
                    if ((llN > 0 || lnN > 0) && samples.size() < 14) {
                        samples.add(String.format(
                                "    region(%d,%d) 节点=%d 湖面节点=%d[%.2f~%.2f] 湖节点数=%d"
                                        + " 首节点块=(%d,%d) 末节点块=(%d,%d)",
                                rx, rz, pl.nodes.length, llN,
                                llN == 0 ? 0.0 : llMin, llN == 0 ? 0.0 : llMax, lnN,
                                Math.round(pl.nodes[0].x() * hs), Math.round(pl.nodes[0].z() * hs),
                                Math.round(pl.nodes[pl.nodes.length - 1].x() * hs),
                                Math.round(pl.nodes[pl.nodes.length - 1].z() * hs)));
                    }
                }
            }
        }
        System.out.printf("[B10·河折线] 折线总数=%d 节点总数=%d ｜ 带 lakeLevel 的折线=%d"
                        + "（其湖面节点=%d，占全部节点 %.1f%%）｜ 带 lakeNodes 的折线=%d%n",
                polys, nodesTotal, polysLakeLevel, nodesLL,
                nodesTotal == 0 ? 0.0 : 100.0 * nodesLL / nodesTotal, polysLakeNodes);
        samples.forEach(System.out::println);
    }

    private static void addSample(List<String> samples, int cls, int cap,
                                  int x, int z, double surf, String detail) {
        long n = samples.stream().filter(s -> s.startsWith("B" + cls + " ")).count();
        if (n < cap) {
            samples.add(String.format("B%d (%d,%d) surf=%.3f %s", cls, x, z, surf, detail));
        }
    }

    private static void printBucket(int id, String label, int n, int total) {
        System.out.printf(" B%d %-22s = %7d（%.1f%%）%n",
                id, label, n, total == 0 ? 0.0 : 100.0 * n / total);
    }
}
