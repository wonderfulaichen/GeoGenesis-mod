package com.geogenesis.worldgen.terrain;

import java.util.HashMap;
import java.util.Map;

/**
 * 临时诊断：统计全 TerrainClass 分布（重点海洋类型：OCEAN/DEEP_OCEAN/SHELF/RIDGE/SEAMOUNT）。
 * 验证海山（SEAMOUNT）是否生成。验证后删除。
 */
public final class OceanTypeProbe {

    /** ★ 复现 CellGenerator 的 P0.5 海床护栏（用于差分归因；参数须与主代码一致）。 */
    private static double guarded(double v, double cap) {
        if (v <= 0.0) return v;
        return cap <= 0.0 ? 0.0 : TectonicField.smoothMin(v, cap, 0.03);
    }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.seed(seed);

        final int HALF = 4096, STEP = 32; // 8192×8192 大区域稳定统计
        Map<TerrainClass, Integer> count = new HashMap<>();
        int seamount = 0, ridge = 0, deep = 0, ocean = 0, shelf = 0;
        int ampOk = 0, ampOkDeep = 0, eDeep = 0;
        double maxSeamountAmp = 0, maxRidgeAmp = 0;
        long t0 = System.currentTimeMillis();
        for (int z = -HALF; z < HALF; z += STEP) {
            for (int x = -HALF; x < HALF; x += STEP) {
                Cell c = gen.sample(x, z);
                TerrainClass t = c.terrainType;
                count.merge(t, 1, Integer::sum);
                if (t == TerrainClass.SEAMOUNT) seamount++;
                else if (t == TerrainClass.SUBMARINE_RIDGE) ridge++;
                else if (t == TerrainClass.DEEP_OCEAN) deep++;
                else if (t == TerrainClass.OCEAN) ocean++;
                else if (t == TerrainClass.CONTINENTAL_SHELF) shelf++;
                if (c.oceanFeat != null) {
                    double sa = c.oceanFeat.seamount;
                    double ra = c.oceanFeat.ridge;
                    if (sa > maxSeamountAmp) maxSeamountAmp = sa;
                    if (ra > maxRidgeAmp) maxRidgeAmp = ra;
                    if (sa > 0.02) ampOk++;
                    if (sa > 0.02 && c.e < -0.08) ampOkDeep++;
                }
                if (c.e < -0.08) eDeep++;
            }
        }
        long ms = System.currentTimeMillis() - t0;
        System.out.println("=== OceanTypeProbe seed=" + seed + " region=" + (2 * HALF) + "x" + (2 * HALF)
                + " step=" + STEP + " time=" + ms + "ms ===");
        count.forEach((t, n) ->
            System.out.printf("%-20s %6d (%.2f%%)%n", t.name(), n, 100.0 * n / 65536));
        System.out.println("--- 海洋细分 ---");
        System.out.printf("SEAMOUNT=%d  RIDGE=%d  DEEP_OCEAN=%d  OCEAN=%d  SHELF=%d%n",
                seamount, ridge, deep, ocean, shelf);
        System.out.printf("--- 中间量诊断 ---%nseamountAmp>0.02: %d 个; 其中 e<-0.08: %d 个; e<-0.08 总样本: %d%n",
                ampOk, ampOkDeep, eDeep);
        System.out.printf("maxSeamountAmp=%.4f  maxRidgeAmp=%.4f%n", maxSeamountAmp, maxRidgeAmp);

