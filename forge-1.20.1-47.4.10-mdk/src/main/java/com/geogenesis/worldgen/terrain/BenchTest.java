// 微基准：串行 vs 4线程并行 terrainEQuick 采样（20核机器）
// 用于诊断 base 并行收益不足的根因
package com.geogenesis.worldgen.terrain;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class BenchTest {
    public static void main(String[] args) throws Exception {
        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.seed(12345L);

        int N0 = 128;
        int originX = -16 * 3 * 16 - 40;
        int originZ = originX;

        // warmup（JIT）
        double sum = 0;
        for (int i = 0; i < 20000; i++) {
            sum += gen.terrainEQuick(originX + (i % 128), originZ + (i % 128));
        }
        System.out.println("warmup sum=" + sum);

        // 串行
        long t0 = System.nanoTime();
        float[][] base1 = new float[N0][N0];
        for (int z = 0; z < N0; z++)
            for (int x = 0; x < N0; x++)
                base1[z][x] = (float) Math.max(gen.terrainEQuick(originX + x, originZ + z), -0.05);
        long t1 = System.nanoTime();
        System.out.println("SERIAL   base=" + (t1 - t0) / 1e6 + "ms");

        // 4 线程并行
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (int rep = 0; rep < 3; rep++) {
            float[][] base2 = new float[N0][N0];
            CountDownLatch latch = new CountDownLatch(4);
            for (int t = 0; t < 4; t++) {
                final int z0 = t * 32, z1 = z0 + 32;
                pool.execute(() -> {
                    try {
                        for (int z = z0; z < z1; z++)
                            for (int x = 0; x < N0; x++)
                                base2[z][x] = (float) Math.max(gen.terrainEQuick(originX + x, originZ + z), -0.05);
                    } finally { latch.countDown(); }
                });
            }
            latch.await();
            long t2 = System.nanoTime();
            System.out.println("PARALLEL base=" + (t2 - t1) / 1e6 + "ms (rep " + rep + ")");
            t1 = t2;
        }
        pool.shutdown();
    }
}
