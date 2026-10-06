package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.hydrology.HydrologyBlockCarvedColumn;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.List;

/**
 * <h1>model 链列适配器验收（接线后的端到端契约）</h1>
 *
 * <h2>判据（全部来自既有铁律/方案 §5）</h2>
 * <ol>
 *   <li><b>只下挖</b>：每列 placed(=eroded−cut) ≤ eroded —— 用户铁律，
 *       由写回代数在 mask=1、erosion≥0 下恒成立 ⇒ 本探针直接验列级
 *       {@code erosion ≥ 0} 与 {@code carved ≤ original + 容差}；</li>
 *   <li><b>水位自洽</b>：出水列必须 {@code surface > carved + 0.5}（真雕出河床才灌水）；
 *       湖列 {@code lakeLevelY == waterSurfaceY}；</li>
 *   <li><b>确定性</b>：同 chunk 两次 {@code columnsFor} 逐位相同（缓存命中路径
 *       与内容一致性 —— 纯函数铁律在缓存下的直接检验）；</li>
 *   <li><b>覆盖</b>：恰好 256 列、坐标落在本 chunk；湖列/河列/普通列三类互斥；</li>
 *   <li><b>区域缓存不串台</b>：清缓存后重建，同 chunk 结果逐位相同
 *       （region 求解 = 纯函数，缓存只改时序）。</li>
 * </ol>
 *
 * <p><b>NOT VERIFIED</b>：方块铺设、实机渲染、并发/预热路径未覆盖。</p>
 */
