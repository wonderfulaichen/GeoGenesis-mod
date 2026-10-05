package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * <h1>全周期判据：源头 → 汇流 → 蓄水（湖泊）→ 溢口 → 下泄 → 入海</h1>
 *
 * <h2>用户判据（原话）</h2>
 * <p>「水文骨架全生命周期重构（源头→汇流→蓄水（湖泊）→溢口→下泄→入海）」；
 * 「我要求是能完成水文全周期」。</p>
 *
 * <h2>为什么必须判"链"而不是判"各段计数都 &gt; 0"</h2>
 * <p>六阶段各段计数全 &gt;0 是<b>弱判据</b>：各段可能存在但<b>互不相连</b>
 * （源头的河没有一条流进湖，湖的溢口没有一条接到海）。
 * ⇒ 本探针<b>逐条链追踪</b>：从每个源头沿排水父指针走到终点，
 * 依次打标六阶段，只有<b>按序全部出现</b>才算一条完整链。</p>
 *
 * <h2>六阶段在此链上的对应物（本模型的定义）</h2>
 * <pre>
 *   源头  = 河道格中【上游无其它河道格】者（入度 0）
 *   汇流  = 沿 parent 下游走过的连续河道格
 *   蓄水  = 进入 lake==true 的格（湖面由水量平衡给出，非"画出来的湖"）
 *   溢口  = 该湖的 overflowAt 键（蓄满后向外的出流格）
 *   下泄  = 溢口之后继续沿 parent 由流量驱动的河道格
 *   入海  = 走到 sea==true
 * </pre>
 *
 * <h2>输出</h2>
 * <p>{@code build/lifecycle/}：{@code metrics.txt} + {@code chains.png}
 * （底图等高线；**完整六阶段链高亮为黄**，其余河道灰、湖蓝、海深蓝）。</p>
 *
 * <p><b>NOT VERIFIED</b>：方块铺设、实机渲染未覆盖。</p>
 */
public final class LifecycleChainProbe {

