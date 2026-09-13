package com.geogenesis.worldgen.terrain;

/**
 * ★ 2026-09-14：<b>chunk 生成耗时分段实测</b>（用户"比几小时前慢"）。
 *
 * <h3>为何需要</h3>
 * <p>线上日志显示部分 chunk 的 {@code cells=} 段达 <b>22 秒</b>（快时 132ms），
 * 相差 167 倍。必须<b>实测分段</b>定位到具体环节，而非推测。</p>
 *
 * <p>本探针直接调 {@link GeoGenesisTerrain#getChunkCells}（= 线上 {@code cells=} 段），
 * 并按 <b>冷/热</b>两种情形分别测量：
 * <ul>
 *   <li><b>冷</b>（首次）：含侵蚀 tile 冷生成 + 水文 region 首次构建</li>
 *   <li><b>热</b>（再取）：纯缓存命中</li>
 * </ul>
 * 二者差异即"冷启动成本"，是判定"是否 tile 冷生成导致卡顿"的关键。</p>
 *
 * <pre>{@code gradlew runChunkTimeProbe [-PprobeArgs="seed chunks"]}</pre>
 */
public final class ChunkTimeProbe {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 5436529513624899584L;
        int chunks = args.length > 1 ? Integer.parseInt(args[1]) : 12;
        System.out.printf("=== ChunkTimeProbe seed=%d chunks=%d ===%n", seed, chunks);

        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, p.minY(), p.maxY());
        gen.seed(seed);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);

        // 沿一条直线取 chunk（覆盖不同 tile）
        long worstCold = 0, worstHot = 0, sumCold = 0, sumHot = 0;
        for (int i = 0; i < chunks; i++) {
            int cx = i - chunks / 2, cz = 2;

            long t0 = System.nanoTime();
            terrain.getChunkCells(cx, cz);
            long cold = (System.nanoTime() - t0) / 1_000_000;

            t0 = System.nanoTime();
            terrain.getChunkCells(cx, cz);
            long hot = (System.nanoTime() - t0) / 1_000_000;

            sumCold += cold; sumHot += hot;
            worstCold = Math.max(worstCold, cold);
            worstHot = Math.max(worstHot, hot);
            if (cold > 200 || i < 3) {
                System.out.printf("  chunk(%d,%d) 冷=%dms 热=%dms%n", cx, cz, cold, hot);
            }
        }
        System.out.printf("平均：冷=%dms 热=%dms | 最差：冷=%dms 热=%dms%n",
                sumCold / chunks, sumHot / chunks, worstCold, worstHot);

        boolean pass = worstCold < 3000;
        System.out.printf("冷启动是否可接受(<3s): %s%n", pass ? "PASS" : "FAIL");
        if (!pass) System.exit(1);
    }
}