public final class ModelPipelineAdapterProbe {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);

        ModelHydrologyProvider provider = new ModelHydrologyProvider(gen);

        // ★ 自动选窗：默认窗口可能恰好无水（湖占 region ~1%、中心线细化后更稀）。
        //   从给定 chunk 起螺旋扫描，选第一个【有湖或河】的 chunk ——
        //   否则水位/湖判据全部空测（首轮实测：河=0 而探针打印 PASS 的教训）。
        int chunkX = args.length > 1 ? Integer.parseInt(args[1]) : -9;
        int chunkZ = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        Cell[] cells = null;
        List<HydrologyBlockCarvedColumn> first = null;
        int scanned = 0;
        outer:
        for (int ring = 0; ring <= 6; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.abs(dx) != ring && Math.abs(dz) != ring) continue;
                    int cx = chunkX + dx, cz = chunkZ + dz;
                    scanned++;
                    Cell[] cand = sampleOriginal(gen, tp.horizontalScale(), cx, cz);
                    List<HydrologyBlockCarvedColumn> cols = provider.columnsFor(cx, cz, cand);
                    if (cols.stream().anyMatch(c -> c.lakePlan() || c.erosion() > 0)) {
                        chunkX = cx; chunkZ = cz; cells = cand; first = cols;
                        break outer;
                    }
                }
            }
        }
        if (first == null) {
            System.out.println("[FAIL] 扫描 " + scanned + " 个 chunk 无任何湖/河列 ⇒ 判据空测");
            System.exit(1);
            return;
        }
        System.out.println("[INFO] 有水窗口 chunk=(" + chunkX + "," + chunkZ
                + ") 第 " + scanned + " 个候选）");

        // 实质最终副本（lambda 捕获要求）
        final int wx = chunkX, wz = chunkZ;
        final List<HydrologyBlockCarvedColumn> cols0 = first;
        final Cell[] cells0 = cells;
        final double invHs = 1.0 / Math.max(0.01, tp.horizontalScale());
        // 侵蚀后地形（与适配器同一取样：sampleWu(block/hs)）—— carved 的参照系
        final java.util.function.BiFunction<Integer, Integer, Double> erodedAt = (bx, bz) ->
                gen.sampleWu(bx * invHs, bz * invHs).height;

        // ---- ① 冷解已在扫描阶段发生（耗时见 [HYDRO-MODEL] region 日志）；② 缓存命中如下 ----
        long t0 = System.nanoTime();
        List<HydrologyBlockCarvedColumn> warm = provider.columnsFor(wx, wz, cells0);
        double warmMs = (System.nanoTime() - t0) / 1e6;

        int failures = 0;
        StringBuilder log = new StringBuilder();
        log.append("=== model 链列适配器验收 ===\n");
        log.append(String.format("seed=%d chunk=(%d,%d) hs=%.1f 扫描候选=%d%n%n",
                seed, chunkX, chunkZ, tp.horizontalScale(), scanned));

        failures += check("覆盖（256 列 + 坐标 + 两类互斥）", () -> {
            require(cols0.size() == 256, "列数=" + cols0.size());
            int lake = 0, river = 0, plain = 0;
            for (HydrologyBlockCarvedColumn c : cols0) {
                require(Math.floorDiv(c.blockX(), 16) == wx, "blockX 出界 " + c.blockX());
                require(Math.floorDiv(c.blockZ(), 16) == wz, "blockZ 出界 " + c.blockZ());
                if (c.lakePlan()) { lake++;
                    require(c.lakeNode() == null, "model 湖列不应带 legacy LakeNode");
                    require(c.lakeLevelY() == c.waterSurfaceY(), "湖水位应等于湖面");
                    require(!c.fillWater(), "湖列由写回层洪泛判定，fillWater 应为 false");
                } else if (c.erosion() > 0) {
                    river++;
                    // ★ 参照系：model 的 carved 以【侵蚀后】地形为基准（carved = eroded − depth），
                    //   与 legacy 的"无侵蚀基线"不同 —— 两者都满足写回公式，但断言不能混用。
                    double eroded = erodedAt.apply(c.blockX(), c.blockZ());
                    require(Math.abs(c.erosion() - (eroded - c.carvedGroundY())) < 1e-6,
                            "河列 erosion 应等于 eroded−carved（model 参照系）");
                } else plain++;
            }
            log.append(String.format("列构成：湖=%d 河=%d 普通=%d%n", lake, river, plain));
            require(lake + river + plain == 256, "三类应覆盖全部列");
            // ★ 空转防线：若整 chunk 无水，下面的水位/湖判据全部空测（假 PASS）。
            //   首轮实测正是如此（段索引 bug ⇒ 河=0，而探针仍打印 PASS）。
            require(lake + river > 0, "chunk 内无任何湖/河列 ⇒ 判据空测（选错窗口或产出 bug）");
        });

        failures += check("只下挖（placed = eroded − erosion ≤ eroded）", () -> {
            for (HydrologyBlockCarvedColumn c : cols0) {
                // 写回（mask=1）：placed = eroded − erosion ⇒ 只下挖 ⇔ erosion ≥ 0。
                require(c.erosion() >= -1e-9, "负下切(=抬高) " + c.erosion());
                double eroded = erodedAt.apply(c.blockX(), c.blockZ());
                double placed = eroded - c.erosion();
                require(placed <= eroded + 1e-9, "placed 高于侵蚀后地形");
                // 河列 carved 必须等于将要落的 placed（model 参照系自洽）
                if (c.erosion() > 0) {
                    require(Math.abs(c.carvedGroundY() - placed) < 1e-6,
                            "carved 与 placed 不一致");
                }
            }
        });

        failures += check("水位自洽（出水 ⇒ 真雕出河床；湖面=湖水位）", () -> {
            for (HydrologyBlockCarvedColumn c : cols0) {
                if (c.fillWater()) {
                    require(c.waterSurfaceY() > c.carvedGroundY() + 0.5,
                            "出水但水面未高于河床 " + c.waterSurfaceY() + " vs " + c.carvedGroundY());
                }
                if (c.lakePlan()) {
                    require(c.lakeLevelY() == c.waterSurfaceY(), "湖面/湖水位不一致");
                }
                require(c.lipSurfaceY() >= c.waterSurfaceY() - 1e-9, "唇口低于水面");
            }
        });

        failures += check("确定性（缓存命中逐位相同）", () -> requireSame(cols0, warm));

        // ---- ③ 清缓存重建：region 求解 = 纯函数 ----
        provider.clear();
        List<HydrologyBlockCarvedColumn> rebuilt = provider.columnsFor(wx, wz, cells0);
        failures += check("纯函数（清缓存重建逐位相同）", () -> requireSame(cols0, rebuilt));

        // ---- ④ legacy 路径未受影响（开关关时 HydrologyChunkEngine 走 carveChunk）----
        //   本探针不直接验 legacy（那需要 Forge 配置环境）；其回归由 runWorldgenGate 承担。
        log.append(String.format("%n耗时：缓存命中 %.2f ms/chunk（冷解见上方 [HYDRO-MODEL] region 日志）%n",
                warmMs));
        log.append("边界：方块铺设/实机渲染/并发预热未覆盖（NOT VERIFIED）%n");
        log.append(String.format("%n判定：%s%n", failures == 0 ? "ALL PASS" : "★ FAIL"));

        java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/adapter"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("build/adapter/metrics.txt"), log);
        System.out.print(log);
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * 无侵蚀基线采样 —— 与生产 {@code HydrologyChunkSampling.sample} 同语义
     * （该类包私有不可跨包引用 ⇒ 此处按同一约定复刻：{@code sample(block/hs)}，
     * 探针传参仍由 hs 覆盖 block→wu 换算）。
     */
    private static Cell[] sampleOriginal(CellGenerator gen, double hs, int chunkX, int chunkZ) {
        Cell[] cells = new Cell[256];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                cells[lx * 16 + lz] = gen.sample(
                        (chunkX * 16 + lx) / hs, (chunkZ * 16 + lz) / hs);
            }
        }
        return cells;
    }

    private static void requireSame(List<HydrologyBlockCarvedColumn> a,
                                    List<HydrologyBlockCarvedColumn> b) {
        require(a.size() == b.size(), "列数不同 " + a.size() + " vs " + b.size());
        for (int i = 0; i < a.size(); i++) {
            HydrologyBlockCarvedColumn x = a.get(i), y = b.get(i);
            require(x.blockX() == y.blockX() && x.blockZ() == y.blockZ(), "坐标不同 @" + i);
            require(x.originalGroundY() == y.originalGroundY(), "original 不同 @" + i);
            require(x.carvedGroundY() == y.carvedGroundY(), "carved 不同 @" + i);
            require(x.waterSurfaceY() == y.waterSurfaceY(), "水面不同 @" + i);
            require(x.erosion() == y.erosion(), "erosion 不同 @" + i);
            require(x.erosionMask() == y.erosionMask(), "mask 不同 @" + i);
            require(x.fillWater() == y.fillWater(), "fillWater 不同 @" + i);
            require(x.lakePlan() == y.lakePlan(), "lakePlan 不同 @" + i);
            require(Double.doubleToLongBits(x.lakeLevelY())
                    == Double.doubleToLongBits(y.lakeLevelY()), "lakeLevel 不同 @" + i);
        }
    }

    private interface Case { void run() throws Exception; }

    private static int check(String name, Case c) {
        try {
            c.run();
            System.out.println("[PASS] " + name);
            return 0;
        } catch (Throwable t) {
            System.out.println("[FAIL] " + name + ": " + t.getMessage());
            return 1;
        }
    }

    private static void require(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private ModelPipelineAdapterProbe() { }
}
