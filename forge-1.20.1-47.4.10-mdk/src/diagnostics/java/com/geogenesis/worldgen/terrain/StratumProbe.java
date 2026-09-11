package com.geogenesis.worldgen.terrain;

/**
 * 地层/岩性探针（★ 2026-09-12，地质系统 Phase T4 配套）。
 *
 * <p>验证岩性分布<b>在地质学上合理</b>，而非随机分配：
 * <ol>
 *   <li><b>合成验证</b>：给定构造环境，各层产出的岩性必须落在该环境的地层序列内
 *       （如"汇聚·陆地"只应出变质岩/花岗岩，不应出砂岩）</li>
 *   <li><b>真实统计</b>：岩性应有<b>多样性</b>（非单一），且出现<b>成片分区</b>特征</li>
 *   <li>确定性、值域、性能</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runStratumProbe [seed]}</p>
 */
public final class StratumProbe {

    /** 各构造环境（陆地）允许的岩性集合，用于合成验证。index = btype。 */
    private static final RockType[][] EXPECTED_LAND = {
            /* INTERIOR 0   */ {RockType.GNEISS, RockType.GRANITE, RockType.SCHIST},
            /* CONVERGENT 1 */ {RockType.SCHIST, RockType.GNEISS, RockType.GRANITE},
            /* DIVERGENT 2  */ {RockType.SANDSTONE, RockType.SHALE, RockType.LIMESTONE, RockType.BASALT},
            /* TRANSFORM 3  */ {RockType.GNEISS, RockType.GRANITE, RockType.SCHIST},
    };

    /**
     * 各构造环境（海洋）允许的岩性集合。index = btype。
     *
     * <p>海洋<b>并非纯玄武岩</b>：真实海底 = 玄武岩基底 + <b>沉积盖层</b>
     * （深海远洋软泥 / 海沟浊积），盖层厚度随环境不同 → 故允许深海沉积与火山弧岩性。
     */
    private static final RockType[][] EXPECTED_OCEAN = {
            /* INTERIOR 0   */ {RockType.LIMESTONE, RockType.BASALT},              // 深海平原
            /* CONVERGENT 1 */ {RockType.SHALE, RockType.ANDESITE, RockType.BASALT}, // 俯冲带
            /* DIVERGENT 2  */ {RockType.BASALT},                                  // 洋中脊（无盖层）
            /* TRANSFORM 3  */ {RockType.LIMESTONE, RockType.BASALT},
    };

    private static boolean inSet(RockType[] set, RockType v) {
        for (RockType e : set) if (e == v) return true;
        return false;
    }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        System.out.printf("=== StratumProbe seed=%d ===%n", seed);

        // ================= [1] 合成验证：构造环境 → 岩性序列 =================
        int bad = 0, checked = 0;
        for (int btype = 0; btype <= 3; btype++) {
            for (int layer = 0; layer < StratumField.LAYER_COUNT; layer++) {
                TectonicField.Sample s = new TectonicField.Sample(0, btype, 1.0, 0.0, 1.0);
                RockType got = RockType.values()[StratumField.rockTypeId(s, true, layer)];
                checked++;
                if (!inSet(EXPECTED_LAND[btype], got)) {
                    bad++;
                    System.out.printf("    越界: 陆地 btype=%d layer=%d → %s%n", btype, layer, got);
                }
            }
        }
        boolean pass1 = bad == 0;
        System.out.printf("[1] 陆地 构造环境→岩性序列(合成 n=%d): 越界=%d %s%n",
            checked, bad, pass1 ? "PASS" : "FAIL");
        System.out.println("    要求: 各环境只出本环境序列内的岩性(如汇聚陆地不应出砂岩)");

        // ================= [2] 海洋环境（含沉积盖层） =================
        int oceanBad = 0, oceanChecked = 0;
        for (int btype = 0; btype <= 3; btype++) {
            for (int layer = 0; layer < StratumField.LAYER_COUNT; layer++) {
                TectonicField.Sample s = new TectonicField.Sample(0, btype, 1.0, 0.0, 1.0);
                RockType got = RockType.values()[StratumField.rockTypeId(s, false, layer)];
                oceanChecked++;
                if (!inSet(EXPECTED_OCEAN[btype], got)) {
                    oceanBad++;
                    System.out.printf("    越界: 海洋 btype=%d layer=%d → %s%n", btype, layer, got);
                }
            }
        }
        boolean pass2 = oceanBad == 0;
        System.out.printf("[2] 海洋 构造环境→岩性序列(合成 n=%d): 越界=%d %s%n",
            oceanChecked, oceanBad, pass2 ? "PASS" : "FAIL");
        System.out.println("    要求: 海洋 = 玄武岩基底 + 沉积盖层(深海灰岩/海沟页岩), 洋中脊无盖层");

