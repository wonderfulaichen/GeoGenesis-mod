package com.geogenesis.worldgen.terrain;

/**
 * 【区块加载性能】基准 <b>v2</b>（2026-09-17/18）—— 用户反馈"区块加载变慢"的量化基线。
 *
 * <h2>v1 的致命问题：噪声比信号大</h2>
 * <p>v1 只扫【单个】区域，同一份代码连跑两次得 <b>16.27 / 14.24 ms/chunk（差 18%）</b>
 * ⇒ 任何小于这个量级的优化（本会话的 inFlood 索引、由粗到细等）<b>都无法判定</b>。</p>
 *
 * <h2>v2 的做法（把噪声压下去）</h2>
 * <ol>
 *   <li>一次运行扫 <b>R 个互不重叠的区域</b>（默认 4），每个区域<b>各自冷启动</b>
 *       （{@code gen.seed()} 清侵蚀 tile 缓存）⇒ 得到 R 个独立样本；</li>
 *   <li>报告 <b>min / 中位 / max</b>：<b>min 是最稳健的估计量</b>
 *       （机器负载尖峰只会让某次变慢，不会让 min 变快）⇒ A/B 一律比 min；</li>
 *   <li>同时按区域报告 tile 生成个数（区分"冷启动成本"与"稳态成本"）。</li>
 * </ol>
 *
 * <h2>为何必须模拟真实负载</h2>
 * <p>只铺 24×24 chunk 的探针（如 {@code WaterPhysicsProbe}）工作集装得下全部侵蚀 tile
 * （tile=48wu=96 块）⇒ 既无驱逐、也无"移动式冷启动" ⇒ 测不出真实回归。</p>
 *
 * <pre>{@code gradlew runChunkLoadPerfProbe [-PprobeArgs="seed spanChunks regions"]}</pre>
 */
public final class ChunkLoadPerfProbe {

    private ChunkLoadPerfProbe() { }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 5436529513624899584L;
        int span = args.length > 1 ? Integer.parseInt(args[1]) : 32;
        int regions = args.length > 2 ? Integer.parseInt(args[2]) : 4;

        // 包装 stdout：数 tile 生成次数与耗时、收集 hydro 段耗时（探针专用，不改生产代码）
        java.io.PrintStream real = System.out;
        final int[] genCount = {0};
        final long[] tileGenMs = {0};
        final java.util.List<Integer> hydroList = new java.util.ArrayList<>();
        System.setOut(new java.io.PrintStream(new java.io.OutputStream() {
            private final StringBuilder buf = new StringBuilder();
            @Override public void write(int b) {
                if (b == '\n') {
                    String s = buf.toString();
                    buf.setLength(0);
                    if (s.contains("[PERF] erosion tile (") && s.contains(" took ")) {
                        genCount[0]++;
                        int i = s.indexOf(" took "), j = s.indexOf("ms", i);
                        if (j > i) tileGenMs[0] += Long.parseLong(s.substring(i + 6, j).trim());
                    }
                    int k = s.indexOf("hydro=");
                    if (k >= 0) {
                        int e = s.indexOf("ms", k);
                        if (e > k) hydroList.add(Integer.parseInt(s.substring(k + 6, e).trim()));
                    }
                    real.println(s);
                } else {
                    buf.append((char) b);
                }
            }
        }, true));

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);

        // JIT 预热（不计入统计）
        for (int cx = 0; cx < 4; cx++) {
            for (int cz = 0; cz < 4; cz++) gt.getChunkCells(cx, cz);
        }

        System.out.printf("=== ChunkLoadPerfProbe v2 seed=%d span=%d chunk × regions=%d ===%n",
                seed, span, regions);
        double[] per = new double[regions];
        for (int r = 0; r < regions; r++) {
            int ox = 100 + r * span;              // 区域互不重叠，且远离预热区
            gen.seed(seed);                       // 清侵蚀 tile 缓存 ⇒ 每个区域都是【冷启动】
            gen.tileCacheStats().reset();
            int g0 = genCount[0];
            long t0 = System.nanoTime();
            for (int cx = ox; cx < ox + span; cx++) {
                for (int cz = 0; cz < span; cz++) gt.getChunkCells(cx, cz);
            }
            long dt = System.nanoTime() - t0;
            per[r] = dt / 1e6 / ((double) span * span);
            System.out.printf("  区域%d chunk[%d..%d] : %6.2f ms/chunk（tile 生成 %d 个）%n",
                    r, ox, ox + span - 1, per[r], genCount[0] - g0);
        }
        double[] sorted = per.clone();
        java.util.Arrays.sort(sorted);
        System.out.printf("统计：min=%.2f  中位=%.2f  max=%.2f  ms/chunk   ← A/B 请比 min%n",
                sorted[0], sorted[regions / 2], sorted[regions - 1]);
        System.out.printf("tile 生成合计 %d 个（总耗时 %d ms）%n", genCount[0], tileGenMs[0]);

        int hydroTot = 0;
        for (int v : hydroList) hydroTot += v;
        java.util.List<Integer> hyd = new java.util.ArrayList<>(hydroList);
        java.util.Collections.sort(hyd);
        int nz = hyd.size();
        System.out.printf("水文(hydro)：总 %d ms / 有耗时的 chunk %d 个 ⇒ 中位 %d、P95 %d、最大 %d ms%n",
                hydroTot, nz, nz == 0 ? 0 : hyd.get(nz / 2),
                nz == 0 ? 0 : hyd.get((int) Math.min(nz - 1, nz * 0.95)),
                nz == 0 ? 0 : hyd.get(nz - 1));
    }
}
