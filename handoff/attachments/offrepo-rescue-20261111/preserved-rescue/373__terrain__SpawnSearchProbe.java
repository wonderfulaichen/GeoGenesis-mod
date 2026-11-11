package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.climate.WhittakerType;

/**
 * ★ 2026-09-14：<b>出生点搜索阶段耗时探针</b>（用户："刚创建加载有一段无动静的空闲期，
 * 等一会才开始动进度条"）。
 *
 * <h3>被测量的真实阶段</h3>
 * <p>MC 创建世界时，<b>进度条出现之前</b>先做 BIOMES 阶段：{@code setInitialSpawn} →
 * {@code BiomeSource.findBiomeHorizontal} 在半径内<b>数千次</b>调用
 * {@code getNoiseBiome(quart)}。此阶段<b>无进度反馈</b> ⇒ 一旦这里有重活，
 * 玩家看到的就是"卡住不动"。</p>
 *
 * <p>本项目该路径为 {@code GeoGenesisBiomeSource.getNoiseBiome} →
 * {@link GeoGenesisTerrain#sampleCellLight}。本探针按<b>同样的调用序列</b>
 * （环绕出生点由内向外扫 quart 网格）复现，并分段计时：</p>
 * <ol>
 *   <li><b>buildTerrain</b>：首次构造全套噪声场（一次性）；</li>
 *   <li><b>biome 查询</b>：逐 quart 的 {@code sampleCellLight} —— 其中
 *       {@code fillRiverDistance} 对沙漠格会触发<b>河网 region 构建</b>
 *       （自述 ~117ms/region，且 {@code computeIfAbsent} 并发时互相阻塞）。</li>
 * </ol>
 *
 * <p>输出 ASCII 键值行，便于与基线/修改后对比。</p>
 *
 * <pre>{@code gradlew runSpawnSearchProbe -PprobeArgs="seed radiusQuart"}</pre>
 */
public final class SpawnSearchProbe {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 5436529513624899584L;
        int radiusQuart = args.length > 1 ? Integer.parseInt(args[1]) : 256;   // ±256 quart = ±1024 块

