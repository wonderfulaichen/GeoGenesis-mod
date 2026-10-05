package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * <h1>验证 {@code erodedHeightForRouting} 与真实取数路径是否逐位等价</h1>
 *
 * <h2>为什么必须先验证这个（用户质疑触发）</h2>
 * <p>B 线在 {@code CellGenerator} 里<b>新增</b>了 {@code erodedHeightForRouting()}，
 * 注释自称"与 {@code sampleWu().height} 等价"、"数值等价性有自检，<b>勿凭假设</b>"。
 * 而 {@code HydroCoreViewProbe} / {@code HydroWorldSolver} 的 sampler
 * <b>正是走这条捷径</b>。</p>
 *
 * <p>⇒ 若二者不等价，则<b>所有基于该 sampler 的水文结论都是测量错误</b>
 * （包括"B 线真实地形 0 条完整六阶段链"这一结论）。
 * 本探针就是回答这个前置问题。</p>
 *
 * <h2>判据</h2>
 * <pre>
 *   [1] maxErr  = max |erodedHeightForRouting − sampleWu().height|
 *   [2] 偏差 &gt; 0.01 块的样本占比
 *   [3] 偏差方向：捷径是否系统性偏高/偏低（若有偏 ⇒ 会系统性影响河网位置）
 * </pre>
 *
 * <p>跑法：{@code gradlew runRoutingHeightEquivalenceProbe [-PprobeArgs="seed spanBlocks stepBlocks"]}</p>
 */
public final class RoutingHeightEquivalenceProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int span = args.length > 1 ? Integer.parseInt(args[1]) : 1200;   // 块
        int step = args.length > 2 ? Integer.parseInt(args[2]) : 7;      // 块

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        final double hs = tp.horizontalScale();

        double maxErr = 0, sumErr = 0, sumSign = 0;
        int n = 0, over = 0;
        double atMaxShort = 0, atMaxLong = 0;
        int hMinShort = Integer.MAX_VALUE, hMaxShort = Integer.MIN_VALUE;

        for (int bx = -span / 2; bx <= span / 2; bx += step) {
            for (int bz = -span / 2; bz <= span / 2; bz += step) {
                double fast = gen.erodedHeightForRouting(bx / hs, bz / hs);
                double full = gen.sampleWu(bx, bz).height;
                double e = fast - full;
                n++;
                sumErr += Math.abs(e);
                sumSign += e;
                if (Math.abs(e) > 0.01) over++;
                if (Math.abs(e) > maxErr) {
                    maxErr = Math.abs(e);
                    atMaxShort = fast;
                    atMaxLong = full;
                }
                // 高速路返回的侵蚀高度范围（应与原地形同量级，不应离谱）
                hMinShort = (int) Math.min(hMinShort, Math.floor(fast));
                hMaxShort = (int) Math.max(hMaxShort, Math.ceil(fast));
            }
        }

        System.out.println("=== erodedHeightForRouting 等价性自检 ===");
        System.out.printf("seed=%d span=%d块 step=%d块 样本=%d%n", seed, span, step, n);
        System.out.printf("[1] maxErr = %.6f 块%n", maxErr);
        System.out.printf("[2] 平均 |err| = %.6f 块   超 0.01 块占比 = %.2f%%%n",
                n > 0 ? sumErr / n : Double.NaN, 100.0 * over / Math.max(1, n));
        System.out.printf("[3] 平均偏差(带符号, 捷径−完整) = %+.6f 块  ⇒ %s%n",
                n > 0 ? sumSign / n : Double.NaN,
                Math.abs(sumSign / Math.max(1, n)) < 1e-9 ? "无系统性偏置" : "★ 有系统性偏置（会影响河网位置）");
        System.out.printf("    最大偏差处: 捷径=%.3f  完整=%.3f%n", atMaxShort, atMaxLong);
        System.out.printf("[4] 捷径返回值范围 = [%.1f, %.1f] 块%n", hMinShort, hMaxShort);

        boolean ok = maxErr < 1e-6;
        System.out.printf("%n判定：%s（阈值 maxErr < 1e-6 块 = 逐位相等）%n",
                ok ? "PASS 等价，可作为测量基准" : "★ FAIL 不等价 ⇒ 基于该 sampler 的水文结论均不可信");
        if (!ok) {
            System.out.println("     须先修等价性（或改用 sampleWu().height），再谈水文形态结论。");
        }
        System.exit(ok ? 0 : 1);   // 真门禁：非零退出码 ⇒ 构建失败
    }
}