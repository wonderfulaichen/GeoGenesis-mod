package com.geogenesis.worldgen.terrain;

/**
 * HS（horizontalScale）wu 化 A/B 验证探针（2026-08-10）。
 *
 * <p>验证 wu 化后的两个核心恒等式：</p>
 * <ol>
 *   <li><b>引擎纯 wu</b>：horizontalScale=1 与 =2 的两个引擎在<b>同一 wu 坐标</b>采样 → 值必须
 *       完全相等（引擎不再感知 HS，换算只发生在门面）。</li>
 *   <li><b>门面等比放大</b>：HS=2 时块坐标 2b 映射到 wu=b，与 HS=1 时块坐标 b（wu=b）采样值
 *       相等 → 世界等比放大 2 倍（DEM 重采样语义）。</li>
 * </ol>
 *
 * <p>用 sampleCellLight（纯 e 场 + 气候 + 分类，不触发侵蚀 tile）→ 轻量、低内存。</p>
 *
 * <p>用法：gradlew runHSABProbe [seed]</p>
 */
public final class HSABProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams p1 = withHs(TerrainParams.defaults(), 1.0);
        TerrainParams p2 = withHs(TerrainParams.defaults(), 2.0);
        CellGenerator g1 = new CellGenerator(p1, p1.minY(), p1.maxY());
        CellGenerator g2 = new CellGenerator(p2, p2.minY(), p2.maxY());
        GeoGenesisTerrain t1 = new GeoGenesisTerrain(g1);
        GeoGenesisTerrain t2 = new GeoGenesisTerrain(g2);
        g1.seed(seed);
        g2.seed(seed);
        t1.seed(seed);
        t2.seed(seed);

        System.out.printf("=== HS AB probe seed=%d ===%n", seed);
        System.out.println("[1] 引擎纯 wu 恒等式: g1.sample(wx,wz) == g2.sample(wx,wz) (同 wu 坐标)");
        int mism = 0, total = 0;
        double maxD = 0;
        for (int z = -256; z <= 256; z += 17) {
            for (int x = -256; x <= 256; x += 13) {
                total++;
                Cell a = g1.sample(x, z);
                Cell b = g2.sample(x, z);
                double d = Math.abs(a.e - b.e);
                maxD = Math.max(maxD, d);
                if (d > 1e-9) mism++;
            }
        }
        System.out.printf("  total=%d mism=%d maxDelta=%e %s%n", total, mism, maxD,
            mism == 0 ? "PASS" : "FAIL");

        System.out.println("[2] 门面等比放大: t2.sampleCellLight(2b) == t1.sampleCellLight(b) (HS=2 世界 = HS=1 世界 x2)");
        int mism2 = 0, total2 = 0;
        double maxD2 = 0;
        for (int z = -64; z <= 64; z += 7) {
            for (int x = -64; x <= 64; x += 5) {
                total2++;
                Cell a = t1.sampleCellLight(x, z);          // HS=1: 块 x → wu x
                Cell b = t2.sampleCellLight(x * 2, z * 2);  // HS=2: 块 2x → wu x
                double d = Math.abs(a.e - b.e);
                maxD2 = Math.max(maxD2, d);
                if (d > 1e-9) mism2++;
            }
        }
        System.out.printf("  total=%d mism=%d maxDelta=%e %s%n", total2, mism2, maxD2,
            mism2 == 0 ? "PASS" : "FAIL");

        System.out.println("[3] terrainEQuick 同 wu 恒等式");
        int mism3 = 0, total3 = 0;
        double maxD3 = 0;
        for (int z = -128; z <= 128; z += 11) {
            for (int x = -128; x <= 128; x += 9) {
                total3++;
                double d = Math.abs(g1.terrainEQuick(x, z) - g2.terrainEQuick(x, z));
                maxD3 = Math.max(maxD3, d);
                if (d > 1e-9) mism3++;
            }
        }
        System.out.printf("  total=%d mism=%d maxDelta=%e %s%n", total3, mism3, maxD3,
            mism3 == 0 ? "PASS" : "FAIL");

        System.out.println(mism + mism2 + mism3 == 0 ? "=== ALL PASS ===" : "=== FAILURES PRESENT ===");
    }

    /** 反射复制 record，仅替换 horizontalScale（JDK 21 record 无自动 with 方法，探针专用）。 */
    private static TerrainParams withHs(TerrainParams p, double hs) {
        try {
            var comps = TerrainParams.class.getRecordComponents();
            Object[] vals = new Object[comps.length];
            for (int i = 0; i < comps.length; i++) {
                vals[i] = comps[i].getName().equals("horizontalScale")
                    ? hs : comps[i].getAccessor().invoke(p);
            }
            return (TerrainParams) TerrainParams.class.getConstructors()[0].newInstance(vals);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
