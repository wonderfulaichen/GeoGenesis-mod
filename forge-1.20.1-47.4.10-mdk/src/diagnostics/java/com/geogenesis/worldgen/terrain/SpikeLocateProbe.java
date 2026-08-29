package com.geogenesis.worldgen.terrain;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次性探针 v2（2026-08-10）：精确定位"山脊尖"并判定来源。
 *
 * <p>v1 教训：粗扫步进 8 漏掉 1-2wu 宽窄尖刺；且"比 ±8wu 邻均值高"会把正常丘陵误判。
 * v2 流程：
 * <ol>
 *   <li>粗扫（步进 8）找 e 比 8 邻均值高 >0.07 的候选（窄尖会落在候选格内）</li>
 *   <li>候选周围 16×16 步进 1 细扫，找"中心比 ±1 邻高 >0.05"的真尖</li>
 *   <li>对真尖输出：sample（基础）vs sampleWu（含侵蚀）→ 判定来源
 *       （基础尖 = 地形场问题；侵蚀尖 = 骨架/液滴 delta 问题）</li>
 * </ol>
 *
 * 用法：gradlew runSpikeLocateProbe [seed]
 */
public class SpikeLocateProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, p.minY(), p.maxY());
        gen.seed(seed);

        int R = 1200;
        double TH = 0.07;
        System.out.printf("=== SpikeLocateProbe v2 seed=%d region=(-%d..%d) thresh=%.2f ===\n", seed, R, R, TH);

        // 阶段 1：粗扫找候选
        List<int[]> candidates = new ArrayList<>();
        for (int z = -R; z < R; z += 8) {
            for (int x = -R; x < R; x += 8) {
                Cell c = gen.sampleWu(x, z);
                if (c.e < 0.15) continue;
                double sum = 0;
                for (int dz = -8; dz <= 8; dz += 8)
                    for (int dx = -8; dx <= 8; dx += 8) {
                        if (dx == 0 && dz == 0) continue;
                        sum += gen.sampleWu(x + dx, z + dz).e;
                    }
                double avg = sum / 8;
                if (c.e - avg > TH) {
                    candidates.add(new int[]{x, z});
                    if (candidates.size() >= 40) break;
                }
            }
            if (candidates.size() >= 40) break;
        }
        System.out.println("candidates=" + candidates.size());

        // 阶段 2：细扫候选区域找真尖（中心 vs ±1 邻）
        int found = 0;
        for (int[] cand : candidates) {
            int cx = cand[0], cz = cand[1];
            outer:
            for (int dz = -8; dz <= 8; dz += 1) {
                for (int dx = -8; dx <= 8; dx += 1) {
                    int x = cx + dx, z = cz + dz;
                    Cell c = gen.sampleWu(x, z);
                    if (c.e < 0.15) continue;
                    double up = gen.sampleWu(x, z - 1).e, down = gen.sampleWu(x, z + 1).e;
                    double lf = gen.sampleWu(x - 1, z).e, rt = gen.sampleWu(x + 1, z).e;
                    double maxN = Math.max(Math.max(up, down), Math.max(lf, rt));
                    if (c.e - maxN > 0.05) { // 比最近邻高 0.05e = 真尖
                        Cell plain = gen.sample(x, z);
                        double delta = c.e - plain.e;
                        System.out.printf("SPIKE (%d,%d): e=%.3f 邻max=%.3f | delta(侵蚀)=%+.3f | baseE=%.3f type=%s h=%.1f%n",
                            x, z, c.e, maxN, delta, plain.e, c.terrainType, c.height);
                        System.out.printf("   邻居: up=%.3f down=%.3f lf=%.3f rt=%.3f | delta>0.05 => 侵蚀尖; delta≈0 => 基础尖%n",
                            up, down, lf, rt);
                        if (++found >= 12) break outer;
                    }
                }
            }
            if (found >= 12) break;
        }
        System.out.println("found=" + found + " (0 = 无 1wu 级尖刺)");
    }
}
