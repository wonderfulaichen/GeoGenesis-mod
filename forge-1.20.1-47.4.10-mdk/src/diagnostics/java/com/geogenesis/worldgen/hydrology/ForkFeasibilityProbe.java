package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 【支流分叉可行性】探针（2026-09-20，M0 先量后改）—— <b>零生产行为变更</b>。
 *
 * <h2>要回答的问题</h2>
 * <p>FTF {@code BaseRiverGenerator.generateForks} 的布点规则（父河 25%~90% 处、
 * ±27°~68.4°、0.44×父长）在<b>本项目地形</b>上到底能出多少条叉？各道闸门挡掉多少？
 * 叉长落在哪个量级？—— 用来标定 {@link RiverLineParams.ForkParams}，<b>不照搬 FTF 数值</b>
 * （FTF 的 300 是它自己的世界单位；且 0.44×父长是同量级大支流，与"短溪"目标相反）。</p>
 *
 * <h2>方法</h2>
 * <ol>
 *   <li>先建一份<b>不开分叉</b>的网络，量基线河数（= 父河池大小）。</li>
 *   <li>再建一份网络，开 {@link RiverLineNetwork#forkDryRun} ⇒ 布点 + 追踪 <b>只统计不提交</b>。</li>
 * </ol>
 *
 * <h2>⚠ 已知口径限制（读结论时必须带上）</h2>
 * <ul>
 *   <li>dry-run <b>不提交</b> ⇒ 没有下一代 ⇒ <b>只量 depth 0</b>（真实递归密度会更高）。</li>
 *   <li>后续候选看不到"前面候选已认领" ⇒ joined 率是<b>乐观上界</b>。</li>
 *   <li>分叉只在 pass-2 生成 ⇒ 统计也只来自 pass-2。</li>
 * </ul>
 *
 * <pre>{@code
 *   gradlew runForkFeasibilityProbe -PprobeArgs="seed rx0 rz0 rn frac lenMin lenMax clearance minParentLen mode upCells requireTrough"
 *   // 例：mode=1（上游分支）回走 3 格、关闭谷槽闸门
 *   gradlew runForkFeasibilityProbe -PprobeArgs="9139912035078620160 -3 -2 5 0.44 24 200 24 40 1 3 0"
 * }</pre>
 *
 * <p><b>判据</b>（纪律 §D-1「判据必须能区分对与错」）：布点数必须 &gt; 0，
 * 否则说明分叉循环根本没接到底（复刻 2026-09-19 minBankHeight 那次"加了参数没验证生效"的教训）。</p>
 */
public final class ForkFeasibilityProbe {

    private ForkFeasibilityProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int rx0 = args.length > 1 ? Integer.parseInt(args[1]) : -3;
        int rz0 = args.length > 2 ? Integer.parseInt(args[2]) : -2;
        int rn = args.length > 3 ? Integer.parseInt(args[3]) : 5;
        double frac = args.length > 4 ? Double.parseDouble(args[4]) : 0.44;
        double lenMin = args.length > 5 ? Double.parseDouble(args[5]) : 24.0;
        double lenMax = args.length > 6 ? Double.parseDouble(args[6]) : 200.0;
        double clearance = args.length > 7 ? Double.parseDouble(args[7]) : 24.0;
        double minParent = args.length > 8 ? Double.parseDouble(args[8]) : 40.0;
        int mode = args.length > 9 ? Integer.parseInt(args[9]) : 1;
        int upCells = args.length > 10 ? Integer.parseInt(args[10]) : 3;
        int troughMode = args.length > 11 ? Integer.parseInt(args[11]) : 2;   // 2=弱(默认) 1=严格 0=不检查
        // ⚠ 默认必须【跟随生产默认】，否则探针与生产口径漂移（实测踩过：默认 0 会落回
        //   params.traceStep()=2 ⇒ 可成叉 16 而非 31，看起来像"功能退化"）。
        int step = args.length > 12 ? Integer.parseInt(args[12])
                : RiverLineParams.ForkParams.defaults().traceStep();

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);

        // ---------- ① 基线：显式关闭分叉（不能直接用 defaults() —— 分叉现已默认启用） ----------
        RiverLineParams.ForkParams off = RiverLineParams.ForkParams.defaults().withEnabled(false);
        RiverLineNetwork base = new RiverLineNetwork(gen::terrainEQuick, null,
                gen.heightCurve(), seed, RiverLineParams.defaults().withFork(off));
        List<RiverLineRegion> baseRegions = buildAll(base, rx0, rz0, rn);
        int baseRivers = 0;
        for (RiverLineRegion r : baseRegions) baseRivers += r.rivers.size();

        // ---------- ② dry-run：只统计不提交 ----------
        RiverLineParams.ForkParams fp = RiverLineParams.ForkParams.defaults()
                .tune(null, mode, null, frac, minParent, lenMin, lenMax,
                        upCells, troughMode, step, clearance, null);
        RiverLineParams params = RiverLineParams.defaults().withFork(fp);
        RiverLineNetwork net = new RiverLineNetwork(gen::terrainEQuick, null,
                gen.heightCurve(), seed, params);
        RiverLineNetwork.forkStats.reset();
        RiverLineNetwork.forkDryRun = true;
        List<RiverLineRegion> regions = buildAll(net, rx0, rz0, rn);
        RiverLineNetwork.forkDryRun = false;

        RiverLineNetwork.ForkStats s = RiverLineNetwork.forkStats;
        int nReg = Math.max(1, regions.size());

        System.out.printf("=== ForkFeasibilityProbe seed=%d region[%d,%d]..[%d,%d] ===%n",
                seed, rx0, rz0, rx0 + rn - 1, rz0 + rn - 1);
        System.out.printf("[参数] dry-run（不提交）  mode=%d%s  槽判据=%d  追踪步长=%d  "
                        + "lengthFrac=%.3f  len=[%.0f,%.0f]wu  clearance=%.0fwu  minParentLen=%.0fwu%n",
                mode, mode == 1 ? ("（上游分支 " + upCells + " 格）") : "（FTF 几何）",
                troughMode, step, frac, lenMin, lenMax, clearance, minParent);
        System.out.printf("[基线] %d region 共 %d 条河 ⇒ 平均 %.1f 条/region（分叉前的父河池）%n%n",
                nReg, baseRivers, baseRivers / (double) nReg);

        System.out.printf("[父河] 考察 %d 条；太短被跳过 %d 条（%.1f%%）%n",
                s.parents, s.parentTooShort, pct(s.parentTooShort, s.parents));
        System.out.printf("       弧长wu：p10=%.0f  p50=%.0f  p90=%.0f  max=%.0f%n%n",
                q(s.parentArc, 0.10), q(s.parentArc, 0.50), q(s.parentArc, 0.90), max(s.parentArc));

        int rej = s.rejOutside + s.rejNoUpstream + s.rejClaimed + s.rejBorder
                + s.rejTrough + s.rejValley + s.rejClear;
        System.out.printf("[布点] 候选点 %d 个；通过 %d 个（%.1f%%）%n",
                s.placements, s.placements - rej, pct(s.placements - rej, s.placements));
        System.out.printf("       拒绝合计 %d：界外 %d / 无未认领上游 %d / 已认领 %d / 近边界 %d / "
                        + "非谷槽 %d / 他人谷壁 %d / 净空不足 %d%n%n",
                rej, s.rejOutside, s.rejNoUpstream, s.rejClaimed, s.rejBorder,
                s.rejTrough, s.rejValley, s.rejClear);

        System.out.printf("[追踪] 回滚(太短/交叉) %d / 未汇入已有河 %d / ★可成叉 %d%n",
                s.traceNull, s.notJoined, s.wouldAccept);
        System.out.printf("       （诊断）纯 flowTo 路径格数：p50=%.0f  p90=%.0f  max=%.0f  "
                        + "其中 <3 格 = %d 个%n",
                q(s.rawPathLen, 0.50), q(s.rawPathLen, 0.90), max(s.rawPathLen),
                countBelow(s.rawPathLen, 3));
        System.out.printf("       回滚分类：路径太短 %d / 其它（交叉·自环） %d%n",
                s.traceNullShort, s.traceNullOther);
        System.out.printf("[叉长] 格数：p50=%.0f  p90=%.0f  max=%.0f  （叉长wu p50=%.0f）%n",
                q(s.forkCells, 0.50), q(s.forkCells, 0.90), max(s.forkCells), q(s.forkLenWu, 0.50));
        int b2 = 0, b3 = 0, b4 = 0, b58 = 0, b915 = 0, bMore = 0;
        for (double c : s.forkCells) {
            if (c < 3) b2++;
            else if (c == 3) b3++;
            else if (c == 4) b4++;
            else if (c <= 8) b58++;
            else if (c <= 15) b915++;
            else bMore++;
        }
        System.out.printf("       桶：<3=%d  3=%d  4=%d  5~8=%d  9~15=%d  >15=%d%n%n",
                b2, b3, b4, b58, b915, bMore);

        System.out.printf("[结论] 每 region 平均分叉 = %.1f 条 ⇒ 密度 %.1f → %.1f 条/region%n",
                s.wouldAccept / (double) nReg, baseRivers / (double) nReg,
                (baseRivers + s.wouldAccept) / (double) nReg);

        // ★ 2026-09-21：本探针测的是【旧追踪链路】的支流分叉（fork 挂在 traceRiver 上）。
        //   流体骨架路线（flowSkeletonRouting=true，默认）不经过 traceRiver ⇒ 本探针
        //   不适用：不是"功能被改坏"，而是"该能力尚未接入新路线"。
        //   用户裁定：旧能力是否搬进新路线【由实测决定】⇒ 此处明确 SKIP，不用它挡路。
        if (RiverLineNetwork.flowSkeletonRouting) {
            System.out.printf("%n[SKIP] 当前为流体骨架路线（flowSkeletonRouting=true）："
                    + "本探针针对旧追踪链路的支流分叉，不适用。%n"
                    + "       若要跑旧链路：RiverLineNetwork.flowSkeletonRouting=false%n");
            return;
        }
        // ---------- 判据 ----------
        boolean wired = s.placements > 0;
        System.out.printf("%n[判据-1] 布点数 > 0（分叉循环真的被执行）⇒ %s%n",
                wired ? "PASS" : "FAIL —— 布点循环没接到底");
        // 判据 2：已启用却一条叉都出不来 ⇒ 该功能已静默失效（参数/闸门被改坏）。
        boolean producing = !fp.enabled() || s.wouldAccept > 0;
        System.out.printf("[判据-2] 启用状态下必须有产出（wouldAccept > 0）⇒ %s（%d 条）%n",
                producing ? "PASS" : "FAIL —— 已启用但零产出", s.wouldAccept);
        System.out.println("         （dry-run 只量 depth 0，真实递归密度更高；joined 率为乐观上界）");
        if (!wired || !producing) System.exit(1);
    }

    private static List<RiverLineRegion> buildAll(RiverLineNetwork net,
                                                  int rx0, int rz0, int rn) {
        for (int dx = 0; dx < rn; dx++) {
            for (int dz = 0; dz < rn; dz++) net.region(rx0 + dx, rz0 + dz);
        }
        return net.cachedList();
    }

    private static double pct(int a, int b) {
        return b == 0 ? 0.0 : 100.0 * a / b;
    }

    /** 分位数（拷贝排序，不改原表）。 */
    private static double q(List<Double> v, double p) {
        if (v.isEmpty()) return Double.NaN;
        List<Double> c = new ArrayList<>(v);
        Collections.sort(c);
        int i = (int) Math.min(c.size() - 1, Math.max(0, Math.floor(p * (c.size() - 1))));
        return c.get(i);
    }

    private static int countBelow(List<Double> v, double th) {
        int n = 0;
        for (double d : v) if (d < th) n++;
        return n;
    }

    private static double max(List<Double> v) {
        double m = Double.NaN;
        for (double d : v) if (Double.isNaN(m) || d > m) m = d;
        return m;
    }
}
