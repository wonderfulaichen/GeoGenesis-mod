package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 河线命中【一致性】探针（2026-09-17）—— <b>控制变量：两个 API 是否给出同一个判定</b>。
 *
 * <h2>背景（已定位的矛盾）</h2>
 * <p>实测某水格（块(-15,661)）：{@code sample().isLake() == false}（判定为河，水位 156.977），
 * 但<b>实际铺水用了湖的水位 166.627</b>。而 carver 判湖用的是
 * {@code engine.sampleBlockAll(...).get(0).isLake()}（经 {@code network.sampleAll}）。</p>
 * <p>⇒ 怀疑 <b>{@code sample()} 与 {@code sampleAll()} 的"第一命中"不是同一个</b>
 * （收集范围 / 排序规则不同）⇒ <b>同一点"一个说是河、一个说是湖"</b>。</p>
 *
 * <h2>本探针做什么</h2>
 * <p>对窗口内每个水格，同时取 {@code sample()} 与 {@code sampleAll().get(0)}，
 * 比较 {@code isLake / surfaceY / distToCenter / width}，统计不一致的格数与最坏差异。</p>
 *
 * <pre>{@code gradlew runHitConsistencyProbe [-PprobeArgs="seed blockX blockZ radiusBlocks"]}</pre>
 */
public final class HitConsistencyProbe {

    private HitConsistencyProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 5436529513624899584L;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : 24;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : 632;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 64;
        int step = args.length > 4 ? Integer.parseInt(args[4]) : 2;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        RiverLineParams rp = RiverLineParams.defaults();
        double hs = tp.horizontalScale();

        RiverLineNetwork net = new RiverLineNetwork(
                (wx, wz) -> gen.terrainEQuick(wx, wz),
                (wx, wz) -> gen.heightCurve().heightFromE(gen.terrainEQuick(wx, wz)),
                gen.heightCurve(), seed, hs, rp);

        System.out.printf("=== HitConsistencyProbe seed=%d 中心块(%d,%d)±%d（步长 %d 块）===%n",
                seed, bx, bz, radius, step);
        System.out.println("比较 sample() 与 sampleAll().get(0) 的判定是否一致");

        int total = 0, bothNull = 0, oneNull = 0;
        int lakeMismatch = 0, surfaceMismatch = 0, multiHit = 0;
        double maxSurfDiff = 0, maxSurfDiffAt = 0;
        int shown = 0;
        for (int z = bz - radius; z <= bz + radius; z += step) {
            for (int x = bx - radius; x <= bx + radius; x += step) {
                double wx = x / hs, wz = z / hs;
                RiverLineNetwork.RiverLineHit a = net.sample(wx, wz);
                java.util.List<RiverLineNetwork.RiverLineHit> all = net.sampleAll(wx, wz);
                if (all.size() > 1) multiHit++;
                RiverLineNetwork.RiverLineHit b = all.isEmpty() ? null : all.get(0);
                if (a == null && b == null) { bothNull++; continue; }
                if (a == null || b == null) { oneNull++; continue; }
                total++;
                boolean lakeDiff = a.isLake() != b.isLake();
                double surfDiff = Math.abs(a.surfaceY() - b.surfaceY());
                if (lakeDiff) {
                    lakeMismatch++;
                    if (shown < 6) {
                        System.out.printf("    块(%d,%d)：sample isLake=%s surf=%.3f d=%.2f ｜ get(0) isLake=%s surf=%.3f d=%.2f%n",
                                x, z, a.isLake(), a.surfaceY(), a.distToCenter(),
                                b.isLake(), b.surfaceY(), b.distToCenter());
                        shown++;
                    }
                }
                if (surfDiff > 0.5) surfaceMismatch++;
                if (surfDiff > maxSurfDiff) { maxSurfDiff = surfDiff; maxSurfDiffAt = z; }
            }
        }
        System.out.println();
        System.out.printf("样本：两 API 均有命中 %d（其中 all.size()>1 的多命中 %d）；一端为 null %d；均 null %d%n",
                total, multiHit, oneNull, bothNull);
        System.out.printf("★ 【isLake 判定不一致】= %d / %d（%.1f%%）%n",
                lakeMismatch, total, total == 0 ? 0 : 100.0 * lakeMismatch / total);
        System.out.printf("★ 【水面差 >0.5 块】= %d / %d（%.1f%%）；最大差 %.3f 块%n",
                surfaceMismatch, total, total == 0 ? 0 : 100.0 * surfaceMismatch / total, maxSurfDiff);
        System.out.println();
        System.out.println("判读：不一致 >0 ⇒ 同一坐标被两个 API 判成不同东西");
        System.out.println("      ⇒ 「预览看到的」与「落块铺的」不是同一套判定（违反单一真值原则）。");
    }
}