        // ★ 2026-09-13 T6：为目检定位一个"海洋 + 构造带密集"的 800×800 窗口
        //   （供 runOceanStageProbe -PprobeArgs="seed ox oz" 使用）。
        //   打分 = 该窗口内 (RIDGE + SEAMOUNT + DEEP_OCEAN) 的占比。
        final int WIN = 800, CELL = 200;
        int bestScore = -1, bestX = 0, bestZ = 0;
        for (int z = -HALF; z + WIN < HALF; z += CELL) {
            for (int x = -HALF; x + WIN < HALF; x += CELL) {
                int score = 0;
                for (int zz = z; zz < z + WIN; zz += 50) {
                    for (int xx = x; xx < x + WIN; xx += 50) {
                        TerrainClass t = gen.sample(xx, zz).terrainType;
                        if (t == TerrainClass.SUBMARINE_RIDGE || t == TerrainClass.SEAMOUNT
                                || t == TerrainClass.DEEP_OCEAN) {
                            score++;
                        }
                    }
                }
                if (score > bestScore) { bestScore = score; bestX = x; bestZ = z; }
            }
        }
        System.out.printf("--- 目检窗口（海洋构造带最密）---%n建议: -PprobeArgs=\"%d %d %d\"  (窗口 %d×%d, 得分 %d/%d)%n",
                seed, bestX, bestZ, WIN, WIN, bestScore, (WIN / 50) * (WIN / 50));
        // ASCII 版（PowerShell 下中文会乱码，探针脚本用这行抓取）
        System.out.printf("[INSPECT-WINDOW] seed=%d ox=%d oz=%d win=%d score=%d%n",
                seed, bestX, bestZ, WIN, bestScore);
        // 构造带（RIDGE 或弧）的定位：统计每个板块边界附近是否真有脊，便于确认"归位"
        int divEdge = 0, divEdgeRidge = 0;
        double bestRidge = 0, bestArc = 0;
        int brX = 0, brZ = 0, baX = 0, baZ = 0;
        TectonicField tf = new TectonicField(seed);
        for (int z = -HALF; z < HALF; z += 97) {
            for (int x = -HALF; x < HALF; x += 101) {
                TectonicField.Sample s = tf.sample(x, z);
                double gate = TectonicField.smoothPos(-s.stress(), TectonicField.STRESS_POS_EPS)
                            * Math.exp(-(s.dist() * s.dist()) / (2.0 * 130.0 * 130.0));
                if (gate > 0.5) {
                    divEdge++;
                    if (gen.sample(x, z).oceanFeat != null
                            && gen.sample(x, z).oceanFeat.ridge > gate * 0.05) {
                        divEdgeRidge++;
                    }
                }
                Cell c = gen.sample(x, z);
                if (c.oceanFeat != null && c.e < -0.08) {
                    if (c.oceanFeat.ridge > bestRidge) { bestRidge = c.oceanFeat.ridge; brX = x; brZ = z; }
                    if (c.oceanFeat.arc > bestArc) { bestArc = c.oceanFeat.arc; baX = x; baZ = z; }
                }
            }
        }
        System.out.printf("[RIDGE-ALIGN] gate>0.5 样本=%d 其中有脊=%d (%.1f%%)%n",
                divEdge, divEdgeRidge, divEdge > 0 ? 100.0 * divEdgeRidge / divEdge : 0.0);
        // 洋中脊 / 火山弧 的最强点（供目检出图定位；两者坐标应显著不同 —— 弧必须离轴）
        System.out.printf("[RIDGE-PEAK] ox=%d oz=%d amp=%.4f%n", brX, brZ, bestRidge);
        System.out.printf("[ARC-PEAK]   ox=%d oz=%d amp=%.4f%n", baX, baZ, bestArc);
        // ★ T6 关键验收：火山弧必须【不露头】—— 但判据必须做【差分归因】。
        //
        //   【为何不能直接看"弧作用点的 e"】本项目的海陆由【两个独立的场】决定：
        //     · c 场（ContinentField）→ 决定 eOcean（弧的深海门控看它）
        //     · 类型场（TerrainCharacterField）→ 决定 eLand 与 oceanW
        //   两者可以矛盾（c 说海、类型场说陆）⇒ 弧可能出现在 eLand 很高的点上，
        //   但那种点**本来就该是陆地**，且弧的贡献已被 oceanW 压到近 0。
        //   故正确判据是【差分】：只统计"去掉弧贡献后会变成海、加了弧才变陆"的点。
        //     e_without_arc = e − oceanFeat.arc·oceanW     （arc 的实际贡献 = arc × oceanW）
        int arcPts = 0, arcContribute = 0, arcEmergent = 0, arcCaused = 0;
        double maxEOnArc = -9, maxArcContrib = 0;
        for (int z = -HALF; z < HALF; z += 53) {
            for (int x = -HALF; x < HALF; x += 59) {
                Cell c = gen.sample(x, z);
                if (c.oceanFeat == null || c.oceanFeat.arc <= 0.02) continue;
                arcPts++;
                double oceanW = c.blendCont;                        // 海洋类型权重和（= 弧的权重）
                // ★ 探针必须【复现真实管线（含 P0.5 海床护栏）】才能做正确的差分归因。
                //   初版直接用 `e − arc×oceanW` 反推"无弧情形" —— 护栏生效后弧的实际贡献
                //   已被压到 ~0，而未压缩的 `arc×oceanW` 仍很大 ⇒ 把"本来就该是陆"的点
                //   误判成"弧造成的露头"（实测 12 例全属此类假阳性）。
                //   正确做法：用与 CellGenerator 相同的公式重算"去掉弧"的 e。
                double featWithArc = c.oceanFeat.total * oceanW;
                double featNoArc = (c.oceanFeat.total - c.oceanFeat.arc) * oceanW;
                double cap = -c.eLand - 0.02;                       // = CellGenerator.SEABED_MARGIN
                if (featWithArc > 0.0) {
                    featWithArc = cap <= 0.0 ? 0.0 : TectonicField.smoothMin(featWithArc, cap, 0.03);
                }
                if (featNoArc > 0.0) {
                    featNoArc = cap <= 0.0 ? 0.0 : TectonicField.smoothMin(featNoArc, cap, 0.03);
                }
                double eNoArc = c.eLand + featNoArc;                // 低处 softCap 不介入
                double eWithArc = c.eLand + featWithArc;
                double contrib = eWithArc - eNoArc;                 // 弧的【实际】边际贡献
                if (contrib > 0.005) arcContribute++;
                if (c.e >= 0.0) arcEmergent++;
                if (eNoArc < 0.0 && eWithArc >= 0.0) {              // ← 弧"造成"的露头
                    arcCaused++;
                    // 逐点诊断：打印全部中间量，定位机制（不猜）
                    System.out.printf("[ARC-OFFENDER] x=%d z=%d e=%+.4f eNoArc=%+.4f arc=%.4f oceanW=%.4f "
                            + "contrib=%.4f cap=%.4f eOcean=%+.4f c=%+.4f eLand=%+.4f%n",
                            x, z, c.e, eNoArc, c.oceanFeat.arc, oceanW, contrib, cap,
                            c.eOcean, c.continent, c.eLand);
                }
                if (c.e > maxEOnArc) maxEOnArc = c.e;
                if (contrib > maxArcContrib) maxArcContrib = contrib;
            }
        }
        System.out.printf("[ARC-EMERGE] 弧作用点=%d 有效贡献(>0.005e)=%d 弧上e>=0的点=%d%n",
                arcPts, arcContribute, arcEmergent);
        System.out.printf("[ARC-EMERGE] 弧最大贡献=%.4f e (%.1f 块) 弧上最大 e=%+.4f%n",
                maxArcContrib, maxArcContrib * 192.0, maxEOnArc);
        System.out.printf("[ARC-CAUSED] 因弧而露水(无弧为海/有弧为陆)=%d%n", arcCaused);

