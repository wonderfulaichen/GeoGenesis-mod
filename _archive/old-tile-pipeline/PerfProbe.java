package com.geogenesis.worldgen.terrain;

/**
 * ★ 2026-09-14：<b>地质系统性能基准</b>（量化最近改动的开销）。
 *
 * <h3>为何需要</h3>
 * <p>用户反馈"性能变得如此之差，卡在 0%" ⇒ 必须<b>量化</b>定位，不能靠猜测。
 * 本探针分别测量各地质组件的<b>每次调用耗时</b>，从而直接对比：
 * <ul>
 *   <li>{@code StratumField.tiltAt}（T10b 多尺度，sampleCore 每 Cell 调用）</li>
 *   <li>{@code packSequence}（T9b，含层厚噪声，sampleCore 每 Cell 调用）</li>
 *   <li>{@code thicknessOf / seqAt / thickLevelAt}（逐 y 累减查找用）</li>
 * </ul>
 * </p>
 *
 * <h3>用法</h3>
 * <pre>{@code gradlew runPerfProbe [-PprobeArgs="seed"]}</pre>
 */
public final class PerfProbe {

    private static final int N = 2_000_000;

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        System.out.printf("=== PerfProbe seed=%d iters=%d ===%n", seed, N);

        StratumField strata = new StratumField(seed);
        strata.setSeed(seed);

        // ---- 1) tiltAt（T10b：3 次 valueNoise）----
        double acc = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < N; i++) {
            acc += strata.tiltAt(i * 0.37, i * 0.71);
        }
        long tTilt = System.nanoTime() - t0;

        // ---- 2) packSequence（T9b：4 层 × thicknessLevel 噪声）----
        CellGenerator gen = new CellGenerator(TerrainParams.defaults(),
                TerrainParams.defaults().minY(), TerrainParams.defaults().maxY());
        gen.seed(seed);
        t0 = System.nanoTime();
        for (int i = 0; i < N / 10; i++) {
            acc += gen.rockResistanceAt(i * 0.37, i * 0.71);
        }
        long tResist = System.nanoTime() - t0;

        // ---- 2.5) terrainEQuick（河网/侵蚀/purch 的公共热路径）----
        //   ★ 关键：P5 给它加了 landFactorAt（火山中心海陆判定），
        //     而 landFactorAt 内部又会重采样 continent + tectonic（昂贵）⇒ 必须实测。
        int M = 200_000;
        t0 = System.nanoTime();
        for (int i = 0; i < M; i++) {
            acc += gen.terrainEQuick(i * 0.37, i * 0.71);
        }
        long tEQ = System.nanoTime() - t0;

        // ---- 2.6) landFactorAt 单独（P5 新增，怀疑是元凶）----
        t0 = System.nanoTime();
        for (int i = 0; i < M / 10; i++) {
            acc += gen.landFactorAt(i * 0.37, i * 0.71);
        }
        long tLF = System.nanoTime() - t0;

        // ---- 3) 逐格累减查找（fillTerrainColumn 热路径）----
        int packed = 0x1234567;
        t0 = System.nanoTime();
        for (int i = 0; i < N; i++) {
            int local = Math.floorMod(i, 97);
            int a = 0;
            for (int k = 0; k < StratumField.LAYER_COUNT; k++) {
                int th = StratumField.thicknessOf(StratumField.thickLevelAt(packed, k));
                if (local < a + th) { acc += StratumField.seqAt(packed, k); break; }
                a += th;
            }
        }
        long tLookup = System.nanoTime() - t0;

        System.out.printf("tiltAt            : %6.1f ns/次  (总 %6.1f ms)%n",
                tTilt / (double) N, tTilt / 1e6);
        System.out.printf("rockResistanceAt  : %6.1f ns/次  (总 %6.1f ms)%n",
                tResist / (double) (N / 10) * 10, tResist / 1e6);
        System.out.printf("逐格累减查找(4层) : %6.1f ns/次  (总 %6.1f ms)%n",
                tLookup / (double) N, tLookup / 1e6);
        System.out.printf("terrainEQuick     : %6.1f ns/次  (总 %6.1f ms)  ← 河网/侵蚀热路径%n",
                tEQ / (double) M, tEQ / 1e6);
        System.out.printf("landFactorAt      : %6.1f ns/次  (总 %6.1f ms)  ← P5 新增，疑元凶%n",
                tLF / (double) (M / 10) * 10, tLF / 1e6);

        // 估算：一个 chunk 256 列，每列 ~384 个 y 需要查层
        double perChunkMs = 256.0 * 384 * (tLookup / (double) N) / 1e6;
        System.out.printf("%n估算：岩层落块占每 chunk %.2f ms%n", perChunkMs);
        System.out.printf("(校验和 %.3f，仅防 JIT 消除)%n", acc);
    }
}
