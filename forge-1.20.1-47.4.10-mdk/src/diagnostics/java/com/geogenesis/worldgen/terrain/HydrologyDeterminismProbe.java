package com.geogenesis.worldgen.terrain;

/**
 * 【确定性 / 顺序无关性】判据探针（2026-09-17 新建，水文统一化重构 M1 的验收基线）。
 *
 * <h2>为什么这是 M1 的第一条门禁</h2>
 * <p>本项目铁律：地形与水体是 <b>(seed, 配置, 坐标) 的纯函数</b>，
 * <b>与世界生成顺序、缓存状态、线程调度无关</b>。
 * 若这条不成立，则"同一颗种子"在不同机器上会得到不同世界，
 * 而且玩家走不同路线也会看到不同地形 —— 且此类缺陷<b>无法用单点探针复现</b>
 * （这正是此前 7 次归因全部失败的深层原因之一）。</p>
 *
 * <h2>三处已知的顺序依赖嫌疑（读码得到）</h2>
 * <ol>
 *   <li>{@code CellGenerator.blendTileDelta}：tile 右/下缘 6wu 做 4 向对称 blend，
 *       但取邻居时是 <b>{@code erosionTileCache.get(...)}（只读缓存）</b>，
 *       缺失即退回 {@code d00}（不 blend）⇒ 同一坐标的结果<b>取决于邻居当时是否已缓存</b>；
 *       而邻居会被 LRU 驱逐、生成顺序也不确定。<br>
 *       ⚠ 此前在 seed=12345 实测该依赖仅 ≈0.0013 块（可忽略），
 *       但需在<b>多种子、多 tile 边</b>上复查才能定性（本探针做这件事）。</li>
 *   <li>侵蚀 tile 的懒生成 + LRU 驱逐 ⇒ 重新生成时邻居集合可能不同。</li>
 *   <li>{@code getChunkCells} 的 miss-生成路径与 cached 路径是否等价。</li>
 * </ol>
 *
 * <h2>判据</h2>
 * <p><b>两次不同顺序下，所有比较字段必须【逐位相等】（diff == 0）</b>。
 * 本项目地形是确定性纯函数，不容许"近似相等"。</p>
 *
 * <pre>{@code gradlew runHydrologyDeterminismProbe [-PprobeArgs="seed"]}</pre>
 */
public final class HydrologyDeterminismProbe {

    /** 与生产一致的 tile 几何（只读，用于定位 blend 带）。 */
    private static final int TILE_CENTER = 48;
    private static final int BLEND_START = 6;

    /** 判据：所有字段必须逐位相等（本项目地形为确定性纯函数）。 */
    private static final double TOL = 0.0;

    private HydrologyDeterminismProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        System.out.printf("=== HydrologyDeterminismProbe seed=%d ===%n", seed);
        System.out.println("判据：不同生成顺序下，所有比较字段必须【逐位相等】（diff == 0）");