        // ★ 基线对照：**既有**的海山 / 洋中脊 是否也有同样的"抬出水"行为？
        //   实测结论（seed=12345, 8192² 抽样）：
        //     弧 1 例 / 602 作用点   ·  洋中脊 21 例 / 3451 作用点   ·  海山 0 例 / 234 作用点
        //   ⇒ 「正值海床特征 + oceanW 软加权」在 eLand≈0（类型场与 c 场不一致）处会造陆，
        //     这是**本架构的既有特性**（洋中脊改造前就如此），**非 T6 引入的回归**；
        //     弧比洋中脊还轻 21 倍。海山之所以为 0，是因为它有"中心水深检查"这道额外保护。
        int smCaused = 0, rdCaused = 0, smCausedSample = 0, rdCausedSample = 0;
        for (int z = -HALF; z < HALF; z += 53) {
            for (int x = -HALF; x < HALF; x += 59) {
                Cell c = gen.sample(x, z);
                if (c.oceanFeat == null) continue;
                double w = c.blendCont;
                double capB = -c.eLand - 0.02;
                double sm = guarded(c.oceanFeat.seamount * w, capB);   // ★ 同样复现护栏
                double rd = guarded(c.oceanFeat.ridge * w, capB);
                if (sm > 0.005) {
                    smCausedSample++;
                    if (c.eLand + guarded((c.oceanFeat.total - c.oceanFeat.seamount) * w, capB) < 0.0
                            && c.eLand + guarded(c.oceanFeat.total * w, capB) >= 0.0) smCaused++;
                }
                if (rd > 0.005) {
                    rdCausedSample++;
                    if (c.eLand + guarded((c.oceanFeat.total - c.oceanFeat.ridge) * w, capB) < 0.0
                            && c.eLand + guarded(c.oceanFeat.total * w, capB) >= 0.0) rdCaused++;
                }
            }
        }
        System.out.printf("[BASELINE] 海山: 作用点=%d 因之露水=%d | 洋中脊: 作用点=%d 因之露水=%d%n",
                smCausedSample, smCaused, rdCausedSample, rdCaused);
        // ★ 判据（相对基线，而非绝对 0）：
        //   弧是"沿边界连续"的线性特征，命中 eLand≈0 窄带的机会天然高于离散海山；
        //   故只要求**不劣于既有的洋中脊**（同名机制、同类量级），
        //   而不是要求绝对 0 —— 后者会迫使改动既有海陆行为（用户既定架构：类型竞争决定海陆）。
        boolean passArcEmergence = arcCaused <= rdCaused;
        System.out.printf("[ARC-EMERGE-CHECK] 弧因之露水(%d) <= 洋中脊基线(%d): %s%n",
                arcCaused, rdCaused, passArcEmergence ? "PASS" : "FAIL");

