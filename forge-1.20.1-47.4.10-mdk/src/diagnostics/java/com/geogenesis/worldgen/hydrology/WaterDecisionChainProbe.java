package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.flowaccum.FlowField;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 「水位决策链」探针（2026-09-17）—— <b>控制变量：逐环节打印谁决定了水位</b>。
 *
 * <h2>要回答的问题</h2>
 * <p>实测（1 块精度）某水格水面 <b>166.63</b>，而它<b>紧邻 1~2 块</b>的旱地只有
 * <b>157.88</b> ⇒ 水本该流走（21.6% 的格如此，最坏 8.747 块）。
 * 本探针在给定块坐标上，把水位决策链的每一环节都打出来，看<b>哪个环节会给出 157.88</b>：</p>
 * <ol>
 *   <li>{@code spill}（{@code RiverLineHit.surfaceY}，无侵蚀）；</li>
 *   <li>{@code erodedWaterLevel}（侵蚀短板，当前生效）；</li>
 *   <li>{@code filledAt}（priority-flood 溢流高程）；</li>
 *   <li>{@code LakeNode} 的域 / {@code inFlood} / {@code floodLevel}（本次新增回传值）；</li>
 *   <li>该格 8 邻与 24 邻的最低非水地面（真实盆沿，1 块精度）。</li>
 * </ol>
 *
 * <pre>{@code gradlew runWaterDecisionChainProbe [-PprobeArgs="seed blockX blockZ"]}</pre>
 */
public final class WaterDecisionChainProbe {

