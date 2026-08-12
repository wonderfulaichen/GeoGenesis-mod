package com.geogenesis.worldgen.terrain;

/**
 * 侵蚀 delta 场周期性探针（2026-08-11）。
 *
 * 用户反馈 HS=2 下"打开粒子侵蚀出现规则间距的破碎图案"。本探针量化 delta 场：
 * 1) 覆盖率：非零 delta 比例（稀疏 → 点状坑）
 * 2) 周期性：沿 x/z 方向对间距 d 计算平均 |Δ(x+d)-Δ(x)| 跳变，找规则周期
 * 3) HS=1 vs HS=2 对比（HS=1 用户说正常）
 *
 * 用法：gradlew runErosionPeriodProbe [seed] [tileCX] [tileCZ]
 */
public final class ErosionPeriodProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int tileCX = args.length > 1 ? Integer.parseInt(args[1]) : 48;
        int tileCZ = args.length > 2 ? Integer.parseInt(args[2]) : 48;
        // ★ 2026-08-12 诊断：--args="seed tileCX tileCZ fade" 强制骨架 fadeHeight=true
        if (args.length > 3 && args[3].equals("fade")) CellGenerator.PROBE_FADE_HEIGHT = true;

        for (double hs : new double[]{1.0, 2.0}) {
            TerrainParams p = withHs(TerrainParams.defaults(), hs);
            CellGenerator g = new CellGenerator(p, p.minY(), p.maxY());
            g.seed(seed);
            System.out.printf("=== HS=%.1f tile=(%d,%d) ===%n", hs, tileCX, tileCZ);
            long t0 = System.currentTimeMillis();
            CellGenerator.ErosionTileResult res = g.getErosionTileResultForProbe(tileCX, tileCZ);
            long ms = System.currentTimeMillis() - t0;
            if (res == null) { System.out.println("  tile null"); continue; }
            float[][] delta = res.delta;
            int N = delta.length;

            // 1) NaN/Inf 检测 + 覆盖率 + 幅值统计（中心区 32x32，避开 border）
            int nonzero = 0, nanCount = 0; float sum = 0, maxAmp = 0, minAmp = 0;
            for (int z = 48; z < 80; z++)
                for (int x = 48; x < 80; x++) {
                    float v = delta[z][x];
                    if (Float.isNaN(v) || Float.isInfinite(v)) { nanCount++; continue; }
                    if (Math.abs(v) > 1e-5f) nonzero++;
                    sum += v; maxAmp = Math.max(maxAmp, v); minAmp = Math.min(minAmp, v);
                }
            int total = 32 * 32;
            System.out.printf("  中心32x32: NaN/Inf=%d 非零覆盖率=%.1f%% mean=%.5f min=%.4f max=%.4f%n",
                nanCount, nonzero * 100.0 / total, sum / total, minAmp, maxAmp);
            // base/postErosion 场 NaN 检测（区分液滴 vs 雕刻来源）
            int baseNan = 0, postNan = 0;
            for (int z = 40; z < 88; z++)
                for (int x = 40; x < 88; x++) {
                    if (Float.isNaN(res.base[z][x]) || Float.isInfinite(res.base[z][x])) baseNan++;
                    if (Float.isNaN(res.postErosion[z][x]) || Float.isInfinite(res.postErosion[z][x])) postNan++;
                }
            System.out.printf("  base 场 NaN/Inf=%d/2304, postErosion(液滴后) NaN/Inf=%d/2304%n", baseNan, postNan);

            // 2) 周期性：沿 x 方向（z=64 行），对间距 d 计算平均跳变 |v[x+d]-v[x]|
            System.out.println("  沿x方向(z=64) 平均跳变 |v[x+d]-v[x]| 按间距 d:");
            for (int d : new int[]{1, 2, 3, 4, 8, 12, 16, 24, 32, 48, 96}) {
                float acc = 0; int cnt = 0;
                for (int x = 40; x + d < N - 40; x++) {
                    acc += Math.abs(delta[64][x + d] - delta[64][x]);
                    cnt++;
                }
                System.out.printf("    d=%-3d avgJump=%.6f%n", d, cnt > 0 ? acc / cnt : 0);
            }
            System.out.printf("  tile 耗时 %dms%n%n", ms);

            // 3) 块空间最终高度扫描（HS=2 的关键：插值应用后是否规则跳变）
            System.out.printf("  块空间高度扫描（HS=%.1f，z=64行，x=0..255）:%n", hs);
            int period = -1;
            float prevH = Float.NaN;
            for (int bx = 0; bx < 256; bx += 1) {
                Cell c = g.sampleWu(bx, 64); // 经 applyTileDelta 的完整高度
                float h = (float) c.height;
                if (!Float.isNaN(prevH) && Math.abs(h - prevH) > 1.5f) {
                    System.out.printf("    STEP@bx=%d h=%.1f->%.1f%n", bx, prevH, h);
                }
                prevH = h;
            }
            // 检测 8/16/32/48 块规则周期：相邻差分自相关
            System.out.print("    period-check |h[x+d]-h[x]|: ");
            for (int d : new int[]{1, 2, 4, 8, 16, 32, 48, 64, 96}) {
                float acc = 0; int cnt = 0;
                for (int x = 40; x + d < 216; x++) {
                    Cell a = g.sampleWu(x, 64);
                    Cell b = g.sampleWu(x + d, 64);
                    acc += Math.abs((float) a.height - (float) b.height);
                    cnt++;
                }
                System.out.printf(" d=%d:%.3f ", d, cnt > 0 ? acc / cnt : 0);
            }
            System.out.println();
        }
    }

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
