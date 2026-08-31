package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 岸坡方向断层探针（2026-08-31）：用户的判据是——
 *   · 沿河（下游）方向出现断崖 = 瀑布，正常；
 *   · 垂直河流的【岸坡方向】出现断崖 = bug。
 * 距离场梯度 ∇d 指向"远离河心"= 岸坡方向，与之垂直 = 河流方向。
 * 本探针把每个相邻列台阶沿这两个方向分解，分开统计并给出最严重处的内部量。
 */
public final class BankFaultProbe {

    private BankFaultProbe() { }

    public static void main(String[] args) {
        long seed = Long.parseLong(args[0]);
        int bx0 = Integer.parseInt(args[1]);
        int bz0 = Integer.parseInt(args[2]);
        int R = args.length > 3 ? Integer.parseInt(args[3]) : 120;
        double T = args.length > 4 ? Double.parseDouble(args[4]) : 4.0;   // 台阶阈值
        double hs = 2.0;

        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);

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
                for (HydrologyBlockCarvedColumn c
                        : HydrologyBlockCarver.carveChunk(engine, cx, cz, hs, ground)) {
                    cols.put(key(c.blockX(), c.blockZ()), c);
                }
            }
        }
        System.out.println("=== BankFaultProbe ===");
        System.out.println("seed=" + seed + " 中心=(" + bx0 + "," + bz0 + ") 窗口=±" + R
                + " 台阶阈值=" + T + " 列数=" + cols.size());

        record Step(int bx, int bz, boolean xStep, double step, double dx, double dz,
                    boolean bank, double frozA, double frozB) { }
        List<Step> bankSteps = new ArrayList<>();
        List<Step> flowSteps = new ArrayList<>();

        for (HydrologyBlockCarvedColumn c : cols.values()) {
            int bx = c.blockX(), bz = c.blockZ();
            for (int dir = 0; dir < 2; dir++) {
                int nbx = dir == 0 ? bx + 1 : bx;
                int nbz = dir == 0 ? bz : bz + 1;
                HydrologyBlockCarvedColumn n = cols.get(key(nbx, nbz));
                if (n == null) continue;
                double step = Math.abs(c.carvedGroundY() - n.carvedGroundY());
                if (step < T) continue;
                // 距离场梯度（最近河线的 distToCenter）—— 只对大台阶处计算
                double d0 = nearestDist(engine, bx, bz, hs);
                double dx = nearestDist(engine, bx + 1, bz, hs) - d0;
                double dz = nearestDist(engine, bx, bz + 1, hs) - d0;
                // 严格判据：步进方向必须【几乎纯岸坡方向】（与 ∇d 夹角 <18°，cos>=0.95）。
                // 否则即使"主要是横着走"，只要带一点沿河分量就可能斜穿跌水线 →
                // 那是瀑布崖面（正常），不能算岸坡断层。
                double gLen = Math.hypot(dx, dz);
                double sx = dir == 0 ? 1.0 : 0.0;
                double sz = dir == 0 ? 0.0 : 1.0;
                double bankProj = gLen > 1e-9 ? Math.abs(sx * dx + sz * dz) / gLen : 0.0;
                boolean bank = bankProj >= 0.95;
                double fa = fallOf(engine, bx, bz, hs), fb = fallOf(engine, nbx, nbz, hs);
                (bank ? bankSteps : flowSteps).add(
                        new Step(bx, bz, dir == 0, step, dx, dz, bank, fa, fb));
            }
        }
        bankSteps.sort((p, q) -> Double.compare(q.step, p.step));
        flowSteps.sort((p, q) -> Double.compare(q.step, p.step));

        System.out.println();
        System.out.println("【岸坡方向】台阶 >= " + T + " 格 = " + bankSteps.size() + " 处  ← 按用户判据这是 bug");
        System.out.println("【沿河方向】台阶 >= " + T + " 格 = " + flowSteps.size() + " 处  ← 瀑布，正常");
        System.out.println();
        System.out.println("岸坡方向最严重 8 处（带两侧瀑布落差 fd）：");
        int k = 0;
        for (Step s : bankSteps) {
            if (k++ >= 8) break;
            double ddx = s.bx - bx0, ddz = s.bz - bz0;
            System.out.printf("  step=%.1f @ (%d,%d) 距中心=%.0f %s fd: %.1f→%.1f gradd=(%.2f,%.2f)%n",
                    s.step, s.bx, s.bz, Math.sqrt(ddx * ddx + ddz * ddz),
                    s.xStep ? "东-西向" : "南-北向", s.frozA, s.frozB, s.dx, s.dz);
        }
        System.out.println();
        System.out.println("岸坡断层前 4 处两侧内部量：");
        int shown = 0;
        for (Step w : bankSteps) {
            if (shown++ >= 4) break;
            int nbx = w.xStep ? w.bx + 1 : w.bx;
            int nbz = w.xStep ? w.bz : w.bz + 1;
            System.out.printf("%n--- step=%.1f (%d,%d)<->(%d,%d)%n",
                    w.step, w.bx, w.bz, nbx, nbz);
            dump(engine, cols, w.bx, w.bz, "A侧", hs);
            dump(engine, cols, nbx, nbz, "B侧", hs);
        }
    }

    private static long key(int bx, int bz) {
        return (((long) bx) << 32) | (bz & 0xffffffffL);
    }

    private static double nearestDist(HydrologyExperimentEngine engine, int bx, int bz, double hs) {
        List<HydrologyBlockSample> sm = engine.sampleBlockAll(bx, bz, hs);
        return sm.isEmpty() ? Double.NaN : sm.get(0).distToCenter();
    }

    private static double fallOf(HydrologyExperimentEngine engine, int bx, int bz, double hs) {
        List<HydrologyBlockSample> sm = engine.sampleBlockAll(bx, bz, hs);
        if (sm.isEmpty()) return -1;
        HydrologyBlockSample s = sm.get(0);
        return s.fallDrop() > 0 ? s.fallDrop() : (s.frozen() ? 0.0 : -1.0);
    }

    private static void dump(HydrologyExperimentEngine engine,
                             Map<Long, HydrologyBlockCarvedColumn> cols,
                             int bx, int bz, String tag, double hs) {
        HydrologyBlockCarvedColumn c = cols.get(key(bx, bz));
        if (c == null) { System.out.printf("  %s(%d,%d): 无列%n", tag, bx, bz); return; }
        List<HydrologyBlockSample> sm = engine.sampleBlockAll(bx, bz, hs);
        System.out.printf("  %s(%d,%d): orig=%.1f carved=%.1f cut=%.1f water=%.1f fill=%s%n",
                tag, bx, bz, c.originalGroundY(), c.carvedGroundY(),
                c.originalGroundY() - c.carvedGroundY(), c.waterSurfaceY(), c.fillWater());
        for (int i = 0; i < Math.min(3, sm.size()); i++) {
            HydrologyBlockSample s = sm.get(i);
            System.out.printf("     #%d dist=%.1f w=%.1f surf=%.1f lip=%.1f frozen=%s fd=%.1f%n",
                    i, s.distToCenter(), s.width(), s.surfaceY(), s.lipSurfaceY(), s.frozen(), s.fallDrop());
        }
    }
}