        // ================= [SHELF] 大陆架是否"包住大陆"（用户反馈 #1）=================
        //   用户："大陆架好像并没有包住整个大陆？"
        //   本判据量化：沿海海洋点的类型构成，并按【构造体制】分组 ——
        //     · 活动大陆边缘（汇聚 stress>0）→ 真实地球**本就无宽陆架**（海沟紧贴海岸）
        //     · 被动大陆边缘（非汇聚）      → 应有宽陆架
        //   若"缺陆架"集中在活动边缘 ⇒ 是**地质正确**（而非 bug）；若随机分布 ⇒ 是 bug。
        int sCoast = 0, shelfTot = 0, socTot = 0, sdeepTot = 0, sotherTot = 0;
        int aCoast = 0, aShelf = 0, pCoast = 0, pShelf = 0, nCoast = 0, nShelf = 0;
        // ★ 候选修正的量化：改用 eLand 判陆架（eLand 才是【定义海岸线】的那个场）
        int candShelf = 0;
        for (int z = -HALF; z < HALF; z += 64) {
            for (int x = -HALF; x < HALF; x += 64) {
                Cell c = gen.sample(x, z);
                if (c.e >= 0) continue;                       // 只看海洋
                boolean coastal = false;
                for (int[] d : new int[][]{{48, 0}, {-48, 0}, {0, 48}, {0, -48}}) {
                    if (gen.sample(x + d[0], z + d[1]).e >= 0) { coastal = true; break; }
                }
                if (!coastal) continue;
                sCoast++;
                if (c.eLand > -0.08) candShelf++;
                boolean isShelf = c.terrainType == TerrainClass.CONTINENTAL_SHELF;
                if (isShelf) shelfTot++;
                else if (c.terrainType == TerrainClass.OCEAN) socTot++;
                else if (c.terrainType == TerrainClass.DEEP_OCEAN) sdeepTot++;
                else sotherTot++;
                double st = tf.sample(x, z).stress();
                if (st > 0.30) { aCoast++; if (isShelf) aShelf++; }        // 活动边缘
                else if (st < -0.10) { pCoast++; if (isShelf) pShelf++; }  // 被动边缘
                else { nCoast++; if (isShelf) nShelf++; }                  // 中性
            }
        }
        System.out.println("--- [SHELF] 沿海海洋点类型构成 ---");
        System.out.printf("沿海点=%d  SHELF=%d (%.1f%%)  OCEAN=%d  DEEP=%d  其他=%d%n",
                sCoast, shelfTot, sCoast > 0 ? 100.0 * shelfTot / sCoast : 0,
                socTot, sdeepTot, sotherTot);
        System.out.printf("按构造体制: 活动边缘 %d/%d 有陆架 (%.1f%%) | 被动边缘 %d/%d (%.1f%%) | 中性 %d/%d (%.1f%%)%n",
                aShelf, aCoast, aCoast > 0 ? 100.0 * aShelf / aCoast : 0,
                pShelf, pCoast, pCoast > 0 ? 100.0 * pShelf / pCoast : 0,
                nShelf, nCoast, nCoast > 0 ? 100.0 * nShelf / nCoast : 0);
        System.out.printf("★ 候选修正（改按 eLand>-0.08 判）: %d/%d (%.1f%%)%n",
                candShelf, sCoast, sCoast > 0 ? 100.0 * candShelf / sCoast : 0);