    private LifecycleChainProbe() { }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : ViewSpec.CENTER_X;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : ViewSpec.CENTER_Z;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : ViewSpec.RADIUS;
        int samples = args.length > 4 ? Integer.parseInt(args[4]) : 300_000;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var climate = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);
        DirectionRule.setMode("baseline");

        var r = HydrologyPipeline.run(gen, tp,
                new HydrologyPipeline.Request(cx, cz, radius, ViewSpec.STEP, climate, 0,
                        HydrologyPipeline.RiverModel.CONTINUOUS, samples));
        int n = r.nx(), cells = n * n;

        boolean[] ch = r.channel();
        boolean[] lake = r.lake();
        boolean[] sea = r.sea();
        int[] parent = r.parent();
        double[] depth = r.depth();
        var lakeRes = r.lakeResult();

        // ---- 源头：河道格中上游无河道格者（入度 0）----
        boolean[] hasUpstream = new boolean[cells];
        for (int k = 0; k < cells; k++) {
            if (!ch[k]) continue;
            int p = parent[k];
            if (p >= 0 && p < cells && ch[p]) hasUpstream[p] = true;
        }
        int srcCount = 0;
        for (int k = 0; k < cells; k++) if (ch[k] && !hasUpstream[k]) srcCount++;

        // ---- 逐源头追踪六阶段 ----
        int[] guard = new int[1];
        int full = 0, toSeaNoLake = 0, toLakeNoSpill = 0, toBoundary = 0, died = 0;
        int statSrc = 0, statCh = 0, statLake = 0, statSpill = 0, statDown = 0, statSea = 0;
        boolean[] chainMark = new boolean[cells];
        StringBuilder example = new StringBuilder();

        for (int k0 = 0; k0 < cells; k0++) {
            if (!ch[k0] || hasUpstream[k0]) continue;
            boolean sawCh = false, sawLake = false, sawSpill = false, sawDown = false, sawSea = false;
            int cur = k0;
            guard[0] = 0;
            int lakeCell = -1;
            while (guard[0]++ < 4 * n && cur >= 0 && cur < cells) {
                if (sea[cur]) { sawSea = true; break; }
                if (lake[cur]) {
                    sawLake = true; lakeCell = cur;
                    // 溢口：该湖盆的 overflowAt 是否非空
                    break;
                }
                if (ch[cur]) sawCh = true;
                int p = parent[cur];
                if (p < 0 || p == cur) break;
                cur = p;
            }
            // 溢口 & 下泄：从入湖格所在盆地的出口继续
            if (sawLake && lakeCell >= 0) {
                Integer outlet = outletOf(lakeRes, lakeCell, depth, n);
                if (outlet != null && outlet >= 0) {
                    sawSpill = true;
                    int cur2 = outlet; int g2 = 0;
                    while (g2++ < 4 * n && cur2 >= 0 && cur2 < cells) {
                        if (sea[cur2]) { sawSea = true; break; }
                        if (lake[cur2]) break;                     // 又入一湖，停止（不递归）
                        if (ch[cur2]) sawDown = true;
                        int p2 = parent[cur2];
                        if (p2 < 0 || p2 == cur2) break;
                        cur2 = p2;
                    }
                }
            }
            statSrc++;
            if (sawCh) statCh++;
            if (sawLake) statLake++;
            if (sawSpill) statSpill++;
            if (sawDown) statDown++;
            if (sawSea) statSea++;

            if (sawCh && sawLake && sawSpill && sawDown && sawSea) {
                full++;
                if (example.length() == 0) example.append(String.format(
                        "源头(%d,%d) → 汇流 → 蓄水 → 溢口 → 下泄 → 入海",
                        k0 / n, k0 % n));
                // 高亮：整条链重走一遍标记
                int c2 = k0; int g3 = 0;
                while (g3++ < 4 * n && c2 >= 0 && c2 < cells) {
                    chainMark[c2] = true;
                    if (sea[c2]) break;
                    int p3 = parent[c2];
                    if (p3 < 0 || p3 == c2) break;
                    c2 = p3;
                }
            } else if (sawSea) toSeaNoLake++;
            else if (sawLake) toLakeNoSpill++;
            else died++;
        }
        if (srcCount == 0) statSrc = 0;

        StringBuilder log = new StringBuilder();
        log.append("=== 全周期判据：源头→汇流→蓄水→溢口→下泄→入海 ===\n");
        log.append(String.format("seed=%d centre=(%d,%d) radius=%d  = %dx%d%n",
                seed, cx, cz, radius, n, n));
        log.append(String.format("源头(入度0的河道格) = %d%n%n", srcCount));
        log.append("各阶段【被链访问到】的源头数（弱计数，仅供参考）：\n");
        log.append(String.format("  汇流=%d  蓄水=%d  溢口=%d  下泄=%d  入海=%d%n%n",
                statCh, statLake, statSpill, statDown, statSea));
        log.append(String.format("★ 完整六阶段链 = %d%n", full));
        log.append(String.format("  到海但未经湖 = %d%n", toSeaNoLake));
        log.append(String.format("  入湖但无溢口 = %d%n", toLakeNoSpill));
        log.append(String.format("  中途消失   = %d%n", died));
        if (example.length() > 0) log.append(String.format("  例：%s%n", example));
        log.append(String.format("  湖泊总数=%d  溢流出口数=%d  海格=%d%n",
                lakeRes.basins().size(), lakeRes.overflowAt().size(), count(sea)));
        log.append(String.format("%n判定：%s%n", full > 0
                ? "PASS（存在完整六阶段链）"
                : "★ FAIL（无任何一条链走完六阶段）"));

        // ★★ 出口级诊断（排除"探针 bug"的可能性）★★
        //   120 个源头入湖、120 个找到溢口，但只有 21 个在溢口下游见到河道
        //   ⇒ 必须直接量【溢口格本身】，而不是靠"最近溢口"的近似归属。
        log.append("%n[出口级诊断] 逐个溢流出口格检查（排除探针归属近似）\n");
        int outletCh = 0, outletToSea = 0, outletToCh = 0, outletDead = 0;
        int[] outletDist = new int[Math.max(1, lakeRes.overflowAt().size())];
        int oi = 0;
        for (Integer o : lakeRes.overflowAt().keySet()) {
            if (o == null || o < 0 || o >= cells) continue;
            boolean isCh = ch[o];
            if (isCh) outletCh++;
            // 从出口格沿 parent 走，量到第一个河道格/海的步数
            int c = o, g = 0, firstCh = -1;
            boolean sea2 = false;
            while (g++ < 4 * n && c >= 0 && c < cells) {
                if (sea[c]) { sea2 = true; break; }
                if (firstCh < 0 && ch[c]) firstCh = g;
                int p = parent[c];
                if (p < 0 || p == c) break;
                c = p;
            }
            if (sea2) outletToSea++;
            if (firstCh >= 0) outletToCh++;
            else outletDead++;
            if (oi < outletDist.length) outletDist[oi++] = (firstCh < 0 ? -1 : firstCh);
        }
        log.append(String.format("  溢流出口数=%d%n", lakeRes.overflowAt().size()));
        log.append(String.format("  出口格【自身】是河道格 = %d%n", outletCh));
        log.append(String.format("  出口下游能走到【河道格】 = %d%n", outletToCh));
        log.append(String.format("  出口下游能走到【海】     = %d%n", outletToSea));
        log.append(String.format("  出口下游既无河道也无海   = %d%n", outletDead));
        log.append("  ⇒ 若 outletToCh 高但 '下泄' 低 ⇒ 探针归属有 bug；\n");
        log.append("     若 outletToCh 本身低 ⇒ 【真缺陷】：溢口下游没有河道延续。\n");
        log.append(String.format("  首次遇河道格的步数: %s%n", java.util.Arrays.toString(
                java.util.Arrays.copyOf(outletDist, Math.min(oi, 8)))));

        // ★★ 溢口格的 parent 状态（"parent缺失=54"的直接检查）★★
        //    outletExit 失败原因全是 parent<0 ⇒ 必须看溢口格自身的 parent 指向。
        log.append("%n[溢口格 parent 检查]\n");
        int outParentNeg = 0, outParentSelf = 0, outParentLake = 0, outParentOk = 0;
        int outSelfIsSea = 0, outSelfIsLake = 0, outSelfIsChannel = 0;
        int sampleShown = 0;
        for (Integer o : lakeRes.overflowAt().keySet()) {
            if (o == null || o < 0 || o >= cells) continue;
            if (sea[o]) outSelfIsSea++;
            if (lake[o]) outSelfIsLake++;
            if (ch[o]) outSelfIsChannel++;
            int p = parent[o];
            if (p < 0) outParentNeg++;
            else if (p == o) outParentSelf++;
            else if (lake[p]) outParentLake++;
            else outParentOk++;
            if (sampleShown < 6) {
                sampleShown++;
                log.append(String.format("    出口(%d,%d): sea=%b lake=%b channel=%b parent=%d %s%n",
                        o / n, o % n, sea[o], lake[o], ch[o], p,
                        p < 0 ? "【parent=-1 ⇒ outletExit 必失败】" : ""));
            }
        }
        log.append(String.format("  出口格自身: 是海=%d 是湖=%d 是河道=%d%n",
                outSelfIsSea, outSelfIsLake, outSelfIsChannel));
        log.append(String.format("  出口格 parent: <0(断)=%d  自指=%d  指向湖=%d  其它=%d%n",
                outParentNeg, outParentSelf, outParentLake, outParentOk));
        log.append("  ⇒ 若 <0 占多数 ⇒【溢口格的下游父指针本身是断的】：\n");
        log.append("     湖盆出口在 D8 图中没有下游（它已是洪泛的终端），\n");
        log.append("     故 outletExit 沿 parent 走必然失败 ⇒ 溢流注入永远发生不了。\n");

        java.nio.file.Path dir = java.nio.file.Path.of("build/lifecycle");
        java.nio.file.Files.createDirectories(dir);
        javax.imageio.ImageIO.write(render(r, n, chainMark), "png",
                dir.resolve("chains.png").toFile());
        java.nio.file.Files.writeString(dir.resolve("metrics.txt"), log);
        System.out.println(log);
        System.out.println("IMAGE=" + dir.resolve("chains.png").toAbsolutePath());
        System.out.println("图例：黄=完整六阶段链 灰=其余河道 蓝=湖 深蓝=海 白=溢口");
        System.exit(full > 0 ? 0 : 1);
    }

    /** 找该湖格所属盆地的溢流出口格；无则 null。 */
    private static Integer outletOf(LakeWaterBalance.Result lr, int lakeCell,
                                    double[] depth, int n) {
        if (lr.overflowAt().isEmpty()) return null;
        // overflowAt 的键即出口格；取离该湖格最近的一个（同盆地）
        Integer best = null; double bd = Double.MAX_VALUE;
        for (Integer o : lr.overflowAt().keySet()) {
            double d = Math.hypot((o / n) - (lakeCell / n), (o % n) - (lakeCell % n));
            if (d < bd) { bd = d; best = o; }
        }
        return best;
    }

    private static int count(boolean[] a) { int c = 0; for (boolean b : a) if (b) c++; return c; }

    private static java.awt.image.BufferedImage render(HydrologyPipeline.Result r, int n,
                                                       boolean[] chainMark) {
        var img = new java.awt.image.BufferedImage(n, n, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                int col;
                if (chainMark[k]) col = 0xFFFF00;
                else if (r.sea()[k]) col = ViewSpec.SEA_COLOR;
                else if (r.lake()[k]) col = ViewSpec.LAKE_COLOR;
                else if (r.channel()[k]) col = 0x808080;
                else col = ViewSpec.contourFixed(r.bed(), n, i, j);
                img.setRGB(i, j, col);
            }
        }
        return img;
    }
}