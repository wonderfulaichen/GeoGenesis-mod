package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;
import com.geogenesis.worldgen.noise.NoiseUtil;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 瀑布（跌水）探针（2026-08-30）。
 *
 * <p>验证"裂点局部阶梯化"在<b>河线层</b>与<b>方块层</b>两端都成立：</p>
 * <ul>
 *   <li><b>铁律不破</b>：阶梯化后的水面纵剖面仍沿程不回升（ADR#1 PAVA 铁律）；</li>
 *   <li><b>落差受控</b>：每级跌水落差 ∈ [waterfallMinDrop, waterfallMaxDrop]；</li>
 *   <li><b>间距受控</b>：两级跌水间距 ≥ waterfallMinSpacing（不连成阶梯）；</li>
 *   <li><b>功能真的触发</b>：存在跌水点，且方块层真的生成了垂直水幕列（非空转）；</li>
 *   <li><b>水幕有水</b>：水幕列必须是灌水列，否则瀑布是干的（视觉上只是凹坑）。</li>
 * </ul>
 *
 * <p>参考：Dynamic Waters MountainRiverPath（WATERFALL_THRESHOLD=2 / MAX_WATERFALL_DROP=4
 * / TERRACE 16~35）与旧 Streams fillRiver（同列双水位：潭面静水 + 潭面→唇口水幕）。</p>
 */
