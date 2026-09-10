package com.geogenesis.worldgen.terrain;

/**
 * B1 / P0-1 止血验证探针（2026-09-11）。
 *
 * <p>验证 {@code getBaseHeight}/{@code getBaseColumn} 改走【非阻塞两级降级】后：
 * <ol>
 *   <li><b>冷启动不产生侵蚀 tile</b>：在未生成区域高频调用
 *       {@link GeoGenesisTerrain#sampleHeightNonBlocking}，侵蚀 tile 缓存必须<b>仍为 0</b>，
 *       且单次耗时必须 << 400 ms（旧路径冷 tile 实测 400~719 ms）。</li>
 *   <li><b>对照</b>：旧路径 {@link GeoGenesisTerrain#sampleCell} 单次冷调用 —— 会真正
 *       生成侵蚀 tile，用于量化两者差距。</li>
 *   <li><b>已生成区域仍精确</b>：chunk 已在缓存时，非阻塞查询必须走 Tier 1 并与
 *       {@code sampleCell} 的 height <b>完全一致</b>（保证落块一致性未退化）。</li>
 * </ol>
 *
 * <p><b>B2（2026-09-11）</b>追加验证"快速路径与完整管线收敛"：
 * <ol start="4">
 *   <li>[5a] {@code sample + applyCachedTileDelta} 与 {@code sampleWu} <b>逐位一致</b>
 *       （隔离"侵蚀增量"这一项）；</li>
 *   <li>[5b] 门面级 {@code sampleCellLight} 与 {@code sampleCell} 的 {@code terrainType}
 *       <b>全部一致</b>（height 残差来自水文河谷雕刻，属已知边界）；</li>
 *   <li>[6] 冷启动负例：未生成区域 {@code applyCachedTileDelta} 必须返回 false、
 *       不产生 tile、不改动 cell。</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runBaseHeightProbe [seed]}</p>
 */
