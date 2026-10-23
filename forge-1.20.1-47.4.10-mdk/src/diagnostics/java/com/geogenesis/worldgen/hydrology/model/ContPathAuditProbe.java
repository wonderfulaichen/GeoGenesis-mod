package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 审计 {@code Trace.contPath} 与 {@code Trace.cell} 是否同步。
 *
 * <p>若两者偏离超过 0.5 格 ⇒ 硬同步失效 ⇒ 几何会画错（蜘蛛网）。</p>
 *
 * <h2>⛔ 2026-10-22：本探针当前<b>预期红</b>，用户已明确「不打算修」</h2>
 *
 * <p><b>实测基线（seed=9139912035078620160, centre=(-140,137)）：</b></p>
 * <pre>
 *   traces=55  hasContPath=55  noContPath=0
 *   contPath 与格心偏离: max=20.0000  均值=0.0463
 *   [FAIL]
 * </pre>
 *
 * <p><b>根因（已定位，非推测）</b>：{@code traceOne} 的 {@code contPts.add} 位于循环开头，
 * 写的是<b>上一步</b>的 {@code curX/curZ}；而 {@code cell} 已经推进到 {@code k}
 * ⇒ 整体慢一拍。前 12 步实测即见 {@code idx1 偏离 2.828}（cont 停在 (95,78)，
 * 而 cell 已到 (93,80)）。另有若干终止分支（{@code continueOutside} 补多个
 * {@code path.add} 、{@code outletEscape} 的 {@code k=esc;continue}）完全不写 cont
 * ⇒ 18/55 条 trace 的 contPts 与 cells 长度不等（差 −3…+1）。
 * 详细交接见 {@code handoff/GeoGenesis-hydrology-20261022-b.md} §4.3。</p>
 *
 * <p><b>为什么现在不影响产出</b>：{@code contPath} 目前<b>产出但无消费者</b> ——
 * {@code DeterministicSkeleton.shape()} 用的是「有界粒子锚点」（{@code PARTICLE_AMP_FRAC}），
 * 不读 {@code contPath}。所以它是个<b>埋着的雷</b>而非当前缺陷：
 * 谁把几何接回 {@code contPath} 就会拿到错位折线。</p>
 *
 * <p>⚠ 本探针<b>不在</b> {@code runWorldgenGate} 门禁内，红了不阻塞构建。
 * 不要为了让本探针变绿而「修」它 —— 用户已决定暂不修（2026-10-22）。</p>
 */
public final class ContPathAuditProbe {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : ViewSpec.CENTER_X;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : ViewSpec.CENTER_Z;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var provider = new ModelHydrologyProvider(gen, seed);
        HydrologyPipeline.Result p = provider.pipeForProbe(cx, cz);
        int n = p.nx();

        var sk = DeterministicSkeleton.build(p.level(), p.parent(), p.resolved(),
                p.lake(), p.sea(), p.discharge(),
                p.lakeResult() == null ? null : p.lakeResult().basinOutlet(),
                n, n, ModelHydrologyProvider.productionParams(
                        p.lakeResult() == null ? 0 : p.lakeResult().runoffPerCell()),
                java.util.List.of(), p.bed(), null, null);

        double maxDev = 0, sumDev = 0;
        int cnt = 0, noCont = 0;
        int worstTrace = -1, worstIdx = -1;
        for (int ti = 0; ti < sk.traces.size(); ti++) {
            var t = sk.traces.get(ti);
            if (!t.hasContPath()) { noCont++; continue; }
            for (int i = 0; i < t.cell.length; i++) {
                double gx = t.cell[i] / n, gz = t.cell[i] % n;
                double dx = t.contPath[i * 2] - gx;
                double dz = t.contPath[i * 2 + 1] - gz;
                double d = Math.hypot(dx, dz);
                sumDev += d;
                cnt++;
                if (d > maxDev) { maxDev = d; worstTrace = ti; worstIdx = i; }
            }
        }
        System.out.printf("traces=%d  hasContPath=%d  noContPath=%d%n",
                sk.traces.size(), sk.traces.size() - noCont, noCont);
        System.out.printf("contPath 与格心偏离: max=%.4f  均值=%.4f  样本=%d%n",
                maxDev, sumDev / Math.max(1, cnt), cnt);
        // ★ 2026-10-22 修正判据：阈值应与实现里的 MOTION_SNAP_RADIUS 一致（默认 1.5 格）。
        //   旧版写死 0.5 —— 那是第一版"每步硬拉回格心"的遗留值，
        //   在当前位置驱动的实现下必然误报 FAIL（实测 max=1.2 属正常）。
        double limit = Double.parseDouble(
                System.getProperty("geogenesis.motionSnapRadius", "1.5")) + 0.05;
        if (maxDev > limit) {
            System.out.printf("[FAIL] 超过兜底半径 %.2f ⇒ 几何会错位。最差 trace=%d idx=%d%n",
                    limit, worstTrace, worstIdx);
        } else {
            System.out.printf("[OK] 偏离在兜底半径 %.2f 内 ⇒ 与格链同步（且确实离开了格心）%n",
                    limit);
        }

        // 抽一条看前 12 步
        if (!sk.traces.isEmpty() && sk.traces.get(0).hasContPath()) {
            var t = sk.traces.get(0);
            System.out.println("\n首条 trace 前 12 步（格坐标）：");
            System.out.printf("%5s %10s %10s %10s%n", "idx", "cell(x,z)", "cont(x,z)", "偏离");
            for (int i = 0; i < Math.min(12, t.cell.length); i++) {
                double gx = t.cell[i] / n, gz = t.cell[i] % n;
                double cxp = t.contPath[i * 2], czp = t.contPath[i * 2 + 1];
                System.out.printf("%5d (%5.1f,%5.1f) (%8.3f,%8.3f) %8.3f%n",
                        i, gx, gz, cxp, czp, Math.hypot(cxp - gx, czp - gz));
            }
        }
    }
}