        boolean t1 = testBlendCacheDependence(seed);
        boolean t2 = testChunkOrderIndependence(seed);
        System.out.println();
        System.out.printf("总判定: %s%n", (t1 && t2) ? "ALL PASS（顺序无关，确定性成立）"
                : "FAILURES（存在顺序依赖 ⇒ 违反纯函数铁律）");
    }

    // ==================================================================
    // 测试 1：侵蚀增量对【邻居 tile 缓存状态】的依赖
    // ==================================================================

    /**
     * 对若干 tile 的右缘 blend 带取点，先在【邻居未缓存】时采样，再预热邻居后采样同一批点，
     * 比较差值。这是 {@code blendTileDelta} 缓存依赖的直接度量。
     */
    private static boolean testBlendCacheDependence(long seed) {
        System.out.println();
        System.out.println("[1] 侵蚀增量对【邻居 tile 缓存状态】的依赖（blendTileDelta）");
        System.out.println("    tile      x(wu)    ArmA(邻居未缓存)      ArmB(邻居已缓存)         差");
        double worst = 0;
        boolean allZero = true;
        int[][] tiles = {{0, 0}, {48, 0}, {0, 48}, {-96, 48}, {96, -96}};
        for (int[] t : tiles) {
            CellGenerator gen = newGen(seed);
            int tileX = t[0], tileZ = t[1];
            int edge = tileX + TILE_CENTER;      // 右边界
            double z = tileZ + 20.0;
            for (int k = 0; k < BLEND_START; k++) {
                double x = edge - BLEND_START + k + 0.5;
                double a = gen.erosionDeltaE(x, z);              // Arm A：邻居未缓存
                // 预热右邻居：采一个属于它的内部点（不在其 blend 带内）
                gen.erosionDeltaE(edge + 12.5, z);
                double b = gen.erosionDeltaE(x, z);              // Arm B：邻居已缓存
                double d = Math.abs(a - b);
                worst = Math.max(worst, d);
                if (d > TOL) allZero = false;
                System.out.printf("    (%4d,%4d) %7.1f  %18.10f  %18.10f  %12.10f%n",
                        tileX, tileZ, x, a, b, d);
            }
        }
        System.out.printf("    → max|A−B| = %.10f e（≈ %.6f 块）%n", worst, worst * 192.0);
        System.out.printf("    判定: %s%n", allZero ? "PASS（无缓存依赖）" : "FAIL（存在缓存依赖）");
        return allZero;
    }

    // ==================================================================
    // 测试 2：同一片 chunk 区域，两种生成顺序是否逐位一致
    // ==================================================================

    /**
     * 对同一组 chunk，分别按【正序】与【倒序】生成，逐格比较
     * {@code height / terrainType / riverType / riverSurfaceY / gradient}。
     *
     * <p>这是"顺序无关性"的整体判据：只要有一格不同，即说明产出依赖生成顺序。</p>
     */
    private static boolean testChunkOrderIndependence(long seed) {
        System.out.println();
        System.out.println("[2] 同一片 chunk 区域：正序 vs 倒序生成是否逐位一致");
        final int n = 3;
        int[] xs = new int[n * n], zs = new int[n * n];
        int i = 0;
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) {
                xs[i] = -72 + a;      // 覆盖用户报的坐标所在区块 (-72,-74) 一带
                zs[i] = -74 + b;
                i++;
            }
        }
        GeoGenesisTerrain fwd = new GeoGenesisTerrain(newGen(seed));
        GeoGenesisTerrain rev = new GeoGenesisTerrain(newGen(seed));

        for (int k = 0; k < xs.length; k++) fwd.getChunkCells(xs[k], zs[k]);          // 正序
        for (int k = xs.length - 1; k >= 0; k--) rev.getChunkCells(xs[k], zs[k]);     // 倒序

        double maxH = 0, maxS = 0, maxG = 0;
        int typeDiff = 0, riverDiff = 0, cells = 0;
        for (int k = 0; k < xs.length; k++) {
            Cell[] a = fwd.getChunkCells(xs[k], zs[k]);
            Cell[] b = rev.getChunkCells(xs[k], zs[k]);
            for (int c = 0; c < a.length; c++) {
                maxH = Math.max(maxH, Math.abs(a[c].height - b[c].height));
                maxS = Math.max(maxS, Math.abs(a[c].riverSurfaceY - b[c].riverSurfaceY));
                maxG = Math.max(maxG, Math.abs(a[c].gradient - b[c].gradient));
                if (a[c].terrainType != b[c].terrainType) typeDiff++;
                if (a[c].riverType != b[c].riverType) riverDiff++;
                cells++;
            }
        }
        System.out.printf("    比较 chunk 数 %d（%d 格）%n", xs.length, cells);
        System.out.printf("    max|Δheight| = %.10f 块%n", maxH);
        System.out.printf("    max|ΔriverSurfaceY| = %.10f 块%n", maxS);
        System.out.printf("    max|Δgradient| = %.10f%n", maxG);
        System.out.printf("    terrainType 不同的格 = %d ；riverType 不同的格 = %d%n",
                typeDiff, riverDiff);
        boolean pass = maxH <= TOL && maxS <= TOL && maxG <= TOL && typeDiff == 0 && riverDiff == 0;
        System.out.printf("    判定: %s%n", pass ? "PASS（顺序无关）" : "FAIL（产出依赖生成顺序）");
        return pass;
    }

    private static CellGenerator newGen(long seed) {
        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, p.minY(), p.maxY());
        gen.seed(seed);
        return gen;
    }
}