public final class BaseHeightProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, p.minY(), p.maxY());
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);
        gen.seed(seed);
        terrain.seed(seed);

        System.out.printf("=== BaseHeight probe seed=%d ===%n", seed);

        // ---- [1] 冷启动：非阻塞查询（未生成区域）→ 必须不产生侵蚀 tile 且极快
        int n = 0;
        double acc = 0;
        long t0 = System.nanoTime();
        for (int z = 3000; z < 3640; z += 37) {
            for (int x = 3000; x < 3640; x += 41) {
                acc += terrain.sampleHeightNonBlocking(x, z);
                n++;
            }
        }
        long t1 = System.nanoTime();
        int tilesAfter1 = gen.erosionTileCacheSize();
        double msPerCall = (t1 - t0) / 1e6 / Math.max(1, n);
        boolean pass1 = tilesAfter1 == 0 && msPerCall < 5.0;
        System.out.printf("[1] 非阻塞查询(冷) n=%d 总耗时=%.1fms 单次=%.4fms 侵蚀tile缓存=%d (sumH=%.1f)%n",
            n, (t1 - t0) / 1e6, msPerCall, tilesAfter1, acc);
        System.out.println("    " + (pass1 ? "PASS" : "FAIL")
            + " — 要求 tile缓存==0(未触发生成) 且 单次 << 400ms");

        // ---- [2] 对照：旧路径 sampleCell 单次冷启动（另一处未生成区域）
        long t2 = System.nanoTime();
        Cell cell = terrain.sampleCell(6000, 6000);
        long t3 = System.nanoTime();
        int tilesAfter2 = gen.erosionTileCacheSize();
        double oldMs = (t3 - t2) / 1e6;
        System.out.printf("[2] 旧路径 sampleCell(冷) 单次=%.1fms 侵蚀tile缓存=%d height=%.3f%n",
            oldMs, tilesAfter2, cell != null ? cell.height : Double.NaN);
        System.out.printf("    加速比 ≈ %.0fx (旧单次 / 新单次)%n",
            msPerCall > 0 ? oldMs / msPerCall : Double.NaN);

        // ---- [3] 已生成区域：非阻塞查询应命中 Tier1 且与 sampleCell 完全一致
        double nb = terrain.sampleHeightNonBlocking(6000, 6000);
        double exact = cell != null ? cell.height : Double.NaN;
        double d = Math.abs(nb - exact);
        boolean pass3 = d < 1e-9;
        System.out.printf("[3] 已生成区域 Tier1 精确性: nonBlocking=%.6f sampleCell=%.6f delta=%e %s%n",
            nb, exact, d, pass3 ? "PASS" : "FAIL");

        // ---- [4] 降级统计（P0-3 埋点）
        System.out.printf("[4] 降级统计: tier1(精确命中)=%d tier2(廉价重算)=%d%n",
            terrain.baseHeightTier1Count(), terrain.baseHeightTier2Count());

        // ================= B2：快速路径与完整管线收敛 =================

        // ---- [5a] CellGenerator 级：sample + applyCachedTileDelta 必须与 sampleWu 逐位一致
        //       （隔离"侵蚀增量"这一项：sampleWu = sample + applyTileDelta，同源的完整侵蚀结果）
        int n5 = 0, notApplied = 0, mismE = 0, mismH = 0, mismT = 0;
        for (int z = 3000; z <= 3200; z += 43) {
            for (int x = 3000; x <= 3200; x += 47) {
                n5++;
                Cell full = gen.sampleWu(x, z);          // 先跑完整 → tile 入缓存
                Cell light = gen.sample(x, z);           // 快速路径基础（无侵蚀）
                boolean applied = gen.applyCachedTileDelta(light, x, z);
                if (!applied) { notApplied++; continue; }
                if (Math.abs(full.e - light.e) > 1e-9) mismE++;
                if (Math.abs(full.height - light.height) > 1e-9) mismH++;
                if (full.terrainType != light.terrainType) mismT++;
            }
        }
        boolean pass5 = n5 > 0 && notApplied == 0 && mismE == 0 && mismH == 0 && mismT == 0;
        System.out.printf("[5a] light+cachedDelta vs sampleWu: n=%d notApplied=%d eMism=%d hMism=%d typeMism=%d %s%n",
            n5, notApplied, mismE, mismH, mismT, pass5 ? "PASS" : "FAIL");

        // ---- [5b] 门面级：sampleCellLight vs sampleCell（后者含水文雕刻 → 河道处高度可不同）
        int n6 = 0, typeMatch = 0;
        double maxHd = 0, sumHd = 0;
        for (int z = 3000; z <= 3200; z += 43) {
            for (int x = 3000; x <= 3200; x += 47) {
                Cell a = terrain.sampleCell(x, z);        // 生成并缓存 chunk + tile
                Cell b = terrain.sampleCellLight(x, z);
                if (a == null || b == null) continue;
                n6++;
                if (a.terrainType == b.terrainType) typeMatch++;
                double hd = Math.abs(a.height - b.height);
                maxHd = Math.max(maxHd, hd);
                sumHd += hd;
            }
        }
        boolean pass6 = n6 > 0 && typeMatch == n6;
        System.out.printf("[5b] sampleCellLight vs sampleCell: n=%d terrainType一致=%d/%d meanHdelta=%.4f maxHdelta=%.3f %s%n",
            n6, typeMatch, n6, sumHd / Math.max(1, n6), maxHd, pass6 ? "PASS" : "FAIL");
        System.out.println("     (height 残差来自水文河谷雕刻：快速路径不跑雕刻计划，属已知边界)");

        // ---- [6] 冷启动负例：未生成区域 applyCachedTileDelta 必须 false 且不产 tile、不改 cell
        double coldX = 50000, coldZ = 50000;
        int tileBefore = gen.erosionTileCacheSize();
        Cell cold = gen.sample(coldX, coldZ);
        double coldH0 = cold.height;
        boolean appliedCold = gen.applyCachedTileDelta(cold, coldX, coldZ);
        int tileAfter = gen.erosionTileCacheSize();
        boolean pass7 = !appliedCold && tileAfter == tileBefore && cold.height == coldH0;
        System.out.printf("[6] 冷启动负例: applied=%s tileΔ=%d height不变=%s %s%n",
            appliedCold, tileAfter - tileBefore, cold.height == coldH0, pass7 ? "PASS" : "FAIL");

        // ================= B3：缓存埋点与 LRU =================

        // ---- [7] chunk Cell 缓存：首次 miss、重复 hit
        long ch0 = terrain.chunkCacheStats().hits(), cm0 = terrain.chunkCacheStats().misses();
        terrain.getChunkCells(300, 300);
        terrain.getChunkCells(301, 300);
        long cMiss1 = terrain.chunkCacheStats().misses() - cm0;
        long cHit1 = terrain.chunkCacheStats().hits() - ch0;
        terrain.getChunkCells(300, 300);
        terrain.getChunkCells(301, 300);
        long cHit2 = terrain.chunkCacheStats().hits() - ch0;
        boolean pass8 = cMiss1 >= 2 && cHit1 == 0 && cHit2 >= 2;
        System.out.printf("[7] chunk缓存: 首轮 miss=%d hit=%d → 二轮累计 hit=%d %s%n",
            cMiss1, cHit1, cHit2, pass8 ? "PASS" : "FAIL");

        // ---- [8] 侵蚀 tile 缓存：首次 miss(生成)、重复 hit
        long th0 = gen.tileCacheStats().hits(), tm0 = gen.tileCacheStats().misses();
        for (int i = 0; i < 3; i++) gen.sampleWu(8000 + i * 13, 8000);
        long tMiss1 = gen.tileCacheStats().misses() - tm0;
        long tHit1 = gen.tileCacheStats().hits() - th0;
        for (int i = 0; i < 3; i++) gen.sampleWu(8000 + i * 13, 8000);
        long tHit2 = gen.tileCacheStats().hits() - th0;
        boolean pass9 = tMiss1 >= 1 && tHit2 > tHit1;
        System.out.printf("[8] tile缓存: 首轮 miss=%d hit=%d → 二轮累计 hit=%d %s%n",
            tMiss1, tHit1, tHit2, pass9 ? "PASS" : "FAIL");
        System.out.println("     " + terrain.chunkCacheStats() + " | " + gen.tileCacheStats());

        // ---- [9] （可选）LRU 驱逐压力测试：需传 `stress` 参数（生成 >256 tiles，较慢）
        boolean pass10 = true;
        if (args.length > 1 && "stress".equalsIgnoreCase(args[1])) {
            long ev0 = gen.tileCacheStats().evictions();
            int made = 0;
            for (int i = 0; i < 300; i++) {
                gen.sampleWu(20000 + i * 48, 20000);   // 每 48wu 一个新 tile
                made++;
            }
            long evicted = gen.tileCacheStats().evictions() - ev0;
            int sz = gen.erosionTileCacheSize();
            pass10 = evicted > 0 && sz <= 300;
            System.out.printf("[9] LRU驱逐压力: 生成tile≈%d evict=%d 剩余条目=%d (容量256+缓冲) %s%n",
                made, evicted, sz, pass10 ? "PASS" : "FAIL");
        } else {
            System.out.println("[9] LRU驱逐压力测试已跳过（加 `stress` 参数启用，约 15~30s）");
        }

        boolean all = pass1 && pass3 && pass5 && pass6 && pass7 && pass8 && pass9 && pass10;
        System.out.println(all ? "=== ALL PASS ===" : "=== FAILURES PRESENT ===");
    }
}
