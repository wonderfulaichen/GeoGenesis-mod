package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 定点断层解剖探针（2026-08-31）：给定种子 + 断层方块坐标，扫小窗口，
 * 画雕刻量分布图，并把断层线两侧列的完整内部量打出来 —— 直接看哪一项在跳。
 */
public final class SeamSpotProbe {

    private SeamSpotProbe() { }

    public static void main(String[] args) {
        long seed = Long.parseLong(args[0]);
        int bx0 = Integer.parseInt(args[1]);
        int bz0 = Integer.parseInt(args[2]);
        int R = args.length > 3 ? Integer.parseInt(args[3]) : 40;
        double hs = 2.0;

        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);

        // 覆盖窗口的 chunk 全部雕刻，收集列
        Map<Long, HydrologyBlockCarvedColumn> cols = new HashMap<>();
        int c0x = Math.floorDiv(bx0 - R, 16), c1x = Math.floorDiv(bx0 + R, 16);
        int c0z = Math.floorDiv(bz0 - R, 16), c1z = Math.floorDiv(bz0 + R, 16);
        for (int cx = c0x; cx <= c1x; cx++) {
            for (int cz = c0z; cz <= c1z; cz++) {
                double[] ground = new double[256];
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        ground[x * 16 + z] = terrain.sample((cx * 16 + x) / hs, (cz * 16 + z) / hs).height;
                    }
                }
                for (HydrologyBlockCarvedColumn c : HydrologyBlockCarver.carveChunk(engine, cx, cz, hs, ground)) {
                    cols.put((((long) c.blockX()) << 32) | (c.blockZ() & 0xffffffffL), c);
                }
            }
        }

        // 1) 雕刻量分布图（行=bz，列=bx）：' '=无河 '.'=cut<0.5 '1'~'9'=cut 0.5~9 '#'=cut>9
        System.out.println("=== SeamSpotProbe ===");
        System.out.println("seed=" + seed + " 中心块=(" + bx0 + "," + bz0 + ") 窗口=±" + R);
        System.out.println("雕刻量分布图（cut=original-carved）：' '=无河 '.'=<0.5 1~9=0.5~9格 #=>9格");
        System.out.println("      " + String.format("%" + (2 * R + 1) + "s", "bx →"));
        for (int bz = bz0 - R; bz <= bz0 + R; bz++) {
            StringBuilder sb = new StringBuilder();
            for (int bx = bx0 - R; bx <= bx0 + R; bx++) {
                HydrologyBlockCarvedColumn c = cols.get((((long) bx) << 32) | (bz & 0xffffffffL));
                if (c == null) { sb.append(' '); continue; }
                double cut = c.originalGroundY() - c.carvedGroundY();
                if (cut < 0.5) sb.append('.');
                else if (cut <= 9.0) sb.append((char) ('0' + (int) Math.min(9, Math.floor(cut))));
                else sb.append('#');
            }
            System.out.printf("%6d %s%s%n", bz, sb, bz == bz0 ? " <= 中心行" : "");
        }

        // 2) 找雕刻后【绝对高差】最大的相邻列悬崖（不限谷壁、不减 origStep），打印内部量
        record Step(int bx, int bz, int nbx, int nbz, double carvedStep) { }
        List<Step> steps = new ArrayList<>();
        for (HydrologyBlockCarvedColumn c : cols.values()) {
            int[][] dirs = {{1, 0}, {0, 1}};
            for (int[] dd : dirs) {
                HydrologyBlockCarvedColumn n = cols.get((((long) (c.blockX() + dd[0])) << 32)
                        | ((c.blockZ() + dd[1]) & 0xffffffffL));
                if (n == null) continue;
                double carvedStep = Math.abs(c.carvedGroundY() - n.carvedGroundY());
                if (carvedStep >= 2.0) {
                    steps.add(new Step(c.blockX(), c.blockZ(), n.blockX(), n.blockZ(), carvedStep));
                }
            }
        }
        steps.sort((a, b) -> Double.compare(b.carvedStep, a.carvedStep));
        System.out.println();
        System.out.println("雕刻后悬崖(相邻高差>=2格)列对数=" + steps.size() + "，全部 >=3 格的位置：");
        int listed = 0;
        for (Step st : steps) {
            if (st.carvedStep < 3.0) break;
            double dx = (st.bx - bx0), dz = (st.bz - bz0);
            System.out.printf("  高差=%.1f @ (%d,%d)  距中心=%.0f%n",
                    st.carvedStep, st.bx, st.bz, Math.sqrt(dx * dx + dz * dz));
            if (++listed >= 40) break;
        }
        // 排除瀑布列（frozen/fallDrop>0）后的坎 —— 用户抱怨的"岸坡过渡区"对象
        record Step2(int bx, int bz, double carvedStep) { }
        List<Step2> nf = new ArrayList<>();
        for (Step st : steps) {
            HydrologyBlockCarvedColumn a = cols.get((((long) st.bx) << 32) | (st.bz & 0xffffffffL));
            HydrologyBlockCarvedColumn b = cols.get((((long) st.nbx) << 32) | (st.nbz & 0xffffffffL));
            if (a == null || b == null) continue;
            List<HydrologyBlockSample> sa = engine.sampleBlockAll(st.bx, st.bz, hs);
            List<HydrologyBlockSample> sb = engine.sampleBlockAll(st.nbx, st.nbz, hs);
            if (sa.isEmpty() || sb.isEmpty()) continue;
            if (sa.get(0).frozen() || sb.get(0).frozen()
                    || sa.get(0).fallDrop() > 0 || sb.get(0).fallDrop() > 0) continue;
            nf.add(new Step2(st.bx, st.bz, st.carvedStep));
        }
        nf.sort((p, q) -> Double.compare(q.carvedStep, p.carvedStep));
        System.out.println();
        System.out.println("排除瀑布列后仍存在的坎（>=2格）=" + nf.size() + "，前 15 大：");
        int n2 = 0;
        for (Step2 st : nf) {
            if (n2++ >= 15) break;
            double dx = (st.bx - bx0), dz = (st.bz - bz0);
            System.out.printf("  高差=%.1f @ (%d,%d)  距中心=%.0f%n",
                    st.carvedStep, st.bx, st.bz, Math.sqrt(dx * dx + dz * dz));
        }
        System.out.println("最严重 4 处内部量：");
        int shown = 0;
        for (Step st : steps) {
            if (shown++ >= 4) break;
            System.out.printf("%n--- 悬崖高差=%.1f (%d,%d)↔(%d,%d)%n",
                    st.carvedStep, st.bx, st.bz, st.nbx, st.nbz);
            dump(engine, cols, st.bx, st.bz, "高侧");
            dump(engine, cols, st.nbx, st.nbz, "低侧");
        }
        // 窗口内的瀑布列（fallDrop>0）位置 —— 定位截图里的水帘
        System.out.println();
        System.out.println("窗口内瀑布列(fallDrop>0)：");
        int falls = 0;
        for (HydrologyBlockCarvedColumn c : cols.values()) {
            List<HydrologyBlockSample> sm = engine.sampleBlockAll(c.blockX(), c.blockZ(), hs);
            if (sm.isEmpty() || sm.get(0).fallDrop() <= 0.0) continue;
            double dx = (c.blockX() - bx0), dz = (c.blockZ() - bz0);
            if (falls < 25) {
                System.out.printf("  瀑布 (%d,%d) 距中心=%.0f fd=%.1f carved=%.1f water=%.1f fill=%s%n",
                        c.blockX(), c.blockZ(), Math.sqrt(dx * dx + dz * dz),
                        sm.get(0).fallDrop(), c.carvedGroundY(), c.waterSurfaceY(), c.fillWater());
            }
            falls++;
        }
        System.out.println("  瀑布列总数=" + falls);
    }

    private static void dump(HydrologyExperimentEngine engine,
                             Map<Long, HydrologyBlockCarvedColumn> cols,
                             int bx, int bz, String tag) {
        HydrologyBlockCarvedColumn c = cols.get((((long) bx) << 32) | (bz & 0xffffffffL));
        if (c == null) { System.out.printf("  %s(%d,%d): 无列%n", tag, bx, bz); return; }
        System.out.printf("  %s(%d,%d): orig=%.1f carved=%.1f cut=%.1f water=%.1f fill=%s%n",
                tag, bx, bz, c.originalGroundY(), c.carvedGroundY(),
                c.originalGroundY() - c.carvedGroundY(), c.waterSurfaceY(), c.fillWater());
        List<HydrologyBlockSample> sm = engine.sampleBlockAll(bx, bz, 2.0);
        System.out.printf("    样本数=%d%n", sm.size());
        for (int i = 0; i < sm.size(); i++) {
            HydrologyBlockSample s = sm.get(i);
            System.out.printf("    样本%d: dist=%.1f width=%.1f surf=%.1f frozen=%s fd=%.1f%n",
                    i, s.distToCenter(), s.width(), s.surfaceY(), s.frozen(), s.fallDrop());
        }
    }
}