    private WaterDecisionChainProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 5436529513624899584L;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -15;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : 661;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        RiverLineParams rp = RiverLineParams.defaults();
        double hs = tp.horizontalScale();
        double seaLevel = gen.heightCurve().seaLevelY();
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);

        double wuX = bx / hs, wuZ = bz / hs;
        System.out.printf("=== WaterDecisionChainProbe seed=%d 块(%d,%d) = wu(%.2f,%.2f) ===%n",
                seed, bx, bz, wuX, wuZ);

        // ---- 实际放置值（权威口径）----
        Cell placed = gt.getChunkCells(bx >> 4, bz >> 4)[Math.floorMod(bx, 16) * 16 + Math.floorMod(bz, 16)];
        System.out.printf("%n[0] 实际放置：height=%.3f  riverType=%d  riverSurfaceY=%.3f%n",
                placed.height, placed.riverType, placed.riverSurfaceY);

        // ---- 河线侧 ----
        // ★★★ 2026-09-17 口径修正（第 8 次同类错误的根治）★★★
        //   此前本探针自建 RiverLineNetwork，**漏了生产 engine 的关键接线**：
        //     `network.setPrecipSampler(terrain::precipitationAt, ...)`  ← 降水驱动汇流累积
        //   而降水影响汇流累积 ⇒ 影响河网/湖的规模与域 ⇒ 两条链路看到的是**不同水体**
        //   （实测：自建版 3 个命中且最近是河；engine 版只剩 1 个湖命中）。
        //   ⇒ 探针一律复用 **生产 engine 的 network 实例**，不再自己拼。
        HydrologyExperimentEngine engine0 = new HydrologyExperimentEngine(gen, seed);
        RiverLineNetwork net = engine0.network();
        RiverLineNetwork.RiverLineHit hit = net.sample(wuX, wuZ);
        if (hit == null) {
            System.out.println("[1] 河线命中：无");
        } else {
            System.out.printf("[1] 河线命中：dist=%.2f width=%.2f isLake=%s surfaceY=%.3f%n",
                    hit.distToCenter(), hit.width(), hit.isLake(), hit.surfaceY());
            RiverLineRegion.LakeNode ln = hit.lake();
            if (ln != null) {
                double eroded = ln.erodedWaterLevel((a, b) -> gen.sampleWu(a, b).height);
                System.out.printf("[2] 湖：中心 wu(%.1f,%.1f)  半径=%.1f  湖面(无侵蚀)=%.3f%n",
                        ln.x, ln.z, ln.radius, ln.height);
                System.out.printf("[3] erodedWaterLevel=%.3f   hasRim=%s  floodLevel(回传)=%.3f%n",
                        eroded, ln.hasRim(), ln.floodLevel());
                System.out.printf("[4] 域内?=%s   inFlood?=%s%n",
                        ln.inDomain(wuX, wuZ, rp.gridCell() * 2.0), ln.inFlood(wuX, wuZ));
            }
        }

        // ---- priority-flood（大窗细格）----
        double R = 192;   // wu
        FlowField f = new FlowField(wuX - R, wuZ - R, wuX + R, wuZ + R, 6.0,
                (wx, wz) -> gen.terrainEQuick(wx, wz));
        f.computeFill((wx, wz) -> gen.heightCurve().heightFromE(gen.terrainEQuick(wx, wz)), seaLevel);
        int idx = f.indexOf(wuX, wuZ);
        System.out.printf("[5] priority-flood（6wu 细格 ±%.0fwu）：地面=%.3f  fillE=%.3f  filledAt=%.3f  accum=%.0f%n",
                R, f.fillEAt(idx), f.fillEAt(idx), f.filledAt(idx), f.accumAt(idx));
        System.out.printf("    该格 flowTo=%d  isBasinCell=%s  basinDepth=%.3f%n",
                f.flowTo(idx), f.isBasinCell(idx), f.basinDepthAt(idx));

        // ---- 真实盆沿（1 块精度，多半径）----
        System.out.printf("%n[6] 真实盆沿（1 块精度，实际放置口径）：%n");
        int[] radii = {1, 2, 4, 8, 24};
        for (int r : radii) {
            double best = Double.MAX_VALUE;
            double bxx = 0, bzz = 0;
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    int nx = bx + dx, nz = bz + dz;
                    Cell c = gt.getChunkCells(nx >> 4, nz >> 4)
                            [Math.floorMod(nx, 16) * 16 + Math.floorMod(nz, 16)];
                    if (c.riverType != 0) continue;
                    if (c.height < best) { best = c.height; bxx = nx; bzz = nz; }
                }
            }
            System.out.printf("     半径 %2d 块：最低旱地 %.3f @块(%.0f,%.0f)"
                            + " ⇒ 水位(%.3f) − 它 = %+.3f 块%n",
                    r, best, bxx, bzz, placed.riverSurfaceY, placed.riverSurfaceY - best);
        }

        // ---- ★★ 全部命中（carver 走 IDW 混合用的就是这份列表）----
        //   若某列 dist ≫ width（不在河道内、是谷壁列），carver 会走
        //   `nearestDist > nearestWidth && !atFall` 分支 ⇒ waterSurfaceY = 多命中 surfaceY 的 IDW 平均
        //   ⇒ **可能被远处/另一水体的高度拉高**，导致"离河 70 块的低地被灌水"。
        System.out.println();
        System.out.printf("[7] ★ 全部命中（sampleAll）—— carver 的 IDW 混合依据%n");
        java.util.List<RiverLineNetwork.RiverLineHit> all = net.sampleAll(wuX, wuZ);
        System.out.printf("    命中数 = %d%n", all.size());
        for (int i = 0; i < all.size(); i++) {
            RiverLineNetwork.RiverLineHit h = all.get(i);
            System.out.printf("    [%d] dist=%7.2f wu (%6.1f 块)  width=%6.2f wu  isLake=%-5s surfaceY=%.3f%n",
                    i, h.distToCenter(), h.distToCenter() * hs, h.width(), h.isLake(), h.surfaceY());
        }
        // ---- ★★★ [8] 直接调用 carver，拿它对【本列】的计划（唯一的权威依据）----
        //   矛盾点：最近命中是河（isLake=false）⇒ carver 湖分支"不该"触发，
        //   但实际铺了湖面 ⇒ 必须让 carver 自己回答"它走了哪条分支、给出什么水面"。
        System.out.println();
        System.out.println("[8] ★ 直接调用 carveChunk，取本列的雕刻计划（权威）");
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(gen, seed);
        double[] originalGround = new double[256];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int gx = (bx >> 4) * 16 + lx, gz = (bz >> 4) * 16 + lz;
                originalGround[lx * 16 + lz] = gen.sample(gx / hs, gz / hs).height;
            }
        }
        java.util.List<HydrologyBlockCarvedColumn> cols =
                HydrologyBlockCarver.carveChunk(engine, bx >> 4, bz >> 4, hs, originalGround);
        int want = Math.floorMod(bx, 16) * 16 + Math.floorMod(bz, 16);
        HydrologyBlockCarvedColumn target = null;
        for (HydrologyBlockCarvedColumn c : cols) {
            if (Math.floorMod(c.blockX(), 16) * 16 + Math.floorMod(c.blockZ(), 16) == want) {
                target = c;
                break;
            }
        }
        if (target == null) {
            System.out.println("    未找到本列（carveChunk 返回 " + cols.size() + " 列）");
        } else {
            System.out.printf("    block=(%d,%d) originalGround=%.3f carved=%.3f%n",
                    target.blockX(), target.blockZ(),
                    target.originalGroundY(), target.carvedGroundY());
            System.out.printf("    ★ waterSurfaceY=%.3f  lipSurfaceY=%.3f  fillWater=%s  lakePlan=%s  fallDrop=%.3f%n",
                    target.waterSurfaceY(), target.lipSurfaceY(),
                    target.fillWater(), target.lakePlan(), target.fallDrop());
            System.out.println("    ⇒ waterSurfaceY 即合成层据以判水的 spil；与 [0] 的 166.627 比对即可定位分支");
            if (target.lakePlan()) {
                System.out.println("    ★★ lakePlan = true ⇒ **走了湖分支**（尽管最近命中 isLake=false）");
                System.out.println("       ⇒ 湖分支的触发条件不是 samples.get(0).isLake()，需再查 carveColumn 的湖判定");
            } else {
                System.out.println("    lakePlan = false ⇒ 未走湖分支 ⇒ 166.627 来自普通分支的 IDW/最近水面");
            }
        }

        // ---- ★★★ [9] 终极对照：carver 内部那条调用链给出的 get(0) ----
        System.out.println();
        System.out.println("[9] 终极对照：carver 用的调用链 vs 我的调用");
        java.util.List<HydrologyBlockSample> bs = engine.sampleBlockAll(bx, bz, hs);
        System.out.printf("    engine.sampleBlockAll(%d,%d,%.1f)：%d 个样本%n", bx, bz, hs, bs.size());
        for (int i = 0; i < bs.size(); i++) {
            HydrologyBlockSample s = bs.get(i);
            System.out.printf("      [%d] dist=%7.2f isLake=%-5s surfaceY=%.3f  (lake()=%s)%n",
                    i, s.distToCenter(), s.isLake(), s.surfaceY(), s.lake() == null ? "null" : "非null");
        }
        System.out.printf("    ★ carver 的判定依据 samples.get(0).isLake() = %s%n",
                bs.isEmpty() ? "空" : bs.get(0).isLake());
        System.out.println("    与 [7] 的 network.sampleAll 对照：若 isLake 不同 ⇒ 两条链路本身不一致");

        System.out.println();
        System.out.println("判读：① 若存在【更近】的命中其 surfaceY ≈ 166.6 ⇒ IDW 把水面拉高到它；");
        System.out.println("      ② 若所有命中的 dist 都 ≫ width ⇒ 本列是【谷壁列】，");
        System.out.println("         carver 的 `nearestDist > nearestWidth` 分支会给它一个混合水面；");
        System.out.println("      ③ 该混合水面若高于本列地面（本列 157.946）⇒ 被灌水 ⇒ 悬空水板。");
        System.out.println();
        System.out.println("（另：哪个环节的输出 ≈『半径 1~2 块的最低旱地』= 正确的短板实现。）");
    }
}