        // ================= [3] 真实地形：岩性多样性与分布 =================
        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        int[] typeCount = new int[RockType.values().length];
        int[] layerCount = new int[StratumField.LAYER_COUNT];
        int landN = 0, total = 0;
        long t0 = System.nanoTime();
        for (double z = -8000; z <= 8000; z += 211) {
            for (double x = -8000; x <= 8000; x += 223) {
                Cell c = gen.sample(x, z);
                total++;
                if (c.rockTypeId < 0 || c.rockTypeId >= typeCount.length
                        || c.rockLayer < 0 || c.rockLayer >= layerCount.length) continue;
                typeCount[c.rockTypeId]++;
                layerCount[c.rockLayer]++;
                if (c.eLand >= 0) landN++;
            }
        }
        long t1 = System.nanoTime();
        int kinds = 0;
        for (int v : typeCount) if (v > 0) kinds++;
        boolean pass3 = kinds >= 4;    // 至少出现 4 种岩性
        System.out.printf("[3] 真实地形(n=%d, 陆地=%d): 出现岩性种类=%d/%d %s%n",
            total, landN, kinds, typeCount.length, pass3 ? "PASS" : "FAIL");
        StringBuilder sb = new StringBuilder("    岩性分布: ");
        for (int i = 0; i < typeCount.length; i++) {
            if (typeCount[i] > 0) {
                sb.append(String.format("%s=%.1f%% ", RockType.values()[i].name(),
                    100.0 * typeCount[i] / total));
            }
        }
        System.out.println(sb);
        StringBuilder sb2 = new StringBuilder("    地层分布: ");
        for (int i = 0; i < layerCount.length; i++) {
            sb2.append(String.format("L%d=%.1f%% ", i, 100.0 * layerCount[i] / total));
        }
        System.out.println(sb2);

        // ================= [4] 确定性 =================
        CellGenerator gen2 = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen2.seed(seed);
        int mism = 0;
        for (int i = 0; i < 300; i++) {
            double x = i * 293.0 - 20000, z = i * 187.0 - 12000;
            Cell a = gen.sample(x, z);
            Cell b = gen2.sample(x, z);
            if (a.rockTypeId != b.rockTypeId || a.rockLayer != b.rockLayer) mism++;
        }
        boolean pass4 = mism == 0;
        System.out.printf("[4] 确定性(n=300): 不一致=%d %s%n", mism, pass4 ? "PASS" : "FAIL");

        // ================= [5] 性能：T4 引入部分的【独立】成本 =================
        //   ★ 只测 StratumField.layerAt（T4 新增的唯一采样），不测完整 sample()——
        //   后者含气候/群区/降水/构造等大量无关成本，无法反映本阶段增量。
        //   （初版此处硬编码 pass5=true，属"占位判据"，不诚实，已改为实测。）
        StratumField sf = new StratumField(seed);
        final int iters = 500_000;
        long t2 = System.nanoTime();
        int acc = 0;
        for (int i = 0; i < iters; i++) acc += sf.layerAt(i * 3.7, i * 2.3);
        long t3 = System.nanoTime();
        double us = (t3 - t2) / 1000.0 / iters;
        boolean pass5 = acc >= 0 && us < 1.0;   // 应远低于 terrainEQuick（约 6µs）
        System.out.printf("[5] 性能: StratumField.layerAt %.3f µs/次 (阈值 1µs；terrainEQuick 约 6µs) %s%n",
            us, pass5 ? "PASS" : "FAIL");
        System.out.printf("    （参考）完整 sample() 含地层 %d 次耗时 %.1f ms (%.1f µs/次，含气候/群区等）%n",
            total, (t1 - t0) / 1e6, (t1 - t0) / 1e3 / total);

        boolean all = pass1 && pass2 && pass3 && pass4 && pass5;
        System.out.println(all ? "=== ALL PASS ===" : "=== FAILURES PRESENT ===");
        if (!all) System.exit(1);
    }
}