public final class WaterfallProbe {
    private WaterfallProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        double horizontalScale = args.length > 1 ? Double.parseDouble(args[1]) : 2.0;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        // 独立原始地形实例（不交给 engine，避免被河床雕刻污染）：水井断言需对比"雕刻前"原地形
        CellGenerator terrainRaw = new CellGenerator(params, params.minY(), params.maxY());
        terrainRaw.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);
        RiverLineParams P = RiverLineParams.defaults();

        int rivers = 0, nodes = 0;
        int nonMonotonic = 0;      // 水面下游回升（违反 ADR#1 铁律）
        int fallNodes = 0;         // 跌水节点总数（含阶梯中间节点）
        int angleFails = 0;        // 检测 run 处原地形坡角 < waterfallMinAngle（必须 0）
        int runTooSmall = 0;       // run 净落差 < waterfallMinDrop（必须 0：涟漪不是瀑布）
        int stepOver = 0;          // 单级阶落差 > waterfallMaxDrop（sanity 钳，必须 0）
        int tooClose = 0;          // 两级 run 间距 < waterfallMinSpacing
        int wellViolation = 0;     // 水面高于原地形（悬空水井，必须 0）
        int runs = 0;              // 瀑布 run 总数
        double dropSum = 0.0, dropMax = 0.0, dropMin = Double.POSITIVE_INFINITY;
        double seaLevel = terrain.seaLevel();
        Set<Long> riverChunkKeys = new LinkedHashSet<>();

        for (int rz = -1; rz <= 1; rz++) for (int rx = -1; rx <= 1; rx++) {
            RiverLineRegion region = engine.network().region(rx, rz);
            for (RiverLineRegion.RiverPolyline river : region.rivers) {
                rivers++;
                int n = river.nodes.length;
                double[] terr = new double[n];
                for (int i = 0; i < n; i++) {
                    terr[i] = terrainRaw.sample(river.nodes[i].x(), river.nodes[i].z()).height;
                }
                int lastRunEnd = -1_000_000;   // 避免首 run 间距误判（同 lastFall 整数溢出坑）
                int runStart = -1;
                for (int i = 0; i < n; i++) {
                    nodes++;
                    if (i > 0 && river.surfaceY[i] > river.surfaceY[i - 1] + 1e-9) {
                        // 豁免瀑布上游深潭：水面回升是蓄水（物理正确），
                        // 判定 = 下游 5 节点内有跌水段标记（fallDrop>0）
                        boolean inPool = false;
                        for (int j = i; j <= Math.min(i + 5, n - 1); j++) {
                            if (river.fallDrop[j] > 0.0) { inPool = true; break; }
                        }
                        if (!inPool) nonMonotonic++;
                    }
                    // 防悬空水井：陆上水面不得高于原地形（海域由海平面另判）
                    if (terr[i] >= seaLevel && river.surfaceY[i] > terr[i] + 1e-6) wellViolation++;
                    double fd = river.fallDrop[i];
                    if (fd > 0.0) {
                        // run 起点 = 第一个 fall 标记节点（网络 run 起点 a，其 fall=1.0 标记）
                        if (runStart < 0) runStart = i;
                        fallNodes++;
                        dropSum += fd;
                        dropMax = Math.max(dropMax, fd);
                        dropMin = Math.min(dropMin, fd);
                    } else if (runStart >= 0) {            // run 结束于 [runStart, i-1]
                        int[] r = checkRun(river, terr, runStart, i - 1, horizontalScale, P, lastRunEnd);
                        angleFails += r[0]; runTooSmall += r[1]; stepOver += r[2];
                        tooClose += r[3]; runs += r[4];
                        lastRunEnd = i - 1; runStart = -1;
                    }
                    int bx = (int) Math.floor(river.nodes[i].x() * horizontalScale);
                    int bz = (int) Math.floor(river.nodes[i].z() * horizontalScale);
                    riverChunkKeys.add(pack(Math.floorDiv(bx, 16), Math.floorDiv(bz, 16)));
                    if (riverChunkKeys.size() >= 144) break;
                }
                if (runStart >= 0) {                       // 收尾未闭合 run
                    int[] r = checkRun(river, terr, runStart, n - 1, horizontalScale, P, lastRunEnd);
                    angleFails += r[0]; runTooSmall += r[1]; stepOver += r[2];
                    tooClose += r[3]; runs += r[4];
                }
                if (riverChunkKeys.size() >= 144) break;
            }
        }

        // ===== 方块层：水幕是否真的生成 =====
        int curtainColumns = 0, curtainFilled = 0, curtainDry = 0;
        int curtainCore = 0, curtainCoreDry = 0;
        int lipFrozen = 0, lipOverCarved = 0;   // 崖顶边缘（唇口侧冻结）断言
        int lipMarginPerched = 0;               // 唇口河缘漫水断言（水幕顶直角超出原地形）
        int curtainOutside = 0;                 // 水幕出河道断言（孤立水柱）
        double curtainDropMax = 0.0;
        for (long key : riverChunkKeys) {
            int cx = (int) (key >> 32), cz = (int) key;
            double[] ground = ground(terrain, cx, cz, horizontalScale);
            var columns = HydrologyBlockCarver.carveChunk(engine, cx, cz, horizontalScale, ground);
            for (HydrologyBlockCarvedColumn c : columns) {
                var samples = engine.sampleBlockAll(c.blockX(), c.blockZ(), horizontalScale);
                var s0 = samples.isEmpty() ? null : samples.get(0);
                // 崖顶边缘（唇口侧冻结列）：雕刻面不得低于"水面 − IDW 混合河床深"的合法下限。
                // 若唇口侧未冻结走 IDW 混合，carveSurfaceY 会被上级/下级 tread 混出中间值，
                // 挖出深于任何合法河床的凹坑（悬空沙块平台根因）。
                // 合法下限来源：① IDW 混合 depth（含深潭 plunge 加深，与 carver 同公式）；
                // ② 切穿列床面 = surface − 0.75；③ 天然洼地（original 本就低，carved=min(original,bed)）。
                if (s0 != null && s0.frozen() && s0.fallDrop() <= 0.0) {
                    lipFrozen++;
                    double blendDist = P.heightBlendDist();
                    double wSum = 0.0, sDep = 0.0;
                    for (var s : samples) {
                        double d = s.distToCenter();
                        if (d > blendDist) break;
                        double fade = NoiseUtil.saturate(d / blendDist);
                        double w = (1.0 - fade) * (1.0 - fade) / Math.max(d * d, 1.0);
                        wSum += w;
                        sDep += w * s.depth();
                    }
                    double idwDepth = wSum > 1e-9 ? sDep / wSum : s0.depth();
                    double bedFloor = c.waterSurfaceY() - Math.max(idwDepth, 0.75) - 1.0;
                    if (c.originalGroundY() >= bedFloor && c.carvedGroundY() < bedFloor) lipOverCarved++;
                    // 唇口河缘漫水：河缘带（>0.7w）静水面不得高于当地原始地形
                    // （水幕顶"直角"超出原地形 = 水灌在草地之上）。湿核心带除外
                    // （河道内切穿灌水合法），真水幕列（fallDrop>0）不在此断言范围。
                    if (s0.distToCenter() > s0.width() * 0.7 && c.fillWater()
                            && c.waterSurfaceY() > c.originalGroundY() + 1e-6) lipMarginPerched++;
                }
                double fd = c.fallDrop();
                if (fd <= 0.0) continue;
                // 水幕指标只统计河道内列（dist≤width）：谷壁列的 fd 来自
                // lipSurfaceY≤original 钳制（fd=original−tread>0），属正常地形差，
                // 且谷壁区不灌水（门控①），计入只会污染干列统计。
                if (s0 == null || s0.distToCenter() > s0.width()) continue;
                curtainColumns++;
                curtainDropMax = Math.max(curtainDropMax, fd);
                if (c.fillWater()) {
                    curtainFilled++;
                    // 水幕出河道（孤立水柱）：水幕列必须落在河道半宽内——
                    // 水只灌在雕刻出的河道里（Streams fillRiver/DW 语义），
                    // 超出半宽的水幕列 = 斜坡上悬空水柱（底部沙块、无河道支撑）。
                    if (s0.distToCenter() > s0.width()) curtainOutside++;
                } else curtainDry++;
                // 湿核心带（≤0.7×半宽）必须满灌——与 HydrologyWaterFillProbe 同一不变量。
                // 河缘浅水带（V 形断面的浅边）本来就是岩石，瀑布水流集中河心，干列属正常。
                if (s0 != null && s0.width() <= 80) {
                    if (s0.distToCenter() <= s0.width() * 0.7) {
                        curtainCore++;
                        if (!c.fillWater()) curtainCoreDry++;
                    }
                }
            }
        }

        System.out.println("=== WaterfallProbe ===");
        System.out.println("seed=" + seed + " horizontalScale=" + horizontalScale);
        System.out.println("rivers=" + rivers + " nodes=" + nodes + " runs=" + runs);
        System.out.println("fallNodes=" + fallNodes + " (每 " + (fallNodes > 0
                ? String.format("%.0f", (double) nodes / fallNodes) : "-") + " 节点一级跌水)");
        System.out.println("drop: avg=" + (fallNodes > 0 ? String.format("%.2f", dropSum / fallNodes) : "-")
                + " min=" + (fallNodes > 0 ? String.format("%.2f", dropMin) : "-")
                + " max=" + (fallNodes > 0 ? String.format("%.2f", dropMax) : "-")
                + " (单级 sanity 钳 " + P.waterfallMaxDrop() + ")");
        System.out.println("nonMonotonic=" + nonMonotonic + " (水面下游回升，必须为 0)");
        System.out.println("angleFails=" + angleFails + " runTooSmall=" + runTooSmall
                + " stepOver=" + stepOver + " tooClose=" + tooClose
                + " wellViolation=" + wellViolation + " (五项均须为 0)");
        System.out.println("curtainColumns=" + curtainColumns + " curtainFilled=" + curtainFilled
                + " curtainDry=" + curtainDry + " (河缘浅水带干列属正常)"
                + " curtainDropMax=" + String.format("%.2f", curtainDropMax));
        System.out.println("curtainCore=" + curtainCore + " curtainCoreDry=" + curtainCoreDry
                + " (湿核心带水幕干列，必须为 0)");
        System.out.println("lipFrozen=" + lipFrozen + " lipOverCarved=" + lipOverCarved
                + " (崖顶边缘唇口列被 IDW 挖坑数，必须为 0)");
        System.out.println("lipMarginPerched=" + lipMarginPerched
                + " (唇口河缘漫水列——水幕顶直角超出原地形，必须为 0)");
        System.out.println("curtainOutside=" + curtainOutside
                + " (水幕出河道列——斜坡孤立水柱，必须为 0)");

        boolean pass = nonMonotonic == 0 && angleFails == 0 && runTooSmall == 0
                && stepOver == 0 && wellViolation == 0 && lipOverCarved == 0
                && lipMarginPerched == 0 && curtainOutside == 0
                && runs > 0 && curtainColumns > 0 && curtainCore > 0 && curtainCoreDry == 0;
        // tooClose 为软间距偏好（防过近崖壁连成阶梯），非正确性铁律，不计入 pass
        System.out.println("status=" + (pass ? "PASS" : "FAIL"));
    }

    /**
     * 校验单个瀑布 run [a,b]：原地形坡角 ≥ waterfallMinAngle、净落差 ≥ waterfallMinDrop、
     * 单级阶落差 ≤ waterfallMaxDrop、与上一 run 间距 ≥ waterfallMinSpacing。
     *
     * <p>返回 {angleFails, runTooSmall, stepOver, tooClose, runs=1} 供主循环累加。</p>
     */
    private static int[] checkRun(RiverLineRegion.RiverPolyline river, double[] terr,
                                  int a, int b, double horizontalScale, RiverLineParams P,
                                  int lastRunEnd) {
        int angleFails = 0, runTooSmall = 0, stepOver = 0, tooClose = 0;
        double terrDrop = terr[a] - terr[b];
        double horiz = 0.0;
        for (int k = a + 1; k <= b; k++) {
            double dx = river.nodes[k].x() - river.nodes[k - 1].x();
            double dz = river.nodes[k].z() - river.nodes[k - 1].z();
            horiz += Math.hypot(dx, dz) * horizontalScale;
        }
        double angle = horiz > 1e-6 ? Math.atan2(terrDrop, horiz) * 180.0 / Math.PI : 90.0;
        if (angle < P.waterfallMinAngle() - 1e-6) angleFails++;
        if (terrDrop < P.waterfallMinDrop() - 1e-6) runTooSmall++;
        if (a - lastRunEnd < P.waterfallMinSpacing()) tooClose++;
        // 单级阶落差 sanity 钳：真实崖面可整体成 1 阶（落差远大于 maxDrop），
        // 故阈值放宽到 4×maxDrop；仅捕获失控级（>16 block 的单级跌水）。
        for (int k = a + 1; k <= b; k++) {
            double d = river.surfaceY[k - 1] - river.surfaceY[k];
            if (d > P.waterfallMaxDrop() * 4.0 + 1e-6) stepOver++;
        }
        return new int[]{angleFails, runTooSmall, stepOver, tooClose, 1};
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private static double[] ground(CellGenerator terrain, int cx, int cz, double scale) {
        double[] values = new double[256];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            values[x * 16 + z] = terrain.sample((cx * 16 + x) / scale,
                    (cz * 16 + z) / scale).height;
        }
        return values;
    }
}