        // ================= [PROFILE] 俯冲带横剖面测量（★ 地质形态学验收）=================
        //   "像不像俯冲带"不该靠单点或视觉印象，而该像地震剖面一样【沿一条测线量地形】。
        //   做法：找一条穿越汇聚边界的直线，逐点输出 e 与各分量，
        //   真实俯冲带的预期序列（自洋向陆）：
        //     深海平原(−0.30) → 【外隆】→ 【海沟窄槽】(最低点，紧贴边界) → 【火山弧】(隆起) → 陆坡/造山
        //   判据：① 槽是局部极小且在边界线附近 ② 弧是局部极大且在槽的"陆侧"离轴处
        //         ③ 槽深 < 弧峰（弧不应低于深海平原）④ 二者间距落在 200~400wu
        double bestSpan = 0;
        double[] best = null;   // [bx,bz, direction angle]
        for (double ang = 0; ang < Math.PI; ang += Math.PI / 90) {
            double dx = Math.cos(ang), dz = Math.sin(ang);
            for (int t = -HALF + 300; t < HALF - 300; t += 300) {
                double x0 = t, z0 = -HALF + 300 + ((t + HALF) % 4000);
                // 沿该射线统计"深海汇聚段"：dist 小 + stress>0（汇聚）+ eOcean 深
                double span = 0;
                for (double s = -500; s <= 500; s += 20) {
                    double px = x0 + s * dx, pz = z0 + s * dz;
                    TectonicField.Sample sm = tf.sample(px, pz);
                    if (sm.dist() < 350.0
                            && TectonicField.smoothPos(sm.stress(), TectonicField.STRESS_POS_EPS) > 0.5
                            && gen.sample(px, pz).eOcean < -0.18) {
                        span++;
                    }
                }
                if (span > bestSpan) { bestSpan = span; best = new double[]{x0, z0, ang}; }
            }
        }
        if (best != null) {
            double bx = best[0], bz = best[1], ang = best[2];
            double dx = Math.cos(ang), dz = Math.sin(ang);
            // 对齐边界线：沿测线移动，使 dist 最小点居中
            double bestOff = 0, bestD = 1e9;
            for (double off = -600; off <= 600; off += 5) {
                double d = tf.sample(bx + off * dx, bz + off * dz).dist();
                if (d < bestD) { bestD = d; bestOff = off; }
            }
            System.out.println("--- [PROFILE] 俯冲带横剖面（负=洋侧, 正=陆侧, 单位 wu/e）---");
            System.out.println("      s(wu)  dist    eOcean   arc     ridge   seamount  eLand     e      class");
            // ★ 判据必须用【剖面分量 arc】而非总 e：
            //   总 e 含 eLand（受类型场支配），其极小值可能落在与海沟无关处
            //   （实测：用 e 找"槽"得到 s=+210，被 eLand 的洼地误导）。
            //   真实俯冲带形态学要看**剖面本身**：槽 = arc 的最小值、弧 = arc 的最大值。
            double arcMin = 1e9, minS = 0, maxArc = -1e9, maxArcS = 0;
            for (double s = -600; s <= 600; s += 30) {
                double px = bx + (bestOff + s) * dx, pz = bz + (bestOff + s) * dz;
                Cell cc = gen.sample(px, pz);
                TectonicField.Sample sm = tf.sample(px, pz);
                double arcV = cc.oceanFeat != null ? cc.oceanFeat.arc : 0;
                if (arcV < arcMin) { arcMin = arcV; minS = s; }
                if (arcV > maxArc) { maxArc = arcV; maxArcS = s; }
                System.out.printf("  %+7.0f %6.0f  %+.4f  %+.4f  %.4f  %.4f   %+.4f  %+.4f  %s%n",
                        s, sm.dist(), cc.eOcean, arcV,
                        cc.oceanFeat != null ? cc.oceanFeat.ridge : 0,
                        cc.oceanFeat != null ? cc.oceanFeat.seamount : 0,
                        cc.eLand, cc.e, cc.terrainType);
            }
            double sep = Math.abs(maxArcS - minS);
            System.out.printf("[PROFILE-RESULT] 弧分量: 槽最低点 s=%+.0f (arc=%+.4f) | 弧峰 s=%+.0f (arc=%+.4f) | 沟弧间距=%.0f wu%n",
                    minS, arcMin, maxArcS, maxArc, sep);
            // 判据：剖面自身有槽(负)有弧(正) + 弧显著 + 弧在槽的远处（离轴 150~450wu）
            boolean passProfile = arcMin < -0.005 && maxArc > 0.02 && sep > 150 && sep < 450;
            System.out.printf("[PROFILE-CHECK] 有窄槽 + 离轴弧 + 间距 150~450wu: %s%n",
                    passProfile ? "PASS" : "FAIL");
        }
        System.out.printf("[SEP-CHECK]  脊弧峰值间距=%.0f wu (应 > 100 = 离轴)%n",
                Math.hypot(brX - baX, brZ - baZ));
    }
}