        // ---------- ① buildTerrain（一次性构造） ----------
        long t0 = System.nanoTime();
        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, p.minY(), p.maxY());
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);
        terrain.seed(seed);
        long buildMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("RESULT build_terrain_ms=" + buildMs);

        // ---------- ② 出生点搜索：环绕 (0,0) 由内向外扫 quart 网格 ----------
        //   与 MC findBiomeHorizontal 的访问模式同构（近距离优先，逐步外扩）。
        int step = 4;                        // quart 步长（MC 实际更密，此处取代表值）
        long sumNs = 0;
        long maxNs = 0;
        double maxX = 0, maxZ = 0;
        int nq = 0;
        int desert = 0;
        long t1 = System.nanoTime();
        for (int r = 0; r <= radiusQuart; r += step) {
            // 环形扫描（周长 ∝ r），与 MC 由内向外一致
            int ring = Math.max(1, (int) (2 * Math.PI * r / step));
            for (int k = 0; k < ring; k++) {
                double a = 2 * Math.PI * k / ring;
                int qx = (int) Math.round(Math.cos(a) * r);
                int qz = (int) Math.round(Math.sin(a) * r);
                long s = System.nanoTime();
                Cell c = terrain.sampleCellLight(qx * 4.0, qz * 4.0);   // quart → block
                long ns = System.nanoTime() - s;
                sumNs += ns;
                nq++;
                if (ns > maxNs) { maxNs = ns; maxX = qx * 4.0; maxZ = qz * 4.0; }
                if (c != null && c.biomeType == WhittakerType.DESERT) desert++;
            }
        }
        long totalMs = (System.nanoTime() - t1) / 1_000_000;
        System.out.println("RESULT spawnsearch_queries=" + nq
                + " desert=" + desert
                + " total_ms=" + totalMs
                + " avg_us=" + (nq > 0 ? sumNs / nq / 1000 : 0)
                + " max_ms=" + (maxNs / 1_000_000)
                + " max_at=(" + (long) maxX + "," + (long) maxZ + ")");

        // ---------- ③ 首个 chunk 的分段耗时（★ 空闲期的直接量化） ----------
        //   ★ 假设：RiverLineNetwork.region() 建 1 个 region 需先建 8 邻居 pass-1
        //     ⇒ "1 个 region = 9 次构建"。本节用【同 region / 新 region】对比验证：
        //     同 region 应 cache 命中（快）；跨 region 应再付 ~9 次构建（慢）。
        long t2 = System.nanoTime();
        terrain.getChunkCells(0, 0);
        long firstChunkMs = (System.nanoTime() - t2) / 1_000_000;
        System.out.println("RESULT first_chunk_ms=" + firstChunkMs);

        t2 = System.nanoTime();
        terrain.getChunkCells(1, 0);                 // 紧邻 → 同水文 region
        long nearChunkMs = (System.nanoTime() - t2) / 1_000_000;
        System.out.println("RESULT near_chunk_ms=" + nearChunkMs);

        t2 = System.nanoTime();
        terrain.getChunkCells(40, 40);               // 远 → 新水文 region（再付 9 次构建）
        long farChunkMs = (System.nanoTime() - t2) / 1_000_000;
        System.out.println("RESULT far_chunk_ms=" + farChunkMs);

        // ---------- ③b 单独量化：1 次 region 构建 vs 9 次（串行/并行对照） ----------
        //   用【全新 terrain】避免缓存干扰；先测"建 1 个 region"的净成本。
        {
            CellGenerator g2 = new CellGenerator(p, p.minY(), p.maxY());
            GeoGenesisTerrain t2b = new GeoGenesisTerrain(g2);
            t2b.seed(seed);
            // 预热触发首次构建（此时付 9 倍代价）
            long s = System.nanoTime();
            t2b.calculateHydrologyChunk(0, 0);
            long coldMs = (System.nanoTime() - s) / 1_000_000;
            // 再取一个【远离已建 region】的 chunk → 又是 9 倍构建
            s = System.nanoTime();
            t2b.calculateHydrologyChunk(80, 80);
            long cold2Ms = (System.nanoTime() - s) / 1_000_000;
            // 紧邻已建 region 的 chunk → 只付 1 次（或 0 次）
            s = System.nanoTime();
            t2b.calculateHydrologyChunk(81, 80);
            long warmMs = (System.nanoTime() - s) / 1_000_000;
            System.out.println("RESULT cold_region_ms=" + coldMs
                    + " cold_region2_ms=" + cold2Ms + " warm_region_ms=" + warmMs);
        }

        // ---------- ③c 验证 preloadSpawnAsync 是否【与主线程抢资源】 ----------
        //   ★ 假设：fillFromNoise 先在后台提交 preloadAround(0,0,3)（49 chunk），
        //     然后主线程同步 getChunkCells(0,0)。两者会【构建同一批 region/tile】
        //     ⇒ 后台任务抢占 CPU + 竞争 computeIfAbsent 锁 ⇒ 主线程反而变慢。
        //   对照：A = 无预热（干净）；B = 并发预热（复现真实路径）。
        {
            CellGenerator gA = new CellGenerator(p, p.minY(), p.maxY());
            GeoGenesisTerrain tA = new GeoGenesisTerrain(gA);
            tA.seed(seed);
            long s = System.nanoTime();
            tA.getChunkCells(0, 0);
            long soloMs = (System.nanoTime() - s) / 1_000_000;

            CellGenerator gB = new CellGenerator(p, p.minY(), p.maxY());
            GeoGenesisTerrain tB = new GeoGenesisTerrain(gB);
            tB.seed(seed);
            // ★ 复刻真实路径：先调 preloadSpawnAsync（它会等首 chunk），再生成首 chunk。
            tB.preloadSpawnAsync();
            s = System.nanoTime();
            tB.getChunkCells(0, 0);          // 主线程：预热此刻应在【等待】，不抢 CPU
            long concurrentMs = (System.nanoTime() - s) / 1_000_000;
            tB.noteChunkGenerated();         // 模拟 fillFromNoise 完成 → 放行预热
            Thread.sleep(1500);              // 让预热跑起来（观察它是否影响后续 chunk）
            s = System.nanoTime();
            tB.getChunkCells(1, 0);          // 预热进行中 → 后续 chunk 是否被拖慢？
            long afterPreloadMs = (System.nanoTime() - s) / 1_000_000;
            System.out.println("RESULT first_chunk_solo_ms=" + soloMs
                    + " first_chunk_withPreload_ms=" + concurrentMs
                    + " preload_cost_ms=" + (concurrentMs - soloMs)
                    + " next_chunk_during_preload_ms=" + afterPreloadMs);
        }

        // ---------- ④ 单独量化水文 chunk（含河网 region 构建） ----------
        t2 = System.nanoTime();
        terrain.calculateHydrologyChunk(0, 0);
        long hydroMs = (System.nanoTime() - t2) / 1_000_000;
        System.out.println("RESULT hydro_chunk_ms=" + hydroMs);

        System.out.println("RESULT done");
    }
}
