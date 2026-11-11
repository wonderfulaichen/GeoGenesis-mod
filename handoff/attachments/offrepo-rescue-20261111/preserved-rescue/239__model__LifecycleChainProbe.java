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
        // ★ 破平局模式（-Dhydro.directionRule=...，默认 baseline 逐位不变）。
        //   ⚠ 2026-10 实测：edgepsilon **被否决** —— 回流自家 26983→27441（不降）、
        //     到海 5776→2498（腰斩）、stagnant 1453→1533182（×1056）、耗时 121→555s。
        //   根因：applyEpsilonSlope 是【迭代松弛 + 64 轮上限】，而平坦链可长数百格
        //     ⇒ 只有出口附近 64 跳内被抬起，湖体大片仍平坦 ⇒ 方向仍不可靠且更不一致。
        DirectionRule.setMode(System.getProperty("hydro.directionRule", "baseline"));

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
        // ★ 终止分类（本轮新增）：区分"合法终端"与"缺陷终端"。
        //   合法：入海 / 内流湖（无出流，水就地蒸发消纳）/ 完整六阶段链 /
        //        【窗口边界端口】（方案 §5.1 明确允许；流量截断是设计契约）。
        //   缺陷：入溢流湖却找不到自己的出口 / 中途消失（既没到海也没到湖、也没出窗）。
        //   ⚠ 旧口径把"入湖但无溢口"一律当缺陷 —— 但**内流湖本就是合法终端**
        //     （用户明确"我又不是不允许内陆湖"，方案 §4.2 亦要求保留）。
        int full = 0, toSeaNoLake = 0, endorheic = 0, lakeOverflowNoOutlet = 0, died = 0;
        int windowExit = 0;
        // ★ 缺陷分解（本轮新增）：溢流湖缺陷的第二段 walk 有多种终止方式，
        //   必须分清是「递归跳转引入的死路」还是「parent 链本身断」。
        int dfNextEndorheic = 0, dfHopBudget = 0, dfParentLost = 0, dfGuard = 0, dfOther = 0;
        String dfExample = "";
        int statSrc = 0, statCh = 0, statLake = 0, statSpill = 0, statDown = 0, statSea = 0;
        boolean[] chainMark = new boolean[cells];
        StringBuilder example = new StringBuilder();

        for (int k0 = 0; k0 < cells; k0++) {
            if (!ch[k0] || hasUpstream[k0]) continue;
            boolean sawCh = false, sawLake = false, sawSpill = false, sawDown = false, sawSea = false;
            int cur = k0;
            guard[0] = 0;
            int lakeCell = -1;
            boolean parentLost = false;     // 终止于 parent 耗尽（窗口端口的必要条件）
            while (guard[0]++ < 4 * n && cur >= 0 && cur < cells) {
                if (sea[cur]) { sawSea = true; break; }
                if (lake[cur]) {
                    sawLake = true; lakeCell = cur;
                    // 溢口：该湖盆的 overflowAt 是否非空
                    break;
                }
                if (ch[cur]) sawCh = true;
                int p = parent[cur];
                if (p < 0 || p == cur) { parentLost = true; break; }
                cur = p;
            }
            // 首次遍历的终点（parent 链耗尽处）——窗口端口判定用
            final int terminal = cur;
            boolean lakeOverflows = false;
            // 溢口 & 下泄：从入湖格所在盆地的出口继续
            if (sawLake && lakeCell >= 0) {
                // ★ 精确盆地归属（本轮修正）：用产出方给出的 basinId/basinOutlet，
                //   不再用"最近溢出口"猜测 —— 相邻两湖时该启发式必然把 A 湖接到 B 湖出口。
                lakeOverflows = basinOverflows(lakeRes, lakeCell);
                Integer outlet = exactOutlet(lakeRes, lakeCell);
                if (outlet != null && outlet >= 0) {
                    sawSpill = true;
                    // ★ 递归过湖（方案 §5.1："允许流入下一湖，再递归至合法终端"）。
                    //   旧实现遇第二湖即停且不计 sawDown ⇒ 洋流式湖链被误判缺陷。
                    //   递归深度上限 = 湖数（防环；湖盆按 basinId 唯一，环仅可能来自
                    //   病态数据 ⇒ 上限触达即记为缺陷退出）。
                    int cur2 = outlet;
                    int g2 = 0;
                    int lakeHops = 0;
                    int endReason = -1;    // -1=见海 0=下一段遇内流湖 1=湖跳预算 2=parent断 3=guard
                    // ★ 湖跳防环（本轮修正）：出口格的 parent 可能指向湖内格
                    //   （平坦湖面 filler 欠定向，13/62 出口如此）⇒ 走 parent 一步进湖、
                    //   又被递归逻辑跳回同一出口 ⇒ 走-跳交替 2-循环，烧光湖跳预算
                    //   （实测 1752 例 100% 卡此，而出口级不跳湖 62/62 到海 ⇒ 链本身健康）。
                    //   修法：已访问的出口不再跳，改为沿 parent 继续走 ——
                    //   filler parent 全局无环 ⇒ 必达海；跳转只为记 sawSpill/sawDown。
                    java.util.BitSet visitedOutlets = new java.util.BitSet();
                    visitedOutlets.set(outlet);
                    int dfLoop = 0;
                    while (g2++ < 4 * n && cur2 >= 0 && cur2 < cells) {
                        if (sea[cur2]) { sawSea = true; endReason = -1; break; }
                        if (lake[cur2]) {
                            // 进入下一湖：若其为溢流湖 ⇒ 继续沿它的出口走（递归）
                            Integer next = exactOutlet(lakeRes, cur2);
                            if (next != null && next >= 0 && lakeHops++ < lakeRes.basins().size()
                                    && !visitedOutlets.get(next)) {
                                visitedOutlets.set(next);
                                cur2 = next;
                                continue;
                            }
                            if (next != null && next >= 0 && visitedOutlets.get(next)) {
                                dfLoop++;       // 跳转环：放弃跳转，沿 parent 走（下方 p2 路径）
                            } else {
                                endReason = (next == null || next < 0) ? 0 : 1;
                                break;   // 内流湖（合法终端）或递归预算耗尽
                            }
                        }
                        if (ch[cur2]) sawDown = true;
                        int p2 = parent[cur2];
                        if (p2 < 0 || p2 == cur2) { endReason = 2; break; }
                        cur2 = p2;
                    }
                    if (g2 >= 4 * n && endReason == -1 && !sawSea) endReason = 3;
                    if (endReason >= 0 && !sawSea) {
                        // 缺陷路径样本：记下入口坐标便于复查
                        if (dfExample.length() == 0) dfExample = String.format(
                                "湖格(%d,%d)→出口(%d,%d) end=%d cur2=(%d,%d)",
                                lakeCell / n, lakeCell % n, outlet / n, outlet % n,
                                endReason, cur2 / n, cur2 % n);
                        if (endReason == 0) dfNextEndorheic++;
                        else if (endReason == 1) dfHopBudget++;
                        else if (endReason == 2) dfParentLost++;
                        else dfGuard++;
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
            else if (sawLake) {
                // 入湖：溢流湖却找不到自己的出口 = 缺陷；无出流 = 合法内流湖。
                if (lakeOverflows) lakeOverflowNoOutlet++;
                else endorheic++;
            } else if (parentLost && hitsWindowBorder(terminal, n)) {
                // ★ 窗口边界终止 = 【明确边界端口】（方案 §5.1 合法终端之一）。
                //   流量沿排水树累加、窗口截断外部上游是设计契约
                //   （HydrologyPipeline javadoc 第 5 条），不是中途消失。
                windowExit++;
            } else died++;
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
        log.append(String.format("★ 完整六阶段链 = %d（源头→汇流→蓄水→溢口→下泄→入海）%n", full));
        log.append("终止分类：\n");
        log.append(String.format("  [合法] 到海但未经湖 = %d%n", toSeaNoLake));
        log.append(String.format("  [合法] 入内流湖（无出流，就地消纳）= %d%n", endorheic));
        log.append(String.format("  [合法] 出窗口边界（边界端口，方案 §5.1）= %d%n", windowExit));
        log.append(String.format("  [缺陷] 入溢流湖却找不到自己的出口 = %d%n", lakeOverflowNoOutlet));
        if (lakeOverflowNoOutlet > 0) {
            log.append(String.format(
                    "    分解：下一段又进内流湖=%d 湖跳预算尽=%d parent断=%d guard尽=%d 其它=%d%n",
                    dfNextEndorheic, dfHopBudget, dfParentLost, dfGuard, dfOther));
            if (dfExample.length() > 0) log.append(String.format("    例：%s%n", dfExample));
        }
        log.append(String.format("  [缺陷] 中途消失（既未到海也未到湖也未出窗）= %d%n", died));
        if (example.length() > 0) log.append(String.format("  例：%s%n", example));
        log.append(String.format("  湖泊总数=%d  溢流出口数=%d  海格=%d%n",
                lakeRes.basins().size(), lakeRes.overflowAt().size(), count(sea)));
        // ★ 湖↔河耦合是否真的收敛（上限用尽 ≠ 收敛；方案 §P1 要求可判定）。
        log.append(String.format("  湖↔河耦合：轮次=%d 收敛=%b 残差=%.3e%n",
                r.couplingRounds(), r.couplingConverged(), r.couplingResidual()));
        if (!r.couplingConverged()) {
            log.append("  ⚠ 未收敛（或未记录）⇒ 下面的湖泊/流量结果是【迭代预算用尽】的中间态，"
                    + "不宜当稳态结论。\n");
        }

        // ★★ 出口级诊断（排除"探针 bug"的可能性）★★
        //   120 个源头入湖、120 个找到溢口，但只有 21 个在溢口下游见到河道
        //   ⇒ 必须直接量【溢口格本身】，而不是靠"最近溢口"的近似归属。
        log.append("%n[出口级诊断] 逐个溢流出口格检查（排除探针归属近似）\n");
        int outletCh = 0, outletToSea = 0, outletToCh = 0, outletDead = 0;
        // ★ 并集计数：同一出口可能"走河道再入海" ⇒ 河道与海两计数【重叠】，
        //   判据若取两者之和会 >100%（实测 16/15=106.7% 的根因）。
        int outletOk = 0;
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
            if (sea2 || firstCh >= 0) outletOk++;
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

        // ★★ 验收判据（本轮强化：从"存在性"改为"覆盖率 + 出口自洽"）★★
        //
        //   旧判据 `full > 0` 只要求【存在一条】完整链 —— 即使 99% 的链都断，
        //   只要有一条连着就 PASS。实测门禁因此长期假绿
        //   （本项目记录：120 源头入湖、仅 21 个在出口下游见到河道仍然"通过"）。
        //   新判据三条，任一不满足即 FAIL：
        //     ① 出口自洽：正出流湖的出口必须至少能走到河道或海
        //        ⇒ 防止"湖与下游脱节"被算作通过；
        //     ② 合法终止率 ≥ MIN_TERMINAL_OK（方案 §5.1 合法终端：
        //        湖/海/边界端口；不要求每条河经湖）；
        //     ③ 至少有一条完整链（保底，防覆盖率被小分母放大）。
        int effectiveSources = statSrc;
        // ② 合法终止率：源头必须终止于【合法终端】（海 / 内流湖 / 窗口边界端口 / 走完溢口下泄）。
        //    ⚠ 不要求"每条河都经湖" —— 方案明确「不是要求所有河都必须经过湖」。
        int validTerminals = full + toSeaNoLake + endorheic + windowExit;
        double terminalOk = effectiveSources > 0 ? validTerminals / (double) effectiveSources : 0;

        // ★★ 「中途消失」终点打样（不打样就是猜 —— 本项目铁律）★★
        //   1451 条链死在"非边框 + 非湖 + 非海"处。它们的终点长什么样？
        //   分类：A) 终点 parent=-1（排水链端）但不在边框 —— 内部链端为何无下游？
        //        B) 终点在【未决格】（水位依赖窗口切割）—— 窗口契约允许截断？
        //        C) 终点处 level>bed 几何湿却非湖 —— 死在水里（已有 A1 类问题同型）
        //        D) 守卫超时（4n 步未终止）
        if (died > 0) {
            log.append("%n[中途消失 终点打样]\n");
            int pNeg = 0, unresolved = 0, geoWet = 0, guardHit = 0, other = 0;
            int shown = 0;
            // 重新遍历（轻量：只记终点分类）
            int[] guard2 = new int[1];
            for (int k0 = 0; k0 < cells; k0++) {
                if (!ch[k0] || hasUpstream[k0]) continue;
                boolean sawL = false, sawS = false;
                int cur = k0;
                guard2[0] = 0;
                boolean exhausted = false;
                while (guard2[0]++ < 4 * n && cur >= 0 && cur < cells) {
                    if (sea[cur]) { sawS = true; break; }
                    if (lake[cur]) { sawL = true; break; }
                    int p = parent[cur];
                    if (p < 0 || p == cur) { exhausted = true; break; }
                    cur = p;
                }
                if (sawS || sawL) continue;
                // 复现首次分类（含完整链/出窗等已合法的情况）
                if (!exhausted) { guardHit++; continue; }   // 守卫超时
                int p = parent[cur];
                if (p < 0 && hitsWindowBorder(cur, n)) continue;   // 出窗（合法）
                boolean unres = !r.resolved()[cur];
                boolean wet = r.level()[cur] - r.bed()[cur] > 0.5;
                if (unres) unresolved++;
                else if (wet) geoWet++;
                else if (p < 0) pNeg++;
                else other++;
                if (shown < 6) {
                    shown++;
                    log.append(String.format(
                            "    终点(%d,%d): parent=%d resolved=%b level-bed=%.3f 河道=%b%n",
                            cur / n, cur % n, p, r.resolved()[cur],
                            r.level()[cur] - r.bed()[cur], ch[cur]));
                }
            }
            log.append(String.format(
                    "  分类：内部链端(p<0且非未决非湿)=%d  未决格=%d  几何湿未标湖=%d  守卫超时=%d  其它=%d%n",
                    pNeg, unresolved, geoWet, guardHit, other));
        }

        int overflowTotal = lakeRes.overflowAt().size();
        // ① 出口自洽：正出流湖的出口必须能走到河道或海（防"湖与下游脱节"）。
        //   ⚠ 用【并集 outletOk】：走河道再入海的出口会被两个分项重复计数（曾得 106.7%）。
        double outletOkRate = overflowTotal > 0
                ? outletOk / (double) overflowTotal : 1.0;
        log.append(String.format("%n[验收判据]%n"));
        log.append(String.format("  ① 出口自洽：正出流出口=%d 中能走到河道或海(并集) = %d (%.1f%%)  阈值 ≥ %.0f%%%n",
                overflowTotal, outletOk, 100 * outletOkRate, 100 * MIN_OUTLET_OK));
        log.append(String.format("  ② 合法终止率：合法终端 %d（到海 %d + 内流湖 %d + 出窗 %d + 完整链 %d）"
                        + " / 源头 %d = %.1f%%  阈值 ≥ %.0f%%%n",
                validTerminals, toSeaNoLake, endorheic, windowExit, full, effectiveSources,
                100 * terminalOk, 100 * MIN_TERMINAL_OK));
        log.append(String.format("  ③ 缺陷终端 = %d（溢流湖无出口 %d + 中途消失 %d）%n",
                lakeOverflowNoOutlet + died, lakeOverflowNoOutlet, died));
        // ★★ ④ 水量收支（方案合同 2）★★
        //
        //   【为什么需要这条】(本轮实测的教训)：湖↔河耦合曾出现【每轮 ×5 的几何放大环】
        //   （ΣIn 1694→…→1093261、maxFlux 每轮恰 ×5、residual 恒 0.800 = 1−1/5），
        //   而**当时没有任何判据能抓住它** —— ①②③ 全 PASS。放大环是靠人工逐轮读
        //   诊断输出才发现的。方案 §5「验收合同 2 水量」正是为此设计，此前未建。
        //
        //   判据 = 全局水量守恒：Σ(每盆地 inflow) 必须与【同一窗口的坡面径流总量】
        //   同量级。放大环的签名是 ΣIn 远超物理水量（实测超出 464×）。
        //   注意不要求精确相等：注入流线止于湖 ⇒ 同一水在【每个流经的盆地各记一次】
        //   是正确的级联语义（方案 §4.2），故允许 ΣIn > 径流总量，但**上限是硬约束**。
        double sumInflow = 0, sumOutflow = 0, sumEvap = 0;
        for (var b : lakeRes.basins()) {
            sumInflow += b.inflow();
            sumOutflow += b.outflow();
            sumEvap += b.evaporationOut();
        }
        // 坡面径流总量 = runoffPerCell × 非海非未决格数（同一单位：体积/时间）
        double runoffTotal = 0;
        for (int k = 0; k < cells; k++) {
            if (r.sea()[k] || !r.resolved()[k]) continue;
            runoffTotal += lakeRes.runoffPerCell();
        }
        // 上限：级联深度未知，取 10× 作宽限（放大环实测达 464× ⇒ 该阈值足以区分）。
        //   ⚠ 刻意不设"必须相等"——那会把正确的级联语义判成 FAIL。
        double inflowRatio = runoffTotal > 0 ? sumInflow / runoffTotal : 0;
        boolean waterOk = sumInflow <= 10.0 * runoffTotal;
        log.append(String.format(
                "  ④ 水量收支：Σ盆地来水=%.1f Σ出流=%.1f Σ蒸发=%.1f | 坡面径流总量=%.1f"
                        + " ⇒ 比值=%.2f×（上限 10×，防几何放大环）%n",
                sumInflow, sumOutflow, sumEvap, runoffTotal, inflowRatio));
        boolean ok = outletOkRate >= MIN_OUTLET_OK && terminalOk >= MIN_TERMINAL_OK && full > 0
                && waterOk;
        log.append(String.format("判定：%s%n", ok
                ? "PASS（出口自洽 + 合法终止率达标 + 水量收支未放大）"
                : "★ FAIL（见上方判据，逐条给出实测值与阈值）"));

        java.nio.file.Path dir = java.nio.file.Path.of("build/lifecycle");
        java.nio.file.Files.createDirectories(dir);
        javax.imageio.ImageIO.write(render(r, n, chainMark), "png",
                dir.resolve("chains.png").toFile());
        java.nio.file.Files.writeString(dir.resolve("metrics.txt"), log);
        System.out.println(log);
        System.out.println("IMAGE=" + dir.resolve("chains.png").toAbsolutePath());
        System.out.println("图例：黄=完整六阶段链 灰=其余河道 蓝=湖 深蓝=海 白=溢口");
        System.exit(ok ? 0 : 1);
    }

    /**
     * 出口自洽下限：正出流湖的出口中"能走到河道或海"的比例。
     *
     * <p>阈值取 0.95 的理由：出口就在盆地边缘，紧邻的第一格通常就是河道或海；
     * 只有"湖与下游脱节"（本判据要抓的缺陷）才会成片失败。
     * 若确有 <5% 的出口因窗口截断而暂时走不通，属边界效应，
     * 应在扩大窗口后消失，而不是放宽阈值。</p>
     */
    private static final double MIN_OUTLET_OK = 0.95;

    /**
     * 合法终止率下限（合法终端 / 源头）。
     *
     * <p>方案 §5.1 的原文要求：<b>「每条已发布河段终止于有效湖节点、海或明确边界端口」</b>。
     * 合法终端包括：入海 / 内流湖（无出流）/ 经溢口下泄入海。
     * <b>不要求每条河都经过湖</b>（方案明示）。</p>
     *
     * <p>阈值 0.95 是"已发布河段不得无声消失"的直接量化。
     * 若实测低于此，说明存在"中途消失"或"入溢流湖却接不上出口"的真缺陷，
     * 应如实 FAIL 并继续收敛（项目纪律：不为了让门禁变绿而放宽阈值）。</p>
     */
    private static final double MIN_TERMINAL_OK = 0.95;

    /**
     * 该湖格所属盆地的真实溢流出口格；无（内流湖/身份未知）则 null。
     *
     * <p><b>为什么不能用"最近出口"</b>：相邻两湖时最近出口常属另一个盆地
     * ⇒ 会把 A 湖的源头接到 B 湖的出口，令"全周期 PASS"变成假阳性。
     * 身份由 {@link LakeWaterBalance} 产出（{@code basinId}/{@code basinOutlet}）。</p>
     *
     * <p>身份缺失（旧 7 参构造）时返回 null ⇒ 该链不计入"完整六阶段"，
     * 而不是退化成猜测（宁可少报，不可假报）。</p>
     */
    /** 该格是否落在窗口边框（0 或 n−1 的行/列）= 明确的边界端口位置。 */
    private static boolean hitsWindowBorder(int cell, int n) {
        if (cell < 0 || cell >= n * n) return false;
        int i = cell / n, j = cell % n;
        return i == 0 || j == 0 || i == n - 1 || j == n - 1;
    }

    /** 该湖格所属盆地是否**有正出流**（= 蓄满并溢流），而非内流湖。 */
    private static boolean basinOverflows(LakeWaterBalance.Result lr, int lakeCell) {
        int basin = LakeWaterBalance.basinOf(lr, lakeCell);
        if (basin < 0) return false;
        int outlet = LakeWaterBalance.outletOf(lr, basin);
        if (outlet < 0) return false;
        Double amount = lr.overflowAt().get(outlet);
        return amount != null && amount > 0;
    }

    private static Integer exactOutlet(LakeWaterBalance.Result lr, int lakeCell) {
        int basin = LakeWaterBalance.basinOf(lr, lakeCell);
        if (basin < 0) return null;
        int outlet = LakeWaterBalance.outletOf(lr, basin);
        if (outlet < 0) return null;
        // 出口必须真的属于【该盆地】的溢流记录（防止身份/记录不一致）
        Double amount = lr.overflowAt().get(outlet);
        if (amount == null || amount <= 0) return null;
        return outlet;
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